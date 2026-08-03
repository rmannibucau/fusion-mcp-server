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
package io.yupiik.fusion.mcp.api;

import static io.yupiik.fusion.testing.assertion.JsonAsserts.assertJsonEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.mcp.configuration.MCPConfiguration;
import io.yupiik.fusion.mcp.model.LoggingLevel;
import io.yupiik.fusion.mcp.model.ProgressNotification;
import io.yupiik.fusion.mcp.protocol.MCPSession;
import io.yupiik.fusion.mcp.protocol.MCPSessions;
import io.yupiik.fusion.mcp.test.SseSubscriber;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The broadcasting side of the API: every notification goes to <b>all</b> the registered sessions, each session then
 * applying its own filtering - the level it asked for and the resources it subscribed to.
 */
@FusionSupport
class MCPNotifierTest {
    @Test
    void logGoesToEveryClientWhichAskedForThatLevel(@Fusion final JsonMapper jsons) {
        final var sessions = sessions(jsons);
        final var quiet = sessions.create();
        quiet.setLoggingLevel(LoggingLevel.error);
        final var verbose = sessions.create();
        verbose.setLoggingLevel(LoggingLevel.debug);

        new MCPNotifier(sessions).log(LoggingLevel.info, "demo", "hello fusion!");

        assertEquals(List.of(), queued(quiet), "info is below the error level this client asked for");
        assertJsonEquals("""
                        {
                          "jsonrpc": "2.0",
                          "method": "notifications/message",
                          "params": {"data": "hello fusion!", "level": "info", "logger": "demo"}
                        }""", queued(verbose).getFirst());
    }

    @Test
    void resourceUpdatedOnlyGoesToTheSubscribers(@Fusion final JsonMapper jsons) {
        final var sessions = sessions(jsons);
        final var subscriber = sessions.create();
        subscriber.subscribe("demo://greeting");
        final var other = sessions.create();
        other.subscribe("demo://something-else");

        new MCPNotifier(sessions).resourceUpdated("demo://greeting");

        assertJsonEquals("""
                        {
                          "jsonrpc": "2.0",
                          "method": "notifications/resources/updated",
                          "params": {"uri": "demo://greeting"}
                        }""", queued(subscriber).getFirst());
        assertEquals(List.of(), queued(other));
    }

    @Test
    void listChangedNotificationsGoToEverybody(@Fusion final JsonMapper jsons) {
        final var sessions = sessions(jsons);
        final var session = sessions.create();
        final var notifier = new MCPNotifier(sessions);

        notifier.resourceListChanged();
        notifier.toolListChanged();
        notifier.promptListChanged();

        // one read only: the frames are consumed by the subscriber the helper attaches
        final var queued = queued(session);
        assertEquals(3, queued.size());
        assertJsonEquals("""
                {"jsonrpc": "2.0", "method": "notifications/resources/list_changed", "params": {}}""", queued.get(0));
        assertJsonEquals("""
                {"jsonrpc": "2.0", "method": "notifications/tools/list_changed", "params": {}}""", queued.get(1));
        assertJsonEquals("""
                {"jsonrpc": "2.0", "method": "notifications/prompts/list_changed", "params": {}}""", queued.get(2));
    }

    @Test
    void progressGoesToEverybody(@Fusion final JsonMapper jsons) {
        final var sessions = sessions(jsons);
        final var first = sessions.create();
        final var second = sessions.create();

        new MCPNotifier(sessions).progress(new ProgressNotification("a-token", 0.5d, 1d, "half way"));

        for (final var session : List.of(first, second)) {
            assertJsonEquals("""
                            {
                              "jsonrpc": "2.0",
                              "method": "notifications/progress",
                              "params": {"message": "half way", "progress": 0.5, "progressToken": "a-token", "total": 1.0}
                            }""", queued(session).getFirst());
        }
    }

    @Test
    void broadcastSendsAnything(@Fusion final JsonMapper jsons) {
        final var sessions = sessions(jsons);
        final var session = sessions.create();

        new MCPNotifier(sessions).broadcast("notifications/custom", java.util.Map.of("key", "value"));

        assertJsonEquals("""
                        {"jsonrpc": "2.0", "method": "notifications/custom", "params": {"key": "value"}}""", queued(session).getFirst());
    }

    @Test
    void nothingIsSentWithoutAnyClient(@Fusion final JsonMapper jsons) {
        final var sessions = sessions(jsons);
        final var notifier = new MCPNotifier(sessions);

        // no session at all: the notifications are simply dropped and nothing blows up
        notifier.log(LoggingLevel.error, "demo", "nobody listens");
        notifier.resourceListChanged();
        notifier.resourceUpdated("demo://greeting");
        notifier.progress(new ProgressNotification("token", 1d, null, null));

        assertTrue(sessions.sessions().isEmpty());
    }

    @Test
    void theSubclassingConstructorHoldsNothing() {
        // the no-arg constructor only exists for the Fusion subclassing proxies
        assertEquals(MCPNotifier.class, new MCPNotifier() {}.getClass().getSuperclass());
    }

    private List<String> queued(final MCPSession session) {
        final var subscriber = SseSubscriber.strict(Long.MAX_VALUE);
        session.sse().subscribe(subscriber);
        return subscriber.data();
    }

    private MCPSessions sessions(final JsonMapper jsons) {
        return new MCPSessions(jsons, new MCPConfiguration("n", "t", "v", "i", 0, 30, false));
    }
}
