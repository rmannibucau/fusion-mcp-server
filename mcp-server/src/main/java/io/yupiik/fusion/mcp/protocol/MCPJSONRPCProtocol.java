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

import static java.util.logging.Level.FINE;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.jsonrpc.JsonRpc;
import io.yupiik.fusion.framework.build.api.jsonrpc.JsonRpcParam;
import io.yupiik.fusion.http.server.api.Request;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.jsonrpc.JsonRpcException;
import io.yupiik.fusion.jsonrpc.JsonRpcHandler;
import io.yupiik.fusion.jsonrpc.Response;
import io.yupiik.fusion.mcp.api.MCPCompletions;
import io.yupiik.fusion.mcp.api.MCPResources;
import io.yupiik.fusion.mcp.api.TaskRegistry;
import io.yupiik.fusion.mcp.configuration.MCPConfiguration;
import io.yupiik.fusion.mcp.model.Capabilities;
import io.yupiik.fusion.mcp.model.ClientInfo;
import io.yupiik.fusion.mcp.model.CompleteResult;
import io.yupiik.fusion.mcp.model.CompletionArgument;
import io.yupiik.fusion.mcp.model.CompletionContext;
import io.yupiik.fusion.mcp.model.CompletionRef;
import io.yupiik.fusion.mcp.model.Content;
import io.yupiik.fusion.mcp.model.InitializeResponse;
import io.yupiik.fusion.mcp.model.InputRequest;
import io.yupiik.fusion.mcp.model.ListPromptsResponse;
import io.yupiik.fusion.mcp.model.ListResourceTemplatesResponse;
import io.yupiik.fusion.mcp.model.ListResourcesResponse;
import io.yupiik.fusion.mcp.model.ListTasksResponse;
import io.yupiik.fusion.mcp.model.ListToolsResponse;
import io.yupiik.fusion.mcp.model.LoggingLevel;
import io.yupiik.fusion.mcp.model.MCPRequestMetadata;
import io.yupiik.fusion.mcp.model.MCPResult;
import io.yupiik.fusion.mcp.model.Metadata;
import io.yupiik.fusion.mcp.model.PromptResponse;
import io.yupiik.fusion.mcp.model.ReadResourceResponse;
import io.yupiik.fusion.mcp.model.ResultType;
import io.yupiik.fusion.mcp.model.ServerCapabilities;
import io.yupiik.fusion.mcp.model.ServerDiscoverResponse;
import io.yupiik.fusion.mcp.model.SubscriptionFilter;
import io.yupiik.fusion.mcp.model.Task;
import io.yupiik.fusion.mcp.model.TaskStatus;
import io.yupiik.fusion.mcp.model.ToolResponse;
import io.yupiik.fusion.mcp.service.DescriptorService;
import io.yupiik.fusion.mcp.spi.MCPRequestStateCodec;
import io.yupiik.fusion.mcp.spi.OpaqueRequestStateCodec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * The MCP protocol itself: every MCP method is a plain Fusion {@code @JsonRpc} method, exactly like the tools of the
 * application, which is what enables the whole JSON-RPC stack - parameter binding, validation, batching, OpenRPC
 * generation - to be reused as is.
 * <p>
 * Only the client to server direction lives here. The other one - logging and {@code list_changed} notifications,
 * sampling, elicitation and roots requests - goes through the SSE channel, see
 * {@link io.yupiik.fusion.mcp.api.MCPNotifier} and {@link MCPSession}.
 *
 * @see MCPEndpoint for the transport concerns (sessions, headers, client responses).
 */
@ApplicationScoped
public class MCPJSONRPCProtocol {
    private final Logger logger = Logger.getLogger(MCPJSONRPCProtocol.class.getName());

    private final DescriptorService descriptors;
    private final JsonRpcHandler handler;
    private final JsonMapper jsons;
    private final MCPSessions sessions;
    private final List<MCPResources> resources;
    private final List<MCPCompletions> completions;
    private final InitializeResponse initializeResponse;
    private final ServerDiscoverResponse discoverResponse;
    private final Long cacheTtlMs;
    private final String cacheScope;
    private final MCPRequestStateCodec requestStateCodec;
    private final TaskRegistry tasks = new TaskRegistry();

    /**
     * @return the store of the {@code io.modelcontextprotocol/tasks} extension, where long-running operations live.
     */
    public TaskRegistry tasks() {
        return tasks;
    }

    // for subclassing proxies
    protected MCPJSONRPCProtocol() {
        this(null, null, null, null, null, null, null, null);
    }

    public MCPJSONRPCProtocol(
            final DescriptorService descriptors,
            final JsonMapper jsons,
            final JsonRpcHandler handler,
            final MCPSessions sessions,
            final MCPConfiguration configuration,
            final List<MCPResources> resources,
            final List<MCPCompletions> completions,
            final List<MCPRequestStateCodec> requestStateCodecs) {
        this.descriptors = descriptors;
        this.handler = handler;
        this.jsons = jsons;
        this.sessions = sessions;
        this.resources = resources == null ? List.of() : resources;
        final var userCompletions = completions == null ? List.<MCPCompletions>of() : completions;
        final var autoCompletions = descriptors == null ? List.<MCPCompletions>of() : descriptors.completionProviders();
        this.completions = Stream.concat(userCompletions.stream(), autoCompletions.stream())
                .toList();
        final var codecs = requestStateCodecs == null ? List.<MCPRequestStateCodec>of() : requestStateCodecs;
        this.requestStateCodec = codecs.stream()
                .filter(it -> it != null && it.isActive() && !(it instanceof OpaqueRequestStateCodec))
                .findFirst()
                .orElseGet(() -> codecs.stream()
                        .filter(MCPRequestStateCodec::isActive)
                        .findFirst()
                        .orElseGet(OpaqueRequestStateCodec::new));
        this.initializeResponse = descriptors == null || configuration == null
                ? null
                : new InitializeResponse(
                        MCPProtocol.LATEST_VERSION,
                        new InitializeResponse.Capabilities(
                                // logging is always there, any bean can push records with MCPNotifier
                                Map.of(),
                                descriptors.prompts().prompts().isEmpty()
                                        ? null
                                        : new InitializeResponse.Prompts(false),
                                // resources are provided at runtime so both subscriptions and list changes are
                                // supported
                                this.resources.isEmpty() ? null : new InitializeResponse.Resources(true, true),
                                descriptors.tools().tools().isEmpty() ? null : new InitializeResponse.Tools(false),
                                // completion/complete is supported, fullCompletion/complete is not (yet)
                                this.completions.isEmpty() ? null : Map.of("completions", Map.of()),
                                null),
                        new InitializeResponse.ServerInfo(
                                configuration.name(), configuration.title(), configuration.version()),
                        configuration.instructions());
        this.discoverResponse = descriptors == null || configuration == null
                ? null
                : new ServerDiscoverResponse(
                        MCPProtocol.STATELESS_VERSIONS,
                        new ServerCapabilities(
                                null,
                                // logging is always there, any bean can push records with MCPNotifier
                                Map.of(),
                                // completion/complete is supported, fullCompletion/complete is not (yet)
                                this.completions.isEmpty() ? null : Map.of("completions", Map.of()),
                                // the stateless protocol delivers the list changes over subscriptions/listen
                                descriptors.prompts().prompts().isEmpty() ? null : new ServerCapabilities.Prompts(true),
                                this.resources.isEmpty() ? null : new ServerCapabilities.Resources(true, true),
                                descriptors.tools().tools().isEmpty() ? null : new ServerCapabilities.Tools(true),
                                Map.of("io.modelcontextprotocol/tasks", Map.of())),
                        initializeResponse.serverInfo(),
                        configuration.instructions(),
                        ResultType.complete,
                        configuration.cacheTtlMs(),
                        configuration.cacheScope(),
                        new Metadata(
                                null, null, Map.of(MCPProtocol.SERVER_INFO_META, initializeResponse.serverInfo())));
        this.cacheTtlMs = configuration == null ? null : configuration.cacheTtlMs();
        this.cacheScope = configuration == null ? null : configuration.cacheScope();
    }

    /**
     * See <a href="https://modelcontextprotocol.io/specification/2025-06-18/basic/lifecycle">lifecycle</a>.
     * <p>
     * The session is created by the transport - {@link MCPEndpoint} - which also returns its identifier in the
     * {@code Mcp-Session-Id} response header.
     */
    @JsonRpc(
            value = "initialize",
            documentation = "Negotiates the protocol version and returns the server capabilities.")
    public InitializeResponse initialize(
            @JsonRpcParam(required = true, documentation = "The protocol version the client wants to use.")
                    final String protocolVersion,
            @JsonRpcParam(documentation = "What the client supports: roots, sampling, elicitation.")
                    final Capabilities capabilities,
            @JsonRpcParam(documentation = "Which client is connecting.") final ClientInfo clientInfo,
            final Request request) {
        // initialize is a legacy handshake, a modern client discovers the server with server/discover
        if (stateless(request)) {
            throw new JsonRpcException(-32601, "initialize is only available over the legacy protocol", null, null);
        }
        // the specification requires to answer a version we support - and not an error - the client then decides if
        // it can go on or not
        final var negotiated = MCPProtocol.negotiate(protocolVersion);
        sessions.of(request).onInitialize(negotiated, capabilities, clientInfo);
        if (negotiated.equals(initializeResponse.protocolVersion())) {
            return initializeResponse;
        }
        return new InitializeResponse(
                negotiated,
                initializeResponse.capabilities(),
                initializeResponse.serverInfo(),
                initializeResponse.instructions());
    }

    @JsonRpc(
            value = "server/discover",
            documentation = "What this server supports over the stateless protocol, it replaces initialize for the "
                    + "stateless clients - no session is created.")
    public ServerDiscoverResponse discover(
            @JsonRpcParam("_meta") final MCPRequestMetadata metadata, final Request request) {
        if (!stateless(request)) {
            throw new JsonRpcException(
                    -32601, "server/discover is only available over the stateless protocol", null, null);
        }
        return discoverResponse;
    }

    @JsonRpc(
            value = "subscriptions/listen",
            documentation = "Opens the server to client stream of the stateless protocol, it stays open until the "
                    + "client calls subscriptions/cancel.")
    // returns Object (the ResponseWithBus) so the JSON-RPC layer does not try to serialize the SseBus, the transport
    // converts it to a stream before any serialization
    public Object onSubscriptionsListen(
            @JsonRpcParam("notifications") final SubscriptionFilter filter,
            @JsonRpcParam("_meta") final Object metadata,
            final Request request) {
        final var session = sessions.of(request);
        if (!session.isStateless()) {
            throw new JsonRpcException(
                    -32601, "subscriptions/listen is only available over the stateless protocol", null, null);
        }
        applyRequestMetadata(session, metadata, request);
        final var subscriptionId = request.attribute(MCPProtocol.SUBSCRIPTION_ID_ATTRIBUTE, String.class);
        if (subscriptionId != null) {
            // register so a later subscriptions/cancel - and the notifications - can reach this ephemeral stream
            sessions.registerSubscription(subscriptionId, filter, session);
            // an ack: the first message of the stream is the notifications/subscriptions/acknowledged notification,
            // it tells the client the stream is committed, live and which notifications it will honor
            session.notify(
                    "notifications/subscriptions/acknowledged",
                    Map.of("notifications", filter == null ? Map.of() : filter),
                    parseSubscriptionId(subscriptionId));
        }
        return new ResponseWithBus(session.sse(), null);
    }

    @JsonRpc(
            value = "subscriptions/cancel",
            documentation = "Closes the server to client stream opened by subscriptions/listen.")
    public Map<String, String> onSubscriptionsCancel(
            @JsonRpcParam("_meta") final Object metadata, final Request request) {
        final var session = sessions.of(request);
        if (!session.isStateless()) {
            throw new JsonRpcException(
                    -32601, "subscriptions/cancel is only available over the stateless protocol", null, null);
        }
        // the cancel arrives on a fresh stateless request, its own session holds no stream - reach the one opened by
        // subscriptions/listen through the registry
        var subscriptionId = requestMetadata(metadata) == null
                ? null
                : requestMetadata(metadata).subscriptionId();
        if (subscriptionId == null) {
            subscriptionId = request.attribute(MCPProtocol.SUBSCRIPTION_ID_ATTRIBUTE, String.class);
        }
        if (subscriptionId != null) {
            final var id = subscriptionId;
            sessions.subscription(id).ifPresent(subscription -> {
                subscription.session().end(parseSubscriptionId(id), MCPResult.ack());
                sessions.unregisterSubscription(id);
            });
        }
        return Map.of();
    }

    /**
     * @param subscriptionId the JSON-RPC identifier of the {@code subscriptions/listen} call, as a string.
     * @return the identifier as a number when it is numeric, else the string itself - to echo it back as sent.
     */
    private Object parseSubscriptionId(final String subscriptionId) {
        try {
            return Long.parseLong(subscriptionId == null ? "" : subscriptionId.trim());
        } catch (final NumberFormatException nfe) {
            return subscriptionId;
        }
    }

    @JsonRpc(value = "notifications/initialized", documentation = "The client is ready, the session can be used.")
    public void onInitialized(@JsonRpcParam("_meta") final Metadata metadata, final Request request) {
        sessions.of(request).onInitialized();
    }

    @JsonRpc(value = "notifications/cancelled", documentation = "The client gave up on a request it sent.")
    public void onCancelled(
            @JsonRpcParam(documentation = "Why the request was cancelled.") final String reason,
            @JsonRpcParam(documentation = "The cancelled request id, a string or a number.") final Object requestId) {
        // the request is already running in the JSON-RPC stack so there is nothing to interrupt - and the SSE
        // channel must stay open, only the request was cancelled, not the session
        logger.log(FINE, () -> "Client cancelled request '" + requestId + "'" + (reason == null ? "" : ": " + reason));
    }

    @JsonRpc(
            value = "ping",
            documentation =
                    "Liveness check, see https://modelcontextprotocol.io/specification/2025-06-18/basic/utilities/ping.")
    public Map<String, String> ping(@JsonRpcParam("_meta") final Metadata metadata, final Request request) {
        if (stateless(request)) {
            // ping is a legacy utility, the stateless protocol does not implement it
            throw new JsonRpcException(-32601, "ping is not available over the stateless protocol", null, null);
        }
        return Map.of();
    }

    @JsonRpc(value = "notifications/progress", documentation = "Progress of a request the client sent to the server.")
    public void onProgress(
            @JsonRpcParam final String message,
            @JsonRpcParam final Double progress,
            @JsonRpcParam final Object progressToken, // a string or a number
            @JsonRpcParam final Double total) {
        // no-op: this server does not send the client requests it could report progress of
    }

    @JsonRpc(value = "notifications/roots/list_changed", documentation = "The client filesystem roots changed.")
    public void onRootsListChanged(@JsonRpcParam("_meta") final Metadata metadata) {
        // no-op: roots are always fetched on demand with MCPSession#listRoots so there is no cache to invalidate
    }

    // note: it returns an empty result and not void because MCP defines it as a request - a void JSON-RPC method is
    // seen as a notification by Fusion, so a client awaiting the response would wait forever
    @JsonRpc(
            value = "logging/setLevel",
            documentation = "Sets the minimum severity of the log records sent to the client.")
    public Map<String, String> setLoggingLevel(
            @JsonRpcParam(
                            required = true,
                            documentation =
                                    "A syslog level name: debug, info, notice, warning, error, critical, alert or emergency.")
                    final String level,
            final Request request) {
        if (stateless(request)) {
            // logging/setLevel is a legacy utility, the stateless protocol does not implement it
            throw new JsonRpcException(
                    -32601, "logging/setLevel is not available over the stateless protocol", null, null);
        }
        final LoggingLevel parsed;
        try {
            parsed = LoggingLevel.valueOf(level);
        } catch (final IllegalArgumentException iae) {
            throw new JsonRpcException(
                    -32602,
                    "Invalid logging level '" + level + "'",
                    Map.of(
                            "supported",
                            Stream.of(LoggingLevel.values())
                                    .map(Enum::name)
                                    .sorted()
                                    .toList()),
                    null);
        }
        sessions.of(request).setLoggingLevel(parsed);
        return Map.of();
    }

    @JsonRpc(value = "tools/list", documentation = "Lists the tools the model can call.")
    public ListToolsResponse listTools(
            @JsonRpcParam(documentation = "Pagination cursor, unused: all the tools are returned at once.")
                    final String cursor,
            final Request httpRequest) {
        return statelessResponse(httpRequest, descriptors.tools(requestProtocolVersion(httpRequest)));
    }

    public CompletionStage<MCPResult> callTool(
            final String name, final Object arguments, final Object metadata, final Request httpRequest) {
        return callTool(name, arguments, null, null, metadata, httpRequest);
    }

    @JsonRpc(value = "tools/call", documentation = "Calls a tool.")
    public CompletionStage<MCPResult> callTool(
            @JsonRpcParam(required = true, documentation = "The tool name, as returned by tools/list.")
                    final String name,
            @JsonRpcParam(documentation = "The tool arguments, they must match its inputSchema.")
                    final Object arguments,
            @JsonRpcParam(documentation = "The responses to a previous input_required request, as top-level params.")
                    final Map<String, Object> inputResponses,
            @JsonRpcParam(documentation = "The token to resume a previous input_required interaction.")
                    final String requestState,
            @JsonRpcParam("_meta") final Object metadata,
            final Request httpRequest) {
        // only the methods flagged with @MCPTool are callable, else every JSON-RPC method of the application - and of
        // the MCP protocol itself - would be reachable through tools/call
        if (!descriptors.isTool(name)) {
            throw new JsonRpcException(-32602, "Unknown tool '" + name + "'", Map.of("name", name), null);
        }
        final var session = httpRequest == null ? null : sessions.of(httpRequest);
        applyRequestMetadata(session, metadata, inputResponses, requestState, httpRequest);
        validateMcpParamHeaders(name, arguments, httpRequest);
        return handler.execute(jsonRpc(name, arguments), httpRequest)
                .thenApply(res -> onToolResult(name, res, httpRequest));
    }

    /**
     * Validates the {@code Mcp-Param-<suffix>} headers (SEP-2243) on a stateless {@code tools/call}: a tool schema
     * property carrying an {@code x-mcp-header} hint is transported through that header, so the request must carry a
     * matching header when the argument is present in the body, its value - possibly base64 encoded with an
     * {@code =?base64?...?=} wrapper - must be valid and must match the body argument. A violation is a {@code -32020}
     * {@code HeaderMismatch} error.
     */
    private void validateMcpParamHeaders(final String name, final Object arguments, final Request httpRequest) {
        if (httpRequest == null || !stateless(httpRequest) || !(arguments instanceof Map<?, ?> argumentMap)) {
            return;
        }
        final var tool = descriptors.tools(requestProtocolVersion(httpRequest)).tools().stream()
                .filter(it -> name.equals(it.name()))
                .findFirst()
                .orElse(null);
        if (tool == null || tool.inputSchema() == null || tool.inputSchema().properties() == null) {
            return;
        }
        for (final var entry : tool.inputSchema().properties().entrySet()) {
            final var suffix = entry.getValue().xMcpHeader();
            if (suffix == null || !argumentMap.containsKey(entry.getKey())) {
                continue;
            }
            final var headerName = "Mcp-Param-" + suffix;
            final var headerValue = httpRequest.header(headerName);
            if (headerValue == null) {
                throw new JsonRpcException(
                        MCPProtocol.HEADER_MISMATCH,
                        "Missing " + headerName + " header while the '" + entry.getKey() + "' argument is present",
                        null,
                        null);
            }
            final var expected = String.valueOf(argumentMap.get(entry.getKey()));
            if (!expected.equals(decodeMcpParamHeader(headerName, headerValue))) {
                throw new JsonRpcException(
                        MCPProtocol.HEADER_MISMATCH,
                        headerName + " header '" + headerValue + "' does not match the '" + entry.getKey()
                                + "' argument",
                        null,
                        null);
            }
        }
    }

    /**
     * @param headerName the header name, only used to build the error message.
     * @param value      the header value.
     * @return the decoded value: an {@code =?base64?...?=} wrapper is decoded - an invalid payload is a {@code -32020}
     * error -, any other value is used as is.
     */
    private String decodeMcpParamHeader(final String headerName, final String value) {
        final var prefix = "=?base64?";
        final var suffix = "?=";
        if (!value.startsWith(prefix) || !value.endsWith(suffix)) {
            return value; // no base64 wrapper: a literal value
        }
        final var encoded = value.substring(prefix.length(), value.length() - suffix.length());
        if (!isStrictBase64(encoded)) {
            throw new JsonRpcException(
                    MCPProtocol.HEADER_MISMATCH,
                    "Invalid base64 encoding in " + headerName + " header '" + value + "'",
                    null,
                    null);
        }
        return new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
    }

    /**
     * @param value a base64 payload of an {@code =?base64?...?=} header value.
     * @return {@code true} when the payload is strict RFC 4648 base64: only the base64 alphabet, a length which is a
     * multiple of 4, and a correctly placed {@code =}/{@code ==} padding - {@code Base64#getDecoder} alone would
     * tolerate an unpadded {@code SGVsbG8} while SEP-2243 requires rejecting it.
     */
    private boolean isStrictBase64(final String value) {
        if (value.isEmpty() || value.length() % 4 != 0) {
            return false;
        }
        final var chars = value.toCharArray();
        var padding = 0;
        for (var i = 0; i < chars.length; i++) {
            final var c = chars[i];
            if (c == '=') {
                if (++padding > 2 || i < chars.length - padding) {
                    return false; // '=' only as a trailing padding of one or two characters
                }
            } else if (!(c >= 'A' && c <= 'Z'
                    || c >= 'a' && c <= 'z'
                    || c >= '0' && c <= '9'
                    || c == '+'
                    || c == '/')) {
                return false;
            }
        }
        return true;
    }

    @JsonRpc(value = "prompts/list", documentation = "Lists the prompt templates the user can pick.")
    public ListPromptsResponse listPrompts(
            @JsonRpcParam(documentation = "Pagination cursor, unused: all the prompts are returned at once.")
                    final String cursor,
            final Request httpRequest) {
        return statelessResponse(httpRequest, descriptors.prompts());
    }

    public CompletionStage<MCPResult> callPrompt(
            final String name, final Map<String, Object> arguments, final Object metadata, final Request request) {
        return callPrompt(name, arguments, null, null, metadata, request);
    }

    @JsonRpc(value = "prompts/get", documentation = "Expands a prompt template.")
    public CompletionStage<MCPResult> callPrompt(
            @JsonRpcParam(required = true, documentation = "The prompt name, as returned by prompts/list.")
                    final String name,
            @JsonRpcParam(documentation = "The prompt arguments, all strings.") final Map<String, Object> arguments,
            @JsonRpcParam(documentation = "The answers to a previous input_required request, as top-level params.")
                    final Map<String, Object> inputResponses,
            @JsonRpcParam(documentation = "The token to resume a previous input_required interaction.")
                    final String requestState,
            @JsonRpcParam("_meta") final Object metadata,
            final Request httpRequest) {
        if (!descriptors.isPrompt(name)) {
            throw new JsonRpcException(-32602, "Unknown prompt '" + name + "'", Map.of("name", name), null);
        }
        final var session = httpRequest == null ? null : sessions.of(httpRequest);
        applyRequestMetadata(session, metadata, inputResponses, requestState, httpRequest);
        return handler.execute(jsonRpc(name, arguments), httpRequest).thenApply(res -> {
            if (res instanceof Response response && response.result() instanceof PromptResponse promptResponse) {
                return MCPResult.complete(promptResponse);
            }
            if (res instanceof Response response
                    && response.error() != null
                    && response.error().code() == MCPProtocol.INPUT_REQUIRED) {
                return inputRequiredResult(response.error(), httpRequest);
            }
            throw toException(res instanceof Response response ? response.error() : null);
        });
    }

    @JsonRpc(value = "resources/list", documentation = "Lists the resources the client can read.")
    public ListResourcesResponse listResources(
            @JsonRpcParam(documentation = "Pagination cursor, unused: all the resources are returned at once.")
                    final String cursor,
            final Request httpRequest) {
        final var response = new ListResourcesResponse(
                resources.stream()
                        .map(MCPResources::resources)
                        .flatMap(List::stream)
                        .distinct()
                        .toList(),
                null,
                null,
                null,
                null,
                null);
        return statelessResponse(httpRequest, response);
    }

    @JsonRpc(
            value = "resources/templates/list",
            documentation = "Lists the parameterized resources the client can read.")
    public ListResourceTemplatesResponse listResourceTemplates(
            @JsonRpcParam(documentation = "Pagination cursor, unused: all the templates are returned at once.")
                    final String cursor,
            final Request httpRequest) {
        final var response = new ListResourceTemplatesResponse(
                resources.stream()
                        .map(MCPResources::resourceTemplates)
                        .flatMap(List::stream)
                        .distinct()
                        .toList(),
                null,
                null,
                null,
                null,
                null);
        return statelessResponse(httpRequest, response);
    }

    @JsonRpc(value = "resources/read", documentation = "Reads the contents of a resource.")
    public MCPResult readResource(
            @JsonRpcParam(required = true, documentation = "The resource uri.") final String uri,
            @JsonRpcParam("_meta") final Object metadata,
            final Request httpRequest) {
        final var session = httpRequest == null ? null : sessions.of(httpRequest);
        applyRequestMetadata(session, metadata, httpRequest);
        final var response = resources.stream()
                .map(it -> it.read(uri))
                .flatMap(Optional::stream)
                .findFirst()
                // -32002 is the code the specification reserves for a missing resource
                .orElseThrow(
                        () -> new JsonRpcException(-32002, "Unknown resource '" + uri + "'", Map.of("uri", uri), null));
        if (!stateless(httpRequest)) {
            return MCPResult.complete(response);
        }
        return MCPResult.complete(new ReadResourceResponse(
                withServerInfo(response.metadata(), httpRequest),
                response.contents(),
                ResultType.complete,
                cacheTtlMs,
                cacheScope));
    }

    // both return an empty result and not void, see setLoggingLevel: MCP defines them as requests
    @JsonRpc(value = "resources/subscribe", documentation = "Asks to be notified when a resource changes.")
    public Map<String, String> subscribeResource(
            @JsonRpcParam(required = true, documentation = "The resource uri to watch.") final String uri,
            final Request request) {
        if (stateless(request)) {
            // subscriptions/listen replaced resources/subscribe on the stateless protocol
            throw new JsonRpcException(
                    -32601, "resources/subscribe is not available over the stateless protocol", null, null);
        }
        // no existence check: resources are dynamic, a client can legitimately watch a uri which does not exist yet
        sessions.of(request).subscribe(uri);
        return Map.of();
    }

    @JsonRpc(value = "resources/unsubscribe", documentation = "Stops watching a resource.")
    public Map<String, String> unsubscribeResource(
            @JsonRpcParam(required = true, documentation = "The resource uri to stop watching.") final String uri,
            final Request request) {
        if (stateless(request)) {
            // subscriptions/cancel replaced resources/unsubscribe on the stateless protocol
            throw new JsonRpcException(
                    -32601, "resources/unsubscribe is not available over the stateless protocol", null, null);
        }
        sessions.of(request).unsubscribe(uri);
        return Map.of();
    }

    @JsonRpc(
            value = "completion/complete",
            documentation = "Suggests values for a prompt argument or a resource template variable.")
    public CompleteResult completion(
            @JsonRpcParam(required = true, documentation = "The argument being completed and its current value.")
                    final CompletionArgument argument,
            @JsonRpcParam(documentation = "The arguments already resolved.") final CompletionContext context,
            @JsonRpcParam(required = true, documentation = "What is completed: a prompt or a resource template.")
                    final CompletionRef ref,
            final Request httpRequest) {
        return new CompleteResult(
                null,
                completions.stream()
                        .map(it -> it.complete(ref, argument, context))
                        .filter(Objects::nonNull)
                        .findFirst()
                        .orElseGet(() -> new CompleteResult.Completion(false, 0, List.of())),
                stateless(httpRequest) ? ResultType.complete : null);
    }

    @JsonRpc(value = "tasks/list", documentation = "Lists the live long-running tasks of the tasks extension.")
    public ListTasksResponse listTasks(
            @JsonRpcParam(documentation = "Pagination cursor, unused: all the tasks are returned at once.")
                    final String cursor,
            final Request httpRequest) {
        return new ListTasksResponse(Map.of(), tasks.all(), stateless(httpRequest) ? ResultType.complete : null);
    }

    @JsonRpc(
            value = "tasks/get",
            documentation = "Polls the current state of a long-running task of the tasks extension.")
    public Task getTask(
            @JsonRpcParam(required = true, documentation = "The task identifier.") final String taskId,
            final Request httpRequest) {
        return tasks.get(taskId)
                .orElseThrow(() ->
                        new JsonRpcException(-32602, "Unknown task '" + taskId + "'", Map.of("taskId", taskId), null));
    }

    @JsonRpc(
            value = "tasks/update",
            documentation = "Answers the input requests a task is blocked on, acknowledged with an empty result.")
    public Map<String, String> updateTask(
            @JsonRpcParam(required = true, documentation = "The task identifier.") final String taskId,
            @JsonRpcParam(documentation = "The answers to the task outstanding input requests.")
                    final Map<String, Object> inputResponses,
            final Request httpRequest) {
        if (tasks.update(
                        taskId,
                        it -> new Task(
                                it.taskId(),
                                TaskStatus.working,
                                null,
                                null,
                                it.result(),
                                it.error(),
                                it.createdAt(),
                                it.lastUpdatedAt(),
                                it.ttlMs(),
                                it.pollIntervalMs(),
                                it.parentTaskId()))
                .isEmpty()) {
            throw new JsonRpcException(-32602, "Unknown task '" + taskId + "'", Map.of("taskId", taskId), null);
        }
        return Map.of();
    }

    @JsonRpc(
            value = "tasks/cancel",
            documentation = "Signals the cancellation of a long-running task; cancellation is cooperative.")
    public Map<String, String> cancelTask(
            @JsonRpcParam(required = true, documentation = "The task identifier.") final String taskId,
            final Request httpRequest) {
        if (tasks.update(
                        taskId,
                        it -> new Task(
                                it.taskId(),
                                TaskStatus.cancelled,
                                null,
                                it.inputRequests(),
                                it.result(),
                                it.error(),
                                it.createdAt(),
                                it.lastUpdatedAt(),
                                it.ttlMs(),
                                it.pollIntervalMs(),
                                it.parentTaskId()))
                .isEmpty()) {
            throw new JsonRpcException(-32602, "Unknown task '" + taskId + "'", Map.of("taskId", taskId), null);
        }
        return Map.of();
    }

    private MCPResult onToolResult(final String name, final Object res, final Request httpRequest) {
        final var resultType = stateless(httpRequest) ? ResultType.complete : null;
        if (res == null) { // a void tool, it succeeded but has nothing to say
            return new MCPResult(null, resultType, false, List.of(), null, null, null, null, null, null, null, null);
        }
        if (!(res instanceof Response response)) { // unlikely, a bulk response for a single request
            throw new JsonRpcException(-32603, "Unexpected result calling '" + name + "'");
        }
        if (response.error() == null) {
            if (response.result() instanceof ToolResponse toolResponse) { // the tool took control of the content
                return MCPResult.complete(toolResponse);
            }
            return MCPResult.complete(response.result(), jsons.toString(response.result()), resultType);
        }
        if (response.error().code() == MCPProtocol.INPUT_REQUIRED) { // the tool asked for an input
            return inputRequiredResult(response.error(), httpRequest);
        }
        if (isProtocolError(response.error().code())) { // the call was wrong, this is not a tool failure
            throw toException(response.error());
        }
        // the specification wants tool failures to be reported in the result so the model can read and react to them
        return MCPResult.error(
                true,
                List.of(Content.text(response.error().message())),
                Map.of(
                        "code",
                        response.error().code(),
                        "message",
                        String.valueOf(response.error().message())),
                stateless(httpRequest) ? ResultType.error : null);
    }

    @SuppressWarnings("unchecked")
    private MCPResult inputRequiredResult(final Response.ErrorResponse error, final Request httpRequest) {
        final var data = error.data() instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.<String, Object>of();
        final var inputRequests = new LinkedHashMap<String, InputRequest>();
        if (data.get("inputRequests") instanceof Map<?, ?> requests) {
            requests.forEach((key, value) -> inputRequests.put(String.valueOf(key), toInputRequest(value)));
        }
        filterByClientCapabilities(inputRequests, httpRequest);
        return MCPResult.inputRequired(inputRequests, requestStateCodec.encode(data.get("state")));
    }

    /**
     * A server MUST NOT include an {@code inputRequests} entry whose method needs a capability the client did not
     * declare ({@code 2026-07-28} MRTR): elicitation needs {@code elicitation}, sampling needs {@code sampling} and
     * roots need {@code roots}.
     */
    private void filterByClientCapabilities(final Map<String, InputRequest> inputRequests, final Request httpRequest) {
        if (inputRequests.isEmpty() || httpRequest == null) {
            return;
        }
        final var session = httpRequest.attribute(MCPSessions.REQUEST_ATTRIBUTE, MCPSession.class);
        if (session == null || session.capabilities() == null) {
            return; // we do not know what the client declared, keep everything
        }
        final var capabilities = session.capabilities();
        inputRequests.entrySet().removeIf(entry -> switch (entry.getValue().method()) {
            case "elicitation/create" -> capabilities.elicitation() == null;
            case "sampling/createMessage" -> capabilities.sampling() == null;
            case "roots/list" -> capabilities.roots() == null;
            default -> false;
        });
    }

    private InputRequest toInputRequest(final Object value) {
        if (value instanceof InputRequest inputRequest) {
            return inputRequest;
        }
        return jsons.fromString(InputRequest.class, jsons.toString(value));
    }

    private Map<String, Object> jsonRpc(final String method, final Object params) {
        // no id: this is a nested invocation, the enclosing MCP request carries the client id
        return Map.of("jsonrpc", "2.0", "method", method, "params", params == null ? Map.of() : params);
    }

    /**
     * @param code a JSON-RPC error code.
     * @return {@code true} for the codes meaning the call itself was invalid - the JSON-RPC reserved codes and the
     * MCP protocol errors ({@code HeaderMismatch}, {@code MissingRequiredClientCapability},
     * {@code UnsupportedProtocolVersion}) - and not that the invoked logic failed: those must surface as a JSON-RPC
     * error (an HTTP {@code 400} on the stateless transport), not as an {@code isError} tool result.
     */
    private boolean isProtocolError(final int code) {
        return code == -32700
                || code <= -32600 && code >= -32603
                || code == MCPProtocol.HEADER_MISMATCH
                || code == MCPProtocol.MISSING_CLIENT_CAPABILITY
                || code == MCPProtocol.UNSUPPORTED_PROTOCOL_VERSION;
    }

    private JsonRpcException toException(final Response.ErrorResponse error) {
        if (error == null) { // unlikely
            return new JsonRpcException(-32603, "Unexpected result");
        }
        return new JsonRpcException(error.code(), error.message(), error.data(), null);
    }

    /**
     * Applies the {@code _meta} envelope of a modern request to its session - and exposes the decoded request state
     * and the client input responses to the invoked methods.
     */
    private void applyRequestMetadata(final MCPSession session, final Object metadata, final Request request) {
        applyRequestMetadata(session, metadata, null, null, request);
    }

    /**
     * Applies the {@code _meta} envelope - and the SDK-style top-level {@code inputResponses}/{@code requestState} -
     * of a modern request to its session and to the request attributes the invoked methods read.
     */
    private void applyRequestMetadata(
            final MCPSession session,
            final Object metadata,
            final Map<String, Object> topLevelInputResponses,
            final String topLevelRequestState,
            final Request request) {
        final var meta = requestMetadata(metadata);
        // the SDK sends the answers and the state as top-level params, the spec puts them in _meta: both are honored,
        // the top-level one wins
        final var stateToken =
                topLevelRequestState != null ? topLevelRequestState : (meta == null ? null : meta.requestState());
        final Object state;
        if (stateToken == null || stateToken.isEmpty()) {
            state = null;
        } else {
            try {
                state = requestStateCodec.decode(stateToken);
            } catch (final RuntimeException re) {
                // the specification requires rejecting, not trusting, a requestState which fails verification
                throw new JsonRpcException(-32602, "Invalid request state", Map.of("requestState", stateToken), re);
            }
        }
        final var responses =
                topLevelInputResponses != null ? topLevelInputResponses : (meta == null ? null : meta.inputResponses());
        if (session != null) {
            session.applyRequestMeta(
                    meta == null ? null : meta.clientCapabilities(),
                    meta == null ? null : meta.clientInfo(),
                    meta == null ? null : meta.logLevel(),
                    meta == null ? null : meta.progressToken(),
                    responses,
                    state);
        }
        if (request != null) {
            request.setAttribute(MCPProtocol.REQUEST_STATE_ATTRIBUTE, state);
            request.setAttribute(MCPProtocol.INPUT_RESPONSES_ATTRIBUTE, responses);
        }
    }

    /**
     * Normalizes the {@code _meta} envelope bound to the {@code _meta} JSON-RPC parameter: a modern client sends the
     * {@code io.modelcontextprotocol/*} namespaced keys, a legacy one - {@code 2025-03-26} to {@code 2025-11-25} - the
     * bare keys. {@link MCPRequestMetadata} reads only the namespaced ones, so the bare {@code progressToken} (the
     * only optional field the conformance suite exercises on both eras) is folded back in.
     *
     * @param raw the value the JSON-RPC binding produced for {@code _meta}: a {@link MCPRequestMetadata} when it was
     *            bound from a namespaced envelope, else a {@link Map}.
     * @return the normalized envelope, {@code null} when there was none.
     */
    private MCPRequestMetadata requestMetadata(final Object raw) {
        if (raw instanceof MCPRequestMetadata metadata) {
            return metadata;
        }
        if (!(raw instanceof Map<?, ?> map) || map.isEmpty()) {
            return null;
        }
        @SuppressWarnings("unchecked")
        final var named = jsons.fromString(MCPRequestMetadata.class, jsons.toString((Map<String, Object>) map));
        if (named.progressToken() != null) { // a namespaced (modern) token was present
            return named;
        }
        // legacy clients only know the bare _meta keys
        return new MCPRequestMetadata(
                named.protocolVersion(),
                named.clientCapabilities(),
                named.clientInfo(),
                named.logLevel(),
                map.get("progressToken"),
                named.subscriptionId(),
                named.requestState(),
                named.inputResponses());
    }

    /**
     * @param httpRequest the current request, may be {@code null} in unit tests.
     * @return {@code true} when the request is handled over the stateless protocol version.
     */
    private boolean stateless(final Request httpRequest) {
        if (httpRequest == null) {
            return false;
        }
        final var session = httpRequest.attribute(MCPSessions.REQUEST_ATTRIBUTE, MCPSession.class);
        return session != null && session.isStateless();
    }

    /**
     * @param httpRequest the current request, may be {@code null} in unit tests.
     * @return the protocol version the request was opened with - the {@code MCP-Protocol-Version} header or the
     * negotiated session version -, {@code null} when it cannot be determined.
     */
    private String requestProtocolVersion(final Request httpRequest) {
        if (httpRequest == null) {
            return null;
        }
        final var header = httpRequest.header(MCPProtocol.PROTOCOL_VERSION_HEADER);
        if (header != null) {
            return header;
        }
        final var session = httpRequest.attribute(MCPSessions.REQUEST_ATTRIBUTE, MCPSession.class);
        return session == null ? null : session.protocolVersion();
    }

    /**
     * Enriches a legacy listing result with the stateless protocol fields - {@code resultType}, {@code ttlMs} and
     * {@code cacheScope} - when the caller is on the stateless protocol.
     *
     * @param httpRequest the current request, used to detect the protocol version.
     * @param response    the legacy listing result.
     * @return the response to send back, enriched when needed.
     */
    private <T> T statelessResponse(final Request httpRequest, final T response) {
        if (!stateless(httpRequest)) {
            return response;
        }
        return switch (response) {
            case ListToolsResponse it ->
                (T) new ListToolsResponse(
                        it.tools(), it.nextCursor(), ResultType.complete, cacheTtlMs, cacheScope, it.metadata());
            case ListPromptsResponse it ->
                (T) new ListPromptsResponse(
                        it.prompts(), it.nextCursor(), ResultType.complete, cacheTtlMs, cacheScope, it.metadata());
            case ListResourcesResponse it ->
                (T) new ListResourcesResponse(
                        it.resources(), it.nextCursor(), ResultType.complete, cacheTtlMs, cacheScope, it.metadata());
            case ListResourceTemplatesResponse it ->
                (T) new ListResourceTemplatesResponse(
                        it.resourceTemplates(),
                        it.nextCursor(),
                        ResultType.complete,
                        cacheTtlMs,
                        cacheScope,
                        it.metadata());
            default -> response;
        };
    }

    /**
     * Adds the {@code io.modelcontextprotocol/serverInfo} to the metadata of a resource when the caller is on the
     * stateless protocol.
     *
     * @param metadata    the resource metadata, may be {@code null}.
     * @param httpRequest the current request, used to detect the protocol version.
     * @return the metadata to send back.
     */
    private Metadata withServerInfo(final Metadata metadata, final Request httpRequest) {
        if (!stateless(httpRequest)) {
            return metadata;
        }
        final var merged = new LinkedHashMap<>(metadata == null ? Map.of() : metadata.others());
        merged.put(MCPProtocol.SERVER_INFO_META, initializeResponse.serverInfo());
        return new Metadata(
                metadata == null ? null : metadata.name(), metadata == null ? null : metadata.title(), merged);
    }
}
