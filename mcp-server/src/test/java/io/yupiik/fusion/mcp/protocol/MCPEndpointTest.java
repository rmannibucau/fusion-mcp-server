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

import static io.yupiik.fusion.mcp.protocol.MCPProtocol.SESSION_HEADER;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.concurrent.CompletableFuture.failedFuture;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.framework.api.RuntimeContainer;
import io.yupiik.fusion.http.server.api.Request;
import io.yupiik.fusion.http.server.api.Response;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.jsonrpc.JsonRpcHandler;
import io.yupiik.fusion.jsonrpc.JsonRpcRegistry;
import io.yupiik.fusion.mcp.configuration.MCPConfiguration;
import io.yupiik.fusion.mcp.test.SseSubscriber;
import io.yupiik.fusion.mcp.test.StubJsonRpcMethod;
import io.yupiik.fusion.mcp.test.StubRequest;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

/**
 * The transport paths a HTTP client cannot easily provoke - a broken connection, a JSON-RPC stack failing
 * asynchronously and the response headers a method sets with a {@code PartialResponse} - see
 * {@link io.yupiik.fusion.mcp.MCPTransportTest} for everything which goes over the wire.
 */
@FusionSupport
class MCPEndpointTest {
    private static final String RESPONSE_HEADERS_ATTRIBUTE = "yupiik.jsonrpc.response.headers";

    @Test
    void anUnreadableBodyIsAParseError(@Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        // a connection dropped while the payload was being read
        final var response = endpoint(jsons, container)
                .handle(new StubRequest())
                .toCompletableFuture()
                .join();

        final var body = body(response);
        assertEquals(200, response.status());
        assertTrue(body.contains("\"code\":-32700"), body);
    }

    @Test
    void aFailingJsonRpcStackIsAnInternalError(
            @Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        final var endpoint = new MCPEndpoint(
                new JsonRpcHandler(container, jsons, registry()) {
                    @Override
                    public CompletionStage<?> execute(final Object request, final Request httpRequest) {
                        return failedFuture(new IllegalStateException("the stack blew up"));
                    }
                },
                jsons,
                sessions(jsons),
                configuration(false));

        final var response = endpoint.handle(new StubRequest(Map.of(), """
                        {"jsonrpc": "2.0", "id": 1, "method": "a/tool", "params": {}}"""))
                .toCompletableFuture()
                .join();

        final var body = body(response);
        assertEquals(200, response.status());
        assertTrue(body.contains("\"code\":-32603"), body);
        assertTrue(body.contains("the stack blew up"), body);
    }

    @Test
    void theHeadersAMethodSetAreCopiedOnTheResponse(
            @Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        // the Fusion JSON-RPC stack fills that attribute in for a method returning a PartialResponse
        final var request = new StubRequest(Map.of(), """
                {"jsonrpc": "2.0", "id": 1, "method": "a/tool", "params": {}}""");
        request.setAttribute(RESPONSE_HEADERS_ATTRIBUTE, Map.of("x-custom", "a-value"));

        final var response =
                endpoint(jsons, container).handle(request).toCompletableFuture().join();

        assertEquals(200, response.status());
        assertEquals(List.of("a-value"), response.headers().get("x-custom"));
    }

    @Test
    void aNewSessionIsReturnedInTheHeader(@Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        final var response = endpoint(jsons, container)
                .handle(new StubRequest(Map.of(), """
                        {"jsonrpc": "2.0", "id": 1, "method": "initialize", "params": {"protocolVersion": "2025-06-18"}}"""))
                .toCompletableFuture()
                .join();

        assertEquals(200, response.status());
        assertEquals(1, response.headers().get(SESSION_HEADER).size());
    }

    @Test
    void aStatelessClientGetsNoSessionHeader(@Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        // no session header and no initialize: the client is served with an ephemeral session, which has no id to
        // hand out
        final var response = endpoint(jsons, container)
                .handle(new StubRequest(Map.of(), """
                        {"jsonrpc": "2.0", "id": 1, "method": "a/tool", "params": {}}"""))
                .toCompletableFuture()
                .join();

        assertEquals(200, response.status());
        assertNull(response.headers().get(SESSION_HEADER));
    }

    @Test
    void anEmptyBodyIsAParseError(@Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        final var response = endpoint(jsons, container)
                .handle(new StubRequest(Map.of(), "null"))
                .toCompletableFuture()
                .join();

        final var body = body(response);
        assertEquals(200, response.status());
        assertTrue(body.contains("Empty request"), body);
    }

    @Test
    void theSubclassingConstructorHoldsNothing() {
        // the no-arg constructor only exists for the Fusion subclassing proxies, it must not blow up
        assertEquals(MCPEndpoint.class, new MCPEndpoint() {}.getClass().getSuperclass());
    }

    @Test
    void aNonLoopbackHostIsRejected(@Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        final var response = endpoint(jsons, container)
                .handle(new StubRequest(Map.of("Host", "evil.example.com"), """
                        {"jsonrpc": "2.0", "id": 1, "method": "a/tool", "params": {}}"""))
                .toCompletableFuture()
                .join();

        assertEquals(403, response.status());
    }

    @Test
    void aNonLoopbackOriginIsRejected(@Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        // the Host is loopback but a page served on a remote host must not drive the local server: the Origin wins
        final var response = endpoint(jsons, container)
                .handle(new StubRequest(Map.of("Host", "localhost", "Origin", "http://evil.example.com"), """
                        {"jsonrpc": "2.0", "id": 1, "method": "a/tool", "params": {}}"""))
                .toCompletableFuture()
                .join();

        assertEquals(403, response.status());
    }

    @Test
    void aLoopbackHostIsAccepted(@Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        final var response = endpoint(jsons, container)
                .handle(new StubRequest(Map.of("Host", "127.0.0.1:8080"), """
                        {"jsonrpc": "2.0", "id": 1, "method": "a/tool", "params": {}}"""))
                .toCompletableFuture()
                .join();

        assertEquals(200, response.status());
    }

    @Test
    void aFileOriginIsAccepted(@Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        // a file:// page sends the literal "null" Origin, it has no host to check
        final var response = endpoint(jsons, container)
                .handle(new StubRequest(Map.of("Host", "localhost", "Origin", "null"), """
                        {"jsonrpc": "2.0", "id": 1, "method": "a/tool", "params": {}}"""))
                .toCompletableFuture()
                .join();

        assertEquals(200, response.status());
    }

    @Test
    void theHostGuardCanBeDisabled(@Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        final var response = endpoint(jsons, container, false)
                .handle(new StubRequest(Map.of("Host", "evil.example.com"), """
                        {"jsonrpc": "2.0", "id": 1, "method": "a/tool", "params": {}}"""))
                .toCompletableFuture()
                .join();

        assertEquals(200, response.status());
    }

    @Test
    void aModernMethodHeaderMismatchIsRejected(
            @Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        final var response = endpoint(jsons, container)
                .handle(new StubRequest(
                        Map.of(MCPProtocol.METHOD_HEADER, "a/tool", MCPProtocol.PROTOCOL_VERSION_HEADER, "2026-07-28"),
                        """
                                {"jsonrpc": "2.0", "id": 1, "method": "other", "params": {}}"""))
                .toCompletableFuture()
                .join();

        assertEquals(400, response.status());
        assertTrue(body(response).contains("\"code\":" + MCPProtocol.HEADER_MISMATCH), body(response));
    }

    @Test
    void anUnsupportedMetaProtocolVersionIsRejected(
            @Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        final var response = endpoint(jsons, container)
                .handle(new StubRequest(Map.of(), """
                                {"jsonrpc": "2.0", "id": 1, "method": "a/tool", "params": {"_meta": {"io.modelcontextprotocol/protocolVersion": "1999-01-01"}}}"""))
                .toCompletableFuture()
                .join();

        assertEquals(400, response.status());
        final var b = body(response);
        assertTrue(b.contains("\"code\":" + MCPProtocol.UNSUPPORTED_PROTOCOL_VERSION), b);
        assertTrue(b.contains("\"requested\":\"1999-01-01\""), b);
        assertTrue(b.contains("\"supported\":[\"2026-07-28\"]"), b);
    }

    @Test
    void aStrictModernRequestNeedsTheRequiredMetaFields(
            @Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        // 2026-07-28 requires clientCapabilities in _meta, else -32602 invalid params
        final var missingCapabilities = endpoint(jsons, container)
                .handle(new StubRequest(Map.of(), """
                                {"jsonrpc": "2.0", "id": 1, "method": "a/tool", "params": {"_meta": {"io.modelcontextprotocol/protocolVersion": "2026-07-28"}}}"""))
                .toCompletableFuture()
                .join();

        assertEquals(400, missingCapabilities.status());
        assertTrue(body(missingCapabilities).contains("\"code\":-32602"), body(missingCapabilities));

        // the transport declares strict 2026-07-28 but the body omits the protocolVersion: also a strict failure
        final var missingVersion = endpoint(jsons, container)
                .handle(new StubRequest(
                        Map.of(MCPProtocol.METHOD_HEADER, "a/tool", MCPProtocol.PROTOCOL_VERSION_HEADER, "2026-07-28"),
                        """
                                {"jsonrpc": "2.0", "id": 1, "method": "a/tool", "params": {"_meta": {"io.modelcontextprotocol/clientCapabilities": {}}}}"""))
                .toCompletableFuture()
                .join();

        assertEquals(400, missingVersion.status());
        assertTrue(body(missingVersion).contains("\"code\":-32602"), body(missingVersion));
    }

    @Test
    void aStrictlyOpenedRequestIsServiced(@Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        // the full required _meta on 2026-07-28 is served, not rejected
        final var response = endpoint(jsons, container)
                .handle(new StubRequest(Map.of(), """
                                {"jsonrpc": "2.0", "id": 1, "method": "a/tool", "params": {"_meta": {
                                  "io.modelcontextprotocol/protocolVersion": "2026-07-28",
                                  "io.modelcontextprotocol/clientInfo": {"name": "test", "version": "1.0.0"},
                                  "io.modelcontextprotocol/clientCapabilities": {}
                                }}}"""))
                .toCompletableFuture()
                .join();

        assertEquals(200, response.status());
    }

    @Test
    void aMetaProtocolVersionMakesTheRequestStateless(
            @Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        final var request = new StubRequest(Map.of(), """
                        {"jsonrpc": "2.0", "id": 1, "method": "a/tool", "params": {"_meta": {
                          "io.modelcontextprotocol/protocolVersion": "2026-07-28",
                          "io.modelcontextprotocol/clientInfo": {"name": "test", "version": "1.0.0"},
                          "io.modelcontextprotocol/clientCapabilities": {}
                        }}}""");

        final var response =
                endpoint(jsons, container).handle(request).toCompletableFuture().join();

        assertEquals(200, response.status());
        assertNull(response.headers().get(SESSION_HEADER), "the modern client gets no session");
    }

    @Test
    void theSubscriptionIdOfAListenCallIsStashedOnTheRequest(
            @Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        final var request = new StubRequest(
                Map.of(MCPProtocol.METHOD_HEADER, "a/tool", MCPProtocol.PROTOCOL_VERSION_HEADER, "2026-07-28"), """
                        {"jsonrpc": "2.0", "id": 55, "method": "a/tool", "params": {"_meta": {
                          "io.modelcontextprotocol/protocolVersion": "2026-07-28",
                          "io.modelcontextprotocol/clientInfo": {"name": "test", "version": "1.0.0"},
                          "io.modelcontextprotocol/clientCapabilities": {}
                        }}}""");

        endpoint(jsons, container).handle(request).toCompletableFuture().join();

        assertEquals("55", request.attribute(MCPProtocol.SUBSCRIPTION_ID_ATTRIBUTE, String.class));
    }

    @Test
    void aCallWithoutAnIdDoesNotStashASubscriptionId(
            @Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        // a (stateless) notification has no JSON-RPC id, so there is nothing to stash
        final var request = new StubRequest(
                Map.of(MCPProtocol.METHOD_HEADER, "a/tool", MCPProtocol.PROTOCOL_VERSION_HEADER, "2026-07-28"), """
                        {"jsonrpc": "2.0", "method": "a/tool", "params": {"_meta": {
                          "io.modelcontextprotocol/protocolVersion": "2026-07-28",
                          "io.modelcontextprotocol/clientCapabilities": {}
                        }}}""");

        endpoint(jsons, container).handle(request).toCompletableFuture().join();

        assertNull(request.attribute(MCPProtocol.SUBSCRIPTION_ID_ATTRIBUTE, String.class));
    }

    @Test
    void anMcpMethodHeaderAloneMakesTheRequestStateless(
            @Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        // no protocol version header, but the mcp-method header marks a modern fire-and-forget client
        final var response = endpoint(jsons, container)
                .handle(new StubRequest(Map.of(MCPProtocol.METHOD_HEADER, "a/tool"), """
                        {"jsonrpc": "2.0", "id": 1, "method": "a/tool", "params": {}}"""))
                .toCompletableFuture()
                .join();

        assertEquals(200, response.status());
        assertNull(response.headers().get(SESSION_HEADER), "the mcp-method header client gets no session");
    }

    @Test
    void aStatelessRequestMissingTheMcpMethodHeaderIsRejected(
            @Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        // the stateless transport requires the Mcp-Method header on every request
        final var response = endpoint(jsons, container)
                .handle(new StubRequest(Map.of(MCPProtocol.PROTOCOL_VERSION_HEADER, "2026-07-28"), """
                        {"jsonrpc": "2.0", "id": 1, "method": "a/tool", "params": {"_meta": {
                          "io.modelcontextprotocol/protocolVersion": "2026-07-28",
                          "io.modelcontextprotocol/clientCapabilities": {}
                        }}}"""))
                .toCompletableFuture()
                .join();

        assertEquals(400, response.status());
        assertTrue(body(response).contains("" + MCPProtocol.HEADER_MISMATCH), body(response));
    }

    @Test
    void anMcpNameHeaderMismatchIsRejected(@Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        final var response = endpoint(jsons, container)
                .handle(new StubRequest(
                        Map.of(
                                MCPProtocol.METHOD_HEADER,
                                "tools/call",
                                MCPProtocol.NAME_HEADER,
                                "wrong_name",
                                MCPProtocol.PROTOCOL_VERSION_HEADER,
                                "2026-07-28"),
                        """
                                {"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": {"name": "right_name", "arguments": {}}}"""))
                .toCompletableFuture()
                .join();

        assertEquals(400, response.status());
        assertTrue(body(response).contains("" + MCPProtocol.HEADER_MISMATCH), body(response));
    }

    @Test
    void aMissingMcpNameHeaderIsRejected(@Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        // SEP-2243: a modern tools/call with params.name in the body must carry the Mcp-Name header
        final var response = endpoint(jsons, container)
                .handle(new StubRequest(
                        Map.of(
                                MCPProtocol.METHOD_HEADER,
                                "tools/call",
                                MCPProtocol.PROTOCOL_VERSION_HEADER,
                                "2026-07-28"),
                        """
                                {"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": {"name": "right_name", "arguments": {}}}"""))
                .toCompletableFuture()
                .join();

        assertEquals(400, response.status());
        assertTrue(body(response).contains("" + MCPProtocol.HEADER_MISMATCH), body(response));
    }

    @Test
    void aMissingMcpNameForAResourcesReadIsRejected(
            @Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        // SEP-2243: resources/read routes by params.uri, so the Mcp-Name header is required as well
        final var response = endpoint(jsons, container)
                .handle(new StubRequest(
                        Map.of(
                                MCPProtocol.METHOD_HEADER,
                                "resources/read",
                                MCPProtocol.PROTOCOL_VERSION_HEADER,
                                "2026-07-28"),
                        """
                                {"jsonrpc": "2.0", "id": 1, "method": "resources/read", "params": {"uri": "demo://greeting"}}"""))
                .toCompletableFuture()
                .join();

        assertEquals(400, response.status());
        assertTrue(body(response).contains("" + MCPProtocol.HEADER_MISMATCH), body(response));
    }

    @Test
    void aMissingMcpNameForATasksGetIsRejected(
            @Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        // SEP-2243: the tasks methods route by params.taskId, so the Mcp-Name header is required as well
        final var response = endpoint(jsons, container)
                .handle(new StubRequest(
                        Map.of(
                                MCPProtocol.METHOD_HEADER,
                                "tasks/get",
                                MCPProtocol.PROTOCOL_VERSION_HEADER,
                                "2026-07-28"),
                        """
                                {"jsonrpc": "2.0", "id": 1, "method": "tasks/get", "params": {"taskId": "42"}}"""))
                .toCompletableFuture()
                .join();

        assertEquals(400, response.status());
        assertTrue(body(response).contains("" + MCPProtocol.HEADER_MISMATCH), body(response));
    }

    @Test
    void aNonMapParamsDoesNotDemandANameHeader(
            @Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        // a tools/call without a params object has no name to match, so the Mcp-Name header is not required
        final var response = endpoint(jsons, container)
                .handle(new StubRequest(
                        Map.of(
                                MCPProtocol.METHOD_HEADER,
                                "tools/call",
                                MCPProtocol.PROTOCOL_VERSION_HEADER,
                                "2026-07-28"),
                        """
                                {"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": 42}"""))
                .toCompletableFuture()
                .join();

        assertEquals(400, response.status());
        assertTrue(!body(response).contains("" + MCPProtocol.HEADER_MISMATCH), body(response));
    }

    @Test
    void aProtocolVersionHeaderMismatchingTheMetaIsRejected(
            @Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        // a header version which disagrees with the body _meta version is a header mismatch
        final var response = endpoint(jsons, container)
                .handle(new StubRequest(
                        Map.of(MCPProtocol.METHOD_HEADER, "a/tool", MCPProtocol.PROTOCOL_VERSION_HEADER, "2026-07-28"),
                        """
                                {"jsonrpc": "2.0", "id": 1, "method": "a/tool", "params": {"_meta": {"io.modelcontextprotocol/protocolVersion": "2025-11-25"}}}"""))
                .toCompletableFuture()
                .join();

        assertEquals(400, response.status());
        assertTrue(body(response).contains("" + MCPProtocol.HEADER_MISMATCH), body(response));
    }

    @Test
    void aStrictModernRequestWithNoMetaIsRejected(
            @Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        // the strict 2026-07-28 transport requires the protocolVersion and clientCapabilities _meta fields
        final var response = endpoint(jsons, container)
                .handle(new StubRequest(
                        Map.of(MCPProtocol.METHOD_HEADER, "a/tool", MCPProtocol.PROTOCOL_VERSION_HEADER, "2026-07-28"),
                        """
                                {"jsonrpc": "2.0", "id": 1, "method": "a/tool", "params": {}}"""))
                .toCompletableFuture()
                .join();

        assertEquals(400, response.status());
        assertTrue(body(response).contains("\"code\":-32602"), body(response));
    }

    @Test
    void aStrictModernRequestWithoutClientCapabilitiesIsRejected(
            @Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        // clientInfo alone is not enough on the strict transport, clientCapabilities is required too
        final var response = endpoint(jsons, container)
                .handle(new StubRequest(
                        Map.of(MCPProtocol.METHOD_HEADER, "a/tool", MCPProtocol.PROTOCOL_VERSION_HEADER, "2026-07-28"),
                        """
                                {"jsonrpc": "2.0", "id": 1, "method": "a/tool", "params": {"_meta": {
                                  "io.modelcontextprotocol/protocolVersion": "2026-07-28",
                                  "io.modelcontextprotocol/clientInfo": {"name": "test", "version": "1.0.0"}
                                }}}"""))
                .toCompletableFuture()
                .join();

        assertEquals(400, response.status());
        assertTrue(body(response).contains("\"code\":-32602"), body(response));
    }

    @Test
    void anUnknownMethodOverTheStatelessProtocolIsANotFound(
            @Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        // -32601 is the one code mapped to HTTP 404, the client can then forget the session/endpoint
        final var response = endpoint(jsons, container)
                .handle(new StubRequest(
                        Map.of(MCPProtocol.METHOD_HEADER, "nope", MCPProtocol.PROTOCOL_VERSION_HEADER, "2026-07-28"),
                        """
                                {"jsonrpc": "2.0", "id": 1, "method": "nope", "params": {"_meta": {
                                  "io.modelcontextprotocol/protocolVersion": "2026-07-28",
                                  "io.modelcontextprotocol/clientInfo": {"name": "test", "version": "1.0.0"},
                                  "io.modelcontextprotocol/clientCapabilities": {}
                                }}}"""))
                .toCompletableFuture()
                .join();

        assertEquals(404, response.status());
    }

    @Test
    void aMatchingMcpNameHeaderIsAccepted(@Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        final var response = endpoint(jsons, container)
                .handle(new StubRequest(
                        Map.of(
                                MCPProtocol.METHOD_HEADER,
                                "tools/call",
                                MCPProtocol.NAME_HEADER,
                                "right_name",
                                MCPProtocol.PROTOCOL_VERSION_HEADER,
                                "2026-07-28"),
                        """
                                {"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": {"name": "right_name", "arguments": {}, "_meta": {
                                  "io.modelcontextprotocol/protocolVersion": "2026-07-28",
                                  "io.modelcontextprotocol/clientInfo": {"name": "test", "version": "1.0.0"},
                                  "io.modelcontextprotocol/clientCapabilities": {}
                                }}}"""))
                .toCompletableFuture()
                .join();

        assertEquals(200, response.status(), body(response));
    }

    @Test
    void aMatchingMethodHeaderIsAccepted(@Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        final var response = endpoint(jsons, container)
                .handle(new StubRequest(
                        Map.of(MCPProtocol.METHOD_HEADER, "a/tool", MCPProtocol.PROTOCOL_VERSION_HEADER, "2026-07-28"),
                        """
                                {"jsonrpc": "2.0", "id": 1, "method": "a/tool", "params": {"_meta": {
                                  "io.modelcontextprotocol/protocolVersion": "2026-07-28",
                                  "io.modelcontextprotocol/clientInfo": {"name": "test", "version": "1.0.0"},
                                  "io.modelcontextprotocol/clientCapabilities": {}
                                }}}"""))
                .toCompletableFuture()
                .join();

        assertEquals(200, response.status());
        assertTrue(body(response).contains("\"result\""), body(response));
    }

    @Test
    void aMethodHeaderIgnoresABatch(@Fusion final JsonMapper jsons, @Fusion final RuntimeContainer container) {
        // the header is only validated against a single call - a batch cannot be checked
        final var response = endpoint(jsons, container)
                .handle(new StubRequest(
                        Map.of(MCPProtocol.METHOD_HEADER, "a/tool", MCPProtocol.PROTOCOL_VERSION_HEADER, "2026-07-28"),
                        """
                                [
                                  {"jsonrpc": "2.0", "id": 1, "method": "a/tool", "params": {"_meta": {
                                     "io.modelcontextprotocol/protocolVersion": "2026-07-28",
                                     "io.modelcontextprotocol/clientInfo": {"name": "test", "version": "1.0.0"},
                                     "io.modelcontextprotocol/clientCapabilities": {}
                                  }}},
                                  {"jsonrpc": "2.0", "id": 2, "method": "a/tool", "params": {}}
                                ]"""))
                .toCompletableFuture()
                .join();

        assertEquals(200, response.status());
    }

    /**
     * @return the response payload, it can only be read once: it is a publisher, not a buffer.
     */
    private String body(final Response response) {
        if (response.body() == null) {
            return "";
        }
        final var subscriber = SseSubscriber.strict(Long.MAX_VALUE);
        response.body().subscribe(subscriber);
        return String.join("", subscriber.received());
    }

    private MCPEndpoint endpoint(final JsonMapper jsons, final RuntimeContainer container) {
        return endpoint(jsons, container, true);
    }

    private MCPEndpoint endpoint(
            final JsonMapper jsons, final RuntimeContainer container, final boolean rejectNonLocalHosts) {
        return new MCPEndpoint(
                new JsonRpcHandler(container, jsons, registry()),
                jsons,
                sessions(jsons),
                configuration(false, rejectNonLocalHosts));
    }

    private JsonRpcRegistry registry() {
        return new JsonRpcRegistry(List.of(
                StubJsonRpcMethod.tool("a/tool", params -> completedFuture(Map.of("ok", true))),
                StubJsonRpcMethod.tool("tools/call", params -> completedFuture(Map.of("ok", true))),
                StubJsonRpcMethod.plain("initialize")));
    }

    private MCPSessions sessions(final JsonMapper jsons) {
        return new MCPSessions(jsons, configuration(false));
    }

    private MCPConfiguration configuration(final boolean requireSession) {
        return configuration(requireSession, true);
    }

    private MCPConfiguration configuration(final boolean requireSession, final boolean rejectNonLocalHosts) {
        return new MCPConfiguration(
                "n", "t", "v", "i", 0, 30, requireSession, 30000L, "private", "", rejectNonLocalHosts);
    }
}
