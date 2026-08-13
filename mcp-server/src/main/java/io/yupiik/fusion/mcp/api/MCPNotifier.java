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

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.http.server.api.Request;
import io.yupiik.fusion.mcp.model.LoggingLevel;
import io.yupiik.fusion.mcp.model.MessageNotification;
import io.yupiik.fusion.mcp.model.MetadataParameters;
import io.yupiik.fusion.mcp.model.ProgressNotification;
import io.yupiik.fusion.mcp.model.ResourceUpdatedNotification;
import io.yupiik.fusion.mcp.model.SubscriptionFilter;
import io.yupiik.fusion.mcp.protocol.MCPProtocol;
import io.yupiik.fusion.mcp.protocol.MCPSession;
import io.yupiik.fusion.mcp.protocol.MCPSessions;

/**
 * Sends notifications to <b>all</b> the connected clients over their SSE channel.
 * <p>
 * Inject it wherever a notification must be emitted:
 * <pre>{@code
 * @ApplicationScoped
 * public class MyResources implements MCPResources {
 *     private final MCPNotifier notifier;
 *
 *     public MyResources(final MCPNotifier notifier) {
 *         this.notifier = notifier;
 *     }
 *
 *     public void onFileChanged(final String uri) {
 *         notifier.resourceUpdated(uri);
 *     }
 * }
 * }</pre>
 * To send something to a single client - the one of the current call - get its {@link MCPSession} with
 * {@link MCPSessions#of(io.yupiik.fusion.http.server.api.Request)} instead.
 * <p>
 * NOTE: a client only receives the notifications while it keeps a {@code GET /mcp} stream open, messages emitted in
 * between are buffered per session.
 */
@ApplicationScoped
public class MCPNotifier {
    private final MCPSessions sessions;

    protected MCPNotifier() {
        this(null);
    }

    public MCPNotifier(final MCPSessions sessions) {
        this.sessions = sessions;
    }

    /**
     * Sends a log record, each client only gets it if it matches the level it asked for with
     * {@code logging/setLevel}.
     *
     * @param level  the severity.
     * @param logger the logger name.
     * @param data   the payload, a string or any JSON structure.
     */
    public void log(final LoggingLevel level, final String logger, final Object data) {
        final var notification = new MessageNotification(logger, level, data);
        sessions.sessions().forEach(it -> it.log(notification));
    }

    /**
     * Tells the clients the resource list changed, they are expected to call {@code resources/list} again.
     */
    public void resourceListChanged() {
        toAllSessions("notifications/resources/list_changed", MetadataParameters.EMPTY);
        sessions.subscriptions().stream()
                .filter(it -> it.filter() != null
                        && SubscriptionFilter.optedIn(it.filter().resourcesListChanged()))
                .forEach(it -> notify(it, "notifications/resources/list_changed", MetadataParameters.EMPTY));
    }

    /**
     * Tells the clients which subscribed to {@code uri} that it changed.
     *
     * @param uri the updated resource.
     */
    public void resourceUpdated(final String uri) {
        final var notification = ResourceUpdatedNotification.of(uri);
        sessions.sessions().forEach(it -> it.resourceUpdated(notification));
        sessions.subscriptions().stream()
                .filter(it -> it.filter() != null
                        && it.filter().resourceSubscriptions() != null
                        && it.filter().resourceSubscriptions().contains(uri))
                .forEach(it -> notify(it, "notifications/resources/updated", notification));
    }

    /**
     * Tells the clients the tool list changed, they are expected to call {@code tools/list} again.
     * <p>
     * Tools being JSON-RPC methods known at build time, this is only useful when a tool becomes usable - or not -
     * at runtime.
     */
    public void toolListChanged() {
        toAllSessions("notifications/tools/list_changed", MetadataParameters.EMPTY);
        sessions.subscriptions().stream()
                .filter(it -> it.filter() != null
                        && SubscriptionFilter.optedIn(it.filter().toolsListChanged()))
                .forEach(it -> notify(it, "notifications/tools/list_changed", MetadataParameters.EMPTY));
    }

    /**
     * Tells the clients the prompt list changed, they are expected to call {@code prompts/list} again.
     */
    public void promptListChanged() {
        toAllSessions("notifications/prompts/list_changed", MetadataParameters.EMPTY);
        sessions.subscriptions().stream()
                .filter(it -> it.filter() != null
                        && SubscriptionFilter.optedIn(it.filter().promptsListChanged()))
                .forEach(it -> notify(it, "notifications/prompts/list_changed", MetadataParameters.EMPTY));
    }

    /**
     * Sends the progress of a long running request.
     *
     * @param notification the progress, its token must be the one the client sent in the originating request.
     */
    public void progress(final ProgressNotification notification) {
        sessions.sessions().forEach(it -> it.progress(notification));
    }

    /**
     * Sends any notification to all the connected clients - the registered sessions and the stateless subscriptions.
     *
     * @param method the notification name.
     * @param params its parameters.
     */
    public void broadcast(final String method, final Object params) {
        toAllSessions(method, params);
        sessions.subscriptions().forEach(it -> notify(it, method, params));
    }

    /**
     * Sends a notification to the subscription of the current request only - the request must be the
     * {@code subscriptions/listen} one for its {@code subscriptionId} to be known. Falls back on
     * {@link #broadcast(String, Object)} when the request carries no subscription identifier.
     *
     * @param request the current call, the one the notification targets.
     * @param method  the notification name.
     * @param params  its parameters.
     */
    public void notify(final Request request, final String method, final Object params) {
        final var subscriptionId =
                request != null ? request.attribute(MCPProtocol.SUBSCRIPTION_ID_ATTRIBUTE, String.class) : null;
        if (subscriptionId != null && sessions.subscription(subscriptionId).isPresent()) {
            notify(sessions.subscription(subscriptionId).orElseThrow(), method, params);
            return;
        }
        broadcast(method, params);
    }

    private void toAllSessions(final String method, final Object params) {
        sessions.sessions().forEach(it -> it.notify(method, params));
    }

    private void notify(final MCPSessions.Subscription subscription, final String method, final Object params) {
        subscription.session().notify(method, params, subscription.subscriptionId());
    }
}
