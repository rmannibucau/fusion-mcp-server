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

import static io.yupiik.fusion.mcp.protocol.MCPProtocol.PROTOCOL_VERSION_HEADER;
import static io.yupiik.fusion.mcp.protocol.MCPProtocol.SESSION_HEADER;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.logging.Level.FINEST;
import static java.util.logging.Level.SEVERE;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.http.HttpMatcher;
import io.yupiik.fusion.framework.build.api.order.Order;
import io.yupiik.fusion.http.server.api.IOConsumer;
import io.yupiik.fusion.http.server.api.Request;
import io.yupiik.fusion.http.server.api.Response;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.jsonrpc.JsonRpcHandler;
import io.yupiik.fusion.mcp.configuration.MCPConfiguration;
import java.io.Writer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.logging.Logger;

/**
 * The {@code POST /mcp} half of the MCP streamable HTTP transport.
 * <p>
 * The JSON-RPC processing itself is fully delegated to the Fusion {@link JsonRpcHandler} - so tools are plain
 * {@code @JsonRpc} methods - this endpoint only adds what the MCP transport requires on top of it:
 * <ul>
 *     <li>session handling: {@code Mcp-Session-Id} is created by {@code initialize} and validated afterwards,</li>
 *     <li>{@code MCP-Protocol-Version} header validation,</li>
 *     <li>routing of the client <em>responses</em> - a JSON-RPC message without {@code method} - to the request
 *     the server sent over the SSE channel (sampling, elicitation, roots),</li>
 *     <li>{@code 202 Accepted} with no body for notifications and responses.</li>
 * </ul>
 * It is registered with a lower {@code @Order} than the Fusion JSON-RPC endpoint so it takes precedence on the
 * shared path - see {@code JSONRPCEndpointConfiguration}.
 */
@ApplicationScoped
public class MCPEndpoint {
    /**
     * Request attribute the Fusion JSON-RPC stack fills in with the headers a method set with a
     * {@code PartialResponse}, they are copied to the HTTP response.
     */
    private static final String JSONRPC_RESPONSE_HEADERS = "yupiik.jsonrpc.response.headers";

    private static final String JSON_CONTENT_TYPE = "application/json;charset=utf-8";
    private static final String INITIALIZE = "initialize";

    private final Logger logger = Logger.getLogger(MCPEndpoint.class.getName());

    private final JsonRpcHandler handler;
    private final JsonMapper jsons;
    private final MCPSessions sessions;
    private final boolean requireSession;

    protected MCPEndpoint() {
        this(null, null, null, null);
    }

    public MCPEndpoint(
            final JsonRpcHandler handler,
            final JsonMapper jsons,
            final MCPSessions sessions,
            final MCPConfiguration configuration) {
        this.handler = handler;
        this.jsons = jsons;
        this.sessions = sessions;
        this.requireSession = configuration != null && configuration.requireSession();
    }

    @Order(900) // < the 1000 of the Fusion JSON-RPC endpoint so this one is picked first on POST /mcp
    @HttpMatcher(methods = "POST", path = "/mcp")
    public CompletionStage<Response> handle(final Request request) {
        final var protocolVersion = request.header(PROTOCOL_VERSION_HEADER);
        if (protocolVersion != null && !MCPProtocol.SUPPORTED_VERSIONS.contains(protocolVersion)) {
            return completedFuture(
                    httpError(400, -32600, "Unsupported " + PROTOCOL_VERSION_HEADER + " '" + protocolVersion + "'"));
        }

        try {
            return handler.readRequest(request.fullBody())
                    .thenCompose(payload -> onPayload(payload, request))
                    .exceptionally(error -> onError(-32700, error));
        } catch (final RuntimeException re) { // deserialization error
            return completedFuture(onError(-32700, re));
        }
    }

    private CompletionStage<Response> onPayload(final Object payload, final Request request) {
        if (payload == null) { // an empty - or "null" - body
            return completedFuture(jsonRpc(200, handler.createResponse(null, -32700, "Empty request")));
        }

        final var messages = payload instanceof List<?> list ? list : List.of(payload);
        final var clientResponses = new ArrayList<Map<String, Object>>(0);
        final var calls = new ArrayList<Object>(messages.size());
        for (final var message : messages) {
            if (isClientResponse(message)) {
                @SuppressWarnings("unchecked")
                final var response = (Map<String, Object>) message;
                clientResponses.add(response);
            } else {
                calls.add(message);
            }
        }

        final var resolution = resolveSession(request, calls);
        if (resolution.error() != null) {
            return completedFuture(resolution.error());
        }

        clientResponses.forEach(it -> onClientResponse(resolution.session(), it));

        if (calls.isEmpty()) { // only notifications and/or responses, nothing to answer
            return completedFuture(response(null, request, resolution));
        }
        return handler.execute(payload instanceof List<?> ? calls : calls.get(0), request)
                .thenApply(result -> response(result, request, resolution))
                .exceptionally(error -> onError(-32603, error));
    }

    /**
     * Resolves - and creates for {@code initialize} - the session of the call and binds it to the request.
     */
    private Resolution resolveSession(final Request request, final List<Object> calls) {
        final var id = request.header(SESSION_HEADER);
        if (id != null) {
            final var existing = sessions.find(id);
            if (existing.isEmpty()) {
                // the specification asks for a 404 so the client knows it must initialize again
                return new Resolution(null, false, httpError(404, -32001, "Unknown or expired MCP session"));
            }
            sessions.bind(request, existing.orElseThrow());
            return new Resolution(existing.orElseThrow(), false, null);
        }

        if (calls.stream().anyMatch(it -> it instanceof Map<?, ?> map && INITIALIZE.equals(map.get("method")))) {
            final var created = sessions.create();
            sessions.bind(request, created);
            return new Resolution(created, true, null);
        }

        if (requireSession) {
            return new Resolution(null, false, httpError(400, -32600, "Missing " + SESSION_HEADER + " header"));
        }

        // tolerate the clients ignoring the session header, they just get a stateless session
        final var ephemeral = sessions.ephemeral();
        sessions.bind(request, ephemeral);
        return new Resolution(ephemeral, false, null);
    }

    private void onClientResponse(final MCPSession session, final Map<String, Object> message) {
        if (!(message.get("id") instanceof Number id)) {
            logger.log(FINEST, () -> "Ignoring client response with an unknown id: " + message.get("id"));
            return;
        }
        @SuppressWarnings("unchecked")
        final var error = message.get("error") instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
        session.onClientResponse(id.longValue(), message.get("result"), error);
    }

    private boolean isClientResponse(final Object message) {
        return message instanceof Map<?, ?> map
                && map.get("method") == null
                && map.get("id") != null
                && (map.containsKey("result") || map.containsKey("error"));
    }

    private Response response(final Object payload, final Request request, final Resolution resolution) {
        final var builder = Response.of().header("content-type", JSON_CONTENT_TYPE);

        final var extraHeaders = request.attribute(JSONRPC_RESPONSE_HEADERS, Map.class);
        if (extraHeaders != null) {
            @SuppressWarnings("unchecked")
            final var headers = (Map<String, String>) extraHeaders;
            headers.forEach(builder::header);
        }
        if (resolution.created() && resolution.session().id() != null) {
            builder.header(SESSION_HEADER, resolution.session().id());
        }

        // a notification has no response, and neither has a batch made of notifications only - which the JSON-RPC
        // stack reports as an empty list
        if (payload == null || (payload instanceof Collection<?> responses && responses.isEmpty())) {
            return builder.status(202).build();
        }
        return builder.status(200)
                .body((IOConsumer<Writer>) writer -> {
                    try (writer) {
                        jsons.write(payload, writer);
                    }
                })
                .build();
    }

    private Response onError(final int code, final Throwable error) {
        logger.log(SEVERE, error, error::getMessage);
        return jsonRpc(200, handler.createResponse(null, code, error.getMessage()));
    }

    private Response httpError(final int status, final int code, final String message) {
        return jsonRpc(status, handler.createResponse(null, code, message));
    }

    private Response jsonRpc(final int status, final Object payload) {
        return Response.of()
                .status(status)
                .header("content-type", JSON_CONTENT_TYPE)
                .body(jsons.toString(payload))
                .build();
    }

    private record Resolution(MCPSession session, boolean created, Response error) {}
}
