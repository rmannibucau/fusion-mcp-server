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
    void frameFormat() {
        final var subscriber = new TestSubscriber(1);
        final var bus = new SseBus();
        bus.subscribe(subscriber);
        bus.publish("{\"jsonrpc\":\"2.0\"}");

        assertEquals(List.of("id: 1\nevent: message\ndata: {\"jsonrpc\":\"2.0\"}\n\n"), subscriber.received);
    }

    @Test
    void bufferUntilSubscription() {
        final var bus = new SseBus();
        bus.publish("{\"a\":1}");
        bus.publish("{\"a\":2}");

        assertEquals(List.of("{\"a\":1}", "{\"a\":2}"), bus.queued());

        final var subscriber = new TestSubscriber(1);
        bus.subscribe(subscriber);

        assertEquals(2, subscriber.received.size());
        assertTrue(bus.queued().isEmpty());
        assertTrue(subscriber.received.get(0).contains("data: {\"a\":1}"));
        assertTrue(subscriber.received.get(1).contains("data: {\"a\":2}"));
    }

    @Test
    void backpressure() {
        final var subscriber = new TestSubscriber(0); // requests nothing
        final var bus = new SseBus();
        bus.subscribe(subscriber);
        bus.publish("{\"a\":1}");

        assertTrue(subscriber.received.isEmpty());

        subscriber.subscription.request(1);
        assertEquals(1, subscriber.received.size());
    }

    @Test
    void keepAliveIsAComment() {
        final var subscriber = new TestSubscriber(1);
        final var bus = new SseBus();
        bus.subscribe(subscriber);
        bus.keepAlive();

        assertEquals(List.of(": ping\n\n"), subscriber.received);
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
        assertEquals(3, first.received.size());

        final var reconnected = new TestSubscriber(1);
        bus.subscribe(reconnected);
        bus.replayFrom(1); // the client got the first event only

        assertEquals(2, reconnected.received.size());
        assertTrue(reconnected.received.get(0).contains("data: {\"a\":2}"), reconnected.received.toString());
        assertTrue(reconnected.received.get(1).contains("data: {\"a\":3}"), reconnected.received.toString());
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
