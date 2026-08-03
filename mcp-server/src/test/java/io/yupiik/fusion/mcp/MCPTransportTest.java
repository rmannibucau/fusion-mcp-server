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
package io.yupiik.fusion.mcp;

import static io.yupiik.fusion.testing.assertion.JsonAsserts.assertJsonEquals;
import static java.net.http.HttpResponse.BodyHandlers.ofString;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.mcp.api.MCPNotifier;
import io.yupiik.fusion.mcp.client.MCPClient;
import io.yupiik.fusion.mcp.model.LoggingLevel;
import io.yupiik.fusion.mcp.protocol.MCPEndpoint;
import io.yupiik.fusion.mcp.protocol.MCPProtocol;
import io.yupiik.fusion.mcp.protocol.MCPSSEProtocol;
import io.yupiik.fusion.mcp.test.Loggers;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Iterator;
import org.junit.jupiter.api.Test;

/**
 * The streamable HTTP transport itself: sessions, headers, {@code DELETE} and the SSE channel.
 */
@FusionSupport
class MCPTransportTest {
    @Test
    void sessionLifecycle(@Fusion final URI mcpEndpoint, @Fusion final HttpClient http) throws Exception {
        try (final var client = new MCPClient(mcpEndpoint, http)) {
            client.initialize().toCompletableFuture().join();
            final var session = client.session();

            assertEquals(
                    200,
                    client.call(2, "ping", "{}").toCompletableFuture().join().statusCode());
            assertEquals(204, client.terminate().toCompletableFuture().join().statusCode());

            // the session is gone, both a new call and a second delete must be rejected
            assertEquals(
                    404,
                    client.call(3, "ping", "{}").toCompletableFuture().join().statusCode());
            assertEquals(404, client.terminate().toCompletableFuture().join().statusCode());
            assertEquals(session, client.session());
        }
    }

    @Test
    void deleteWithoutSession(@Fusion final URI mcpEndpoint, @Fusion final HttpClient http) throws Exception {
        final var res = http.send(HttpRequest.newBuilder(mcpEndpoint).DELETE().build(), ofString());
        assertEquals(400, res.statusCode());
    }

    @Test
    void unknownSessionIsRejected(@Fusion final URI mcpEndpoint, @Fusion final HttpClient http) throws Exception {
        try (final var client = new MCPClient(mcpEndpoint, http).session("i-made-it-up")) {
            final var res = client.call(1, "ping", "{}").toCompletableFuture().join();

            assertEquals(404, res.statusCode());
            assertTrue(res.body().contains("Unknown or expired MCP session"), res.body());
        }
    }

    @Test
    void eachInitializeGetsItsOwnSession(@Fusion final URI mcpEndpoint, @Fusion final HttpClient http)
            throws Exception {
        try (final var first = new MCPClient(mcpEndpoint, http);
                final var second = new MCPClient(mcpEndpoint, http)) {
            first.initialize().toCompletableFuture().join();
            second.initialize().toCompletableFuture().join();

            assertNotEquals(first.session(), second.session());
        }
    }

    @Test
    void unsupportedProtocolVersionHeader(@Fusion final URI mcpEndpoint, @Fusion final HttpClient http)
            throws Exception {
        final var res = http.send(
                HttpRequest.newBuilder(mcpEndpoint)
                        .POST(HttpRequest.BodyPublishers.ofString("""
                                {"jsonrpc": "2.0", "id": 1, "method": "ping", "params": {}}"""))
                        .header("content-type", "application/json")
                        .header(MCPProtocol.PROTOCOL_VERSION_HEADER, "1999-01-01")
                        .build(),
                ofString());

        assertEquals(400, res.statusCode());
        assertTrue(res.body().contains("Unsupported mcp-protocol-version"), res.body());
    }

    @Test
    void supportedProtocolVersionHeader(@Fusion final URI mcpEndpoint, @Fusion final HttpClient http) throws Exception {
        final var res = http.send(
                HttpRequest.newBuilder(mcpEndpoint)
                        .POST(HttpRequest.BodyPublishers.ofString("""
                                {"jsonrpc": "2.0", "id": 1, "method": "ping", "params": {}}"""))
                        .header("content-type", "application/json")
                        .header(MCPProtocol.PROTOCOL_VERSION_HEADER, MCPProtocol.LATEST_VERSION)
                        .build(),
                ofString());

        assertEquals(200, res.statusCode());
    }

    @Test
    void statelessClientIsTolerated(@Fusion final URI mcpEndpoint, @Fusion final HttpClient http) throws Exception {
        // a client ignoring the session header keeps working, it just gets no server to client channel
        final var res = http.send(
                HttpRequest.newBuilder(mcpEndpoint)
                        .POST(HttpRequest.BodyPublishers.ofString("""
                                {"jsonrpc": "2.0", "id": 1, "method": "tools/list", "params": {}}"""))
                        .header("content-type", "application/json")
                        .build(),
                ofString());

        assertEquals(200, res.statusCode());
        assertJsonEquals("""
                {"jsonrpc": "2.0", "id": 1, "result": {"tools": []}}""", res.body());
    }

    @Test
    void sseGetUnknownSession(@Fusion final URI mcpEndpoint, @Fusion final HttpClient http) throws Exception {
        final var res = http.send(
                HttpRequest.newBuilder(mcpEndpoint)
                        .GET()
                        .header("accept", "text/event-stream")
                        .header(MCPProtocol.SESSION_HEADER, "i-made-it-up")
                        .build(),
                ofString());

        assertEquals(404, res.statusCode());
    }

    @Test
    void logNotificationIsPushedOnSse(
            @Fusion final URI mcpEndpoint, @Fusion final HttpClient http, @Fusion final MCPNotifier notifier)
            throws Exception {
        try (final var client = new MCPClient(mcpEndpoint, http)) {
            client.initialize().toCompletableFuture().join();

            // published before the stream is opened on purpose: messages are buffered per session
            notifier.log(LoggingLevel.warning, "test", "hello sse!");

            assertJsonEquals(
                    """
                            {
                              "jsonrpc": "2.0",
                              "method": "notifications/message",
                              "params": {"level": "warning", "logger": "test", "data": "hello sse!"}
                            }""",
                    client.openSse()
                            .thenCompose(client::nextMessage)
                            .toCompletableFuture()
                            .join());
        }
    }

    @Test
    void logNotificationIsFilteredByLevel(
            @Fusion final URI mcpEndpoint, @Fusion final HttpClient http, @Fusion final MCPNotifier notifier)
            throws Exception {
        try (final var client = new MCPClient(mcpEndpoint, http)) {
            client.initialize().toCompletableFuture().join();
            client.notify("logging/setLevel", """
                    {"level": "error"}""").toCompletableFuture().join();

            notifier.log(LoggingLevel.debug, "test", "dropped");
            notifier.log(LoggingLevel.critical, "test", "kept");

            final var message = client.openSse()
                    .thenCompose(client::nextMessage)
                    .toCompletableFuture()
                    .join();
            assertTrue(message.contains("\"kept\""), message);
        }
    }

    @Test
    void listChangedNotification(
            @Fusion final URI mcpEndpoint, @Fusion final HttpClient http, @Fusion final MCPNotifier notifier)
            throws Exception {
        try (final var client = new MCPClient(mcpEndpoint, http)) {
            client.initialize().toCompletableFuture().join();
            notifier.toolListChanged();

            assertJsonEquals(
                    """
                            {"jsonrpc": "2.0", "method": "notifications/tools/list_changed", "params": {}}""",
                    client.openSse()
                            .thenCompose(client::nextMessage)
                            .toCompletableFuture()
                            .join());
        }
    }

    @Test
    void resourceUpdatedOnlyGoesToSubscribers(
            @Fusion final URI mcpEndpoint, @Fusion final HttpClient http, @Fusion final MCPNotifier notifier)
            throws Exception {
        try (final var client = new MCPClient(mcpEndpoint, http)) {
            client.initialize().toCompletableFuture().join();

            client.notify("resources/subscribe", """
                    {"uri": "demo://greeting"}""").toCompletableFuture().join();
            notifier.resourceUpdated("demo://not-subscribed"); // dropped, the session does not watch it
            notifier.resourceUpdated("demo://greeting");

            assertJsonEquals(
                    """
                            {
                              "jsonrpc": "2.0",
                              "method": "notifications/resources/updated",
                              "params": {"uri": "demo://greeting"}
                            }""",
                    client.openSse()
                            .thenCompose(client::nextMessage)
                            .toCompletableFuture()
                            .join());
        }
    }

    @Test
    void invalidJsonIsAParseError(@Fusion final URI mcpEndpoint, @Fusion final HttpClient http) throws Exception {
        try (final var client = new MCPClient(mcpEndpoint, http)) {
            final var res = client.post("{not json").toCompletableFuture().join();

            assertEquals(200, res.statusCode());
            assertTrue(res.body().contains("\"code\":-32700"), res.body());
        }
    }

    @Test
    void emptyBodyIsAParseError(@Fusion final URI mcpEndpoint, @Fusion final HttpClient http) throws Exception {
        try (final var client = new MCPClient(mcpEndpoint, http)) {
            final var res = client.post("null").toCompletableFuture().join();

            assertEquals(200, res.statusCode());
            assertTrue(res.body().contains("\"code\":-32700"), res.body());
        }
    }

    @Test
    void emptyBatchHasNothingToAnswer(@Fusion final URI mcpEndpoint, @Fusion final HttpClient http) throws Exception {
        try (final var client = new MCPClient(mcpEndpoint, http)) {
            client.initialize().toCompletableFuture().join();

            final var res = client.post("[]").toCompletableFuture().join();

            assertEquals(202, res.statusCode());
            assertEquals("", res.body());
        }
    }

    @Test
    void batchMixingARequestAndAClientResponse(@Fusion final URI mcpEndpoint, @Fusion final HttpClient http)
            throws Exception {
        try (final var client = new MCPClient(mcpEndpoint, http)) {
            client.initialize().toCompletableFuture().join();

            // the response targets no pending request so it is ignored, the request is still answered
            final var res = client.post("""
                    [
                      {"jsonrpc": "2.0", "id": 7, "result": {"nothing": "pending"}},
                      {"jsonrpc": "2.0", "id": 8, "method": "ping", "params": {}}
                    ]""").toCompletableFuture().join();

            assertEquals(200, res.statusCode());
            assertJsonEquals("""
                    [{"jsonrpc": "2.0", "id": 8, "result": {}}]""", res.body());
        }
    }

    @Test
    void batchOfNotificationsOnly(@Fusion final URI mcpEndpoint, @Fusion final HttpClient http) throws Exception {
        try (final var client = new MCPClient(mcpEndpoint, http)) {
            client.initialize().toCompletableFuture().join();

            final var res = client.post("""
                    [
                      {"jsonrpc": "2.0", "method": "notifications/roots/list_changed"},
                      {"jsonrpc": "2.0", "method": "notifications/cancelled", "params": {"requestId": 1}}
                    ]""").toCompletableFuture().join();

            assertEquals(202, res.statusCode());
            assertEquals("", res.body());
        }
    }

    @Test
    void lastEventIdReplaysTheMissedMessages(
            @Fusion final URI mcpEndpoint, @Fusion final HttpClient http, @Fusion final MCPNotifier notifier)
            throws Exception {
        try (final var client = new MCPClient(mcpEndpoint, http)) {
            client.initialize().toCompletableFuture().join();

            notifier.log(LoggingLevel.error, "test", "first");
            notifier.log(LoggingLevel.error, "test", "second");

            // read both, then reconnect stating only the first one was processed
            final var stream = client.openSse().toCompletableFuture().join();
            assertTrue(client.nextMessage(stream).toCompletableFuture().join().contains("first"));
            assertTrue(client.nextMessage(stream).toCompletableFuture().join().contains("second"));

            final var resumed = client.openSse("1")
                    .thenCompose(client::nextMessage)
                    .toCompletableFuture()
                    .join();
            assertTrue(resumed.contains("second"), resumed);
        }
    }

    @Test
    void deleteClosesTheStream(
            @Fusion final URI mcpEndpoint, @Fusion final HttpClient http, @Fusion final MCPNotifier notifier)
            throws Exception {
        try (final var client = new MCPClient(mcpEndpoint, http)) {
            client.initialize().toCompletableFuture().join();
            final var stream = client.openSse().toCompletableFuture().join();

            assertEquals(204, client.terminate().toCompletableFuture().join().statusCode());

            // the session is gone: nothing is delivered anymore - the stream ended or simply stays silent
            notifier.log(LoggingLevel.error, "test", "never delivered");
            assertNothingDelivered(client, stream);
        }
    }

    @Test
    void reconnectingKeepsDeliveringOnTheNewStream(
            @Fusion final URI mcpEndpoint, @Fusion final HttpClient http, @Fusion final MCPNotifier notifier)
            throws Exception {
        try (final var client = new MCPClient(mcpEndpoint, http)) {
            client.initialize().toCompletableFuture().join();
            client.openSse().toCompletableFuture().join();

            // at most one stream per session: reconnecting supersedes the previous one - see SseBusTest for the unit
            // level assertion - and the session keeps working
            final var second = client.openSse().toCompletableFuture().join();
            notifier.log(LoggingLevel.error, "test", "for the second stream");

            assertTrue(client.nextMessage(second).toCompletableFuture().join().contains("for the second stream"));
        }
    }

    @Test
    void unknownSessionOnDeleteAndSse(@Fusion final URI mcpEndpoint, @Fusion final HttpClient http) throws Exception {
        try (final var client = new MCPClient(mcpEndpoint, http).session("i-made-it-up")) {
            assertEquals(404, client.terminate().toCompletableFuture().join().statusCode());
            assertThrows(
                    Exception.class,
                    () -> client.openSse().toCompletableFuture().join());
        }
    }

    @Test
    void anInvalidLastEventIdIsIgnored(
            @Fusion final URI mcpEndpoint, @Fusion final HttpClient http, @Fusion final MCPNotifier notifier) {
        try (final var client = new MCPClient(mcpEndpoint, http)) {
            client.initialize().toCompletableFuture().join();
            notifier.log(LoggingLevel.error, "test", "hello");

            // a client sending garbage must get a live stream and not an error, it simply gets no replay
            final var stream = Loggers.atFinest(
                    MCPSSEProtocol.class,
                    () -> client.openSse("not-a-number").toCompletableFuture().join());

            assertTrue(client.nextMessage(stream).toCompletableFuture().join().contains("hello"));
        }
    }

    @Test
    void aClientResponseWithoutANumericIdIsIgnored(@Fusion final URI mcpEndpoint, @Fusion final HttpClient http) {
        try (final var client = new MCPClient(mcpEndpoint, http)) {
            client.initialize().toCompletableFuture().join();

            // this server only sends numeric request ids, so there is nothing such a response could complete
            final var res = Loggers.atFinest(
                    MCPEndpoint.class,
                    () -> client.post("""
                    {"jsonrpc": "2.0", "id": "not-a-number", "result": {}}""").toCompletableFuture().join());

            assertEquals(202, res.statusCode());
            assertEquals("", res.body());
        }
    }

    @Test
    void aClientErrorResponseIsAcceptedToo(@Fusion final URI mcpEndpoint, @Fusion final HttpClient http) {
        try (final var client = new MCPClient(mcpEndpoint, http)) {
            client.initialize().toCompletableFuture().join();

            // nothing is pending here, what matters is that the transport routes it instead of trying to execute it
            final var res = client.post("""
                    {"jsonrpc": "2.0", "id": 1, "error": {"code": -1, "message": "user refused"}}""").toCompletableFuture().join();

            assertEquals(202, res.statusCode());
        }
    }

    @Test
    void aMessageWithNeitherAMethodNorAResultIsACall(@Fusion final URI mcpEndpoint, @Fusion final HttpClient http) {
        try (final var client = new MCPClient(mcpEndpoint, http)) {
            client.initialize().toCompletableFuture().join();

            // it is not a client response - no result and no error - so it goes to the JSON-RPC stack, which
            // reports the missing method
            final var res = client.post("""
                    {"jsonrpc": "2.0", "id": 2}""").toCompletableFuture().join();

            assertEquals(200, res.statusCode());
            assertTrue(res.body().contains("Missing method"), res.body());
        }
    }

    @Test
    void aBatchOfScalarsIsRejectedByTheJsonRpcStack(@Fusion final URI mcpEndpoint, @Fusion final HttpClient http) {
        try (final var client = new MCPClient(mcpEndpoint, http)) {
            // no session and no initialize in there, so the client is served statelessly
            final var res = client.post("[1, 2]").toCompletableFuture().join();

            assertEquals(200, res.statusCode());
            assertTrue(res.body().contains("\"error\""), res.body());
        }
    }

    @Test
    void aNullBodyIsAParseError(@Fusion final URI mcpEndpoint, @Fusion final HttpClient http) {
        try (final var client = new MCPClient(mcpEndpoint, http)) {
            // the client helper serializes a null body as the "null" JSON literal
            final var res = client.post(null).toCompletableFuture().join();

            assertEquals(200, res.statusCode());
            assertTrue(res.body().contains("Empty request"), res.body());
        }
    }

    @Test
    void theStreamCanBeOpenedBeforeInitialize(@Fusion final URI mcpEndpoint, @Fusion final HttpClient http)
            throws Exception {
        // no session header at all: the client gets a valid - but silent - stream, which is what a client opening
        // the channel before initialize asks for. Such a stream belongs to an unregistered session so nothing can
        // complete it server side, the web server releases it when it stops - see Fusion FusionServlet.
        final var response = http.sendAsync(
                        HttpRequest.newBuilder(mcpEndpoint)
                                .GET()
                                .header("accept", "text/event-stream")
                                .build(),
                        HttpResponse.BodyHandlers.ofInputStream())
                .toCompletableFuture()
                .get(10, SECONDS);

        // the body is closed whatever happens: the shared HttpClient is closed with the container and it awaits its
        // pending operations, so an open response stream would block that shutdown
        try (final var body = response.body()) {
            assertEquals(200, response.statusCode());
            assertEquals(
                    "text/event-stream;charset=utf-8",
                    response.headers().firstValue("content-type").orElseThrow());
            assertNotNull(body);
        }
    }

    /**
     * A stream which must not deliver anything either ended - the read fails - or stays silent - the read times out.
     * Both are asserted with a bounded wait so a broken expectation can never hang the suite.
     */
    private void assertNothingDelivered(final MCPClient client, final Iterator<String> stream) {
        assertThrows(
                Exception.class,
                () -> client.nextMessage(stream).toCompletableFuture().get(2, SECONDS));
    }
}
