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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.mcp.client.MCPClient;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import java.net.URI;
import java.net.http.HttpClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * {@code fusion.mcp.requireSession=true}: the stricter reading of the specification, where a client ignoring the
 * session header is refused instead of being served statelessly.
 * <p>
 * The flag is read when the container starts, so it is set as a system property before {@code @FusionSupport} boots
 * it and cleared afterwards.
 */
@FusionSupport
class MCPStrictSessionTest {
    private static final String PROPERTY = "fusion.mcp.requireSession";

    @BeforeAll
    static void requireSessions() {
        System.setProperty(PROPERTY, "true");
    }

    @AfterAll
    static void reset() {
        System.clearProperty(PROPERTY);
    }

    @Test
    void initializeIsStillAllowedWithoutASession(@Fusion final URI mcpEndpoint, @Fusion final HttpClient http) {
        try (final var client = new MCPClient(mcpEndpoint, http)) {
            final var res = client.post("""
                    {
                      "jsonrpc": "2.0",
                      "id": 1,
                      "method": "initialize",
                      "params": {"protocolVersion": "2025-06-18", "capabilities": {}}
                    }""").toCompletableFuture().join();

            assertEquals(200, res.statusCode());
            assertTrue(res.headers().firstValue("mcp-session-id").isPresent(), "a session must be created");
        }
    }

    @Test
    void statelessClientIsRefused(@Fusion final URI mcpEndpoint, @Fusion final HttpClient http) {
        try (final var client = new MCPClient(mcpEndpoint, http)) {
            final var res =
                    client.call(1, "tools/list", "{}").toCompletableFuture().join();

            assertEquals(400, res.statusCode());
            assertTrue(res.body().contains("Missing mcp-session-id header"), res.body());
        }
    }

    @Test
    void aSessionMakesItWorkAgain(@Fusion final URI mcpEndpoint, @Fusion final HttpClient http) {
        try (final var client = new MCPClient(mcpEndpoint, http)) {
            client.initialize().toCompletableFuture().join();

            assertEquals(
                    200,
                    client.call(2, "tools/list", "{}")
                            .toCompletableFuture()
                            .join()
                            .statusCode());
        }
    }

    @Test
    void anUnknownSessionIsStillA404(@Fusion final URI mcpEndpoint, @Fusion final HttpClient http) {
        try (final var client = new MCPClient(mcpEndpoint, http).session("i-made-it-up")) {
            assertEquals(
                    404,
                    client.call(1, "tools/list", "{}")
                            .toCompletableFuture()
                            .join()
                            .statusCode());
        }
    }
}
