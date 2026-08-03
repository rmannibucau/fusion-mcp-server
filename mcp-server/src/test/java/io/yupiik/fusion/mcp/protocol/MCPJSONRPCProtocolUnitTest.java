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
import io.yupiik.fusion.mcp.configuration.MCPConfiguration;
import io.yupiik.fusion.mcp.model.Capabilities;
import io.yupiik.fusion.mcp.model.ClientInfo;
import io.yupiik.fusion.mcp.model.CompletionArgument;
import io.yupiik.fusion.mcp.model.CompletionContext;
import io.yupiik.fusion.mcp.model.CompletionRef;
import io.yupiik.fusion.mcp.model.Content;
import io.yupiik.fusion.mcp.model.LoggingLevel;
import io.yupiik.fusion.mcp.model.PromptResponse;
import io.yupiik.fusion.mcp.model.ReadResourceResponse;
import io.yupiik.fusion.mcp.model.Resource;
import io.yupiik.fusion.mcp.model.ResourceContents;
import io.yupiik.fusion.mcp.model.ResourceTemplate;
import io.yupiik.fusion.mcp.model.Role;
import io.yupiik.fusion.mcp.model.ToolResponse;
import io.yupiik.fusion.mcp.model.fusion.OpenRpc;
import io.yupiik.fusion.mcp.service.DescriptorService;
import io.yupiik.fusion.mcp.service.OpenRpcService;
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
        assertEquals(Map.of(), capabilities.completions());
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
        final var capabilities = new Capabilities(null, Map.of(), Map.of(), Map.of());
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
        assertEquals(Map.of(), setup.protocol().ping(null));
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
                setup.protocol().listTools(null).tools().stream()
                        .map(it -> it.name())
                        .toList());
        assertEquals(
                List.of("a/prompt"),
                setup.protocol().listPrompts("ignored-cursor").prompts().stream()
                        .map(it -> it.name())
                        .toList());
    }

    @Test
    void callToolWrapsThePlainResult(@Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        final var setup = protocol(jsons, container)
                .tool("a/tool", params -> completedFuture(Map.of("value", "hello")))
                .build();

        final var response = setup.protocol()
                .callTool("a/tool", Map.of(), new StubRequest())
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
                        .callTool("a/tool", Map.of("name", "fusion"), new StubRequest())
                        .toCompletableFuture()
                        .join()
                        .structuredContent()
                        .toString());

        // no argument at all: the method gets an empty map and not null
        assertEquals(
                "{received={}}",
                setup.protocol()
                        .callTool("a/tool", null, new StubRequest())
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
                .callTool("a/tool", Map.of(), new StubRequest())
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
                .callTool("a/tool", Map.of(), new StubRequest())
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
                .callTool("a/tool", Map.of(), new StubRequest())
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
                .callTool("a/tool", Map.of(), new StubRequest())
                .toCompletableFuture()
                .join();

        assertTrue(response.isError());
        assertEquals(Map.of("code", -32000, "message", "the backend is down"), response.structuredContent());
    }

    @Test
    void aProtocolErrorIsNotAToolFailure(@Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        // the reserved codes mean the call itself was wrong - bad arguments for example - so they must surface as a
        // JSON-RPC error and not as a result the model would try to make sense of
        for (final int code : new int[] {-32700, -32600, -32601, -32602, -32603}) {
            final var setup = protocol(jsons, container)
                    .tool("a/tool", params -> failedFuture(new JsonRpcException(code, "invalid call")))
                    .build();

            final var error = assertThrows(
                    java.util.concurrent.CompletionException.class,
                    () -> setup.protocol()
                            .callTool("a/tool", Map.of(), new StubRequest())
                            .toCompletableFuture()
                            .join());

            assertInstanceOfJsonRpc(error.getCause(), code, "invalid call");
        }
    }

    @Test
    void aCodeOutsideTheReservedRangeStaysAToolFailure(
            @Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        // -32604 and -32599 are just below/above the reserved -32600..-32603 window
        for (final int code : new int[] {-32604, -32599, -1}) {
            final var setup = protocol(jsons, container)
                    .tool("a/tool", params -> failedFuture(new JsonRpcException(code, "business failure")))
                    .build();

            assertTrue(
                    setup.protocol()
                            .callTool("a/tool", Map.of(), new StubRequest())
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

        final var error =
                assertThrows(JsonRpcException.class, () -> setup.protocol().callTool("a/plain", Map.of(), request));

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
                        .callTool("a/tool", Map.of(), new StubRequest())
                        .toCompletableFuture()
                        .join());

        assertInstanceOfJsonRpc(error.getCause(), -32603, "Unexpected result calling 'a/tool'");
    }

    @Test
    void callPrompt(@Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        final var expected = new PromptResponse(
                null, "A description", List.of(new PromptResponse.Message(Role.user, Content.text("hello"))));
        final var setup = protocol(jsons, container)
                .prompt("a/prompt", params -> completedFuture(expected))
                .build();

        final var response = setup.protocol()
                .callPrompt("a/prompt", Map.of("code", "fusion"), new StubRequest())
                .toCompletableFuture()
                .join();

        assertSame(expected, response);
    }

    @Test
    void callUnknownPrompt(@Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        final var setup = protocol(jsons, container).tool("a/tool").build();
        final var request = new StubRequest();

        // a tool is not a prompt, prompts/get must not reach it
        final var error =
                assertThrows(JsonRpcException.class, () -> setup.protocol().callPrompt("a/tool", Map.of(), request));

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
                        .callPrompt("a/prompt", Map.of(), new StubRequest())
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
                        .callPrompt("a/prompt", Map.of(), new StubRequest())
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
                setup.protocol().listResources(null).resources().stream()
                        .map(Resource::uri)
                        .toList());
        assertEquals(
                List.of("demo://echo/{message}"),
                setup.protocol().listResourceTemplates(null).resourceTemplates().stream()
                        .map(ResourceTemplate::uriTemplate)
                        .toList());
        assertNull(setup.protocol().listResources(null).nextCursor());
        assertNull(setup.protocol().listResourceTemplates(null).nextCursor());
    }

    @Test
    void readResourceStopsOnTheFirstProviderKnowingTheUri(
            @Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        final var setup = protocol(jsons, container)
                // the first one does not know it, the second does
                .resources(new MCPResources() {}, new StaticResources())
                .build();

        final var response = setup.protocol().readResource("demo://greeting");

        assertEquals(1, response.contents().size());
        assertEquals("hello fusion!", response.contents().getFirst().text());
    }

    @Test
    void readAnUnknownResource(@Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        final var setup =
                protocol(jsons, container).resources(new StaticResources()).build();

        final var error =
                assertThrows(JsonRpcException.class, () -> setup.protocol().readResource("demo://nope"));

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
                        new CompletionRef("ref/prompt", "a/prompt", null, null));

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
                        new CompletionArgument("code", ""), null, new CompletionRef("ref/prompt", "nope", null, null))
                .completion();

        assertFalse(completion.hasMore());
        assertEquals(0, completion.total());
        assertEquals(List.of(), completion.values());
    }

    @Test
    void theSubclassingConstructorHoldsNothing() {
        // the no-arg constructor only exists for the Fusion subclassing proxies, it must not blow up
        final var protocol = new MCPJSONRPCProtocol() {};

        assertEquals(Map.of(), protocol.ping(null));
        // no provider at all, so the listings are empty and not a NullPointerException
        assertEquals(List.of(), protocol.listResources(null).resources());
        assertEquals(List.of(), protocol.listResourceTemplates(null).resourceTemplates());
        assertEquals(
                0,
                protocol.completion(
                                new CompletionArgument("a", ""), null, new CompletionRef("ref/prompt", "p", null, null))
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
                protocol(jsons, container).tool("a/tool").build().protocol().listTools(null);
        assertNotNull(descriptors);

        assertEquals(
                Map.of(), new MCPJSONRPCProtocol(null, jsons, handler, null, null, List.of(), List.of()).ping(null));
        assertEquals(
                Map.of(),
                new MCPJSONRPCProtocol(descriptorService(jsons), jsons, handler, null, null, List.of(), List.of())
                        .ping(null));
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
                        .callPrompt("a/prompt", Map.of(), new StubRequest())
                        .toCompletableFuture()
                        .join());

        assertInstanceOfJsonRpc(error.getCause(), -32603, "Unexpected result");
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
                jsons);
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
                    jsons);
            final var sessions = new MCPSessions(jsons, configuration());
            return new Built(
                    new MCPJSONRPCProtocol(
                            descriptors,
                            jsons,
                            handler.apply(new JsonRpcHandler(container, jsons, registry), registry),
                            sessions,
                            configuration(),
                            resources,
                            completions),
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
                    false);
        }
    }

    /**
     * The protocol and the sessions it was built with, tests assert on both.
     */
    private record Built(MCPJSONRPCProtocol protocol, MCPSessions sessions) {}
}
