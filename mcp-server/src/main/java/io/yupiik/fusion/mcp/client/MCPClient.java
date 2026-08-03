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
package io.yupiik.fusion.mcp.client;

import static java.net.http.HttpResponse.BodyHandlers.ofInputStream;
import static java.net.http.HttpResponse.BodyHandlers.ofString;
import static java.nio.charset.StandardCharsets.UTF_8;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.mcp.protocol.MCPProtocol;
import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/**
 * A minimal MCP client for the streamable HTTP transport: it drives a MCP server, which is what testing your own
 * server needs - and what calling another MCP server needs too.
 * <p>
 * It keeps the {@code Mcp-Session-Id} header across the calls, like a real client does, and can read the SSE channel
 * to assert the server to client messages. Every call is asynchronous, which is what a MCP interaction needs: a tool
 * using sampling or elicitation only answers once the client responded on the SSE channel, so its invocation cannot
 * be awaited first.
 * <pre>{@code
 * try (final var client = new MCPClient(endpoint, HttpClient.newHttpClient(), jsonMapper)) {
 *     final var tools = client.initialize("{\"sampling\":{}}")
 *             .thenCompose(ignored -> client.call(2, "tools/list", "{}"))
 *             .toCompletableFuture()
 *             .join();
 * }
 * }</pre>
 * Bodies and parameters accept either a {@link CharSequence} - used as is, which keeps the JSON readable in a test -
 * or any object, then serialized with the {@link JsonMapper} passed to the constructor.
 * <p>
 * It has no test framework dependency on purpose, use the assertions of your choice on the returned
 * {@link HttpResponse}s.
 */
public class MCPClient implements AutoCloseable {
    private static final String DATA_PREFIX = "data: ";

    private final URI endpoint;
    private final HttpClient http;
    private final JsonMapper jsonMapper;

    private volatile String session;
    private volatile BufferedReader sse;

    /**
     * @param endpoint the MCP endpoint, {@code http://localhost:8080/mcp} for example.
     * @param http     the client to use, it is not closed by this class.
     */
    public MCPClient(final URI endpoint, final HttpClient http) {
        this(endpoint, http, null);
    }

    /**
     * @param endpoint   the MCP endpoint, {@code http://localhost:8080/mcp} for example.
     * @param http       the client to use, it is not closed by this class.
     * @param jsonMapper the mapper used to serialize the bodies which are not a {@link CharSequence}, it can be
     *                   {@code null} when only strings are sent.
     */
    public MCPClient(final URI endpoint, final HttpClient http, final JsonMapper jsonMapper) {
        this.endpoint = endpoint;
        this.http = http;
        this.jsonMapper = jsonMapper;
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
     * Runs the initialization handshake - {@code initialize} then {@code notifications/initialized} - and keeps the
     * returned session identifier for the next calls.
     *
     * @param clientCapabilities what the client supports, a JSON string ({@code {}} for nothing) or any object.
     * @return the {@code initialize} response, once the handshake is complete.
     */
    public CompletionStage<HttpResponse<String>> initialize(final Object clientCapabilities) {
        return post("""
                {
                  "jsonrpc": "2.0",
                  "id": 1,
                  "method": "initialize",
                  "params": {
                    "protocolVersion": "%s",
                    "capabilities": %s,
                    "clientInfo": {"name": "fusion-mcp-test-client", "title": "Fusion MCP Test Client", "version": "1.0.0"}
                  }
                }""".formatted(MCPProtocol.LATEST_VERSION, body(clientCapabilities)))
                .thenCompose(response -> {
                    response.headers().firstValue(MCPProtocol.SESSION_HEADER).ifPresent(this::session);
                    return notify("notifications/initialized", null).thenApply(ignored -> response);
                });
    }

    /**
     * Same as {@link #initialize(Object)} without any client capability.
     */
    public CompletionStage<HttpResponse<String>> initialize() {
        return initialize("{}");
    }

    /**
     * Sends a JSON-RPC request.
     *
     * @param id     the request identifier.
     * @param method the MCP method, {@code tools/call} for example.
     * @param params its parameters, a JSON string or any object.
     * @return the server response.
     */
    public CompletionStage<HttpResponse<String>> call(final int id, final String method, final Object params) {
        return post("""
                {"jsonrpc": "2.0", "id": %d, "method": "%s", "params": %s}""".formatted(id, method, body(params)));
    }

    /**
     * Sends a JSON-RPC notification, i.e. a request without identifier - the server answers {@code 202}.
     *
     * @param method the MCP notification, {@code notifications/initialized} for example.
     * @param params its parameters, a JSON string or any object, {@code null} for none.
     * @return the server response.
     */
    public CompletionStage<HttpResponse<String>> notify(final String method, final Object params) {
        return post(params == null ? """
                        {"jsonrpc": "2.0", "method": "%s"}""".formatted(method) : """
                        {"jsonrpc": "2.0", "method": "%s", "params": %s}""".formatted(method, body(params)));
    }

    /**
     * Answers a request the server sent on the SSE channel - sampling, elicitation, roots.
     *
     * @param id     the identifier of the server request.
     * @param result its result, a JSON string or any object.
     * @return the server response, {@code 202} when the request was pending.
     */
    public CompletionStage<HttpResponse<String>> respond(final long id, final Object result) {
        return post("""
                {"jsonrpc": "2.0", "id": %d, "result": %s}""".formatted(id, body(result)));
    }

    /**
     * @param body a raw JSON-RPC message - a request, a notification, a response or a batch - as a JSON string or any
     *             object.
     * @return the server response, {@code 202} with an empty body for a notification or a response.
     */
    public CompletionStage<HttpResponse<String>> post(final Object body) {
        return http.sendAsync(request(body(body)), ofString());
    }

    /**
     * Opens the {@code GET /mcp} stream. The messages published before this call are not lost, they are buffered per
     * session.
     *
     * @return an iterator on the raw SSE lines.
     */
    public CompletionStage<Iterator<String>> openSse() {
        return openSse(null);
    }

    /**
     * @param lastEventId the {@code Last-Event-ID} to resume from, {@code null} to start from now on.
     * @return an iterator on the raw SSE lines.
     */
    public CompletionStage<Iterator<String>> openSse(final String lastEventId) {
        closeStream(); // at most one stream at a time, else the abandoned one is written to until the socket breaks

        final var builder = HttpRequest.newBuilder(endpoint).GET().header("accept", "text/event-stream");
        if (lastEventId != null) {
            builder.header(MCPProtocol.LAST_EVENT_ID_HEADER, lastEventId);
        }
        withSession(builder);
        // an input stream and not ofLines(): closing it cancels the exchange, which is what abandoning a stream
        // requires - else the connection stays busy and the next request waits for it
        return http.sendAsync(builder.build(), ofInputStream()).thenApply(response -> {
            if (response.statusCode() != 200) {
                quietClose(response.body());
                throw new IllegalStateException("Can't open the SSE stream: HTTP " + response.statusCode());
            }
            final var reader = new BufferedReader(new InputStreamReader(response.body(), UTF_8));
            sse = reader;
            return reader.lines().iterator();
        });
    }

    /**
     * Reads the next message of a stream opened with {@link #openSse()}, skipping the event ids, names and the
     * keep-alive comments.
     *
     * @param lines the stream iterator.
     * @return the JSON of the next server to client message, it completes when the server sends one.
     */
    public CompletionStage<String> nextMessage(final Iterator<String> lines) {
        final var promise = new CompletableFuture<String>();
        // a dedicated virtual thread and not the common pool: reading the stream blocks until the server sends
        // something, which would starve a shared pool
        Thread.ofVirtual().name("mcp-client-sse-reader").start(() -> {
            try {
                while (lines.hasNext()) {
                    final var line = lines.next();
                    if (line.startsWith(DATA_PREFIX)) {
                        promise.complete(line.substring(DATA_PREFIX.length()));
                        return;
                    }
                }
                promise.completeExceptionally(new NoSuchElementException("The SSE stream ended before any message"));
            } catch (final RuntimeException re) {
                promise.completeExceptionally(re);
            }
        });
        return promise;
    }

    /**
     * Terminates the session, i.e. {@code DELETE /mcp}.
     */
    public CompletionStage<HttpResponse<String>> terminate() {
        final var builder = HttpRequest.newBuilder(endpoint).DELETE();
        withSession(builder);
        return http.sendAsync(builder.build(), ofString());
    }

    /**
     * Abandons the SSE stream then ends the session with {@link #terminate()} when there is one, i.e. the client
     * leaves nothing behind: the server releases the session and completes its channel.
     * <p>
     * A failing {@code DELETE} is ignored - the session can legitimately be gone already, and the server can even be
     * stopped - so closing is always safe.
     */
    @Override
    public void close() {
        closeStream();
        if (session != null) {
            try {
                // reads the session header so it must run before dropping it, and it is bounded: a client must not
                // hang on a server which is gone
                terminate()
                        .toCompletableFuture()
                        .orTimeout(30, TimeUnit.SECONDS)
                        .join();
            } catch (final RuntimeException re) {
                // nothing to recover: the session is being dropped anyway
            } finally {
                session = null;
            }
        }
    }

    /**
     * Abandons the SSE stream, if any, without touching the session - reopening one keeps working.
     */
    private void closeStream() {
        final var current = sse;
        if (current != null) {
            sse = null;
            quietClose(current);
        }
    }

    private void quietClose(final Closeable closeable) {
        try {
            closeable.close();
        } catch (final IOException ioe) {
            // the stream is being abandoned, there is nothing to recover
        }
    }

    /**
     * @param body a {@link CharSequence} - used as is - or any object, then serialized with the mapper.
     * @return the JSON to send.
     */
    private String body(final Object body) {
        if (body == null) {
            return "null";
        }
        if (body instanceof CharSequence charSequence) {
            return charSequence.toString();
        }
        if (jsonMapper == null) {
            throw new IllegalStateException("No JsonMapper set, pass one to the constructor to send a '"
                    + body.getClass().getName() + "' or pass its JSON as a String");
        }
        return jsonMapper.toString(body);
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
