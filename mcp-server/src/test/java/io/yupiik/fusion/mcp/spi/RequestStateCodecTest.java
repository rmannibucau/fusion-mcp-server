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
package io.yupiik.fusion.mcp.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.mcp.configuration.MCPConfiguration;
import org.junit.jupiter.api.Test;

class RequestStateCodecTest {
    @Test
    void opaqueKeepsTheStateAndMarksNoState() {
        final var opaque = new OpaqueRequestStateCodec();

        assertTrue(opaque.isActive());
        assertEquals("some/state", opaque.encode("some/state"));
        assertEquals("some/state", opaque.decode(opaque.encode("some/state")));
        assertEquals("", opaque.empty());
        assertNull(opaque.decode(null));
        assertNull(opaque.decode(opaque.empty()));
        assertEquals(opaque.empty(), opaque.encode(null));
    }

    @Test
    void hmacIsInactiveWithoutASecret() {
        final var hmac = new HmacRequestStateCodec(
                new MCPConfiguration("n", "t", "v", "i", 0, 30, false, 30000L, "private", "", true));

        assertEquals("", hmac.empty());
        assertThrows(IllegalStateException.class, () -> hmac.encode("anything"));
        assertEquals(false, hmac.isActive());

        // the Fusion subclassing proxy uses the no-arg constructor with no configuration at all
        assertEquals(false, new HmacRequestStateCodec() {}.isActive());
    }

    @Test
    void hmacRoundTripsWhenActive() {
        final var hmac = new HmacRequestStateCodec(
                new MCPConfiguration("n", "t", "v", "i", 0, 30, false, 30000L, "private", "a-secret", true));

        assertTrue(hmac.isActive());
        assertEquals(hmac.empty(), hmac.encode(null));
        assertNull(hmac.decode(hmac.empty()));
        final var token = hmac.encode("state/42");
        // opaque, no raw state in the token
        assertEquals("state/42", hmac.decode(token));
        // a tampered token (any char flipped) fails the HMAC check
        assertThrows(IllegalArgumentException.class, () -> hmac.decode("x" + token.substring(1)));
        assertThrows(IllegalArgumentException.class, () -> hmac.decode("not-a-token"));
        assertNull(hmac.decode(null));
    }

    @Test
    void hmacRejectsAnExpiredToken() {
        final var hmac = new HmacRequestStateCodec(
                new MCPConfiguration("n", "t", "v", "i", 0, 30, false, 30000L, "private", "a-secret", true));

        // a token with a negative lifetime is already expired and must be rejected
        final var expired = hmac.encode("state/42", -1L);
        assertThrows(IllegalArgumentException.class, () -> hmac.decode(expired));
        // a non-expired token still round-trips
        assertEquals("state/42", hmac.decode(hmac.encode("state/42", 1_000L)));
    }
}
