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

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Flow;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SseBusTest {
    @Test
    void everySubscriptionIsGreetedWithAComment() {
        final var subscriber = new TestSubscriber(1);
        final var bus = new SseBus();
        bus.subscribe(subscriber);

        // it commits the HTTP response so the client knows the stream is live before any message
        assertEquals(List.of(": ping\n\n"), subscriber.received);
    }

    @Test
    void frameFormat() {
        final var subscriber = new TestSubscriber(1);
        final var bus = new SseBus();
        bus.subscribe(subscriber);
        bus.publish("{\"jsonrpc\":\"2.0\"}");

        assertEquals(List.of("id: 1\nevent: message\ndata: {\"jsonrpc\":\"2.0\"}\n\n"), subscriber.messages());
    }

    @Test
    void bufferUntilSubscription() {
        final var bus = new SseBus();
        bus.publish("{\"a\":1}");
        bus.publish("{\"a\":2}");

        assertEquals(List.of("{\"a\":1}", "{\"a\":2}"), bus.queued());

        final var subscriber = new TestSubscriber(1);
        bus.subscribe(subscriber);

        assertEquals(2, subscriber.messages().size());
        assertTrue(bus.queued().isEmpty());
        assertTrue(subscriber.messages().get(0).contains("data: {\"a\":1}"));
        assertTrue(subscriber.messages().get(1).contains("data: {\"a\":2}"));
    }

    @Test
    void backpressure() {
        final var subscriber = new TestSubscriber(0); // requests nothing
        final var bus = new SseBus();
        bus.subscribe(subscriber);
        bus.publish("{\"a\":1}");

        assertTrue(subscriber.received.isEmpty(), "not even the greeting is written without demand");

        subscriber.subscription.request(1);
        assertEquals(List.of(": ping\n\n"), subscriber.received, "one item of demand, one frame: the greeting");

        subscriber.subscription.request(1);
        assertEquals(1, subscriber.messages().size(), "the next one delivers the message");
    }

    @Test
    void keepAliveIsAComment() {
        final var subscriber = new TestSubscriber(1);
        final var bus = new SseBus();
        bus.subscribe(subscriber);
        bus.keepAlive();

        assertEquals(List.of(": ping\n\n", ": ping\n\n"), subscriber.received, "the greeting plus the explicit one");
        assertEquals(0, bus.lastEventId(), "a keep-alive is not a message so it must not consume an event id");
    }

    @Test
    void replay() {
        final var first = new TestSubscriber(1);
        final var bus = new SseBus();
        bus.subscribe(first);
        bus.publish("{\"a\":1}");
        bus.publish("{\"a\":2}");
        bus.publish("{\"a\":3}");
        assertEquals(3, first.messages().size());

        // like the transport does: arm the replay, then let the HTTP layer subscribe
        bus.replayFrom(1); // the client got the first event only
        final var reconnected = new TestSubscriber(1);
        bus.subscribe(reconnected);

        assertEquals(2, reconnected.messages().size());
        assertTrue(reconnected.messages().get(0).contains("data: {\"a\":2}"), reconnected.messages().toString());
        assertTrue(reconnected.messages().get(1).contains("data: {\"a\":3}"), reconnected.messages().toString());
    }

    @Test
    void subscribingAgainSupersedesThePreviousStream() {
        final var first = new TestSubscriber(1);
        final var bus = new SseBus();
        bus.subscribe(first);

        final var second = new TestSubscriber(1);
        bus.subscribe(second);

        assertTrue(first.completed, "at most one stream per session, the previous one is completed");
        assertFalse(second.completed);

        bus.publish("{\"a\":1}");
        assertTrue(first.messages().isEmpty(), "nothing is delivered to the superseded stream");
        assertEquals(1, second.messages().size());
    }

    @Test
    void replayBufferIsBounded() {
        final var subscriber = new TestSubscriber(1);
        final var bus = new SseBus();
        bus.subscribe(subscriber);
        for (int i = 1; i <= 130; i++) {
            bus.publish("{\"a\":" + i + "}");
        }
        assertEquals(130, subscriber.messages().size());
        assertEquals(130, bus.lastEventId(), "every message consumes an event id");

        bus.replayFrom(0); // ask for everything, only the last 128 are kept
        final var reconnected = new TestSubscriber(1);
        bus.subscribe(reconnected);

        assertEquals(128, reconnected.messages().size());
        assertTrue(reconnected.messages().get(0).contains("data: {\"a\":3}"), reconnected.messages().get(0));
    }

    @Test
    void keepAliveIsNotReplayed() {
        final var subscriber = new TestSubscriber(1);
        final var bus = new SseBus();
        bus.subscribe(subscriber);
        bus.publish("{\"a\":1}");
        bus.keepAlive();

        bus.replayFrom(0);
        final var reconnected = new TestSubscriber(1);
        bus.subscribe(reconnected);

        assertEquals(1, reconnected.messages().size(), "a comment is not a message, it is not replayed");
        assertTrue(reconnected.messages().get(0).contains("data: {\"a\":1}"));
    }

    @Test
    void cancelCompletesTheStream() {
        final var subscriber = new TestSubscriber(1);
        final var bus = new SseBus();
        bus.subscribe(subscriber);

        assertFalse(subscriber.completed);
        bus.cancel();

        assertTrue(subscriber.completed);
        assertTrue(bus.isClosed());
    }

    @Test
    void lateSubscriberOfAClosedBusIsCompleted() {
        final var bus = new SseBus();
        bus.cancel();

        final var subscriber = new TestSubscriber(1);
        bus.subscribe(subscriber);

        assertTrue(subscriber.completed);
    }

    /**
     * Mimics the HTTP layer: it requests one item at a time and does it from within {@code onNext}, which makes the
     * bus drain loop re-entrant.
     */
    private static class TestSubscriber implements Flow.Subscriber<ByteBuffer> {
        private final List<String> received = new ArrayList<>();
        private final long request;
        private Flow.Subscription subscription;
        private boolean completed;

        private TestSubscriber(final long request) {
            this.request = request;
        }

        /**
         * @return the received frames without the comments - the greeting and the keep-alives.
         */
        private List<String> messages() {
            return received.stream().filter(it -> !it.startsWith(":")).toList();
        }

        @Override
        public void onSubscribe(final Flow.Subscription subscription) {
            this.subscription = subscription;
            if (request > 0) {
                subscription.request(request);
            }
        }

        @Override
        public void onNext(final ByteBuffer item) {
            received.add(UTF_8.decode(item).toString());
            if (request > 0) {
                subscription.request(request);
            }
        }

        @Override
        public void onError(final Throwable throwable) {
            throw new IllegalStateException(throwable);
        }

        @Override
        public void onComplete() {
            completed = true;
        }
    }
}
