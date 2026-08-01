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
package io.yupiik.fusion.mcp.client;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.mcp.api.MCPNotifier;
import io.yupiik.fusion.mcp.model.LoggingLevel;
import io.yupiik.fusion.mcp.model.MetadataParameters;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.Map;

import static io.yupiik.fusion.testing.assertion.JsonAsserts.assertJsonEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The test client itself: it is shipped in the main artifact so applications test their own server with it.
 */
@FusionSupport
class MCPClientTest {
    @Test
    void handshakeKeepsTheSession(@Fusion final URI mcpEndpoint, @Fusion final HttpClient http) {
        try (final var client = new MCPClient(mcpEndpoint, http)) {
            assertNull(client.session(), "no session before the handshake");

            final var response = client.initialize().toCompletableFuture().join();

            assertEquals(200, response.statusCode());
            assertNotNull(client.session(), "initialize must capture the Mcp-Session-Id header");
            // the session is reused, else the server would answer 404
            assertEquals(200, client.call(2, "ping", "{}").toCompletableFuture().join().statusCode());
        }
    }

    @Test
    void stringBodyIsSentAsIs(@Fusion final URI mcpEndpoint, @Fusion final HttpClient http) {
        try (final var client = new MCPClient(mcpEndpoint, http)) {
            client.initialize().toCompletableFuture().join();

            final var response = client.post("""
                            {"jsonrpc": "2.0", "id": 2, "method": "ping", "params": {}}""")
                    .toCompletableFuture()
                    .join();

            assertJsonEquals("""
                    {"jsonrpc": "2.0", "id": 2, "result": {}}""", response.body());
        }
    }

    @Test
    void objectParamsAreSerialized(@Fusion final URI mcpEndpoint, @Fusion final HttpClient http,
                                   @Fusion final JsonMapper jsonMapper) {
        try (final var client = new MCPClient(mcpEndpoint, http, jsonMapper)) {
            client.initialize(Map.of("sampling", Map.of())).toCompletableFuture().join();

            // a plain map and a @JsonModel record, both serialized by the mapper
            assertJsonEquals("""
                            {"jsonrpc": "2.0", "id": 2, "result": {}}""",
                    client.call(2, "ping", Map.of()).toCompletableFuture().join().body());
            assertJsonEquals("""
                            {"jsonrpc": "2.0", "id": 3, "result": {}}""",
                    client.call(3, "ping", MetadataParameters.EMPTY).toCompletableFuture().join().body());

            // logging/setLevel is a request, not a notification, so it must answer an empty result
            assertJsonEquals("""
                            {"jsonrpc": "2.0", "id": 4, "result": {}}""",
                    client.call(4, "logging/setLevel", Map.of("level", "debug")).toCompletableFuture().join().body());
        }
    }

    @Test
    void objectBodyWithoutMapperFailsWithAClearMessage(@Fusion final URI mcpEndpoint, @Fusion final HttpClient http) {
        try (final var client = new MCPClient(mcpEndpoint, http)) {
            final var error = assertThrows(IllegalStateException.class, () -> client.post(Map.of("a", 1)));
            assertTrue(error.getMessage().contains("No JsonMapper set"), error.getMessage());
        }
    }

    @Test
    void notificationAndResponseAreAccepted(@Fusion final URI mcpEndpoint, @Fusion final HttpClient http) {
        try (final var client = new MCPClient(mcpEndpoint, http)) {
            client.initialize().toCompletableFuture().join();

            final var notification = client.notify("notifications/roots/list_changed", null)
                    .toCompletableFuture()
                    .join();
            assertEquals(202, notification.statusCode());
            assertEquals("", notification.body());

            // nothing is pending so it is ignored, but the transport must still accept it
            final var response = client.respond(1234, """
                            {"role": "assistant", "model": "test", "content": {"type": "text", "text": "hi"}}""")
                    .toCompletableFuture()
                    .join();
            assertEquals(202, response.statusCode());
        }
    }

    @Test
    void readTheSseChannel(@Fusion final URI mcpEndpoint, @Fusion final HttpClient http,
                           @Fusion final MCPNotifier notifier) {
        try (final var client = new MCPClient(mcpEndpoint, http)) {
            client.initialize().toCompletableFuture().join();
            notifier.log(LoggingLevel.warning, "test", "hello sse!");

            // the whole read is composable: open the stream then await the next message
            final var message = client.openSse()
                    .thenCompose(client::nextMessage)
                    .toCompletableFuture()
                    .join();

            assertJsonEquals("""
                            {
                              "jsonrpc": "2.0",
                              "method": "notifications/message",
                              "params": {"level": "warning", "logger": "test", "data": "hello sse!"}
                            }""",
                    message);
        }
    }

    @Test
    void openSseOnAnUnknownSessionFails(@Fusion final URI mcpEndpoint, @Fusion final HttpClient http) {
        try (final var client = new MCPClient(mcpEndpoint, http).session("i-made-it-up")) {
            final var error = assertThrows(Exception.class, () -> client.openSse().toCompletableFuture().join());
            assertInstanceOf(IllegalStateException.class, error.getCause() == null ? error : error.getCause());
        }
    }

    @Test
    void terminate(@Fusion final URI mcpEndpoint, @Fusion final HttpClient http) {
        try (final var client = new MCPClient(mcpEndpoint, http)) {
            client.initialize().toCompletableFuture().join();

            assertEquals(204, client.terminate().toCompletableFuture().join().statusCode());
            assertEquals(404, client.call(2, "ping", "{}").toCompletableFuture().join().statusCode());
        }
    }
}
