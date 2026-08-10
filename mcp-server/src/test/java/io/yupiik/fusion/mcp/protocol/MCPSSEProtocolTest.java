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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.mcp.configuration.MCPConfiguration;
import io.yupiik.fusion.mcp.test.StubRequest;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The DNS rebinding guard of the two {@code /mcp} HTTP verbs that open and close the stream, {@code GET} and
 * {@code DELETE}: a request coming from a non-loopback host must never reach the sessions.
 */
class MCPSSEProtocolTest {
    @Test
    void sseRejectsANonLoopbackHost() {
        final var sse = new MCPSSEProtocol(null, configuration());
        final var response = sse.sse(new StubRequest(Map.of("Host", "evil.example.com")))
                .toCompletableFuture()
                .join();

        assertEquals(403, response.status());
    }

    @Test
    void deleteRejectsANonLoopbackHost() {
        final var sse = new MCPSSEProtocol(null, configuration());
        final var response = sse.delete(new StubRequest(Map.of("Host", "evil.example.com")))
                .toCompletableFuture()
                .join();

        assertEquals(403, response.status());
    }

    @Test
    void anIpv6BracketHostIsParsedAsLocalhost() {
        assertTrue(HostGuard.isAllowed(new StubRequest(Map.of("Host", "[::1]:8080"))));
        assertFalse(HostGuard.isAllowed(new StubRequest(Map.of("Host", "evil.example.com"))));
    }

    @Test
    void constructionAlwaysHasAnHttpMatcher() {
        // the two-arg and the no-arg constructors both wire a default rejection policy
        assertNotNull(new MCPSSEProtocol(null, configuration()));
        assertNotNull(new MCPSSEProtocol(null));
    }

    private MCPConfiguration configuration() {
        return new MCPConfiguration("n", "t", "v", "i", 0, 30, false, 30000L, "private", "", true);
    }
}
