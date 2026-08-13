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

import static java.util.Optional.ofNullable;
import static java.util.logging.Level.FINE;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.lifecycle.Destroy;
import io.yupiik.fusion.http.server.api.Request;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.mcp.configuration.MCPConfiguration;
import io.yupiik.fusion.mcp.model.SubscriptionFilter;
import java.time.Duration;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Holds the live {@link MCPSession}s.
 * <p>
 * Inject it to reach the session of the current call - {@link #of(Request)} - or to broadcast something to all the
 * connected clients - {@link #sessions()}.
 * <p>
 * NOTE: sessions are kept in memory, so a clustered deployment needs a session aware load balancer (sticky
 * sessions on the {@code Mcp-Session-Id} header).
 */
@ApplicationScoped
public class MCPSessions {
    /**
     * Request attribute the transport uses to expose the resolved session to the JSON-RPC methods.
     */
    public static final String REQUEST_ATTRIBUTE = "io.yupiik.fusion.mcp.session";

    private final Logger logger = Logger.getLogger(MCPSessions.class.getName());

    private final Map<String, MCPSession> sessions = new ConcurrentHashMap<>();
    private final Map<String, Subscription> subscriptions = new ConcurrentHashMap<>();
    private final JsonMapper jsons;
    private final Duration sessionTimeout;
    private final Duration clientRequestTimeout;

    protected MCPSessions() {
        this(null, null);
    }

    public MCPSessions(final JsonMapper jsons, final MCPConfiguration configuration) {
        this.jsons = jsons;
        this.sessionTimeout =
                configuration == null ? Duration.ZERO : Duration.ofSeconds(configuration.sessionTimeout());
        this.clientRequestTimeout = configuration == null
                ? Duration.ofSeconds(30)
                : Duration.ofSeconds(configuration.clientRequestTimeout());
    }

    /**
     * Creates a session and registers it, this is what {@code initialize} does.
     *
     * @return the new session, its {@link MCPSession#id()} is sent to the client.
     */
    public MCPSession create() {
        evictExpired();
        final var session = new MCPSession(UUID.randomUUID().toString(), jsons, clientRequestTimeout);
        sessions.put(session.id(), session);
        return session;
    }

    /**
     * Creates a session which is <b>not</b> registered, it is used for the clients not sending back the
     * {@code Mcp-Session-Id} header: they get a fresh state on every request and, since nothing can find it back,
     * such a session has no usable server to client channel.
     *
     * @return a session bound to the current request only.
     */
    public MCPSession ephemeral() {
        return ephemeral(false);
    }

    /**
     * Creates a session which is <b>not</b> registered, it is used for the clients not sending back the
     * {@code Mcp-Session-Id} header: they get a fresh state on every request and, since nothing can find it back,
     * such a session has no usable server to client channel.
     *
     * @param stateless {@code true} for a stateless protocol request - the session only lives for this call and its
     *                  responses are enriched with the stateless result fields.
     * @return a session bound to the current request only.
     */
    public MCPSession ephemeral(final boolean stateless) {
        return new MCPSession(null, jsons, clientRequestTimeout, stateless);
    }

    /**
     * @param id a session identifier.
     * @return the matching live session if any.
     */
    public Optional<MCPSession> find(final String id) {
        if (id == null) {
            return Optional.empty();
        }
        return ofNullable(sessions.get(id))
                .filter(it -> {
                    if (it.isExpired(sessionTimeout)) {
                        drop(id);
                        return false;
                    }
                    return true;
                })
                .map(it -> {
                    it.touch();
                    return it;
                });
    }

    /**
     * Resolves the session of the current call: the one the transport bound to the request, else the one the
     * {@code Mcp-Session-Id} header points to, else a new {@link #ephemeral()} one.
     *
     * @param request the current HTTP request.
     * @return the session of this call, never {@code null}.
     */
    public MCPSession of(final Request request) {
        final var bound = request.attribute(REQUEST_ATTRIBUTE, MCPSession.class);
        if (bound != null) {
            return bound;
        }
        final var session = find(request.header(MCPProtocol.SESSION_HEADER)).orElseGet(this::ephemeral);
        request.setAttribute(REQUEST_ATTRIBUTE, session);
        return session;
    }

    /**
     * Binds a session to the current request so {@link #of(Request)} returns it.
     */
    public void bind(final Request request, final MCPSession session) {
        request.setAttribute(REQUEST_ATTRIBUTE, session);
    }

    /**
     * Terminates a session, this is what {@code DELETE /mcp} does.
     *
     * @param id the session to drop.
     * @return {@code true} when a session was dropped.
     */
    public boolean drop(final String id) {
        final var session = id == null ? null : sessions.remove(id);
        if (session == null) {
            return false;
        }
        logger.log(FINE, () -> "Dropping MCP session '" + id + "'");
        session.close();
        return true;
    }

    /**
     * @return all the registered sessions, i.e. the clients a notification can be sent to.
     */
    public Collection<MCPSession> sessions() {
        return sessions.values();
    }

    /**
     * Registers a stateless {@code subscriptions/listen} stream so a later {@code subscriptions/cancel} - or a
     * notification - can reach the subscription living in an otherwise unrecoverable ephemeral session.
     * <p>
     * The session stream is armed to unregister itself when it closes, so a client leaving without cancelling leaks
     * nothing.
     *
     * @param subscriptionId the identifier the client correlates the notifications with.
     * @param filter         the notifications the client opted in to.
     * @param session        the ephemeral session owning the stream.
     * @return the registered subscription.
     */
    public Subscription registerSubscription(
            final String subscriptionId, final SubscriptionFilter filter, final MCPSession session) {
        final var subscription = new Subscription(subscriptionId, filter, session);
        subscriptions.put(subscriptionId, subscription);
        session.sse().onClose(() -> subscriptions.remove(subscriptionId, subscription));
        return subscription;
    }

    /**
     * @param subscriptionId a subscription identifier.
     * @return the matching live subscription if any.
     */
    public Optional<Subscription> subscription(final String subscriptionId) {
        return ofNullable(subscriptions.get(subscriptionId));
    }

    /**
     * Removes a subscription, its stream stays as the client closed it already when this is the {@code cancel} path.
     *
     * @param subscriptionId the identifier to drop.
     */
    public void unregisterSubscription(final String subscriptionId) {
        subscriptions.remove(subscriptionId);
    }

    /**
     * @return all the live {@code subscriptions/listen} streams, i.e. the stateless clients a notification can be
     * routed to.
     */
    public Collection<Subscription> subscriptions() {
        return subscriptions.values();
    }

    /**
     * A live {@code subscriptions/listen} stream: its identifier, the notifications the client opted in to and the
     * session owning the channel.
     */
    public record Subscription(String subscriptionId, SubscriptionFilter filter, MCPSession session) {}

    /**
     * Drops the sessions which were idle for more than {@code fusion.mcp.sessionTimeout}.
     * <p>
     * It is called when a session is created so no background thread is needed - which keeps the native image
     * simple - the amount of sessions staying low by nature.
     */
    public void evictExpired() {
        if (sessionTimeout.isZero() || sessionTimeout.isNegative()) {
            return;
        }
        sessions.entrySet().removeIf(it -> {
            if (!it.getValue().isExpired(sessionTimeout)) {
                return false;
            }
            logger.log(FINE, () -> "MCP session '" + it.getKey() + "' expired");
            it.getValue().close();
            return true;
        });
    }

    @Destroy
    public void close() {
        sessions.values().forEach(MCPSession::close);
        sessions.clear();
        subscriptions.clear();
    }
}
