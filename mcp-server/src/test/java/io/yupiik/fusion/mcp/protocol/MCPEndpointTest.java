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
        return new MCPEndpoint(
                new JsonRpcHandler(container, jsons, registry()), jsons, sessions(jsons), configuration(false));
    }

    private JsonRpcRegistry registry() {
        return new JsonRpcRegistry(List.of(
                StubJsonRpcMethod.tool("a/tool", params -> completedFuture(Map.of("ok", true))),
                StubJsonRpcMethod.plain("initialize")));
    }

    private MCPSessions sessions(final JsonMapper jsons) {
        return new MCPSessions(jsons, configuration(false));
    }

    private MCPConfiguration configuration(final boolean requireSession) {
        return new MCPConfiguration("n", "t", "v", "i", 0, 30, requireSession);
    }
}
