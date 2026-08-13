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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.mcp.test.Loggers;
import io.yupiik.fusion.mcp.test.SseSubscriber;
import java.util.List;
import java.util.concurrent.Flow;
import org.junit.jupiter.api.Test;

class SseBusTest {
    @Test
    void everySubscriptionIsGreetedWithAComment() {
        final var subscriber = SseSubscriber.strict(1);
        final var bus = new SseBus();
        bus.subscribe(subscriber);

        // it commits the HTTP response so the client knows the stream is live before any message
        assertEquals(List.of(": ping\n\n"), subscriber.received());
    }

    @Test
    void frameFormat() {
        final var subscriber = SseSubscriber.strict(1);
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

        final var subscriber = SseSubscriber.strict(1);
        bus.subscribe(subscriber);

        assertEquals(2, subscriber.messages().size());
        assertTrue(bus.queued().isEmpty());
        assertTrue(subscriber.messages().get(0).contains("data: {\"a\":1}"));
        assertTrue(subscriber.messages().get(1).contains("data: {\"a\":2}"));
    }

    @Test
    void drainingQueuedJsonReturnsOnlyTheMessages() {
        // what the stateless transport streams before the final result: the buffered JSON, without keep-alives
        final var bus = new SseBus();
        bus.publish("{\"a\":1}");
        bus.publish("{\"a\":2}");

        assertEquals(List.of("{\"a\":1}", "{\"a\":2}"), bus.drainQueuedJson());
        assertTrue(bus.drainQueuedJson().isEmpty(), "the drained frames are dropped, they are not replayed");
    }

    @Test
    void backpressure() {
        final var subscriber = SseSubscriber.strict(0); // requests nothing
        final var bus = new SseBus();
        bus.subscribe(subscriber);
        bus.publish("{\"a\":1}");

        assertTrue(subscriber.received().isEmpty(), "not even the greeting is written without demand");

        subscriber.subscription().request(1);
        assertEquals(List.of(": ping\n\n"), subscriber.received(), "one item of demand, one frame: the greeting");

        subscriber.subscription().request(1);
        assertEquals(1, subscriber.messages().size(), "the next one delivers the message");
    }

    @Test
    void keepAliveIsAComment() {
        final var subscriber = SseSubscriber.strict(1);
        final var bus = new SseBus();
        bus.subscribe(subscriber);
        bus.keepAlive();

        assertEquals(List.of(": ping\n\n", ": ping\n\n"), subscriber.received(), "the greeting plus the explicit one");
        assertEquals(0, bus.lastEventId(), "a keep-alive is not a message so it must not consume an event id");
    }

    @Test
    void replay() {
        final var first = SseSubscriber.strict(1);
        final var bus = new SseBus();
        bus.subscribe(first);
        bus.publish("{\"a\":1}");
        bus.publish("{\"a\":2}");
        bus.publish("{\"a\":3}");
        assertEquals(3, first.messages().size());

        // like the transport does: arm the replay, then let the HTTP layer subscribe
        bus.replayFrom(1); // the client got the first event only
        final var reconnected = SseSubscriber.strict(1);
        bus.subscribe(reconnected);

        assertEquals(2, reconnected.messages().size());
        assertTrue(
                reconnected.messages().get(0).contains("data: {\"a\":2}"),
                reconnected.messages().toString());
        assertTrue(
                reconnected.messages().get(1).contains("data: {\"a\":3}"),
                reconnected.messages().toString());
    }

    @Test
    void subscribingAgainSupersedesThePreviousStream() {
        final var first = SseSubscriber.strict(1);
        final var bus = new SseBus();
        bus.subscribe(first);

        final var second = SseSubscriber.strict(1);
        bus.subscribe(second);

        assertTrue(first.isCompleted(), "at most one stream per session, the previous one is completed");
        assertFalse(second.isCompleted());

        bus.publish("{\"a\":1}");
        assertTrue(first.messages().isEmpty(), "nothing is delivered to the superseded stream");
        assertEquals(1, second.messages().size());
    }

    @Test
    void replayBufferIsBounded() {
        final var subscriber = SseSubscriber.strict(1);
        final var bus = new SseBus();
        bus.subscribe(subscriber);
        for (int i = 1; i <= 130; i++) {
            bus.publish("{\"a\":" + i + "}");
        }
        assertEquals(130, subscriber.messages().size());
        assertEquals(130, bus.lastEventId(), "every message consumes an event id");

        bus.replayFrom(0); // ask for everything, only the last 128 are kept
        final var reconnected = SseSubscriber.strict(1);
        bus.subscribe(reconnected);

        assertEquals(128, reconnected.messages().size());
        assertTrue(
                reconnected.messages().get(0).contains("data: {\"a\":3}"),
                reconnected.messages().get(0));
    }

    @Test
    void keepAliveIsNotReplayed() {
        final var subscriber = SseSubscriber.strict(1);
        final var bus = new SseBus();
        bus.subscribe(subscriber);
        bus.publish("{\"a\":1}");
        bus.keepAlive();

        bus.replayFrom(0);
        final var reconnected = SseSubscriber.strict(1);
        bus.subscribe(reconnected);

        assertEquals(1, reconnected.messages().size(), "a comment is not a message, it is not replayed");
        assertTrue(reconnected.messages().get(0).contains("data: {\"a\":1}"));
    }

    @Test
    void cancelCompletesTheStream() {
        final var subscriber = SseSubscriber.strict(1);
        final var bus = new SseBus();
        bus.subscribe(subscriber);

        assertFalse(subscriber.isCompleted());
        bus.cancel();

        assertTrue(subscriber.isCompleted());
        assertTrue(bus.isClosed());
    }

    @Test
    void lateSubscriberOfAClosedBusIsCompleted() {
        final var bus = new SseBus();
        bus.cancel();

        final var subscriber = SseSubscriber.strict(1);
        bus.subscribe(subscriber);

        assertTrue(subscriber.isCompleted());
    }

    @Test
    void cancelIsIdempotent() {
        final var bus = new SseBus();
        final var subscriber = SseSubscriber.strict(1);
        bus.subscribe(subscriber);

        bus.cancel();
        assertTrue(bus.isClosed());
        assertTrue(subscriber.isCompleted());

        // the second call must not complete the - now unrelated - subscriber again
        bus.cancel();
        assertTrue(bus.isClosed());
    }

    @Test
    void aNonPositiveDemandIsARequestViolation() {
        final var bus = new SseBus();
        final var subscriber = SseSubscriber.broken(0); // records onError instead of failing the test
        bus.subscribe(subscriber);

        subscriber.subscription().request(0);
        assertInstanceOf(IllegalArgumentException.class, subscriber.error());
        assertEquals("Invalid request: 0", subscriber.error().getMessage());

        subscriber.subscription().request(-5);
        assertEquals("Invalid request: -5", subscriber.error().getMessage());

        // nothing was delivered, an invalid request adds no demand
        assertTrue(subscriber.received().isEmpty());
    }

    @Test
    void anUnboundedDemandIsNeverDecremented() {
        final var bus = new SseBus();
        final var subscriber = SseSubscriber.strict(0);
        bus.subscribe(subscriber);

        subscriber.subscription().request(Long.MAX_VALUE);
        // more demand on top of an already unbounded one stays unbounded
        subscriber.subscription().request(1);
        bus.publish("{\"a\":1}");
        bus.publish("{\"a\":2}");

        assertEquals(2, subscriber.messages().size());
    }

    @Test
    void anOverflowingDemandIsClampedToUnbounded() {
        final var bus = new SseBus();
        final var subscriber = SseSubscriber.strict(0);
        bus.subscribe(subscriber);

        subscriber.subscription().request(Long.MAX_VALUE - 1);
        subscriber.subscription().request(10); // would wrap around to a negative amount of demand
        bus.publish("{\"a\":1}");

        assertEquals(1, subscriber.messages().size());
    }

    @Test
    void cancellingTheSubscriptionStopsTheDelivery() {
        final var bus = new SseBus();
        final var subscriber = SseSubscriber.strict(1);
        bus.subscribe(subscriber);
        assertEquals(1, subscriber.received().size(), "the greeting");

        subscriber.subscription().cancel();
        bus.publish("{\"a\":1}");

        assertTrue(subscriber.messages().isEmpty());
        // the frame stays queued, the client will get it when it opens a new stream
        assertEquals(List.of("{\"a\":1}"), bus.queued());
    }

    @Test
    void cancellingASupersededSubscriptionDoesNotDropTheLiveOne() {
        final var bus = new SseBus();
        final var first = SseSubscriber.strict(1);
        bus.subscribe(first);
        final var second = SseSubscriber.strict(1);
        bus.subscribe(second);

        first.subscription().cancel(); // it is not the current subscriber anymore, this must be a no-op
        bus.publish("{\"a\":1}");

        assertEquals(1, second.messages().size());
    }

    @Test
    void aClientGoingAwayMidStreamDropsTheChannel() {
        final var bus = new SseBus();
        final var subscriber = SseSubscriber.broken(1);

        // a write failure is the normal end of a SSE channel, not a server error
        Loggers.atFinest(SseBus.class, () -> bus.subscribe(subscriber));

        assertInstanceOf(IllegalStateException.class, subscriber.error());
        assertEquals("the client went away", subscriber.error().getMessage());
        // the channel was forgotten, so publishing again only queues
        bus.publish("{\"a\":1}");
        assertEquals(List.of("{\"a\":1}"), bus.queued());
    }

    @Test
    void aClientFailingOnTheFailureNotificationIsStillDropped() {
        final var bus = new SseBus();
        final var subscriber = SseSubscriber.brokenBeyondRepair(1);

        Loggers.atFinest(SseBus.class, () -> bus.subscribe(subscriber));

        bus.publish("{\"a\":1}");
        assertEquals(List.of("{\"a\":1}"), bus.queued());
    }

    @Test
    void aClientGivingUpDuringTheHandshakeOfAClosedBusIsSafe() {
        final var bus = new SseBus();
        bus.cancel();

        // it cancels from onSubscribe, so the bus has no subscriber left to complete when it notices it is closed
        bus.subscribe(SseSubscriber.cancelling());

        assertTrue(bus.isClosed());
    }

    @Test
    void endDeliversTheLastMessageThenCloses() {
        final var subscriber = SseSubscriber.strict(1);
        final var bus = new SseBus();
        bus.subscribe(subscriber);

        bus.end("{\"jsonrpc\":\"2.0\",\"result\":{}}");

        assertEquals(List.of("{\"jsonrpc\":\"2.0\",\"result\":{}}"), subscriber.data());
        assertTrue(subscriber.isCompleted());
        assertTrue(bus.isClosed());
    }

    @Test
    void onCloseIsInvokedOnceOnCancel() {
        final var bus = new SseBus();
        final var notifications = new java.util.concurrent.atomic.AtomicInteger();
        bus.onClose(notifications::incrementAndGet);

        bus.cancel();
        bus.cancel(); // idempotent: the hook fires once

        assertEquals(1, notifications.get());
        assertTrue(bus.isClosed());
    }

    @Test
    void cancellingWithoutAnyHookIsSafe() {
        // no onClose registered: nothing to notify, but closing still works
        final var bus = new SseBus();
        assertDoesNotThrow(bus::cancel);
        assertTrue(bus.isClosed());
        assertDoesNotThrow(bus::cancel);
    }

    @Test
    void aFailingOnCloseHookIsSwallowed() {
        final var bus = new SseBus();
        // the owner bug is not the stream's problem: the close still succeeds
        bus.onClose(() -> {
            throw new IllegalStateException("the owner blew up");
        });

        assertDoesNotThrow(bus::cancel);
        assertTrue(bus.isClosed());
    }

    @Test
    void aCompletionFailingSubscriberIsNotClosureDoomForTheBus() {
        final var bus = new SseBus();
        final var failing = new Flow.Subscriber<java.nio.ByteBuffer>() {
            @Override
            public void onSubscribe(final Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(final java.nio.ByteBuffer item) {
                // a normal write
            }

            @Override
            public void onError(final Throwable throwable) {
                throw new IllegalStateException(throwable);
            }

            @Override
            public void onComplete() {
                throw new IllegalStateException("the stream bails out on completion");
            }
        };

        bus.subscribe(failing);
        // the completion failure is swallowed, the bus still closes
        assertDoesNotThrow(bus::cancel);
        assertTrue(bus.isClosed());
    }
}
