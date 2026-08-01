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

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.jsonrpc.JsonRpcException;
import io.yupiik.fusion.mcp.model.Capabilities;
import io.yupiik.fusion.mcp.model.ClientInfo;
import io.yupiik.fusion.mcp.model.Content;
import io.yupiik.fusion.mcp.model.CreateMessageResponse;
import io.yupiik.fusion.mcp.model.CreateSamplingMessageParameters;
import io.yupiik.fusion.mcp.model.ElicitRequestParameters;
import io.yupiik.fusion.mcp.model.JsonSchema;
import io.yupiik.fusion.mcp.model.LoggingLevel;
import io.yupiik.fusion.mcp.model.MessageNotification;
import io.yupiik.fusion.mcp.model.ProgressNotification;
import io.yupiik.fusion.mcp.model.ResourceUpdatedNotification;
import io.yupiik.fusion.mcp.model.Role;
import io.yupiik.fusion.mcp.model.SamplingMessage;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static io.yupiik.fusion.testing.assertion.JsonAsserts.assertJsonEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The session, unit tested: what it queues on the SSE channel and how it correlates the client answers.
 */
@FusionSupport
class MCPSessionTest {
    @Test
    void state(@Fusion final JsonMapper jsons) {
        final var session = session(jsons);

        assertEquals("test-session", session.id());
        assertEquals(LoggingLevel.info, session.loggingLevel(), "info is the default level");
        assertFalse(session.isInitialized());

        session.onInitialize("2025-03-26", null, new ClientInfo("test", "Test", "1.0.0"));
        session.onInitialized();

        assertEquals("2025-03-26", session.protocolVersion());
        assertEquals("test", session.clientInfo().name());
        assertTrue(session.isInitialized());
    }

    @Test
    void notifyBuildsAJsonRpcEnvelope(@Fusion final JsonMapper jsons) {
        final var session = session(jsons);

        session.notify("notifications/custom", Map.of("a", "b"));

        assertJsonEquals("""
                        {"jsonrpc": "2.0", "method": "notifications/custom", "params": {"a": "b"}}""",
                session.sse().queued().get(0));
    }

    @Test
    void logIsFilteredByTheClientLevel(@Fusion final JsonMapper jsons) {
        final var session = session(jsons);

        session.log(new MessageNotification("test", LoggingLevel.debug, "dropped")); // info by default
        assertTrue(session.sse().queued().isEmpty());

        session.log(new MessageNotification("test", LoggingLevel.error, "kept"));
        assertEquals(1, session.sse().queued().size());

        session.setLoggingLevel(LoggingLevel.debug);
        session.log(new MessageNotification("test", LoggingLevel.debug, "now kept"));
        assertEquals(2, session.sse().queued().size());

        session.setLoggingLevel(null); // back to the default
        assertEquals(LoggingLevel.info, session.loggingLevel());
    }

    @Test
    void progressIsAlwaysSent(@Fusion final JsonMapper jsons) {
        final var session = session(jsons);

        session.progress(new ProgressNotification("t", .5, 1., "half"));

        assertTrue(session.sse().queued().get(0).contains("notifications/progress"));
    }

    @Test
    void resourceUpdatedNeedsASubscription(@Fusion final JsonMapper jsons) {
        final var session = session(jsons);
        final var notification = ResourceUpdatedNotification.of("app://config");

        session.resourceUpdated(notification);
        assertTrue(session.sse().queued().isEmpty(), "not subscribed, nothing is sent");

        session.subscribe("app://config");
        assertTrue(session.isSubscribedTo("app://config"));
        session.resourceUpdated(notification);
        assertEquals(1, session.sse().queued().size());

        session.unsubscribe("app://config");
        session.resourceUpdated(notification);
        assertEquals(1, session.sse().queued().size(), "unsubscribed, nothing more is sent");
    }

    @Test
    void samplingNeedsTheClientCapability(@Fusion final JsonMapper jsons) {
        final var session = initialized(jsons, new Capabilities(null, null, null, null));

        final var error = assertThrows(JsonRpcException.class, () -> session.createMessage(sampling()));

        assertEquals(-32601, error.code());
        assertTrue(error.getMessage().contains("sampling"), error.getMessage());
        assertTrue(session.sse().queued().isEmpty(), "nothing must be sent to a client which cannot answer");
    }

    @Test
    void elicitationNeedsTheClientCapability(@Fusion final JsonMapper jsons) {
        final var session = initialized(jsons, new Capabilities(null, Map.of(), null, null));

        final var error = assertThrows(JsonRpcException.class, () -> session.elicit(new ElicitRequestParameters(
                "which one?", JsonSchema.object(null, Map.of("a", JsonSchema.bool(null)), List.of("a")))));

        assertTrue(error.getMessage().contains("elicitation"), error.getMessage());
    }

    @Test
    void rootsNeedsTheClientCapability(@Fusion final JsonMapper jsons) {
        final var session = initialized(jsons, new Capabilities(null, Map.of(), Map.of(), null));

        final var error = assertThrows(JsonRpcException.class, session::listRoots);

        assertTrue(error.getMessage().contains("roots"), error.getMessage());
    }

    @Test
    void samplingIsCorrelatedWithTheClientResponse(@Fusion final JsonMapper jsons) throws Exception {
        final var session = initialized(jsons, new Capabilities(null, Map.of(), null, null));

        final var promise = session.createMessage(sampling()).toCompletableFuture();

        // the request went out on the SSE channel, with an id the client will echo
        final var request = session.sse().queued().get(0);
        assertTrue(request.contains("\"method\":\"sampling/createMessage\""), request);
        assertTrue(request.contains("\"id\":1"), request);
        assertFalse(promise.isDone(), "the request is pending until the client answers");

        assertTrue(session.onClientResponse(1, Map.of(
                "role", "assistant",
                "model", "test-model",
                "content", Map.of("type", "text", "text", "hello")), null));

        final var response = promise.get(5, TimeUnit.SECONDS);
        assertInstanceOf(CreateMessageResponse.class, response);
        assertEquals(Role.assistant, response.role());
        assertEquals("hello", response.content().text());
    }

    @Test
    void clientErrorFailsTheRequest(@Fusion final JsonMapper jsons) {
        final var session = initialized(jsons, new Capabilities(null, Map.of(), null, null));
        final var promise = session.createMessage(sampling()).toCompletableFuture();

        session.onClientResponse(1, null, Map.of("code", -1, "message", "user refused"));

        final var error = assertThrows(ExecutionException.class, () -> promise.get(5, TimeUnit.SECONDS));
        final var cause = assertInstanceOf(JsonRpcException.class, error.getCause());
        assertEquals(-1, cause.code());
        assertEquals("user refused", cause.getMessage());
    }

    @Test
    void unknownClientResponseIsIgnored(@Fusion final JsonMapper jsons) {
        final var session = session(jsons);

        assertFalse(session.onClientResponse(404, Map.of(), null), "nothing was pending for that id");
    }

    @Test
    void requestsTimeOut(@Fusion final JsonMapper jsons) {
        final var session = new MCPSession("timeout", jsons, Duration.ofMillis(150));
        session.onInitialize("2025-06-18", new Capabilities(null, Map.of(), null, null), null);

        final var error = assertThrows(
                CompletionException.class,
                () -> session.createMessage(sampling()).toCompletableFuture().join());

        assertInstanceOf(TimeoutException.class, error.getCause());
    }

    @Test
    void closeFailsThePendingRequests(@Fusion final JsonMapper jsons) {
        final var session = initialized(jsons, new Capabilities(null, Map.of(), null, null));
        final var promise = session.createMessage(sampling()).toCompletableFuture();
        session.subscribe("app://x");

        session.close();

        final var error = assertThrows(CompletionException.class, promise::join);
        final var cause = assertInstanceOf(JsonRpcException.class, error.getCause());
        assertEquals(-32001, cause.code());
        assertTrue(session.sse().isClosed(), "the channel is completed too");
        assertFalse(session.isSubscribedTo("app://x"), "the subscriptions are released");
    }

    @Test
    void expiration(@Fusion final JsonMapper jsons) throws Exception {
        final var session = session(jsons);

        assertFalse(session.isExpired(Duration.ofMinutes(30)));
        assertFalse(session.isExpired(Duration.ZERO), "a zero timeout disables the expiration");

        Thread.sleep(20);
        assertTrue(session.isExpired(Duration.ofMillis(10)));

        session.touch();
        assertFalse(session.isExpired(Duration.ofMinutes(1)), "touching postpones it");
    }

    @Test
    void anyRequestCanBeSent(@Fusion final JsonMapper jsons) throws Exception {
        final var session = session(jsons);

        final var promise = session.request("custom/thing", Map.of("a", 1), Map.class).toCompletableFuture();

        assertTrue(session.sse().queued().get(0).contains("\"method\":\"custom/thing\""));
        session.onClientResponse(1, Map.of("ok", true), null);
        assertEquals(Map.of("ok", true), promise.get(5, TimeUnit.SECONDS));
    }

    private MCPSession session(final JsonMapper jsons) {
        return new MCPSession("test-session", jsons, Duration.ofSeconds(5));
    }

    private MCPSession initialized(final JsonMapper jsons, final Capabilities capabilities) {
        final var session = session(jsons);
        session.onInitialize("2025-06-18", capabilities, new ClientInfo("test", "Test", "1.0.0"));
        return session;
    }

    private CreateSamplingMessageParameters sampling() {
        return new CreateSamplingMessageParameters(
                null, 16, List.of(new SamplingMessage(Role.user, Content.text("hi"))),
                null, null, null, null, null);
    }
}
