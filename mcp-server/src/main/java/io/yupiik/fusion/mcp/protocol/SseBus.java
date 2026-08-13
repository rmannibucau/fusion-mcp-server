/*
 * Copyright (c) 2025 - present - Yupiik SAS - https://www.yupiik.com
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.yupiik.fusion.mcp.protocol;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.logging.Level.FINE;
import static java.util.logging.Level.FINEST;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

/**
 * The {@code text/event-stream} body of a MCP session, i.e. the queue of the messages the server sends to the client.
 * <p>
 * Messages are buffered until the subscriber - the HTTP layer - requests them, so publishing never blocks and works
 * even when no client is currently connected on {@code GET /mcp}: a tool can send a request and the client will get
 * it as soon as it opens the stream. The last frames are also kept to be replayed when a client reconnects with a
 * {@code Last-Event-ID} header, as the MCP streamable HTTP transport allows.
 */
public class SseBus implements Flow.Publisher<ByteBuffer> {
    /**
     * How many frames are kept for a {@code Last-Event-ID} resumption.
     */
    private static final int REPLAY_BUFFER_SIZE = 128;

    private static final ByteBuffer KEEP_ALIVE =
            ByteBuffer.wrap(": ping\n\n".getBytes(UTF_8)).asReadOnlyBuffer();

    /**
     * Sentinel of {@link #replayFrom}: no {@code Last-Event-ID} was requested.
     */
    private static final long NO_REPLAY = -1;

    private final Logger logger = Logger.getLogger(SseBus.class.getName());

    private final Lock lock = new ReentrantLock();
    private final AtomicLong pending = new AtomicLong();
    private final AtomicLong lastEventId = new AtomicLong();
    private final AtomicLong replayFrom = new AtomicLong(NO_REPLAY);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean closeNotified = new AtomicBoolean();
    // serializes the emission, the HTTP layer calls request(1) from within onNext so drain() is re-entrant
    private final AtomicInteger wip = new AtomicInteger();
    private final Deque<Frame> frames = new ConcurrentLinkedDeque<>();
    private final Deque<Frame> sent = new ConcurrentLinkedDeque<>();
    private volatile Flow.Subscriber<? super ByteBuffer> subscriber;
    private volatile Runnable onClose;

    /**
     * Queues a JSON-RPC message for the client.
     *
     * @param json the already serialized JSON-RPC request/notification.
     * @return the identifier of the SSE event, i.e. what a client can resume from.
     */
    public long publish(final String json) {
        final var frame = new Frame(lastEventId.incrementAndGet(), json);
        frames.add(frame);
        drain();
        return frame.id();
    }

    /**
     * Queues a SSE comment, it keeps the connection - and any proxy in between - alive without being a message.
     */
    public void keepAlive() {
        frames.add(Frame.KEEP_ALIVE_FRAME);
        drain();
    }

    /**
     * Queues a last message then closes the stream, what a graceful termination - e.g. {@code subscriptions/cancel} -
     * delivers: the subscriber still reads the closing result before the {@code onComplete}.
     *
     * @param json the already serialized JSON-RPC message to deliver.
     */
    public void end(final String json) {
        publish(json);
        cancel();
    }

    /**
     * @param onClose the hook invoked once when the stream is closed with {@link #cancel()}, it lets the owner
     *                release what the stream was holding - e.g. a stateless subscription.
     */
    public void onClose(final Runnable onClose) {
        this.onClose = onClose;
    }

    /**
     * Arms the replay of the frames the client did not get: they are re-queued when the next stream subscribes.
     * <p>
     * It is deliberately not immediate - the transport calls this while building the response, i.e. before the HTTP
     * layer subscribes - else the previous, already dead, subscriber would silently swallow the replayed frames.
     *
     * @param lastEventId the value of the {@code Last-Event-ID} header.
     */
    public void replayFrom(final long lastEventId) {
        replayFrom.set(lastEventId);
    }

    /**
     * @return the identifier of the last event which was queued.
     */
    public long lastEventId() {
        return lastEventId.get();
    }

    /**
     * Completes the stream, the client has to open a new one to get further messages.
     */
    public void cancel() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        complete(subscriber);
        notifyClosed();
    }

    /**
     * @return {@code true} once {@link #cancel()} was called.
     */
    public boolean isClosed() {
        return closed.get();
    }

    private void notifyClosed() {
        final var current = onClose;
        if (current != null && closeNotified.compareAndSet(false, true)) {
            try {
                current.run();
            } catch (final RuntimeException re) {
                logger.log(FINEST, re, re::getMessage);
            }
        }
    }

    @Override
    public void subscribe(final Flow.Subscriber<? super ByteBuffer> newSubscriber) {
        lock.lock();
        try {
            complete(subscriber); // at most one stream per session, the last one to connect wins
            subscriber = newSubscriber;
            pending.set(0);

            // everything queued here targets the new subscriber only: doing it before the swap would let the
            // previous - already dead - one silently swallow the frames, and the new stream would never flush
            final var from = replayFrom.getAndSet(NO_REPLAY);
            if (from >= 0) {
                sent.stream().filter(it -> it.id() > from).toList().reversed().forEach(frames::addFirst);
            }
            // a comment first: it commits the HTTP response so the client knows the stream is live before any message
            frames.addFirst(Frame.KEEP_ALIVE_FRAME);
        } finally {
            lock.unlock();
        }

        newSubscriber.onSubscribe(new Flow.Subscription() {
            @Override
            public void request(final long n) {
                if (n <= 0) {
                    newSubscriber.onError(new IllegalArgumentException("Invalid request: " + n));
                    return;
                }
                pending.updateAndGet(
                        p -> p == Long.MAX_VALUE || n == Long.MAX_VALUE || p + n < 0 ? Long.MAX_VALUE : p + n);
                drain();
            }

            @Override
            public void cancel() {
                lock.lock();
                try {
                    if (subscriber == newSubscriber) {
                        subscriber = null;
                    }
                } finally {
                    lock.unlock();
                }
            }
        });

        if (closed.get()) {
            complete(newSubscriber);
        }
    }

    private void drain() {
        if (wip.getAndIncrement() != 0) { // another thread is emitting, it will see our frame
            return;
        }
        int missed = 1;
        do {
            emitWhilePossible();
            missed = wip.addAndGet(-missed);
        } while (missed != 0);
    }

    private void emitWhilePossible() {
        while (!frames.isEmpty() && subscriber != null && pending.get() > 0) {
            final var frame = frames.pollFirst();
            if (frame == null) { // another thread was faster
                continue;
            }
            if (pending.get() != Long.MAX_VALUE) {
                pending.decrementAndGet();
            }
            if (frame.isReplayable()) {
                track(frame);
            }
            emit(frame.toBuffer());
        }
    }

    private void track(final Frame frame) {
        sent.add(frame);
        while (sent.size() > REPLAY_BUFFER_SIZE) {
            sent.pollFirst();
        }
    }

    private void emit(final ByteBuffer buffer) {
        final var current = subscriber;
        if (current == null) {
            return;
        }
        try {
            current.onNext(buffer);
        } catch (final RuntimeException re) {
            // a client going away mid-stream is the normal end of a SSE channel, not a server error
            logger.log(FINE, re, () -> "Can't write to the SSE channel, dropping it: " + re.getMessage());
            forget(current);
            try {
                current.onError(re);
            } catch (final RuntimeException nested) {
                logger.log(FINEST, nested, nested::getMessage);
            }
        }
    }

    private void complete(final Flow.Subscriber<? super ByteBuffer> current) {
        if (current == null) {
            return;
        }
        forget(current);
        try {
            current.onComplete();
        } catch (final RuntimeException re) {
            logger.log(FINEST, re, re::getMessage);
        }
    }

    private void forget(final Flow.Subscriber<? super ByteBuffer> current) {
        lock.lock();
        try {
            if (subscriber == current) {
                subscriber = null;
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Drains the buffered JSON frames - the notifications a request published while running - so the transport can
     * stream them before the final result. The keep-alive comments are dropped, they only make sense on a live stream.
     *
     * @return the queued JSON-RPC messages, in emission order.
     */
    public List<String> drainQueuedJson() {
        lock.lock();
        try {
            final var json = new ArrayList<String>(frames.size());
            for (final var iterator = frames.iterator(); iterator.hasNext(); ) {
                final var frame = iterator.next();
                if (frame.json() != null) {
                    json.add(frame.json());
                }
            }
            frames.removeIf(Frame::isReplayable);
            return json;
        } finally {
            lock.unlock();
        }
    }

    // visible for testing
    List<String> queued() {
        return frames.stream()
                .map(Frame::json)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    private record Frame(long id, String json) {
        private static final Frame KEEP_ALIVE_FRAME = new Frame(-1, null);

        private boolean isReplayable() {
            return json != null;
        }

        private ByteBuffer toBuffer() {
            if (json == null) {
                return KEEP_ALIVE.duplicate();
            }
            // a serialized JSON-RPC message has no raw newline so a single data line is always enough
            return ByteBuffer.wrap(("id: " + id + "\nevent: message\ndata: " + json + "\n\n").getBytes(UTF_8));
        }
    }
}
