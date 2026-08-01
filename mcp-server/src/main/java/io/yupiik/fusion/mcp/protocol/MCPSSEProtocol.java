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
import io.yupiik.fusion.framework.build.api.http.HttpMatcher;
import io.yupiik.fusion.http.server.api.Request;
import io.yupiik.fusion.http.server.api.Response;
import jakarta.servlet.http.HttpServletRequest;

import java.util.concurrent.CompletionStage;
import java.util.logging.Logger;

import static io.yupiik.fusion.mcp.protocol.MCPProtocol.LAST_EVENT_ID_HEADER;
import static io.yupiik.fusion.mcp.protocol.MCPProtocol.SESSION_HEADER;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.logging.Level.FINE;

/**
 * The {@code GET /mcp} and {@code DELETE /mcp} halves of the MCP streamable HTTP transport:
 * <ul>
 *     <li>{@code GET} opens the {@code text/event-stream} channel the server pushes its notifications and requests
 *     to - logging, {@code list_changed}, progress, sampling, elicitation and roots,</li>
 *     <li>{@code DELETE} terminates the session.</li>
 * </ul>
 *
 * @see MCPEndpoint for the {@code POST} part.
 */
@ApplicationScoped
public class MCPSSEProtocol {
    private final Logger logger = Logger.getLogger(MCPSSEProtocol.class.getName());

    private final MCPSessions sessions;

    protected MCPSSEProtocol() {
        this(null);
    }

    public MCPSSEProtocol(final MCPSessions sessions) {
        this.sessions = sessions;
    }

    @HttpMatcher(methods = "GET", path = "/mcp")
    public CompletionStage<Response> sse(final Request request) {
        final var id = request.header(SESSION_HEADER);
        if (id != null && sessions.find(id).isEmpty()) {
            return completedFuture(Response.of()
                    .status(404)
                    .header("content-type", "text/plain")
                    .body("Unknown or expired MCP session")
                    .build());
        }

        // the client of an unknown session gets a valid - but silent - stream, it is what a client opening the
        // stream before initialize asks for
        final var session = sessions.of(request);
        final var sse = session.sse();
        final var lastEventId = request.header(LAST_EVENT_ID_HEADER);
        if (lastEventId != null) {
            try {
                sse.replayFrom(Long.parseLong(lastEventId.strip()));
            } catch (final NumberFormatException nfe) {
                logger.log(FINE, () -> "Ignoring invalid " + LAST_EVENT_ID_HEADER + ": '" + lastEventId + "'");
            }
        }

        // note: the bus greets every new subscriber with a comment, which commits the response, so a client opening
        // the stream never waits for the first message to know it is live

        // the stream stays open until the client closes it or the session is dropped
        request.unwrap(HttpServletRequest.class).getAsyncContext().setTimeout(0);

        return completedFuture(Response.of()
                .status(200)
                .header("content-type", "text/event-stream;charset=utf-8")
                .header("cache-control", "no-cache")
                .header("connection", "keep-alive")
                .body(sse)
                .build());
    }

    @HttpMatcher(methods = "DELETE", path = "/mcp")
    public CompletionStage<Response> delete(final Request request) {
        final var id = request.header(SESSION_HEADER);
        if (id == null) {
            return completedFuture(Response.of()
                    .status(400)
                    .header("content-type", "text/plain")
                    .body("Missing " + SESSION_HEADER + " header")
                    .build());
        }
        if (!sessions.drop(id)) {
            return completedFuture(Response.of()
                    .status(404)
                    .header("content-type", "text/plain")
                    .body("Unknown or expired MCP session")
                    .build());
        }
        return completedFuture(Response.of().status(204).build());
    }
}
