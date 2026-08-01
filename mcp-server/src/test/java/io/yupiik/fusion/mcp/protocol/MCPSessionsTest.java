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
import io.yupiik.fusion.mcp.configuration.MCPConfiguration;
import io.yupiik.fusion.mcp.test.StubRequest;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The session registry: creation, lookup, expiration and how a call is bound to its session.
 */
@FusionSupport
class MCPSessionsTest {
    @Test
    void createAndFind(@Fusion final JsonMapper jsons) {
        final var registry = sessions(jsons, 1800);

        final var session = registry.create();

        assertNotNull(session.id());
        assertSame(session, registry.find(session.id()).orElseThrow());
        assertTrue(registry.sessions().contains(session));
        assertTrue(registry.find("nope").isEmpty());
        assertTrue(registry.find(null).isEmpty(), "no session header means no session");
    }

    @Test
    void idsAreUnique(@Fusion final JsonMapper jsons) {
        final var registry = sessions(jsons, 1800);

        assertNotEquals(registry.create().id(), registry.create().id());
        assertEquals(2, registry.sessions().size());
    }

    @Test
    void ephemeralIsNotRegistered(@Fusion final JsonMapper jsons) {
        final var registry = sessions(jsons, 1800);

        final var session = registry.ephemeral();

        assertNull(session.id(), "an ephemeral session has no identifier, nothing can find it back");
        assertTrue(registry.sessions().isEmpty());
    }

    @Test
    void drop(@Fusion final JsonMapper jsons) {
        final var registry = sessions(jsons, 1800);
        final var session = registry.create();

        assertTrue(registry.drop(session.id()));

        assertTrue(registry.find(session.id()).isEmpty());
        assertTrue(session.sse().isClosed(), "dropping releases the channel");
        assertFalse(registry.drop(session.id()), "dropping twice is a no-op");
        assertFalse(registry.drop(null));
    }

    @Test
    void expirationIsDisabledWithAZeroTimeout(@Fusion final JsonMapper jsons) {
        final var registry = sessions(jsons, 0);
        final var session = registry.create();

        registry.evictExpired();

        assertTrue(registry.find(session.id()).isPresent());
    }

    @Test
    void idleSessionsAreDropped(@Fusion final JsonMapper jsons) throws Exception {
        // one second is the finest a configuration can express, so this test does sleep
        final var registry = sessions(jsons, 1);
        final var expiring = registry.create();
        final var stale = registry.create();
        assertTrue(registry.find(expiring.id()).isPresent());

        Thread.sleep(1_100);

        // a lookup drops the expired session it just found...
        assertTrue(registry.find(expiring.id()).isEmpty(), "an idle session is not returned");
        assertTrue(expiring.sse().isClosed(), "and its channel is released");

        // ...and creating a new one evicts the remaining ones, so no background thread is needed
        registry.create();
        assertFalse(registry.sessions().contains(stale));
        assertTrue(stale.sse().isClosed());
    }

    @Test
    void ofUsesTheBoundSessionFirst(@Fusion final JsonMapper jsons) {
        final var registry = sessions(jsons, 1800);
        final var session = registry.create();
        final var request = new StubRequest();

        registry.bind(request, session);

        assertSame(session, registry.of(request));
    }

    @Test
    void ofFallsBackOnTheSessionHeader(@Fusion final JsonMapper jsons) {
        final var registry = sessions(jsons, 1800);
        final var session = registry.create();
        final var request = new StubRequest(Map.of(MCPProtocol.SESSION_HEADER, session.id()));

        assertSame(session, registry.of(request));
        // and it is bound for the next lookups of the same call
        assertSame(session, request.attribute(MCPSessions.REQUEST_ATTRIBUTE, MCPSession.class));
    }

    @Test
    void ofCreatesAnEphemeralSessionForAStatelessClient(@Fusion final JsonMapper jsons) {
        final var registry = sessions(jsons, 1800);
        final var request = new StubRequest();

        final var session = registry.of(request);

        assertNull(session.id());
        assertTrue(registry.sessions().isEmpty(), "such a session is not registered");
        assertSame(session, registry.of(request), "but it is stable within the call");
    }

    @Test
    void ofIgnoresAnUnknownHeader(@Fusion final JsonMapper jsons) {
        final var registry = sessions(jsons, 1800);
        final var request = new StubRequest(Map.of(MCPProtocol.SESSION_HEADER, "i-made-it-up"));

        // the transport already answered 404 for that case, the lookup itself must simply not fail
        assertNull(registry.of(request).id());
    }

    @Test
    void closeReleasesEverything(@Fusion final JsonMapper jsons) {
        final var registry = sessions(jsons, 1800);
        final var session = registry.create();

        registry.close();

        assertTrue(registry.sessions().isEmpty());
        assertTrue(session.sse().isClosed());
    }

    private MCPSessions sessions(final JsonMapper jsons, final int sessionTimeout) {
        return new MCPSessions(jsons, new MCPConfiguration(
                "test", "Test", "1.0.0", "instructions", sessionTimeout, 5, false));
    }
}
