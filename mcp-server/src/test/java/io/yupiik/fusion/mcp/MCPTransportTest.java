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

import io.yupiik.fusion.mcp.api.MCPNotifier;
import io.yupiik.fusion.mcp.protocol.MCPProtocol;
import io.yupiik.fusion.mcp.model.LoggingLevel;
import io.yupiik.fusion.mcp.testing.MCPClient;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;

import static io.yupiik.fusion.testing.assertion.JsonAsserts.assertJsonEquals;
import static java.net.http.HttpResponse.BodyHandlers.ofString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The streamable HTTP transport itself: sessions, headers, {@code DELETE} and the SSE channel.
 */
@FusionSupport
class MCPTransportTest {
    @Test
    void sessionLifecycle(@Fusion final URI mcpEndpoint, @Fusion final HttpClient http) throws Exception {
        try (final var client = new MCPClient(mcpEndpoint, http)) {
            client.initialize();
            final var session = client.session();

            assertEquals(200, client.call(2, "ping", "{}").statusCode());
            assertEquals(204, client.terminate().statusCode());

            // the session is gone, both a new call and a second delete must be rejected
            assertEquals(404, client.call(3, "ping", "{}").statusCode());
            assertEquals(404, client.terminate().statusCode());
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
            final var res = client.call(1, "ping", "{}");

            assertEquals(404, res.statusCode());
            assertTrue(res.body().contains("Unknown or expired MCP session"), res.body());
        }
    }

    @Test
    void eachInitializeGetsItsOwnSession(@Fusion final URI mcpEndpoint, @Fusion final HttpClient http) throws Exception {
        try (final var first = new MCPClient(mcpEndpoint, http);
             final var second = new MCPClient(mcpEndpoint, http)) {
            first.initialize();
            second.initialize();

            assertNotEquals(first.session(), second.session());
        }
    }

    @Test
    void unsupportedProtocolVersionHeader(@Fusion final URI mcpEndpoint, @Fusion final HttpClient http) throws Exception {
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
    void logNotificationIsPushedOnSse(@Fusion final URI mcpEndpoint, @Fusion final HttpClient http,
                                      @Fusion final MCPNotifier notifier) throws Exception {
        try (final var client = new MCPClient(mcpEndpoint, http)) {
            client.initialize();

            // published before the stream is opened on purpose: messages are buffered per session
            notifier.log(LoggingLevel.warning, "test", "hello sse!");

            final var stream = client.openSse();
            assertJsonEquals("""
                            {
                              "jsonrpc": "2.0",
                              "method": "notifications/message",
                              "params": {"level": "warning", "logger": "test", "data": "hello sse!"}
                            }""",
                    client.nextMessage(stream));
        }
    }

    @Test
    void logNotificationIsFilteredByLevel(@Fusion final URI mcpEndpoint, @Fusion final HttpClient http,
                                          @Fusion final MCPNotifier notifier) throws Exception {
        try (final var client = new MCPClient(mcpEndpoint, http)) {
            client.initialize();
            client.post("""
                    {"jsonrpc": "2.0", "method": "logging/setLevel", "params": {"level": "error"}}""");

            notifier.log(LoggingLevel.debug, "test", "dropped");
            notifier.log(LoggingLevel.critical, "test", "kept");

            final var stream = client.openSse();
            assertTrue(client.nextMessage(stream).contains("\"kept\""));
        }
    }

    @Test
    void listChangedNotification(@Fusion final URI mcpEndpoint, @Fusion final HttpClient http,
                                 @Fusion final MCPNotifier notifier) throws Exception {
        try (final var client = new MCPClient(mcpEndpoint, http)) {
            client.initialize();
            notifier.toolListChanged();

            final var stream = client.openSse();
            assertJsonEquals("""
                    {"jsonrpc": "2.0", "method": "notifications/tools/list_changed", "params": {}}""",
                    client.nextMessage(stream));
        }
    }

    @Test
    void resourceUpdatedOnlyGoesToSubscribers(@Fusion final URI mcpEndpoint, @Fusion final HttpClient http,
                                              @Fusion final MCPNotifier notifier) throws Exception {
        try (final var client = new MCPClient(mcpEndpoint, http)) {
            client.initialize();

            client.post("""
                    {"jsonrpc": "2.0", "method": "resources/subscribe", "params": {"uri": "demo://greeting"}}""");
            notifier.resourceUpdated("demo://not-subscribed"); // dropped, the session does not watch it
            notifier.resourceUpdated("demo://greeting");

            final var stream = client.openSse();
            assertJsonEquals("""
                            {
                              "jsonrpc": "2.0",
                              "method": "notifications/resources/updated",
                              "params": {"uri": "demo://greeting"}
                            }""",
                    client.nextMessage(stream));
        }
    }
}
