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
import io.yupiik.fusion.mcp.configuration.MCPConfiguration;
import io.yupiik.fusion.mcp.model.Capabilities;
import io.yupiik.fusion.mcp.model.ClientInfo;
import io.yupiik.fusion.mcp.model.CompleteResult;
import io.yupiik.fusion.mcp.model.CompletionArgument;
import io.yupiik.fusion.mcp.model.CompletionContext;
import io.yupiik.fusion.mcp.model.CompletionRef;
import io.yupiik.fusion.mcp.model.Content;
import io.yupiik.fusion.mcp.model.InitializeResponse;
import io.yupiik.fusion.mcp.model.ListPromptsResponse;
import io.yupiik.fusion.mcp.model.ListResourceTemplatesResponse;
import io.yupiik.fusion.mcp.model.ListResourcesResponse;
import io.yupiik.fusion.mcp.model.ListToolsResponse;
import io.yupiik.fusion.mcp.model.LoggingLevel;
import io.yupiik.fusion.mcp.model.Metadata;
import io.yupiik.fusion.mcp.model.PromptResponse;
import io.yupiik.fusion.mcp.model.ReadResourceResponse;
import io.yupiik.fusion.mcp.model.ToolResponse;
import io.yupiik.fusion.mcp.service.DescriptorService;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.logging.Logger;
import java.util.stream.Stream;

import static java.util.logging.Level.FINE;

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

    // for subclassing proxies
    protected MCPJSONRPCProtocol() {
        this(null, null, null, null, null, null, null);
    }

    public MCPJSONRPCProtocol(final DescriptorService descriptors,
                              final JsonMapper jsons,
                              final JsonRpcHandler handler,
                              final MCPSessions sessions,
                              final MCPConfiguration configuration,
                              final List<MCPResources> resources,
                              final List<MCPCompletions> completions) {
        this.descriptors = descriptors;
        this.handler = handler;
        this.jsons = jsons;
        this.sessions = sessions;
        this.resources = resources == null ? List.of() : resources;
        this.completions = completions == null ? List.of() : completions;
        this.initializeResponse = descriptors == null || configuration == null ?
                null :
                new InitializeResponse(
                        MCPProtocol.LATEST_VERSION,
                        new InitializeResponse.Capabilities(
                                // logging is always there, any bean can push records with MCPNotifier
                                Map.of(),
                                descriptors.prompts().prompts().isEmpty() ? null : new InitializeResponse.Prompts(false),
                                // resources are provided at runtime so both subscriptions and list changes are supported
                                this.resources.isEmpty() ? null : new InitializeResponse.Resources(true, true),
                                descriptors.tools().tools().isEmpty() ? null : new InitializeResponse.Tools(false),
                                this.completions.isEmpty() ? null : Map.of(),
                                null),
                        new InitializeResponse.ServerInfo(configuration.name(), configuration.title(), configuration.version()),
                        configuration.instructions());
    }

    /**
     * See <a href="https://modelcontextprotocol.io/specification/2025-06-18/basic/lifecycle">lifecycle</a>.
     * <p>
     * The session is created by the transport - {@link MCPEndpoint} - which also returns its identifier in the
     * {@code Mcp-Session-Id} response header.
     */
    @JsonRpc(value = "initialize", documentation = "Negotiates the protocol version and returns the server capabilities.")
    public InitializeResponse initialize(
            @JsonRpcParam(required = true, documentation = "The protocol version the client wants to use.") final String protocolVersion,
            @JsonRpcParam(documentation = "What the client supports: roots, sampling, elicitation.") final Capabilities capabilities,
            @JsonRpcParam(documentation = "Which client is connecting.") final ClientInfo clientInfo,
            final Request request
    ) {
        // the specification requires to answer a version we support - and not an error - the client then decides if
        // it can go on or not
        final var negotiated = MCPProtocol.negotiate(protocolVersion);
        sessions.of(request).onInitialize(negotiated, capabilities, clientInfo);
        if (negotiated.equals(initializeResponse.protocolVersion())) {
            return initializeResponse;
        }
        return new InitializeResponse(
                negotiated, initializeResponse.capabilities(), initializeResponse.serverInfo(), initializeResponse.instructions());
    }

    @JsonRpc(value = "notifications/initialized", documentation = "The client is ready, the session can be used.")
    public void onInitialized(@JsonRpcParam("_meta") final Metadata metadata, final Request request) {
        sessions.of(request).onInitialized();
    }

    @JsonRpc(value = "notifications/cancelled", documentation = "The client gave up on a request it sent.")
    public void onCancelled(@JsonRpcParam(documentation = "Why the request was cancelled.") final String reason,
                            @JsonRpcParam(documentation = "The cancelled request id, a string or a number.") final Object requestId) {
        // the request is already running in the JSON-RPC stack so there is nothing to interrupt - and the SSE
        // channel must stay open, only the request was cancelled, not the session
        logger.log(FINE, () -> "Client cancelled request '" + requestId + "'" + (reason == null ? "" : ": " + reason));
    }

    @JsonRpc(value = "ping", documentation = "Liveness check, see https://modelcontextprotocol.io/specification/2025-06-18/basic/utilities/ping.")
    public Map<String, String> ping(@JsonRpcParam("_meta") final Metadata metadata) {
        return Map.of();
    }

    @JsonRpc(value = "notifications/progress", documentation = "Progress of a request the client sent to the server.")
    public void onProgress(
            @JsonRpcParam final String message,
            @JsonRpcParam final Double progress,
            @JsonRpcParam final Object progressToken, // a string or a number
            @JsonRpcParam final Double total
    ) {
        // no-op: this server does not send the client requests it could report progress of
    }

    @JsonRpc(value = "notifications/roots/list_changed", documentation = "The client filesystem roots changed.")
    public void onRootsListChanged(@JsonRpcParam("_meta") final Metadata metadata) {
        // no-op: roots are always fetched on demand with MCPSession#listRoots so there is no cache to invalidate
    }

    @JsonRpc(value = "logging/setLevel", documentation = "Sets the minimum severity of the log records sent to the client.")
    public void setLoggingLevel(@JsonRpcParam(required = true, documentation = "A syslog level name: debug, info, notice, warning, error, critical, alert or emergency.") final String level,
                                final Request request) {
        final LoggingLevel parsed;
        try {
            parsed = LoggingLevel.valueOf(level);
        } catch (final IllegalArgumentException iae) {
            throw new JsonRpcException(-32602, "Invalid logging level '" + level + "'", Map.of(
                    "supported", Stream.of(LoggingLevel.values()).map(Enum::name).sorted().toList()), null);
        }
        sessions.of(request).setLoggingLevel(parsed);
    }

    @JsonRpc(value = "tools/list", documentation = "Lists the tools the model can call.")
    public ListToolsResponse listTools(@JsonRpcParam(documentation = "Pagination cursor, unused: all the tools are returned at once.") final String cursor) {
        return descriptors.tools();
    }

    @JsonRpc(value = "tools/call", documentation = "Calls a tool.")
    public CompletionStage<ToolResponse> callTool(@JsonRpcParam(required = true, documentation = "The tool name, as returned by tools/list.") final String name,
                                                 @JsonRpcParam(documentation = "The tool arguments, they must match its inputSchema.") final Object arguments,
                                                 final Request httpRequest) {
        // only the methods flagged with @MCPTool are callable, else every JSON-RPC method of the application - and of
        // the MCP protocol itself - would be reachable through tools/call
        if (!descriptors.isTool(name)) {
            throw new JsonRpcException(-32602, "Unknown tool '" + name + "'", Map.of("name", name), null);
        }
        return handler
                .execute(jsonRpc(name, arguments), httpRequest)
                .thenApply(res -> onToolResult(name, res));
    }

    @JsonRpc(value = "prompts/list", documentation = "Lists the prompt templates the user can pick.")
    public ListPromptsResponse listPrompts(@JsonRpcParam(documentation = "Pagination cursor, unused: all the prompts are returned at once.") final String cursor) {
        return descriptors.prompts();
    }

    @JsonRpc(value = "prompts/get", documentation = "Expands a prompt template.")
    public CompletionStage<PromptResponse> callPrompt(@JsonRpcParam(required = true, documentation = "The prompt name, as returned by prompts/list.") final String name,
                                                     @JsonRpcParam(documentation = "The prompt arguments, all strings.") final Map<String, Object> arguments,
                                                     final Request httpRequest) {
        if (!descriptors.isPrompt(name)) {
            throw new JsonRpcException(-32602, "Unknown prompt '" + name + "'", Map.of("name", name), null);
        }
        return handler
                .execute(jsonRpc(name, arguments), httpRequest)
                .thenApply(res -> {
                    if (res instanceof Response response && response.result() instanceof PromptResponse promptResponse) {
                        return promptResponse;
                    }
                    throw toException(res instanceof Response response ? response.error() : null);
                });
    }

    @JsonRpc(value = "resources/list", documentation = "Lists the resources the client can read.")
    public ListResourcesResponse listResources(@JsonRpcParam(documentation = "Pagination cursor, unused: all the resources are returned at once.") final String cursor) {
        return new ListResourcesResponse(
                resources.stream()
                        .map(MCPResources::resources)
                        .flatMap(List::stream)
                        .distinct()
                        .toList(),
                null);
    }

    @JsonRpc(value = "resources/templates/list", documentation = "Lists the parameterized resources the client can read.")
    public ListResourceTemplatesResponse listResourceTemplates(@JsonRpcParam(documentation = "Pagination cursor, unused: all the templates are returned at once.") final String cursor) {
        return new ListResourceTemplatesResponse(
                resources.stream()
                        .map(MCPResources::resourceTemplates)
                        .flatMap(List::stream)
                        .distinct()
                        .toList(),
                null);
    }

    @JsonRpc(value = "resources/read", documentation = "Reads the contents of a resource.")
    public ReadResourceResponse readResource(@JsonRpcParam(required = true, documentation = "The resource uri.") final String uri) {
        return resources.stream()
                .map(it -> it.read(uri))
                .flatMap(Optional::stream)
                .findFirst()
                // -32002 is the code the specification reserves for a missing resource
                .orElseThrow(() -> new JsonRpcException(-32002, "Unknown resource '" + uri + "'", Map.of("uri", uri), null));
    }

    @JsonRpc(value = "resources/subscribe", documentation = "Asks to be notified when a resource changes.")
    public void subscribeResource(@JsonRpcParam(required = true, documentation = "The resource uri to watch.") final String uri,
                                 final Request request) {
        // no existence check: resources are dynamic, a client can legitimately watch a uri which does not exist yet
        sessions.of(request).subscribe(uri);
    }

    @JsonRpc(value = "resources/unsubscribe", documentation = "Stops watching a resource.")
    public void unsubscribeResource(@JsonRpcParam(required = true, documentation = "The resource uri to stop watching.") final String uri,
                                   final Request request) {
        sessions.of(request).unsubscribe(uri);
    }

    @JsonRpc(value = "completion/complete", documentation = "Suggests values for a prompt argument or a resource template variable.")
    public CompleteResult completion(@JsonRpcParam(required = true, documentation = "The argument being completed and its current value.") final CompletionArgument argument,
                                     @JsonRpcParam(documentation = "The arguments already resolved.") final CompletionContext context,
                                     @JsonRpcParam(required = true, documentation = "What is completed: a prompt or a resource template.") final CompletionRef ref) {
        return new CompleteResult(
                null,
                completions.stream()
                        .map(it -> it.complete(ref, argument, context))
                        .filter(Objects::nonNull)
                        .findFirst()
                        .orElseGet(() -> new CompleteResult.Completion(false, 0, List.of())));
    }

    private ToolResponse onToolResult(final String name, final Object res) {
        if (res == null) { // a void tool, it succeeded but has nothing to say
            return new ToolResponse(null, false, List.of(), null);
        }
        if (!(res instanceof Response response)) { // unlikely, a bulk response for a single request
            throw new JsonRpcException(-32603, "Unexpected result calling '" + name + "'");
        }
        if (response.error() == null) {
            if (response.result() instanceof ToolResponse toolResponse) { // the tool took control of the content
                return toolResponse;
            }
            return ToolResponse.structure(jsons, response.result());
        }
        if (isProtocolError(response.error().code())) { // the call was wrong, this is not a tool failure
            throw toException(response.error());
        }
        // the specification wants tool failures to be reported in the result so the model can read and react to them
        return new ToolResponse(
                null, true,
                List.of(Content.text(response.error().message())),
                Map.of("code", response.error().code(), "message", String.valueOf(response.error().message())));
    }

    private Map<String, Object> jsonRpc(final String method, final Object params) {
        // no id: this is a nested invocation, the enclosing MCP request carries the client id
        return Map.of(
                "jsonrpc", "2.0",
                "method", method,
                "params", params == null ? Map.of() : params);
    }

    /**
     * @param code a JSON-RPC error code.
     * @return {@code true} for the codes JSON-RPC reserves, i.e. the ones meaning the call itself was invalid and not
     * that the invoked logic failed.
     */
    private boolean isProtocolError(final int code) {
        return code == -32700 || (code <= -32600 && code >= -32603);
    }

    private JsonRpcException toException(final Response.ErrorResponse error) {
        if (error == null) { // unlikely
            return new JsonRpcException(-32603, "Unexpected result");
        }
        return new JsonRpcException(error.code(), error.message(), error.data(), null);
    }
}
