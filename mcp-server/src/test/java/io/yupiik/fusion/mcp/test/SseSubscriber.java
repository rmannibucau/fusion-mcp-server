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
package io.yupiik.fusion.mcp.test;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Flow;

/**
 * Collects what a {@code SseBus} writes, i.e. it stands for the HTTP layer reading the {@code text/event-stream}
 * body of a session.
 * <p>
 * Use {@link #strict(long)} unless the test is about a client going away, then {@link #broken(long)} - it fails on
 * every write, like a closed socket does.
 */
public class SseSubscriber implements Flow.Subscriber<ByteBuffer> {
    private static final String DATA_PREFIX = "data: ";

    private final List<String> received = new ArrayList<>();
    private final long request;
    private final boolean failOnNext;
    private final boolean failOnError;
    private final boolean cancelOnSubscribe;

    private Flow.Subscription subscription;
    private boolean completed;
    private Throwable error;

    private SseSubscriber(
            final long request, final boolean failOnNext, final boolean failOnError, final boolean cancelOnSubscribe) {
        this.request = request;
        this.failOnNext = failOnNext;
        this.failOnError = failOnError;
        this.cancelOnSubscribe = cancelOnSubscribe;
    }

    /**
     * @param request how much demand to signal on subscription and after every frame, {@code 0} to request nothing.
     * @return a subscriber reading everything and failing the test on an unexpected {@code onError}.
     */
    public static SseSubscriber strict(final long request) {
        return new SseSubscriber(request, false, true, false);
    }

    /**
     * @return a subscriber failing on every write, like a client which went away mid-stream.
     */
    public static SseSubscriber broken(final long request) {
        return new SseSubscriber(request, true, false, false);
    }

    /**
     * @return a subscriber failing on every write <b>and</b> on the notification of that failure.
     */
    public static SseSubscriber brokenBeyondRepair(final long request) {
        return new SseSubscriber(request, true, true, false);
    }

    /**
     * @return a subscriber giving up right away, i.e. a client which closed the connection during the handshake.
     */
    public static SseSubscriber cancelling() {
        return new SseSubscriber(0, false, true, true);
    }

    /**
     * @return every frame, comments - the greeting and the keep-alives - included.
     */
    public List<String> received() {
        return received;
    }

    /**
     * @return the received frames without the comments.
     */
    public List<String> messages() {
        return received.stream().filter(it -> !it.startsWith(":")).toList();
    }

    /**
     * @return the {@code data:} payload of every message frame, i.e. the JSON-RPC messages.
     */
    public List<String> data() {
        return messages().stream()
                .map(it -> it.substring(it.indexOf(DATA_PREFIX) + DATA_PREFIX.length())
                        .strip())
                .toList();
    }

    public Flow.Subscription subscription() {
        return subscription;
    }

    public boolean isCompleted() {
        return completed;
    }

    public Throwable error() {
        return error;
    }

    @Override
    public void onSubscribe(final Flow.Subscription subscription) {
        this.subscription = subscription;
        if (cancelOnSubscribe) {
            subscription.cancel();
            return;
        }
        if (request > 0) {
            subscription.request(request);
        }
    }

    @Override
    public void onNext(final ByteBuffer item) {
        received.add(UTF_8.decode(item).toString());
        if (failOnNext) {
            throw new IllegalStateException("the client went away");
        }
        if (request > 0) {
            subscription.request(request);
        }
    }

    @Override
    public void onError(final Throwable throwable) {
        error = throwable;
        if (failOnError) {
            throw new IllegalStateException(throwable);
        }
    }

    @Override
    public void onComplete() {
        completed = true;
    }
}
