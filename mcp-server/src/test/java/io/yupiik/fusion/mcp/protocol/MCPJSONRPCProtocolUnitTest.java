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

import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.concurrent.CompletableFuture.failedFuture;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.framework.api.RuntimeContainer;
import io.yupiik.fusion.http.server.api.Request;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.jsonrpc.JsonRpcException;
import io.yupiik.fusion.jsonrpc.JsonRpcHandler;
import io.yupiik.fusion.jsonrpc.JsonRpcRegistry;
import io.yupiik.fusion.jsonrpc.impl.JsonRpcMethod;
import io.yupiik.fusion.mcp.api.MCPCompletions;
import io.yupiik.fusion.mcp.api.MCPResources;
import io.yupiik.fusion.mcp.api.MCPToolSchemas;
import io.yupiik.fusion.mcp.configuration.MCPConfiguration;
import io.yupiik.fusion.mcp.exception.InputRequiredException;
import io.yupiik.fusion.mcp.model.Capabilities;
import io.yupiik.fusion.mcp.model.ClientInfo;
import io.yupiik.fusion.mcp.model.CompleteResult;
import io.yupiik.fusion.mcp.model.CompletionArgument;
import io.yupiik.fusion.mcp.model.CompletionContext;
import io.yupiik.fusion.mcp.model.CompletionRef;
import io.yupiik.fusion.mcp.model.Content;
import io.yupiik.fusion.mcp.model.InputRequest;
import io.yupiik.fusion.mcp.model.JsonSchema;
import io.yupiik.fusion.mcp.model.LoggingLevel;
import io.yupiik.fusion.mcp.model.MCPRequestMetadata;
import io.yupiik.fusion.mcp.model.PromptResponse;
import io.yupiik.fusion.mcp.model.ReadResourceResponse;
import io.yupiik.fusion.mcp.model.Resource;
import io.yupiik.fusion.mcp.model.ResourceContents;
import io.yupiik.fusion.mcp.model.ResourceTemplate;
import io.yupiik.fusion.mcp.model.ResultType;
import io.yupiik.fusion.mcp.model.Role;
import io.yupiik.fusion.mcp.model.Task;
import io.yupiik.fusion.mcp.model.TaskStatus;
import io.yupiik.fusion.mcp.model.ToolResponse;
import io.yupiik.fusion.mcp.model.fusion.OpenRpc;
import io.yupiik.fusion.mcp.service.DescriptorService;
import io.yupiik.fusion.mcp.service.OpenRpcService;
import io.yupiik.fusion.mcp.spi.HmacRequestStateCodec;
import io.yupiik.fusion.mcp.spi.MCPRequestStateCodec;
import io.yupiik.fusion.mcp.spi.OpaqueRequestStateCodec;
import io.yupiik.fusion.mcp.test.Loggers;
import io.yupiik.fusion.mcp.test.StubJsonRpcMethod;
import io.yupiik.fusion.mcp.test.StubRequest;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The MCP methods called directly, i.e. with the tools, prompts, resources and completions this module has none of -
 * {@link io.yupiik.fusion.mcp.MCPJSONRPCProtocolTest} covers the deployed - and therefore empty - case over HTTP.
 * <p>
 * The JSON-RPC stack is the real one, only the invoked methods are stubbed, so the delegation {@code tools/call} and
 * {@code prompts/get} do is exercised end to end: result unwrapping, tool failures and protocol errors included.
 */
@FusionSupport
class MCPJSONRPCProtocolUnitTest {
    @Test
    void capabilitiesAdvertiseWhatIsDeployed(@Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        final var response = protocol(jsons, container)
                .tool("a/tool")
                .prompt("a/prompt")
                .resources(new StaticResources())
                .completions((ref, argument, context) -> null)
                .build()
                .protocol()
                .initialize(MCPProtocol.LATEST_VERSION, null, null, new StubRequest());

        final var capabilities = response.capabilities();
        assertEquals(Map.of(), capabilities.logging(), "logging is always there, any bean can push records");
        assertNotNull(capabilities.tools());
        assertFalse(capabilities.tools().listChanged());
        assertNotNull(capabilities.prompts());
        assertFalse(capabilities.prompts().listChanged());
        // resources are provided at runtime so both subscriptions and list changes are supported
        assertNotNull(capabilities.resources());
        assertTrue(capabilities.resources().subscribe());
        assertTrue(capabilities.resources().listChanged());
        // a completions implementation is deployed, so the completion sub-capabilities are advertised
        assertEquals(Map.of("completions", Map.of()), capabilities.completions());
    }

    @Test
    void capabilitiesAreAbsentWhenNothingIsDeployed(
            @Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        final var response = protocol(jsons, container)
                .build()
                .protocol()
                .initialize(MCPProtocol.LATEST_VERSION, null, null, new StubRequest());

        assertNull(response.capabilities().tools());
        assertNull(response.capabilities().prompts());
        assertNull(response.capabilities().resources());
        assertNull(response.capabilities().completions());
        assertEquals(Map.of(), response.capabilities().logging());
    }

    @Test
    void initializeStoresTheClientStateOnTheSession(
            @Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        final var setup = protocol(jsons, container).build();
        final var request = new StubRequest();
        final var capabilities = new Capabilities(null, Map.of(), Map.of(), Map.of(), null);
        final var clientInfo = new ClientInfo("a-client", "A client", "1.2.3");

        final var response = setup.protocol().initialize(MCPProtocol.LATEST_VERSION, capabilities, clientInfo, request);

        assertEquals(MCPProtocol.LATEST_VERSION, response.protocolVersion());
        final var session = setup.sessions().of(request);
        assertEquals(MCPProtocol.LATEST_VERSION, session.protocolVersion());
        assertSame(capabilities, session.capabilities());
        assertSame(clientInfo, session.clientInfo());
        assertFalse(session.isInitialized(), "only notifications/initialized flips it");

        setup.protocol().onInitialized(null, request);
        assertTrue(session.isInitialized());
    }

    @Test
    void initializeKeepsAnOlderNegotiatedVersion(
            @Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        final var setup = protocol(jsons, container).build();

        final var response = setup.protocol().initialize("2025-03-26", null, null, new StubRequest());

        // the capabilities and the server info are the ones of the single response computed at startup, only the
        // version differs
        assertEquals("2025-03-26", response.protocolVersion());
        assertEquals("fusion-mcp-server", response.serverInfo().name());
        assertEquals("Use the exposed tools to answer the user.", response.instructions());
    }

    @Test
    void initializeNegotiatesAnUnknownVersion(
            @Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        // the specification requires to answer a version the server supports and not an error
        final var response =
                protocol(jsons, container).build().protocol().initialize("1999-01-01", null, null, new StubRequest());

        assertEquals(MCPProtocol.LATEST_VERSION, response.protocolVersion());
    }

    @Test
    void theNoOpNotifications(@Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        final var setup = protocol(jsons, container).build();

        // nothing to assert but that they are accepted: there is no request to interrupt, no progress to report and
        // no root cache to invalidate
        setup.protocol().onCancelled("user gave up", 5);
        setup.protocol().onCancelled(null, "an-id");
        setup.protocol().onProgress("half way", 0.5d, "token", 1d);
        setup.protocol().onRootsListChanged(null);
        assertEquals(Map.of(), setup.protocol().ping(null, null));
    }

    @Test
    void setLoggingLevel(@Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        final var setup = protocol(jsons, container).build();
        final var request = new StubRequest();

        assertEquals(Map.of(), setup.protocol().setLoggingLevel("debug", request));
        assertEquals(LoggingLevel.debug, setup.sessions().of(request).loggingLevel());
    }

    @Test
    void setLoggingLevelRejectsAnUnknownName(@Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        final var setup = protocol(jsons, container).build();

        final var error =
                assertThrows(JsonRpcException.class, () -> setup.protocol().setLoggingLevel("oops", new StubRequest()));

        assertEquals(-32602, error.code());
        assertEquals("Invalid logging level 'oops'", error.getMessage());
        // the supported names are sent back so the client can fix its call
        assertEquals(
                List.of("alert", "critical", "debug", "emergency", "error", "info", "notice", "warning"),
                ((Map<?, ?>) error.data()).get("supported"));
    }

    @Test
    void listings(@Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        final var setup =
                protocol(jsons, container).tool("a/tool").prompt("a/prompt").build();

        assertEquals(
                List.of("a/tool"),
                setup.protocol().listTools(null, null).tools().stream()
                        .map(it -> it.name())
                        .toList());
        assertEquals(
                List.of("a/prompt"),
                setup.protocol().listPrompts("ignored-cursor", null).prompts().stream()
                        .map(it -> it.name())
                        .toList());
    }

    @Test
    void callToolWrapsThePlainResult(@Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        final var setup = protocol(jsons, container)
                .tool("a/tool", params -> completedFuture(Map.of("value", "hello")))
                .build();

        final var response = setup.protocol()
                .callTool("a/tool", Map.of(), null, new StubRequest())
                .toCompletableFuture()
                .join();

        assertFalse(response.isError());
        // the JSON is sent as text - for the models without structured output support - and as structuredContent
        assertEquals(1, response.content().size());
        assertEquals("{\"value\":\"hello\"}", response.content().getFirst().text());
        assertEquals(Map.of("value", "hello"), response.structuredContent());
    }

    @Test
    void callToolPassesTheArguments(@Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        final var setup = protocol(jsons, container)
                .tool("a/tool", params -> completedFuture(Map.of("received", String.valueOf(params))))
                .build();

        assertEquals(
                "{received={name=fusion}}",
                setup.protocol()
                        .callTool("a/tool", Map.of("name", "fusion"), null, new StubRequest())
                        .toCompletableFuture()
                        .join()
                        .structuredContent()
                        .toString());

        // no argument at all: the method gets an empty map and not null
        assertEquals(
                "{received={}}",
                setup.protocol()
                        .callTool("a/tool", null, null, new StubRequest())
                        .toCompletableFuture()
                        .join()
                        .structuredContent()
                        .toString());
    }

    @Test
    void aToolCanTakeControlOfTheContent(@Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        final var setup = protocol(jsons, container)
                .tool("a/tool", params -> completedFuture(ToolResponse.text("first", "second")))
                .build();

        final var response = setup.protocol()
                .callTool("a/tool", Map.of(), null, new StubRequest())
                .toCompletableFuture()
                .join();

        assertFalse(response.isError());
        assertEquals(
                List.of("first", "second"),
                response.content().stream().map(Content::text).toList());
        assertNull(response.structuredContent());
    }

    @Test
    void aVoidToolSucceedsWithNothingToSay(@Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        // Fusion sees a void method as a notification, so the JSON-RPC stack answers nothing at all
        final var setup = protocol(jsons, container)
                .method(StubJsonRpcMethod.notification("a/tool", DescriptorService.TOOL))
                .build();

        final var response = setup.protocol()
                .callTool("a/tool", Map.of(), null, new StubRequest())
                .toCompletableFuture()
                .join();

        assertFalse(response.isError());
        assertEquals(List.of(), response.content());
        assertNull(response.structuredContent());
    }

    @Test
    void aToolFailureIsReportedInTheResult(@Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        // the specification wants tool failures in the result so the model can read and react to them
        final var setup = protocol(jsons, container)
                .tool("a/tool", params -> {
                    throw new IllegalArgumentException("name should not be blank");
                })
                .build();

        final var response = setup.protocol()
                .callTool("a/tool", Map.of(), null, new StubRequest())
                .toCompletableFuture()
                .join();

        assertTrue(response.isError());
        assertEquals(
                List.of("name should not be blank"),
                response.content().stream().map(Content::text).toList());
        assertEquals(Map.of("code", -2, "message", "name should not be blank"), response.structuredContent());
    }

    @Test
    void anAsynchronousToolFailureIsReportedInTheResultToo(
            @Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        final var setup = protocol(jsons, container)
                .tool("a/tool", params -> failedFuture(new JsonRpcException(-32000, "the backend is down")))
                .build();

        final var response = setup.protocol()
                .callTool("a/tool", Map.of(), null, new StubRequest())
                .toCompletableFuture()
                .join();

        assertTrue(response.isError());
        assertEquals(Map.of("code", -32000, "message", "the backend is down"), response.structuredContent());
    }

    @Test
    void aValidBase64McpParamHeaderIsAccepted(
            @Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        // SEP-2243: =?base64?...?= is decoded and must match the body argument
        final var setup = protocol(jsons, container)
                .tool("a/tool", params -> completedFuture(ToolResponse.text("ok")))
                .schemas(headerToolSchemas())
                .build();
        final var request = statelessRequest(
                Map.of(
                        "Mcp-Param-message",
                        "=?base64?" + java.util.Base64.getEncoder().encodeToString("Hello".getBytes()) + "?="),
                setup);

        final var response = setup.protocol()
                .callTool("a/tool", Map.of("message", "Hello"), null, request)
                .toCompletableFuture()
                .join();

        assertFalse(response.isError());
    }

    @Test
    void anInvalidBase64PaddingMcpParamHeaderIsA32020(
            @Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        // SEP-2243: "SGVsbG8" misses the = padding of base64("Hello") so it must be rejected
        final var setup = protocol(jsons, container)
                .tool("a/tool", params -> completedFuture(ToolResponse.text("ok")))
                .schemas(headerToolSchemas())
                .build();
        final var request = statelessRequest(Map.of("Mcp-Param-message", "=?base64?SGVsbG8?="), setup);

        final var error = assertThrows(
                JsonRpcException.class,
                () -> setup.protocol().callTool("a/tool", Map.of("message", "Hello"), null, request));

        assertEquals(MCPProtocol.HEADER_MISMATCH, error.code());
    }

    @Test
    void anInvalidBase64CharMcpParamHeaderIsA32020(
            @Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        // SEP-2243: '!' is not in the base64 alphabet so it must be rejected
        final var setup = protocol(jsons, container)
                .tool("a/tool", params -> completedFuture(ToolResponse.text("ok")))
                .schemas(headerToolSchemas())
                .build();
        final var request = statelessRequest(Map.of("Mcp-Param-message", "=?base64?SGV!?="), setup);

        final var error = assertThrows(
                JsonRpcException.class,
                () -> setup.protocol().callTool("a/tool", Map.of("message", "Hello"), null, request));

        assertEquals(MCPProtocol.HEADER_MISMATCH, error.code());
    }

    @Test
    void aMissingMcpParamHeaderIsA32020(@Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        // SEP-2243: the argument is in the body but its Mcp-Param-* header is absent, the request must be rejected
        final var setup = protocol(jsons, container)
                .tool("a/tool", params -> completedFuture(ToolResponse.text("ok")))
                .schemas(headerToolSchemas())
                .build();
        final var request = statelessRequest(Map.of(), setup);

        final var error = assertThrows(
                JsonRpcException.class,
                () -> setup.protocol().callTool("a/tool", Map.of("message", "test-value"), null, request));

        assertEquals(MCPProtocol.HEADER_MISMATCH, error.code());
    }

    @Test
    void aMismatchingMcpParamHeaderIsA32020(@Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        // SEP-2243: a decoded value which does not match the body argument is a mismatch
        final var setup = protocol(jsons, container)
                .tool("a/tool", params -> completedFuture(ToolResponse.text("ok")))
                .schemas(headerToolSchemas())
                .build();
        final var request = statelessRequest(
                Map.of(
                        "Mcp-Param-message",
                        "=?base64?" + java.util.Base64.getEncoder().encodeToString("Else".getBytes()) + "?="),
                setup);

        final var error = assertThrows(
                JsonRpcException.class,
                () -> setup.protocol().callTool("a/tool", Map.of("message", "Hello"), null, request));

        assertEquals(MCPProtocol.HEADER_MISMATCH, error.code());
    }

    @Test
    void anInvalidBase64WithPaddingInTheMiddleIsA32020(
            @Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        // SEP-2243: '=' is only allowed as trailing padding, putting one in the middle is invalid base64
        final var setup = protocol(jsons, container)
                .tool("a/tool", params -> completedFuture(ToolResponse.text("ok")))
                .schemas(headerToolSchemas())
                .build();
        final var request = statelessRequest(Map.of("Mcp-Param-message", "=?base64?SGVs=G8=?="), setup);

        final var error = assertThrows(
                JsonRpcException.class,
                () -> setup.protocol().callTool("a/tool", Map.of("message", "Hello"), null, request));

        assertEquals(MCPProtocol.HEADER_MISMATCH, error.code());
        assertTrue(error.getMessage().contains("Invalid base64"), error.getMessage());
    }

    @Test
    void aBase64HeaderWithTheUrlAlphabetCharactersIsDecoded(
            @Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        // '+' and '/' are part of the base64 alphabet, they must not be rejected as invalid characters: the value
        // decodes and then mismatches the body argument
        final var setup = protocol(jsons, container)
                .tool("a/tool", params -> completedFuture(ToolResponse.text("ok")))
                .schemas(headerToolSchemas())
                .build();
        final var request = statelessRequest(Map.of("Mcp-Param-message", "=?base64?SG+/bG8=?="), setup);

        final var error = assertThrows(
                JsonRpcException.class,
                () -> setup.protocol().callTool("a/tool", Map.of("message", "Hello"), null, request));

        assertEquals(MCPProtocol.HEADER_MISMATCH, error.code());
        assertTrue(error.getMessage().contains("does not match"), error.getMessage());
    }

    @Test
    void aToolCallWithNoHttpRequestDoesNotRequireHeaders(
            @Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        // the header validations are transport concerns, a null request - the tool calling another tool - bypasses them
        final var setup = protocol(jsons, container)
                .tool("a/tool", params -> completedFuture(ToolResponse.text("ok")))
                .schemas(headerToolSchemas())
                .build();

        final var response = setup.protocol()
                .callTool("a/tool", Map.of("message", "test"), null, null)
                .toCompletableFuture()
                .join();

        assertFalse(response.isError());
    }

    @Test
    void aLiteralMcpParamHeaderIsAccepted(@Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        // SEP-2243: a value without the =?base64? prefix or the ?= suffix is a literal, not base64
        final var setup = protocol(jsons, container)
                .tool("a/tool", params -> completedFuture(ToolResponse.text("ok")))
                .schemas(headerToolSchemas())
                .build();
        for (final var value : List.of("SGVsbG8=", "=?base64?SGVsbG8=", "plain")) {
            final var request = statelessRequest(Map.of("Mcp-Param-message", value), setup);
            final var response = setup.protocol()
                    .callTool("a/tool", Map.of("message", value), null, request)
                    .toCompletableFuture()
                    .join();
            assertFalse(response.isError(), () -> "value " + value);
        }
    }

    @Test
    void aLegacyRequestDoesNotRequireTheMcpParamHeaders(
            @Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        // the Mcp-Param-* headers belong to the modern stateless transport, a legacy request is not enforced
        final var setup = protocol(jsons, container)
                .tool("a/tool", params -> completedFuture(ToolResponse.text("ok")))
                .schemas(headerToolSchemas())
                .build();
        final var request = new StubRequest(); // no header, no stateless session

        final var response = setup.protocol()
                .callTool("a/tool", Map.of("message", "test-value"), null, request)
                .toCompletableFuture()
                .join();

        assertFalse(response.isError());
    }

    private MCPToolSchemas headerToolSchemas() {
        return new MCPToolSchemas() {
            @Override
            public Map<String, JsonSchema> toolSchemas() {
                return Map.of(
                        "a/tool",
                        JsonSchema.object(
                                "A tool receiving its message through an Mcp-Param-* header.",
                                Map.of(
                                        "message",
                                        JsonSchema.string("The message to echo.")
                                                .withHeader("message")),
                                false,
                                List.of("message")));
            }
        };
    }

    private StubRequest statelessRequest(final Map<String, String> headers, final Built setup) {
        final var request = new StubRequest(headers);
        request.setAttribute(MCPSessions.REQUEST_ATTRIBUTE, setup.sessions().ephemeral(true));
        return request;
    }

    @Test
    void aProtocolErrorIsNotAToolFailure(@Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        // the reserved codes mean the call itself was wrong - bad arguments, a capability the client did not declare,
        // a mismatched header - so they must surface as a JSON-RPC error and not as a result the model would try to
        // make sense of
        for (final int code : new int[] {
            -32700,
            -32600,
            -32601,
            -32602,
            -32603,
            MCPProtocol.HEADER_MISMATCH,
            MCPProtocol.MISSING_CLIENT_CAPABILITY,
            MCPProtocol.UNSUPPORTED_PROTOCOL_VERSION
        }) {
            final var setup = protocol(jsons, container)
                    .tool("a/tool", params -> failedFuture(new JsonRpcException(code, "invalid call")))
                    .build();

            final var error = assertThrows(
                    java.util.concurrent.CompletionException.class,
                    () -> setup.protocol()
                            .callTool("a/tool", Map.of(), null, new StubRequest())
                            .toCompletableFuture()
                            .join());

            assertInstanceOfJsonRpc(error.getCause(), code, "invalid call");
        }
    }

    @Test
    void aMissingClientCapabilityIsA32021NotAToolFailure(
            @Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        final var setup = protocol(jsons, container)
                .tool(
                        "a/tool",
                        params -> failedFuture(new JsonRpcException(
                                MCPProtocol.MISSING_CLIENT_CAPABILITY,
                                "Missing required client capability 'sampling'",
                                Map.of("requiredCapabilities", Map.of("sampling", Map.of())),
                                null)))
                .build();

        final var error = assertThrows(
                java.util.concurrent.CompletionException.class,
                () -> setup.protocol()
                        .callTool("a/tool", Map.of(), null, new StubRequest())
                        .toCompletableFuture()
                        .join());

        final var cause = assertInstanceOf(JsonRpcException.class, error.getCause());
        assertEquals(MCPProtocol.MISSING_CLIENT_CAPABILITY, cause.code());
        assertEquals("Missing required client capability 'sampling'", cause.getMessage());
        assertEquals(Map.of("requiredCapabilities", Map.of("sampling", Map.of())), cause.data());
    }

    @Test
    void aCodeOutsideTheProtocolWindowStaysAToolFailure(
            @Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        // -32604 and -32599 are just below/above the reserved -32600..-32603 window, and the MCP codes -32023/-32024
        // sit just outside the -32020..-32022 protocol window
        for (final int code : new int[] {-32604, -32599, -32023, -32024, -1}) {
            final var setup = protocol(jsons, container)
                    .tool("a/tool", params -> failedFuture(new JsonRpcException(code, "business failure")))
                    .build();

            assertTrue(
                    setup.protocol()
                            .callTool("a/tool", Map.of(), null, new StubRequest())
                            .toCompletableFuture()
                            .join()
                            .isError(),
                    () -> "code " + code);
        }
    }

    @Test
    void callUnknownTool(@Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        // a JSON-RPC method which is not flagged with @MCPTool must not be reachable through tools/call, else every
        // method of the application - and of the MCP protocol itself - would be
        final var setup = protocol(jsons, container)
                .method(StubJsonRpcMethod.plain("a/plain"))
                .build();
        final var request = new StubRequest();

        final var error = assertThrows(
                JsonRpcException.class, () -> setup.protocol().callTool("a/plain", Map.of(), null, request));

        assertEquals(-32602, error.code());
        assertEquals("Unknown tool 'a/plain'", error.getMessage());
        assertEquals(Map.of("name", "a/plain"), error.data());
    }

    @Test
    void anUnexpectedResultShapeIsAnInternalError(
            @Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        // a bulk response for a single request: it cannot happen with the real stack, but the guard must hold
        final var setup = protocol(jsons, container)
                .tool("a/tool")
                .handler((delegate, registry) -> new JsonRpcHandler(container, jsons, registry) {
                    @Override
                    public CompletionStage<?> execute(final Object request, final Request httpRequest) {
                        return completedFuture(List.of("not a response"));
                    }
                })
                .build();

        final var error = assertThrows(
                java.util.concurrent.CompletionException.class,
                () -> setup.protocol()
                        .callTool("a/tool", Map.of(), null, new StubRequest())
                        .toCompletableFuture()
                        .join());

        assertInstanceOfJsonRpc(error.getCause(), -32603, "Unexpected result calling 'a/tool'");
    }

    @Test
    void callPrompt(@Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        final var expected = new PromptResponse(
                null, "A description", List.of(new PromptResponse.Message(Role.user, Content.text("hello"))), null);
        final var setup = protocol(jsons, container)
                .prompt("a/prompt", params -> completedFuture(expected))
                .build();

        final var response = setup.protocol()
                .callPrompt("a/prompt", Map.of("code", "fusion"), null, new StubRequest())
                .toCompletableFuture()
                .join();

        assertSame(expected.resultType(), response.resultType());
        assertSame(expected.description(), response.description());
        assertSame(expected.messages(), response.messages());
    }

    @Test
    void callUnknownPrompt(@Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        final var setup = protocol(jsons, container).tool("a/tool").build();
        final var request = new StubRequest();

        // a tool is not a prompt, prompts/get must not reach it
        final var error = assertThrows(
                JsonRpcException.class, () -> setup.protocol().callPrompt("a/tool", Map.of(), null, request));

        assertEquals(-32602, error.code());
        assertEquals("Unknown prompt 'a/tool'", error.getMessage());
        assertEquals(Map.of("name", "a/tool"), error.data());
    }

    @Test
    void aFailingPromptSurfacesTheError(@Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        // unlike a tool, a prompt failure is a plain JSON-RPC error: it is the user - not the model - which picks a
        // prompt, so there is nothing to react to
        final var setup = protocol(jsons, container)
                .prompt(
                        "a/prompt",
                        params -> failedFuture(new JsonRpcException(-32001, "nope", Map.of("k", "v"), null)))
                .build();

        final var error = assertThrows(
                java.util.concurrent.CompletionException.class,
                () -> setup.protocol()
                        .callPrompt("a/prompt", Map.of(), null, new StubRequest())
                        .toCompletableFuture()
                        .join());

        final var jsonRpc = assertInstanceOfJsonRpc(error.getCause(), -32001, "nope");
        assertEquals(Map.of("k", "v"), jsonRpc.data());
    }

    @Test
    void aPromptReturningSomethingElseIsAnInternalError(
            @Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        final var setup = protocol(jsons, container)
                .prompt("a/prompt", params -> completedFuture("not a prompt response"))
                .build();

        final var error = assertThrows(
                java.util.concurrent.CompletionException.class,
                () -> setup.protocol()
                        .callPrompt("a/prompt", Map.of(), null, new StubRequest())
                        .toCompletableFuture()
                        .join());

        assertInstanceOfJsonRpc(error.getCause(), -32603, "Unexpected result");
    }

    @Test
    void resourceListingsMergeTheProvidersAndDeduplicate(
            @Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        final var setup = protocol(jsons, container)
                // the same resource twice, plus a provider declaring none at all
                .resources(new StaticResources(), new StaticResources(), new MCPResources() {})
                .build();

        assertEquals(
                List.of("demo://greeting"),
                setup.protocol().listResources(null, null).resources().stream()
                        .map(Resource::uri)
                        .toList());
        assertEquals(
                List.of("demo://echo/{message}"),
                setup.protocol().listResourceTemplates(null, null).resourceTemplates().stream()
                        .map(ResourceTemplate::uriTemplate)
                        .toList());
        assertNull(setup.protocol().listResources(null, null).nextCursor());
        assertNull(setup.protocol().listResourceTemplates(null, null).nextCursor());
    }

    @Test
    void readResourceStopsOnTheFirstProviderKnowingTheUri(
            @Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        final var setup = protocol(jsons, container)
                // the first one does not know it, the second does
                .resources(new MCPResources() {}, new StaticResources())
                .build();

        final var response = setup.protocol().readResource("demo://greeting", null, null);

        assertEquals(1, response.contents().size());
        assertEquals("hello fusion!", response.contents().getFirst().text());
    }

    @Test
    void readAnUnknownResource(@Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        final var setup =
                protocol(jsons, container).resources(new StaticResources()).build();

        final var error =
                assertThrows(JsonRpcException.class, () -> setup.protocol().readResource("demo://nope", null, null));

        // -32002 is the code the specification reserves for a missing resource
        assertEquals(-32002, error.code());
        assertEquals("Unknown resource 'demo://nope'", error.getMessage());
        assertEquals(Map.of("uri", "demo://nope"), error.data());
    }

    @Test
    void subscriptions(@Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        final var setup =
                protocol(jsons, container).resources(new StaticResources()).build();
        final var request = new StubRequest();
        final var session = setup.sessions().of(request);

        // no existence check: a client can legitimately watch a uri which does not exist yet
        assertEquals(Map.of(), setup.protocol().subscribeResource("demo://not-yet", request));
        assertTrue(session.isSubscribedTo("demo://not-yet"));

        assertEquals(Map.of(), setup.protocol().unsubscribeResource("demo://not-yet", request));
        assertFalse(session.isSubscribedTo("demo://not-yet"));
    }

    @Test
    void completionVisitsTheProvidersUntilOneAnswers(
            @Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        final var setup = protocol(jsons, container)
                .completions(
                        (ref, argument, context) -> null, // not for us
                        (ref, argument, context) ->
                                MCPCompletions.matching(List.of("fusion", "fun", "other"), argument.value()),
                        (ref, argument, context) -> {
                            throw new IllegalStateException(
                                    "the first non null answer wins, this one is never reached");
                        })
                .build();

        final var result = setup.protocol()
                .completion(
                        new CompletionArgument("code", "fu"),
                        new CompletionContext(Map.of("lang", "java")),
                        new CompletionRef("ref/prompt", "a/prompt", null, null),
                        null);

        assertNull(result.metadata());
        assertEquals(List.of("fusion", "fun"), result.completion().values());
        assertEquals(2, result.completion().total());
        assertFalse(result.completion().hasMore());
    }

    @Test
    void completionFallsBackOnAnEmptyResult(@Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        final var setup = protocol(jsons, container)
                .completions((ref, argument, context) -> null)
                .build();

        final var completion = setup.protocol()
                .completion(
                        new CompletionArgument("code", ""),
                        null,
                        new CompletionRef("ref/prompt", "nope", null, null),
                        null)
                .completion();

        assertFalse(completion.hasMore());
        assertEquals(0, completion.total());
        assertEquals(List.of(), completion.values());
    }

    @Test
    void theSubclassingConstructorHoldsNothing() {
        // the no-arg constructor only exists for the Fusion subclassing proxies, it must not blow up
        final var protocol = new MCPJSONRPCProtocol() {};

        assertEquals(Map.of(), protocol.ping(null, null));
        // no provider at all, so the listings are empty and not a NullPointerException
        assertEquals(List.of(), protocol.listResources(null, null).resources());
        assertEquals(List.of(), protocol.listResourceTemplates(null, null).resourceTemplates());
        assertEquals(
                0,
                protocol.completion(
                                new CompletionArgument("a", ""),
                                null,
                                new CompletionRef("ref/prompt", "p", null, null),
                                null)
                        .completion()
                        .total());
    }

    @Test
    void whatTheClientCancelledIsLogged(@Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        final var setup = protocol(jsons, container).build();

        // there is nothing to interrupt - the request already runs in the JSON-RPC stack - so it is only traced
        Loggers.atFinest(MCPJSONRPCProtocol.class, () -> {
            setup.protocol().onCancelled("user gave up", 5);
            setup.protocol().onCancelled(null, "an-id");
        });
    }

    @Test
    void thereIsNoInitializeResponseWithoutDescriptorsNorConfiguration(
            @Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        // half built, i.e. what a subclassing proxy of a partially initialized container looks like - either half
        // missing is enough to have no initialize response to hand out
        final var handler = new JsonRpcHandler(container, jsons, new JsonRpcRegistry(List.of()));
        final var descriptors =
                protocol(jsons, container).tool("a/tool").build().protocol().listTools(null, null);
        assertNotNull(descriptors);

        assertEquals(
                Map.of(),
                new MCPJSONRPCProtocol(null, jsons, handler, null, null, List.of(), List.of(), null).ping(null, null));
        assertEquals(
                Map.of(),
                new MCPJSONRPCProtocol(descriptorService(jsons), jsons, handler, null, null, List.of(), List.of(), null)
                        .ping(null, null));
    }

    @Test
    void anUnexpectedPromptResultShapeIsAnInternalError(
            @Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        // a bulk response for a single request: it cannot happen with the real stack, but the guard must hold
        final var setup = protocol(jsons, container)
                .prompt("a/prompt")
                .handler((delegate, registry) -> new JsonRpcHandler(container, jsons, registry) {
                    @Override
                    public CompletionStage<?> execute(final Object request, final Request httpRequest) {
                        return completedFuture(List.of("not a response"));
                    }
                })
                .build();

        final var error = assertThrows(
                java.util.concurrent.CompletionException.class,
                () -> setup.protocol()
                        .callPrompt("a/prompt", Map.of(), null, new StubRequest())
                        .toCompletableFuture()
                        .join());

        assertInstanceOfJsonRpc(error.getCause(), -32603, "Unexpected result");
    }

    @Test
    void aToolAskingAnInputReturnsAnInputRequiredResult(
            @Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        final var setup = protocol(jsons, container)
                .tool(
                        "a/tool",
                        params -> failedFuture(new InputRequiredException(
                                Map.of(
                                        "elicitation/create",
                                        new InputRequest("elicitation/create", Map.of("prompt", "What?"))),
                                "entry/state")))
                .build();
        final var request = new StubRequest();
        setup.sessions().bind(request, setup.sessions().ephemeral(true));

        final var result = setup.protocol()
                .callTool("a/tool", Map.of(), null, request)
                .toCompletableFuture()
                .join();

        assertEquals(ResultType.input_required, result.resultType());
        // opaque codec, the state is the token itself
        assertEquals("entry/state", result.requestState());
        assertEquals(
                "elicitation/create", result.inputRequests().keySet().iterator().next());
    }

    @Test
    void aToolAskingAnInputWithPlainMapRequestsIsDecoded(
            @Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        final var setup = protocol(jsons, container)
                .tool(
                        "a/tool",
                        params -> failedFuture(new JsonRpcException(
                                MCPProtocol.INPUT_REQUIRED,
                                "input",
                                Map.of(
                                        "inputRequests",
                                        Map.of(
                                                "elicitation/create",
                                                Map.of(
                                                        "method",
                                                        "elicitation/create",
                                                        "params",
                                                        Map.of("prompt", "What?"))),
                                        "state",
                                        "s"),
                                null)))
                .build();
        final var request = new StubRequest();
        setup.sessions().bind(request, setup.sessions().ephemeral(true));

        final var result = setup.protocol()
                .callTool("a/tool", Map.of(), null, request)
                .toCompletableFuture()
                .join();

        assertEquals(ResultType.input_required, result.resultType());
        assertEquals(
                "elicitation/create",
                result.inputRequests().get("elicitation/create").method());
        assertEquals("s", result.requestState());
    }

    @Test
    void aTamperedRequestStateIsRejected(@Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        final var hmac = new HmacRequestStateCodec(
                new MCPConfiguration("n", "t", "v", "i", 0, 30, false, 30000L, "private", "top-secret", true));
        final var setup = protocol(jsons, container)
                .tool("a/tool", params -> completedFuture(ToolResponse.text("ok")))
                .codecs(hmac)
                .build();
        final var request = new StubRequest();
        setup.sessions().bind(request, setup.sessions().ephemeral(true));
        final var meta = new MCPRequestMetadata("2026-07-28", null, null, null, null, null, "garbage", null);

        assertInstanceOfJsonRpc(
                assertThrows(
                        JsonRpcException.class, () -> setup.protocol().callTool("a/tool", Map.of(), meta, request)),
                -32602,
                "Invalid request state");
    }

    @Test
    void theRequestStateCodecSelectionPrefersAnActiveNonOpaqueOne(
            @Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        final var handler = new JsonRpcHandler(container, jsons, new JsonRpcRegistry(List.of()));
        final var secret = new HmacRequestStateCodec(
                new MCPConfiguration("n", "t", "v", "i", 0, 30, false, 30000L, "private", "top-secret", true));
        assertEquals(
                Map.of(),
                new MCPJSONRPCProtocol(null, jsons, handler, null, null, List.of(), List.of(), List.of(secret))
                        .ping(null, null));
        assertEquals(
                Map.of(),
                new MCPJSONRPCProtocol(null, jsons, handler, null, null, List.of(), List.of(), List.of())
                        .ping(null, null));
        // an inactive (no secret) codec and a null element are both skipped, opaque wins
        final var inactive = new HmacRequestStateCodec(
                new MCPConfiguration("n", "t", "v", "i", 0, 30, false, 30000L, "private", "", true));
        assertEquals(
                Map.of(),
                new MCPJSONRPCProtocol(null, jsons, handler, null, null, List.of(), List.of(), List.of(inactive))
                        .ping(null, null));
    }

    @Test
    void aRequestMetaEnvelopeIsAppliedToTheSessionAndTheRequest(
            @Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        final var setup = protocol(jsons, container)
                .tool("a/tool", params -> completedFuture(ToolResponse.text("ok")))
                .build();
        final var request = new StubRequest();
        final var session = setup.sessions().ephemeral(true);
        setup.sessions().bind(request, session);
        final var capabilities = new Capabilities(null, Map.of(), Map.of(), null, null);
        final var clientInfo = new ClientInfo("test", "Test", "1.0.0");
        final var meta = new MCPRequestMetadata(
                "2026-07-28", capabilities, clientInfo, LoggingLevel.debug, "tok", "7", null, Map.of("a", 1));

        setup.protocol()
                .callTool("a/tool", Map.of(), meta, request)
                .toCompletableFuture()
                .join();

        assertSame(capabilities, session.capabilities());
        assertSame(clientInfo, session.clientInfo());
        assertEquals(LoggingLevel.debug, session.loggingLevel());
        assertEquals("tok", session.progressToken());
        assertEquals(Map.of("a", 1), request.attribute(MCPProtocol.INPUT_RESPONSES_ATTRIBUTE, Map.class));
    }

    @Test
    void aBareProgressTokenInTheMetaIsApplied(
            @Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        // a legacy client - and a conformance tools/call - sends the bare _meta key, not the namespaced one
        final var setup = protocol(jsons, container)
                .tool("a/tool", params -> completedFuture(ToolResponse.text("ok")))
                .build();
        final var request = new StubRequest();
        final var session = setup.sessions().ephemeral(true);
        setup.sessions().bind(request, session);

        setup.protocol()
                .callTool("a/tool", Map.of(), Map.of("progressToken", "legacy-tok"), request)
                .toCompletableFuture()
                .join();

        assertEquals("legacy-tok", session.progressToken());
    }

    @Test
    void aNamespacedProgressTokenInARawMetaIsApplied(
            @Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        // the raw (map) _meta path, when the codec hands the envelope as a Map carrying the namespaced token
        final var setup = protocol(jsons, container)
                .tool("a/tool", params -> completedFuture(ToolResponse.text("ok")))
                .build();
        final var request = new StubRequest();
        final var session = setup.sessions().ephemeral(true);
        setup.sessions().bind(request, session);

        setup.protocol()
                .callTool(
                        "a/tool", Map.of(), Map.of("io.modelcontextprotocol/progressToken", "namespaced-tok"), request)
                .toCompletableFuture()
                .join();

        assertEquals("namespaced-tok", session.progressToken());
    }

    @Test
    void aFailingToolReportsAnErrorResultOverStateless(
            @Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        final var setup = protocol(jsons, container)
                .tool("a/tool", params -> failedFuture(new IllegalArgumentException("boom")))
                .build();
        final var request = new StubRequest();
        setup.sessions().bind(request, setup.sessions().ephemeral(true));

        final var result = setup.protocol()
                .callTool("a/tool", Map.of(), null, request)
                .toCompletableFuture()
                .join();

        assertTrue(result.isError());
        assertEquals(ResultType.error, result.resultType());
    }

    @Test
    void readingAResourceOntoStatelessAddsTheServerInfo(
            @Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        final var setup =
                protocol(jsons, container).resources(new StaticResources()).build();
        final var request = new StubRequest();
        setup.sessions().bind(request, setup.sessions().ephemeral(true));

        final var response = setup.protocol().readResource("demo://greeting", null, request);

        assertEquals(ResultType.complete, response.resultType());
        assertNotNull(response.metadata());
        assertTrue(response.metadata().others().containsKey(MCPProtocol.SERVER_INFO_META));
    }

    @Test
    void listingTasksOverStatelessReturnsTheCompleteType(
            @Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        final var setup = protocol(jsons, container).build();
        final var request = new StubRequest();
        setup.sessions().bind(request, setup.sessions().ephemeral(true));

        assertEquals(
                ResultType.complete, setup.protocol().listTasks(null, request).resultType());
    }

    @Test
    void callingPromptWithoutARequestStillRunsIt(
            @Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        final var setup = protocol(jsons, container)
                .prompt("a/prompt", params -> completedFuture(new PromptResponse(null, "A prompt", List.of(), null)))
                .build();

        final var result = setup.protocol()
                .callPrompt("a/prompt", Map.of(), null, null)
                .toCompletableFuture()
                .join();

        assertNull(result.resultType()); // legacy (no request): no stateless result type
    }

    @Test
    void cancellingASubscriptionByNumericId(@Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        final var setup = protocol(jsons, container).build();
        final var session = setup.sessions().ephemeral(true);
        setup.sessions().registerSubscription("42", null, session);
        final var request = new StubRequest();
        setup.sessions().bind(request, session);
        request.setAttribute(MCPProtocol.SUBSCRIPTION_ID_ATTRIBUTE, "42");

        assertEquals(Map.of(), setup.protocol().onSubscriptionsCancel(null, request));
    }

    @Test
    void theTopLevelInputResponsesAndRequestStateReachTheTool(
            @Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        final var setup = protocol(jsons, container)
                .tool("a/tool", params -> completedFuture(ToolResponse.text("ok")))
                .build();
        final var request = new StubRequest();
        setup.sessions().bind(request, setup.sessions().ephemeral(true));

        // the SDK sends the answers and the state as top-level params
        setup.protocol()
                .callTool("a/tool", Map.of(), Map.of("user_name", Map.of("name", "Alice")), "some-state", null, request)
                .toCompletableFuture()
                .join();

        assertEquals(
                Map.of("user_name", Map.of("name", "Alice")),
                request.attribute(MCPProtocol.INPUT_RESPONSES_ATTRIBUTE, Map.class));
        assertEquals("some-state", request.attribute(MCPProtocol.REQUEST_STATE_ATTRIBUTE, String.class));

        // a top-level value overrides the _meta one
        final var meta = new MCPRequestMetadata("2026-07-28", null, null, null, null, null, "meta-state", null);
        setup.protocol()
                .callTool("a/tool", Map.of(), Map.of("k", "v"), "top-state", meta, request)
                .toCompletableFuture()
                .join();
        assertEquals("top-state", request.attribute(MCPProtocol.REQUEST_STATE_ATTRIBUTE, String.class));
    }

    @Test
    void initializeIsRejectedOnAStatelessRequest(
            @Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        final var setup = protocol(jsons, container).build();
        final var request = new StubRequest();
        setup.sessions().bind(request, setup.sessions().ephemeral(true));

        assertInstanceOfJsonRpc(
                assertThrows(
                        JsonRpcException.class, () -> setup.protocol().initialize("2025-11-25", null, null, request)),
                -32601,
                "initialize is only available over the legacy protocol");
    }

    @Test
    void discoverIsRejectedOnALegacyConnection(
            @Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        final var setup = protocol(jsons, container).build();
        final var request = new StubRequest();
        setup.sessions().bind(request, setup.sessions().ephemeral());

        assertInstanceOfJsonRpc(
                assertThrows(JsonRpcException.class, () -> setup.protocol().discover(null, request)),
                -32601,
                "server/discover is only available over the stateless protocol");
    }

    @Test
    void subscriptionsListenAndCancel(@Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        final var setup = protocol(jsons, container).build();
        final var request = new StubRequest();
        setup.sessions().bind(request, setup.sessions().ephemeral(true));
        request.setAttribute(MCPProtocol.SUBSCRIPTION_ID_ATTRIBUTE, "1");

        assertTrue(setup.protocol().onSubscriptionsListen(null, null, request) instanceof ResponseWithBus);
        assertEquals(1, setup.sessions().subscriptions().size());

        final var cancel = new StubRequest();
        setup.sessions().bind(cancel, setup.sessions().ephemeral(true));
        assertEquals(
                Map.of(),
                setup.protocol()
                        .onSubscriptionsCancel(
                                new MCPRequestMetadata(null, null, null, null, null, "1", null, null), cancel));
        assertTrue(setup.sessions().subscriptions().isEmpty());
    }

    @Test
    void subscriptionsListenWithoutAnIdIsNotRegistered(
            @Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        final var setup = protocol(jsons, container).build();
        final var request = new StubRequest();
        setup.sessions().bind(request, setup.sessions().ephemeral(true));

        assertTrue(setup.protocol().onSubscriptionsListen(null, null, request) instanceof ResponseWithBus);
        assertEquals(0, setup.sessions().subscriptions().size());
    }

    @Test
    void statelessReadResourceAddsTheServerInfo(
            @Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        final var setup =
                protocol(jsons, container).resources(new StaticResources()).build();
        final var request = new StubRequest();
        setup.sessions().bind(request, setup.sessions().ephemeral(true));

        final var response = setup.protocol().readResource("demo://greeting", null, request);

        assertEquals(ResultType.complete, response.resultType());
        assertSame(
                MCPProtocol.SERVER_INFO_META,
                response.metadata().others().keySet().iterator().next());
    }

    @Test
    void aLegacyReadResourceDoesNotAddTheServerInfo(
            @Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        final var setup =
                protocol(jsons, container).resources(new StaticResources()).build();
        final var request = new StubRequest();
        setup.sessions().bind(request, setup.sessions().ephemeral());

        final var response = setup.protocol().readResource("demo://greeting", null, request);

        assertNull(response.metadata(), () -> String.valueOf(response.metadata()));
    }

    @Test
    void aPromptAskingAnInputReturnsAnInputRequiredResult(
            @Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        final var setup = protocol(jsons, container)
                .prompt(
                        "a/prompt",
                        params -> failedFuture(new InputRequiredException(
                                Map.of(
                                        "sampling/createMessage",
                                        new InputRequest("sampling/createMessage", Map.of("query", "explain"))),
                                "state")))
                .build();
        final var request = new StubRequest();
        setup.sessions().bind(request, setup.sessions().ephemeral(true));

        final var result = setup.protocol()
                .callPrompt("a/prompt", Map.of(), null, request)
                .toCompletableFuture()
                .join();

        assertEquals(ResultType.input_required, result.resultType());
        assertEquals(
                "sampling/createMessage",
                result.inputRequests().keySet().iterator().next());
    }

    @Test
    void tasksHandlersDriveTheRegistry(@Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        final var setup = protocol(jsons, container).build();
        final var protocol = setup.protocol();
        final var request = new StubRequest();
        setup.sessions().bind(request, setup.sessions().ephemeral(true));

        assertThrows(JsonRpcException.class, () -> protocol.getTask("nope", request));
        assertThrows(JsonRpcException.class, () -> protocol.updateTask("nope", Map.of("k", "v"), request));
        assertThrows(JsonRpcException.class, () -> protocol.cancelTask("nope", request));

        // a task created by the store is exposed by the handlers
        final var created = protocol.tasks()
                .create(new Task(
                        null, TaskStatus.working, "starting", null, null, null, null, null, 300000L, 1000L, null));
        assertSame(created, protocol.getTask(created.taskId(), request));
        assertEquals(Map.of(), protocol.updateTask(created.taskId(), Map.of("confirm", true), request));
        assertEquals(Map.of(), protocol.cancelTask(created.taskId(), request));
        assertEquals(1, protocol.listTasks(null, request).tasks().size());
    }

    @Test
    void statelessTasksListCarryTheResultType(
            @Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        final var setup = protocol(jsons, container).build();
        final var request = new StubRequest();
        setup.sessions().bind(request, setup.sessions().ephemeral(true));

        assertEquals(
                ResultType.complete, setup.protocol().listTasks(null, request).resultType());
    }

    @Test
    void statelessSubListingsCarryTheResultType(
            @Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        final var setup = protocol(jsons, container)
                .prompt("a/prompt")
                .resources(new StaticResources())
                .tool("a/tool")
                .completions((ref, argument, context) -> null)
                .build();
        final var request = new StubRequest();
        setup.sessions().bind(request, setup.sessions().ephemeral(true));

        assertEquals(
                ResultType.complete, setup.protocol().listPrompts(null, request).resultType());
        assertEquals(
                ResultType.complete,
                setup.protocol().listResources(null, request).resultType());
        assertEquals(
                ResultType.complete,
                setup.protocol().listResourceTemplates(null, request).resultType());
        assertEquals(
                ResultType.complete, setup.protocol().listTools(null, request).resultType());
    }

    @Test
    void aStatelessCompletionCarriesTheResultType(
            @Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        final var setup = protocol(jsons, container)
                .completions((ref, argument, context) -> new CompleteResult.Completion(true, 1, List.of("a")))
                .build();
        final var request = new StubRequest();
        setup.sessions().bind(request, setup.sessions().ephemeral(true));

        final var result = setup.protocol()
                .completion(
                        new CompletionArgument("arg", "x"),
                        new CompletionContext(Map.of("other", "value")),
                        new CompletionRef("prompt", "a/prompt", null, null),
                        request);

        assertEquals(ResultType.complete, result.resultType());
        assertTrue(result.completion().hasMore());
    }

    @Test
    void subscriptionsCancelWithAStringIdentifierClosesTheStream(
            @Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        final var setup = protocol(jsons, container).build();
        final var listen = new StubRequest();
        setup.sessions().bind(listen, setup.sessions().ephemeral(true));
        listen.setAttribute(MCPProtocol.SUBSCRIPTION_ID_ATTRIBUTE, "abc");

        assertTrue(setup.protocol().onSubscriptionsListen(null, null, listen) instanceof ResponseWithBus);
        assertEquals(1, setup.sessions().subscriptions().size());

        final var cancel = new StubRequest();
        setup.sessions().bind(cancel, setup.sessions().ephemeral(true));
        assertEquals(
                Map.of(),
                setup.protocol()
                        .onSubscriptionsCancel(
                                new MCPRequestMetadata(null, null, null, null, null, "abc", null, null), cancel));
        assertTrue(setup.sessions().subscriptions().isEmpty());
    }

    @Test
    void subscriptionsListenIsRejectedOnALegacyConnection(
            @Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        final var setup = protocol(jsons, container).build();
        final var request = new StubRequest();
        setup.sessions().bind(request, setup.sessions().ephemeral());

        assertInstanceOfJsonRpc(
                assertThrows(JsonRpcException.class, () -> setup.protocol().onSubscriptionsListen(null, null, request)),
                -32601,
                "subscriptions/listen is only available over the stateless protocol");
    }

    @Test
    void subscriptionsCancelIsRejectedOnALegacyConnection(
            @Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        final var setup = protocol(jsons, container).build();
        final var request = new StubRequest();
        setup.sessions().bind(request, setup.sessions().ephemeral());

        assertInstanceOfJsonRpc(
                assertThrows(JsonRpcException.class, () -> setup.protocol().onSubscriptionsCancel(null, request)),
                -32601,
                "subscriptions/cancel is only available over the stateless protocol");
    }

    @Test
    void theLegacyOnlyUtilitiesAreRejectedOverTheStatelessProtocol(
            @Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        // ping, logging/setLevel, resources/subscribe and resources/unsubscribe belong to the legacy protocol: the
        // modern one replaced them (subscriptions/listen, logging on server/discover, subscriptions)
        final var setup = protocol(jsons, container).build();
        final var request = new StubRequest();
        setup.sessions().bind(request, setup.sessions().ephemeral(true));

        assertInstanceOfJsonRpc(
                assertThrows(JsonRpcException.class, () -> setup.protocol().ping(null, request)),
                -32601,
                "ping is not available over the stateless protocol");
        assertInstanceOfJsonRpc(
                assertThrows(JsonRpcException.class, () -> setup.protocol().setLoggingLevel("debug", request)),
                -32601,
                "logging/setLevel is not available over the stateless protocol");
        assertInstanceOfJsonRpc(
                assertThrows(JsonRpcException.class, () -> setup.protocol().subscribeResource("demo://x", request)),
                -32601,
                "resources/subscribe is not available over the stateless protocol");
        assertInstanceOfJsonRpc(
                assertThrows(JsonRpcException.class, () -> setup.protocol().unsubscribeResource("demo://x", request)),
                -32601,
                "resources/unsubscribe is not available over the stateless protocol");
    }

    @Test
    void aValidHmacRequestStateIsDecoded(@Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        final var hmac = new HmacRequestStateCodec(
                new MCPConfiguration("n", "t", "v", "i", 0, 30, false, 30000L, "private", "top-secret", true));
        final var setup = protocol(jsons, container)
                .tool("a/tool", params -> completedFuture(ToolResponse.text("ok")))
                .codecs(hmac)
                .build();
        final var request = new StubRequest();
        setup.sessions().bind(request, setup.sessions().ephemeral(true));
        final var meta =
                new MCPRequestMetadata("2026-07-28", null, null, null, null, null, hmac.encode("client=7"), null);

        setup.protocol()
                .callTool("a/tool", Map.of(), meta, request)
                .toCompletableFuture()
                .join();

        assertEquals("client=7", request.attribute(MCPProtocol.REQUEST_STATE_ATTRIBUTE, String.class));
    }

    @Test
    void subscriptionsCancelWithoutAnIdentifierIsANoOp(
            @Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        final var setup = protocol(jsons, container).build();
        // a stateless cancel with no identifier: nothing registered, nothing to do
        final var cancel = new StubRequest();
        setup.sessions().bind(cancel, setup.sessions().ephemeral(true));
        assertEquals(Map.of(), setup.protocol().onSubscriptionsCancel(null, cancel));
    }

    @Test
    void aVoidToolResultAndANonStatelessOneBothWork(
            @Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        final var setup = protocol(jsons, container)
                .tool("a/void", params -> completedFuture(null))
                .tool("a/tool", params -> completedFuture(ToolResponse.text("ok")))
                .build();

        // a void tool on a legacy (non-stateless) request
        final var legacy = new StubRequest();
        setup.sessions().bind(legacy, setup.sessions().ephemeral());
        assertNull(setup.protocol()
                .callTool("a/void", Map.of(), null, legacy)
                .toCompletableFuture()
                .join()
                .resultType());

        // a void tool on a stateless request carries the complete result type
        final var stateless = new StubRequest();
        setup.sessions().bind(stateless, setup.sessions().ephemeral(true));
        assertEquals(
                ResultType.complete,
                setup.protocol()
                        .callTool("a/void", Map.of(), null, stateless)
                        .toCompletableFuture()
                        .join()
                        .resultType());
    }

    @Test
    void inputRequestsAreFilteredByTheClientCapabilities(
            @Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        final var setup = protocol(jsons, container)
                .tool(
                        "a/tool",
                        params -> failedFuture(new InputRequiredException(
                                Map.of(
                                        "elicitation", new InputRequest("elicitation/create", Map.of()),
                                        "sampling", new InputRequest("sampling/createMessage", Map.of()),
                                        "roots", new InputRequest("roots/list", Map.of()),
                                        "custom", new InputRequest("custom/thing", Map.of())),
                                "state")))
                .build();
        final var request = new StubRequest();
        final var session = setup.sessions().ephemeral(true);
        setup.sessions().bind(request, session);
        // the client only declared sampling - the elicitation input request must be dropped
        session.applyRequestMeta(new Capabilities(null, Map.of(), null, null, null), null, null, null, null, null);

        final var result = setup.protocol()
                .callTool("a/tool", Map.of(), null, request)
                .toCompletableFuture()
                .join();

        assertEquals(ResultType.input_required, result.resultType());
        assertEquals(
                2,
                result.inputRequests().size(),
                "the elicitation and roots (undeclared) requests are dropped, sampling (declared) and un-gated are kept");
        assertEquals(
                "sampling/createMessage", result.inputRequests().get("sampling").method());
        assertTrue(result.inputRequests().containsKey("custom"));

        // a client declaring only roots keeps the roots request
        final var rootsOnly = new StubRequest();
        final var rootsSession = setup.sessions().ephemeral(true);
        setup.sessions().bind(rootsOnly, rootsSession);
        rootsSession.applyRequestMeta(
                new Capabilities(new Capabilities.Roots(false), null, null, null, null), null, null, null, null, null);
        final var keptRoots = setup.protocol()
                .callTool("a/tool", Map.of(), null, rootsOnly)
                .toCompletableFuture()
                .join();
        assertTrue(keptRoots.inputRequests().containsKey("roots"));
        assertFalse(keptRoots.inputRequests().containsKey("elicitation"));
        assertFalse(keptRoots.inputRequests().containsKey("sampling"));

        // when the client declared nothing, nothing is filtered out
        final var noCaps = new StubRequest();
        setup.sessions().bind(noCaps, setup.sessions().ephemeral(true));
        final var kept = setup.protocol()
                .callTool("a/tool", Map.of(), null, noCaps)
                .toCompletableFuture()
                .join();
        assertEquals(4, kept.inputRequests().size());
    }

    private JsonRpcException assertInstanceOfJsonRpc(final Throwable actual, final int code, final String message) {
        assertNotNull(actual);
        assertTrue(actual instanceof JsonRpcException, () -> String.valueOf(actual));
        final var jsonRpc = (JsonRpcException) actual;
        assertEquals(code, jsonRpc.code());
        assertEquals(message, jsonRpc.getMessage());
        return jsonRpc;
    }

    private Setup protocol(final JsonMapper jsons, final RuntimeContainer container) {
        return new Setup(jsons, container);
    }

    /**
     * @return descriptors with nothing in them, only used to have a non {@code null} one.
     */
    private DescriptorService descriptorService(final JsonMapper jsons) {
        return new DescriptorService(
                new OpenRpcService(jsons) {
                    @Override
                    public OpenRpc load() {
                        return new OpenRpc(Map.of(), Map.of());
                    }
                },
                new JsonRpcRegistry(List.of()),
                jsons,
                List.of());
    }

    /**
     * Exposes two resources, one static and one parameterized, so the listings and {@code resources/read} have
     * something to return.
     */
    private static class StaticResources implements MCPResources {
        @Override
        public List<Resource> resources() {
            return List.of(Resource.of("demo://greeting", "greeting", "text/plain", "A greeting."));
        }

        @Override
        public List<ResourceTemplate> resourceTemplates() {
            return List.of(ResourceTemplate.of("demo://echo/{message}", "echo", "text/plain", "Echoes the uri."));
        }

        @Override
        public Optional<ReadResourceResponse> read(final String uri) {
            return "demo://greeting".equals(uri)
                    ? Optional.of(ReadResourceResponse.of(ResourceContents.text(uri, "text/plain", "hello fusion!")))
                    : Optional.empty();
        }
    }

    /**
     * Builds a {@link MCPJSONRPCProtocol} on a real JSON-RPC stack whose deployed methods are stubs.
     */
    private static class Setup {
        private final JsonMapper jsons;
        private final RuntimeContainer container;
        private final List<JsonRpcMethod> methods = new java.util.ArrayList<>();
        private List<MCPResources> resources = List.of();
        private List<MCPCompletions> completions = List.of();
        private List<MCPRequestStateCodec> codecs = List.of();
        private List<MCPToolSchemas> schemas = List.of();
        private java.util.function.BiFunction<JsonRpcHandler, JsonRpcRegistry, JsonRpcHandler> handler =
                (delegate, registry) -> delegate;

        private Setup(final JsonMapper jsons, final RuntimeContainer container) {
            this.jsons = jsons;
            this.container = container;
        }

        private Setup method(final JsonRpcMethod method) {
            methods.add(method);
            return this;
        }

        private Setup tool(final String name) {
            return method(StubJsonRpcMethod.tool(name));
        }

        private Setup tool(
                final String name, final java.util.function.Function<Object, CompletionStage<?>> invocation) {
            return method(StubJsonRpcMethod.tool(name, invocation));
        }

        private Setup prompt(final String name) {
            return method(StubJsonRpcMethod.prompt(name));
        }

        private Setup prompt(
                final String name, final java.util.function.Function<Object, CompletionStage<?>> invocation) {
            return method(StubJsonRpcMethod.prompt(name, invocation));
        }

        private Setup resources(final MCPResources... resources) {
            this.resources = List.of(resources);
            return this;
        }

        private Setup completions(final MCPCompletions... completions) {
            this.completions = List.of(completions);
            return this;
        }

        private Setup codecs(final MCPRequestStateCodec... codecs) {
            this.codecs = List.of(codecs);
            return this;
        }

        private Setup schemas(final MCPToolSchemas... schemas) {
            this.schemas = List.of(schemas);
            return this;
        }

        private Setup handler(
                final java.util.function.BiFunction<JsonRpcHandler, JsonRpcRegistry, JsonRpcHandler> handler) {
            this.handler = handler;
            return this;
        }

        private Built build() {
            final var document = new OpenRpc(
                    Map.of(),
                    methods.stream()
                            .collect(Collectors.toMap(
                                    JsonRpcMethod::name,
                                    it -> new OpenRpc.JsonRpcMethod(
                                            it.name(), "Documentation of " + it.name(), List.of(), null))));
            final var registry = new JsonRpcRegistry(methods);
            final var descriptors = new DescriptorService(
                    new OpenRpcService(jsons) {
                        @Override
                        public OpenRpc load() { // the classpath one would bring the MCP protocol methods in
                            return document;
                        }
                    },
                    registry,
                    jsons,
                    schemas);
            final var sessions = new MCPSessions(jsons, configuration());
            return new Built(
                    new MCPJSONRPCProtocol(
                            descriptors,
                            jsons,
                            handler.apply(new JsonRpcHandler(container, jsons, registry), registry),
                            sessions,
                            configuration(),
                            resources,
                            completions,
                            codecs.isEmpty() ? List.of(new OpaqueRequestStateCodec()) : codecs),
                    sessions);
        }

        private MCPConfiguration configuration() {
            return new MCPConfiguration(
                    "fusion-mcp-server",
                    "Fusion MCP Server",
                    "1.0.0",
                    "Use the exposed tools to answer the user.",
                    0,
                    30,
                    false,
                    30000L,
                    "private",
                    "",
                    true);
        }
    }

    /**
     * The protocol and the sessions it was built with, tests assert on both.
     */
    private record Built(MCPJSONRPCProtocol protocol, MCPSessions sessions) {}
}
