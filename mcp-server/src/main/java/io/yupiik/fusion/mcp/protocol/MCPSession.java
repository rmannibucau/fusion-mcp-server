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
import io.yupiik.fusion.jsonrpc.JsonRpcException;
import io.yupiik.fusion.mcp.model.Capabilities;
import io.yupiik.fusion.mcp.model.ClientInfo;
import io.yupiik.fusion.mcp.model.CreateMessageResponse;
import io.yupiik.fusion.mcp.model.CreateSamplingMessageParameters;
import io.yupiik.fusion.mcp.model.ElicitRequestParameters;
import io.yupiik.fusion.mcp.model.ElicitResponse;
import io.yupiik.fusion.mcp.model.JsonRpcMessage;
import io.yupiik.fusion.mcp.model.ListRootsResponse;
import io.yupiik.fusion.mcp.model.LoggingLevel;
import io.yupiik.fusion.mcp.model.MessageNotification;
import io.yupiik.fusion.mcp.model.MetadataParameters;
import io.yupiik.fusion.mcp.model.ProgressNotification;
import io.yupiik.fusion.mcp.model.ResourceUpdatedNotification;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import static java.util.Optional.ofNullable;
import static java.util.logging.Level.FINEST;

/**
 * The state of a MCP connection: the negotiated protocol version, what the client can do, the logging level it
 * asked for, its resource subscriptions and the SSE channel used to push messages to it.
 * <p>
 * A session is created by {@code initialize} and its identifier is returned to the client in the
 * {@code Mcp-Session-Id} header, the client then sends it back on every subsequent request. It is destroyed by
 * {@code DELETE /mcp} or when it expires.
 * <p>
 * Get the session of the current call with {@link MCPSessions#of(io.yupiik.fusion.http.server.api.Request)} - a
 * JSON-RPC method just has to declare a {@code Request} parameter to have access to it.
 */
public class MCPSession {
    private static final LoggingLevel DEFAULT_LOGGING_LEVEL = LoggingLevel.info;

    private final Logger logger = Logger.getLogger(MCPSession.class.getName());

    private final String id;
    private final JsonMapper jsons;
    private final Duration requestTimeout;
    private final AtomicLong requestIds = new AtomicLong();
    private final Map<Long, CompletableFuture<Object>> pendingRequests = new ConcurrentHashMap<>();
    private final Set<String> subscriptions = ConcurrentHashMap.newKeySet();
    private final SseBus sse = new SseBus();

    private volatile LoggingLevel loggingLevel = DEFAULT_LOGGING_LEVEL;
    private volatile String protocolVersion;
    private volatile ClientInfo clientInfo;
    private volatile Capabilities capabilities;
    private volatile boolean initialized;
    private volatile long lastAccess = System.nanoTime();

    public MCPSession(final String id, final JsonMapper jsons, final Duration requestTimeout) {
        this.id = id;
        this.jsons = jsons;
        this.requestTimeout = requestTimeout;
    }

    /**
     * @return the value of the {@code Mcp-Session-Id} header identifying this session.
     */
    public String id() {
        return id;
    }

    /**
     * @return the protocol version {@code initialize} negotiated.
     */
    public String protocolVersion() {
        return protocolVersion;
    }

    /**
     * @return what the client said it can do, it is {@code null} before {@code initialize}.
     */
    public Capabilities capabilities() {
        return capabilities;
    }

    /**
     * @return which client is connected, it is only informative.
     */
    public ClientInfo clientInfo() {
        return clientInfo;
    }

    /**
     * @return {@code true} once the client sent {@code notifications/initialized}.
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * @return the minimum level the client wants to receive log records at.
     */
    public LoggingLevel loggingLevel() {
        return loggingLevel;
    }

    public void setLoggingLevel(final LoggingLevel loggingLevel) {
        this.loggingLevel = loggingLevel == null ? DEFAULT_LOGGING_LEVEL : loggingLevel;
    }

    /**
     * @return the server to client channel, it buffers the messages until the client connects on {@code GET /mcp}.
     */
    public SseBus sse() {
        return sse;
    }

    /**
     * Marks the session as used, it is what postpones its expiration.
     */
    public void touch() {
        lastAccess = System.nanoTime();
    }

    /**
     * @param timeout the maximum idle duration.
     * @return {@code true} when the session was not used for more than {@code timeout}.
     */
    public boolean isExpired(final Duration timeout) {
        return !timeout.isZero() && !timeout.isNegative() &&
                System.nanoTime() - lastAccess > timeout.toNanos();
    }

    void onInitialize(final String protocolVersion, final Capabilities capabilities, final ClientInfo clientInfo) {
        this.protocolVersion = protocolVersion;
        this.capabilities = capabilities;
        this.clientInfo = clientInfo;
    }

    void onInitialized() {
        this.initialized = true;
    }

    public void subscribe(final String uri) {
        subscriptions.add(uri);
    }

    public void unsubscribe(final String uri) {
        subscriptions.remove(uri);
    }

    /**
     * @param uri a resource uri.
     * @return {@code true} when the client asked to be notified of the changes of {@code uri}.
     */
    public boolean isSubscribedTo(final String uri) {
        return subscriptions.contains(uri);
    }

    /**
     * Sends a log record to the client, it is dropped when the client asked for a less verbose level.
     *
     * @param notification the record to send.
     */
    public void log(final MessageNotification notification) {
        if (notification.level() != null && !notification.level().isEnabled(loggingLevel)) {
            return;
        }
        notify("notifications/message", notification);
    }

    /**
     * Sends the progress of a long running request, the client correlates it thanks to the progress token it sent
     * in the originating request.
     *
     * @param notification the progress to send.
     */
    public void progress(final ProgressNotification notification) {
        notify("notifications/progress", notification);
    }

    /**
     * Tells the client one of the resources it subscribed to changed - nothing is sent when it did not subscribe.
     *
     * @param notification the updated resource.
     */
    public void resourceUpdated(final ResourceUpdatedNotification notification) {
        if (!isSubscribedTo(notification.uri())) {
            return;
        }
        notify("notifications/resources/updated", notification);
    }

    /**
     * Sends a notification to the client.
     *
     * @param method the notification name.
     * @param params its parameters, any {@code @JsonModel} instance, map or list.
     */
    public void notify(final String method, final Object params) {
        sse.publish(jsons.toString(JsonRpcMessage.notification(method, params)));
    }

    /**
     * Asks the client to run a LLM completion.
     * <p>
     * It requires the client to have declared the {@code sampling} capability in {@code initialize}.
     *
     * @param parameters the conversation to complete.
     * @return the client answer.
     */
    public CompletionStage<CreateMessageResponse> createMessage(final CreateSamplingMessageParameters parameters) {
        requireClientCapability(capabilities == null ? null : capabilities.sampling(), "sampling");
        return request("sampling/createMessage", parameters, CreateMessageResponse.class);
    }

    /**
     * Asks the client to get an input from its user.
     * <p>
     * It requires the client to have declared the {@code elicitation} capability in {@code initialize}.
     *
     * @param parameters what to ask and the shape of the expected answer.
     * @return the user answer, check {@link ElicitResponse#action()} before reading the content.
     */
    public CompletionStage<ElicitResponse> elicit(final ElicitRequestParameters parameters) {
        requireClientCapability(capabilities == null ? null : capabilities.elicitation(), "elicitation");
        return request("elicitation/create", parameters, ElicitResponse.class);
    }

    /**
     * Lists the filesystem roots the client gives access to.
     * <p>
     * It requires the client to have declared the {@code roots} capability in {@code initialize}.
     *
     * @return the client roots.
     */
    public CompletionStage<ListRootsResponse> listRoots() {
        requireClientCapability(capabilities == null ? null : capabilities.roots(), "roots");
        return request("roots/list", MetadataParameters.EMPTY, ListRootsResponse.class);
    }

    /**
     * Sends a request to the client and awaits its response - the client sends it back with a {@code POST /mcp}.
     *
     * @param method     the request name.
     * @param params     its parameters.
     * @param resultType the expected result type.
     * @param <T>        the expected result type.
     * @return the client response, it fails with a {@link JsonRpcException} when the client answers an error or
     * when it does not answer within the configured timeout.
     */
    public <T> CompletionStage<T> request(final String method, final Object params, final Class<T> resultType) {
        final var requestId = requestIds.incrementAndGet();
        final var promise = new CompletableFuture<Object>();
        pendingRequests.put(requestId, promise);
        try {
            sse.publish(jsons.toString(JsonRpcMessage.request(requestId, method, params)));
        } catch (final RuntimeException re) {
            pendingRequests.remove(requestId);
            throw re;
        }
        return promise
                .orTimeout(requestTimeout.toMillis(), TimeUnit.MILLISECONDS)
                .whenComplete((ok, ko) -> pendingRequests.remove(requestId))
                .thenApply(result -> map(result, resultType));
    }

    /**
     * Completes the request the client is answering to.
     *
     * @param id     the request identifier, it must be one this session generated.
     * @param result the JSON-RPC {@code result}, {@code null} when {@code error} is set.
     * @param error  the JSON-RPC {@code error}.
     * @return {@code true} when a pending request was waiting for this response.
     */
    public boolean onClientResponse(final long id, final Object result, final Map<String, Object> error) {
        final var promise = pendingRequests.remove(id);
        if (promise == null) {
            logger.log(FINEST, () -> "No pending request '" + id + "' in session '" + this.id + "'");
            return false;
        }
        if (error != null) {
            promise.completeExceptionally(new JsonRpcException(
                    error.get("code") instanceof Number code ? code.intValue() : -32603,
                    String.valueOf(error.getOrDefault("message", "Client error")),
                    error.get("data"), null));
        } else {
            promise.complete(result);
        }
        return true;
    }

    /**
     * Releases the session: the SSE channel is completed and all the requests still awaiting a client response fail.
     */
    public void close() {
        sse.cancel();
        pendingRequests.values().forEach(it -> it.completeExceptionally(
                new JsonRpcException(-32001, "Session '" + id + "' closed")));
        pendingRequests.clear();
        subscriptions.clear();
    }

    private <T> T map(final Object result, final Class<T> resultType) {
        if (result == null) {
            return null;
        }
        if (resultType.isInstance(result)) {
            return resultType.cast(result);
        }
        // the response was read as a plain JSON structure, bind it to the expected model
        return jsons.fromString(resultType, jsons.toString(result));
    }

    private void requireClientCapability(final Object capability, final String name) {
        if (capability == null) {
            throw new JsonRpcException(-32601, "Client does not support '" + name + "'", Map.of(
                    "capability", name,
                    "client", ofNullable(clientInfo).map(ClientInfo::name).orElse("?")), null);
        }
    }
}
