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
package io.yupiik.fusion.mcp.testing;

import io.yupiik.fusion.mcp.protocol.MCPProtocol;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import static java.net.http.HttpResponse.BodyHandlers.ofLines;
import static java.net.http.HttpResponse.BodyHandlers.ofString;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * A minimal MCP client - streamable HTTP transport - to drive a MCP server from a test.
 * <p>
 * It keeps the {@code Mcp-Session-Id} header across the calls, like a real client does, and can read the SSE channel
 * to assert the server to client messages:
 * <pre>{@code
 * try (final var client = new MCPClient(endpoint, HttpClient.newHttpClient())) {
 *     client.initialize("{\"sampling\":{}}");
 *     final var tools = client.call(2, "tools/list", "{}");
 *     final var stream = client.openSse();
 *     // ... assert on client.nextMessage(stream)
 * }
 * }</pre>
 * It has no test framework dependency on purpose, use the assertions of your choice on the returned
 * {@link HttpResponse}s.
 */
public class MCPClient implements AutoCloseable {
    private final URI endpoint;
    private final HttpClient http;

    private volatile String session;
    private HttpResponse<Stream<String>> sse;

    /**
     * @param endpoint the MCP endpoint, {@code http://localhost:8080/mcp} for example.
     * @param http     the client to use, it is not closed by this class.
     */
    public MCPClient(final URI endpoint, final HttpClient http) {
        this.endpoint = endpoint;
        this.http = http;
    }

    /**
     * @return the session identifier {@code initialize} returned, {@code null} before it was called.
     */
    public String session() {
        return session;
    }

    /**
     * Forces the session identifier sent in the {@code Mcp-Session-Id} header, mainly to test the invalid cases.
     *
     * @param session the identifier to send, {@code null} to send none.
     * @return this.
     */
    public MCPClient session(final String session) {
        this.session = session;
        return this;
    }

    /**
     * Runs the initialization handshake: {@code initialize} then {@code notifications/initialized}, and keeps the
     * returned session identifier for the next calls.
     *
     * @param clientCapabilities the JSON of the client capabilities, {@code {}} when it supports nothing.
     * @return the {@code initialize} response.
     */
    public HttpResponse<String> initialize(final String clientCapabilities) throws IOException, InterruptedException {
        final var response = post("""
                {
                  "jsonrpc": "2.0",
                  "id": 1,
                  "method": "initialize",
                  "params": {
                    "protocolVersion": "%s",
                    "capabilities": %s,
                    "clientInfo": {"name": "fusion-mcp-test-client", "title": "Fusion MCP Test Client", "version": "1.0.0"}
                  }
                }""".formatted(MCPProtocol.LATEST_VERSION, clientCapabilities));
        response.headers().firstValue(MCPProtocol.SESSION_HEADER).ifPresent(this::session);
        post("""
                {"jsonrpc": "2.0", "method": "notifications/initialized"}""");
        return response;
    }

    /**
     * Same as {@link #initialize(String)} without any client capability.
     */
    public HttpResponse<String> initialize() throws IOException, InterruptedException {
        return initialize("{}");
    }

    /**
     * @param id     the JSON-RPC request identifier.
     * @param method the MCP method, {@code tools/call} for example.
     * @param params the JSON of the parameters.
     * @return the server response.
     */
    public HttpResponse<String> call(final int id, final String method, final String params) throws IOException, InterruptedException {
        return post("""
                {"jsonrpc": "2.0", "id": %d, "method": "%s", "params": %s}""".formatted(id, method, params));
    }

    /**
     * Same as {@link #call(int, String, String)} but without awaiting the response, which is what a request needing
     * an interaction on the SSE channel - sampling, elicitation, roots - requires.
     */
    public CompletableFuture<HttpResponse<String>> callAsync(final int id, final String method, final String params) {
        return postAsync("""
                {"jsonrpc": "2.0", "id": %d, "method": "%s", "params": %s}""".formatted(id, method, params));
    }

    /**
     * @param body a raw JSON-RPC message: a request, a notification, a response to a server request or a batch.
     * @return the server response, {@code 202} with an empty body for a notification or a response.
     */
    public HttpResponse<String> post(final String body) throws IOException, InterruptedException {
        return http.send(request(body), ofString());
    }

    public CompletableFuture<HttpResponse<String>> postAsync(final String body) {
        return http.sendAsync(request(body), ofString());
    }

    /**
     * Opens the {@code GET /mcp} stream. The messages published before this call are not lost, they are buffered per
     * session.
     *
     * @return an iterator on the raw SSE lines.
     */
    public Iterator<String> openSse() throws Exception {
        return openSse(null);
    }

    /**
     * @param lastEventId the {@code Last-Event-ID} to resume from, {@code null} to start from now on.
     * @return an iterator on the raw SSE lines.
     */
    public Iterator<String> openSse(final String lastEventId) throws Exception {
        final var builder = HttpRequest.newBuilder(endpoint)
                .GET()
                .header("accept", "text/event-stream");
        if (lastEventId != null) {
            builder.header(MCPProtocol.LAST_EVENT_ID_HEADER, lastEventId);
        }
        withSession(builder);
        sse = http.sendAsync(builder.build(), ofLines()).get(30, SECONDS);
        if (sse.statusCode() != 200) {
            throw new IllegalStateException("Can't open the SSE stream: HTTP " + sse.statusCode());
        }
        return sse.body().iterator();
    }

    /**
     * Reads the next message of a stream opened with {@link #openSse()}, skipping the event ids, names and the
     * keep-alive comments. It blocks until a message arrives or the stream ends.
     *
     * @param lines the stream iterator.
     * @return the JSON of the next server to client message.
     */
    public String nextMessage(final Iterator<String> lines) {
        while (lines.hasNext()) {
            final var line = lines.next();
            if (line.startsWith("data: ")) {
                return line.substring("data: ".length());
            }
        }
        throw new NoSuchElementException("The SSE stream ended before any message");
    }

    /**
     * Terminates the session, i.e. {@code DELETE /mcp}.
     */
    public HttpResponse<String> terminate() throws IOException, InterruptedException {
        final var builder = HttpRequest.newBuilder(endpoint).DELETE();
        withSession(builder);
        return http.send(builder.build(), ofString());
    }

    @Override
    public void close() {
        if (sse != null) {
            sse.body().close();
            sse = null;
        }
    }

    private HttpRequest request(final String body) {
        final var builder = HttpRequest.newBuilder(endpoint)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                // a MCP client must accept both, the server picks the one it needs
                .header("accept", "application/json, text/event-stream")
                .header("content-type", "application/json");
        withSession(builder);
        return builder.build();
    }

    private void withSession(final HttpRequest.Builder builder) {
        final var current = session;
        if (current != null) {
            builder.header(MCPProtocol.SESSION_HEADER, current);
        }
    }
}
