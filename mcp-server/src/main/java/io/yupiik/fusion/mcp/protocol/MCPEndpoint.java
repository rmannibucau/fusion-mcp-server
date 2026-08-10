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

import static io.yupiik.fusion.mcp.protocol.MCPProtocol.HEADER_MISMATCH;
import static io.yupiik.fusion.mcp.protocol.MCPProtocol.PROTOCOL_VERSION_HEADER;
import static io.yupiik.fusion.mcp.protocol.MCPProtocol.SESSION_HEADER;
import static io.yupiik.fusion.mcp.protocol.MCPProtocol.SUBSCRIPTION_ID_ATTRIBUTE;
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
import jakarta.servlet.http.HttpServletRequest;
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
    private final boolean rejectNonLocalHosts;

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
        this.rejectNonLocalHosts = configuration == null || configuration.rejectNonLocalHosts();
    }

    @Order(900) // < the 1000 of the Fusion JSON-RPC endpoint so this one is picked first on POST /mcp
    @HttpMatcher(methods = "POST", path = "/mcp")
    public CompletionStage<Response> handle(final Request request) {
        if (rejectNonLocalHosts && !HostGuard.isAllowed(request)) {
            return completedFuture(HostGuard.forbidden());
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

        final var id = requestId(calls);

        final var headerCheck = validateMethodHeader(request, calls, id);
        if (headerCheck != null) {
            return completedFuture(headerCheck);
        }
        final var versionCheck = validateVersion(request, calls, id);
        if (versionCheck != null) {
            return completedFuture(versionCheck);
        }
        final var strictCheck = validateStrictMeta(request, calls, id);
        if (strictCheck != null) {
            return completedFuture(strictCheck);
        }
        // the subscriptions/listen stream keeps the JSON-RPC id as its subscriptionId, stash it so the notifier and
        // the cancel can reach it - only meaningful for a single call, batching a stream makes no sense
        if (calls.size() == 1 && calls.get(0) instanceof Map<?, ?> single && single.get("id") != null) {
            request.setAttribute(SUBSCRIPTION_ID_ATTRIBUTE, String.valueOf(single.get("id")));
        }

        final var resolution = resolveSession(request, calls);
        if (resolution.error() != null) {
            return completedFuture(resolution.error());
        }

        clientResponses.forEach(it -> onClientResponse(resolution.session(), it));

        if (calls.isEmpty()) { // only notifications and/or responses, nothing to answer
            return completedFuture(response(null, request, resolution, false));
        }
        final var modern = isModern(request, calls);
        return handler.execute(payload instanceof List<?> ? calls : calls.get(0), request)
                .thenApply(result -> response(result, request, resolution, modern))
                .exceptionally(error -> onError(-32603, error));
    }

    /**
     * The stateless {@code 2026-07-28} transport requires the {@code MCP-Method}/{@code MCP-Name} headers to route
     * fire-and-forget responses: a modern request must carry an {@code Mcp-Method} matching its body method, and an
     * {@code Mcp-Name} matching the {@code params.name}/{@code params.uri} of a {@code tools/call}/{@code resources/read}.
     * A missing or mismatched header is a {@code -32020} error. Legacy requests - and batches - are not enforced.
     */
    private Response validateMethodHeader(final Request request, final List<Object> calls, final Object id) {
        if (calls.size() != 1 || !(calls.get(0) instanceof Map<?, ?> map)) {
            return null;
        }
        final String version = request.header(PROTOCOL_VERSION_HEADER);
        final boolean modern = request.header(MCPProtocol.METHOD_HEADER) != null
                || version != null && MCPProtocol.STATELESS_VERSIONS.contains(version);
        if (!modern) {
            return null; // a legacy - or a body-_meta opened - request, no header obligation
        }
        final var method = request.header(MCPProtocol.METHOD_HEADER);
        if (method == null) {
            return error(
                    id,
                    400,
                    HEADER_MISMATCH,
                    "Missing " + MCPProtocol.METHOD_HEADER + " header on a stateless request");
        }
        if (!method.equals(String.valueOf(map.get("method")))) {
            return error(
                    id,
                    400,
                    HEADER_MISMATCH,
                    MCPProtocol.METHOD_HEADER + " says '" + method + "' but the body says '" + map.get("method") + "'");
        }
        final var name = request.header(MCPProtocol.NAME_HEADER);
        final var paramName = routableName(String.valueOf(map.get("method")), map.get("params"));
        if (paramName != null && (name == null || name.isBlank())) {
            return error(
                    id,
                    400,
                    HEADER_MISMATCH,
                    "Missing " + MCPProtocol.NAME_HEADER + " header for a " + map.get("method") + " request");
        }
        if (paramName != null && name != null && !name.equals(paramName)) {
            return error(
                    id,
                    400,
                    HEADER_MISMATCH,
                    MCPProtocol.NAME_HEADER + " says '" + name + "' but the body says '" + paramName + "'");
        }
        return null;
    }

    private String routableName(final String method, final Object paramsValue) {
        if (!(paramsValue instanceof Map<?, ?> params)) {
            return null;
        }
        final String name =
                switch (method) {
                    case "tools/call", "prompts/get" -> String.valueOf(params.get("name"));
                    case "resources/read" -> String.valueOf(params.get("uri"));
                    case "tasks/get", "tasks/update", "tasks/cancel" -> String.valueOf(params.get("taskId"));
                    default -> "null";
                };
        return name == null || name.isBlank() || "null".equals(name) ? null : name;
    }

    /**
     * When the {@code MCP-Protocol-Version} header - or the {@code _meta.io.modelcontextprotocol/protocolVersion} of a
     * modern request - names a version this server does not implement, the client is rejected with a {@code -32022}
     * {@code UnsupportedProtocolVersionError} listing the versions this server supports. A header and a body version
     * which disagree are rejected first with a {@code -32020} {@code HeaderMismatch}.
     */
    private Response validateVersion(final Request request, final List<Object> calls, final Object id) {
        final var header = request.header(PROTOCOL_VERSION_HEADER);
        final var bodyVersion = modernVersion(calls);
        if (header != null && bodyVersion != null && !header.equals(bodyVersion)) {
            return error(
                    id,
                    400,
                    HEADER_MISMATCH,
                    PROTOCOL_VERSION_HEADER + " header '" + header + "' does not match the _meta '"
                            + MCPProtocol.PROTOCOL_VERSION_META + "' value '" + bodyVersion + "'");
        }
        if (header != null && !supported(header)) {
            return unsupportedVersion(id, header);
        }
        if (bodyVersion != null && !supported(bodyVersion)) {
            return unsupportedVersion(id, bodyVersion);
        }
        return null;
    }

    private boolean supported(final String version) {
        return MCPProtocol.SUPPORTED_VERSIONS.contains(version) || MCPProtocol.STATELESS_VERSIONS.contains(version);
    }

    private Response unsupportedVersion(final Object id, final String version) {
        return jsonRpc(
                400,
                Map.of(
                        "jsonrpc",
                        "2.0",
                        "id",
                        id,
                        "error",
                        Map.of(
                                "code",
                                MCPProtocol.UNSUPPORTED_PROTOCOL_VERSION,
                                "message",
                                "Unsupported " + PROTOCOL_VERSION_HEADER + " '" + version + "'",
                                "data",
                                Map.of("supported", MCPProtocol.STATELESS_VERSIONS, "requested", version))));
    }

    /**
     * @param calls the calls of the payload.
     * @return the JSON-RPC {@code id} of a single call, {@code null} for a batch, a notification or a response.
     */
    private Object requestId(final List<Object> calls) {
        if (calls.size() == 1 && calls.get(0) instanceof Map<?, ?> map) {
            return map.get("id");
        }
        return null;
    }

    /**
     * Enforces the strict {@code 2026-07-28} requirement that every modern request carries a
     * {@code io.modelcontextprotocol/protocolVersion}, a {@code io.modelcontextprotocol/clientInfo} and a
     * {@code io.modelcontextprotocol/clientCapabilities} in {@code _meta} - a missing field is a {@code -32602}
     * invalid-params error, and on HTTP a {@code 400}.
     * <p>
     * Gated on the resolved modern version being one of {@link MCPProtocol#STRICT_MODERN_VERSIONS}: a tolerated
     * legacy-modern request on an older version stays tolerant.
     */
    private Response validateStrictMeta(final Request request, final List<Object> calls, final Object id) {
        // the strict signal is the transport: a stateless MCP-Protocol-Version header, or a body _meta version
        final var header = request.header(PROTOCOL_VERSION_HEADER);
        final var bodyVersion = modernVersion(calls);
        final var strict = (header != null && MCPProtocol.STRICT_MODERN_VERSIONS.contains(header))
                || (bodyVersion != null && MCPProtocol.STRICT_MODERN_VERSIONS.contains(bodyVersion));
        if (!strict) {
            return null; // not a strict modern request, nothing to enforce here
        }
        final var missing = requiredMetaMissing(calls);
        if (missing != null) {
            return error(id, 400, -32602, "Missing required _meta field: " + missing);
        }
        return null;
    }

    /**
     * @param calls the calls of the payload, the version is read from the single call - or the header.
     * @return the {@code io.modelcontextprotocol/protocolVersion} that opened this request, or {@code null} when each
     * call declares a non-modern version.
     */
    private String modernVersion(final List<Object> calls) {
        if (calls.size() == 1 && calls.get(0) instanceof Map<?, ?> call) {
            final var meta = meta(call);
            if (meta != null) {
                final var version = meta.get(MCPProtocol.PROTOCOL_VERSION_META);
                return version == null ? null : String.valueOf(version);
            }
        }
        return null;
    }

    /**
     * @param the calls this request carries.
     * @return the name of the first strictly-required {@code _meta} field which is missing, {@code null} when the
     * request (single call) declares all of them.
     */
    private String requiredMetaMissing(final List<Object> calls) {
        if (!(calls.get(0) instanceof Map<?, ?> map)) {
            return null;
        }
        final var meta = meta(map);
        if (meta == null) {
            return MCPProtocol.PROTOCOL_VERSION_META;
        }
        if (meta.get(MCPProtocol.PROTOCOL_VERSION_META) == null) {
            return MCPProtocol.PROTOCOL_VERSION_META;
        }
        if (meta.get(MCPProtocol.CLIENT_INFO_META) == null) {
            return MCPProtocol.CLIENT_INFO_META;
        }
        if (meta.get(MCPProtocol.CLIENT_CAPABILITIES_META) == null) {
            return MCPProtocol.CLIENT_CAPABILITIES_META;
        }
        return null;
    }

    private static Map<?, ?> meta(final Map<?, ?> message) {
        return message.get("params") instanceof Map<?, ?> params && params.get("_meta") instanceof Map<?, ?> meta
                ? meta
                : null;
    }

    /**
     * Decides if a request is served over the stateless {@code 2026-07-28} protocol: an {@code MCP-Protocol-Version}
     * header naming a stateless version, an {@code MCP-Method} header or a {@code _meta} protocolVersion naming one.
     */
    private boolean isModern(final Request request, final List<Object> calls) {
        // an explicit protocol version wins: a stateless one (2026-07-28) is modern, a supported legacy one is not -
        // a conformance client crafts a legacy initialize carrying an Mcp-Method header, which must stay legacy
        final var protocolVersion = request.header(PROTOCOL_VERSION_HEADER);
        if (protocolVersion != null) {
            return MCPProtocol.STATELESS_VERSIONS.contains(protocolVersion);
        }
        if (request.header(MCPProtocol.METHOD_HEADER) != null) {
            return true;
        }
        return calls.stream().anyMatch(call -> {
            if (!(call instanceof Map<?, ?> map)) {
                return false;
            }
            final var meta = meta(map);
            return meta != null
                    && MCPProtocol.STATELESS_VERSIONS.contains(
                            String.valueOf(meta.get(MCPProtocol.PROTOCOL_VERSION_META)));
        });
    }

    /**
     * Resolves - and creates for {@code initialize} - the session of the call and binds it to the request.
     */
    private Resolution resolveSession(final Request request, final List<Object> calls) {
        if (isModern(request, calls)) {
            // the stateless protocol has no session: every request is independent, the client gets a fresh state and
            // the responses carry the stateless result fields
            final var stateless = sessions.ephemeral(true);
            sessions.bind(request, stateless);
            return new Resolution(stateless, false, null);
        }

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

    private Response response(
            final Object payload, final Request request, final Resolution resolution, final boolean modern) {
        if (payload instanceof io.yupiik.fusion.jsonrpc.Response jsonRpc
                && jsonRpc.result() instanceof ResponseWithBus rwb) {
            // a stateless subscriptions/listen: the "result" is a stream, not a JSON body, the bus commits the
            // response with a comment so the client knows the stream is live
            request.unwrap(HttpServletRequest.class).getAsyncContext().setTimeout(0);
            return Response.of()
                    .status(200)
                    .header("content-type", "text/event-stream;charset=utf-8")
                    .header("cache-control", "no-cache")
                    .header("connection", "keep-alive")
                    .body(rwb.bus())
                    .build();
        }

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
        // a stateless request which published notifications while running - e.g. progress - answers on the response
        // stream: the buffered notifications first, the final result last
        if (modern && payload instanceof io.yupiik.fusion.jsonrpc.Response jsonRpc && jsonRpc.result() != null) {
            final var notifications = resolution.session().sse().drainQueuedJson();
            if (!notifications.isEmpty()) {
                return streamResponse(request, notifications, payload);
            }
        }
        // the stateless protocol maps the JSON-RPC errors to HTTP: a method the server does not implement is a 404,
        // everything else is a 400 - the legacy transport answers every error with a 200
        final var status = modern ? httpStatusOf(payload) : 200;
        return builder.status(status)
                .body((IOConsumer<Writer>) writer -> {
                    try (writer) {
                        jsons.write(payload, writer);
                    }
                })
                .build();
    }

    /**
     * Answers a stateless request with the {@code text/event-stream} body of its response stream: the notifications
     * it published while running - {@code notifications/progress} for a long tool - then the final JSON-RPC result.
     *
     * @param request       the current HTTP request.
     * @param notifications the JSON-RPC notifications to stream first, in emission order.
     * @param payload       the final JSON-RPC response.
     * @return the streaming response.
     */
    private Response streamResponse(final Request request, final List<String> notifications, final Object payload) {
        request.unwrap(HttpServletRequest.class).getAsyncContext().setTimeout(0);
        return Response.of()
                .status(200)
                .header("content-type", "text/event-stream;charset=utf-8")
                .header("cache-control", "no-cache")
                .header("connection", "keep-alive")
                .body((IOConsumer<Writer>) writer -> {
                    try (writer) {
                        var id = 0L;
                        for (final var notification : notifications) {
                            writer.write(event(id, notification));
                            id++;
                        }
                        writer.write("id: " + id + "\nevent: message\ndata: ");
                        jsons.write(payload, writer);
                        writer.write("\n\n");
                    }
                })
                .build();
    }

    private static String event(final long id, final String json) {
        return "id: " + id + "\nevent: message\ndata: " + json + "\n\n";
    }

    /**
     * @param payload a JSON-RPC response.
     * @return the HTTP status code the {@code 2026-07-28} transport assigns to a JSON-RPC error: {@code 404} for a
     * method the server does not implement ({@code -32601}), {@code 400} for every other error.
     */
    private int httpStatusOf(final Object payload) {
        if (payload instanceof io.yupiik.fusion.jsonrpc.Response response && response.error() != null) {
            return response.error().code() == -32601 ? 404 : 400;
        }
        return 200;
    }

    private Response onError(final int code, final Throwable error) {
        logger.log(SEVERE, error, error::getMessage);
        return jsonRpc(200, handler.createResponse(null, code, error.getMessage()));
    }

    private Response httpError(final int status, final int code, final String message) {
        return jsonRpc(status, handler.createResponse(null, code, message));
    }

    private Response error(final Object id, final int status, final int code, final String message) {
        return jsonRpc(
                status,
                new io.yupiik.fusion.jsonrpc.Response(
                        "2.0", id, null, new io.yupiik.fusion.jsonrpc.Response.ErrorResponse(code, message, null)));
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
