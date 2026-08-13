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

import java.util.List;

/**
 * The MCP protocol constants: supported versions and transport header names.
 */
public final class MCPProtocol {
    /**
     * The version the wire model of this implementation is generated from, i.e. the one used when the client asks
     * for a version we do not know.
     */
    public static final String LATEST_VERSION = "2025-11-25";

    /**
     * The versions {@code initialize} can negotiate, newest first.
     * <p>
     * Only revisions using the streamable HTTP transport are listed: {@code 2024-11-05} and older use the
     * deprecated HTTP+SSE transport - two endpoints - which this server does not implement.
     */
    public static final List<String> SUPPORTED_VERSIONS = List.of(LATEST_VERSION, "2025-06-18", "2025-03-26");

    /**
     * The versions of the stateless protocol this server supports.
     * <p>
     * A stateless client discovers the server with {@code server/discover} - no {@code initialize} handshake and no
     * session - lists the changes over {@code subscriptions/listen} and reads a {@code resultType}/{@code ttlMs}/
     * {@code cacheScope} on every result.
     */
    public static final List<String> STATELESS_VERSIONS = List.of("2026-07-28");

    /**
     * The stateless protocol versions the server treats with strict {@code 2026-07-28} semantics: required
     * per-request {@code _meta}, mandatory {@code resultType}, and the {@code 2026-07-28} error-code policy. A
     * request opened on any other version - legacy, or a tolerated legacy request - stays tolerant with its own rules.
     */
    public static final List<String> STRICT_STATELESS_VERSIONS = List.of("2026-07-28");

    /**
     * Header carrying the session identifier, it is returned by {@code initialize} and sent back by the client on
     * every subsequent request.
     */
    public static final String SESSION_HEADER = "mcp-session-id";

    /**
     * Header the client uses to state the version it negotiated, it must be one of {@link #SUPPORTED_VERSIONS}.
     */
    public static final String PROTOCOL_VERSION_HEADER = "mcp-protocol-version";

    /**
     * Standard SSE header enabling a client to resume a stream where it stopped.
     */
    public static final String LAST_EVENT_ID_HEADER = "last-event-id";

    /**
     * Header a stateless client sends to state which JSON-RPC method a fire-and-forget body carries, it must match
     * the body {@code method} when both are present - a mismatch is a {@link #HEADER_MISMATCH}.
     */
    public static final String METHOD_HEADER = "mcp-method";

    /**
     * Header a stateless client may send to name the stream it opens, it is only informative.
     */
    public static final String NAME_HEADER = "mcp-name";

    /**
     * The {@code _meta} key carrying the protocol version of a stateless request - when it is absent the version is
     * read from the {@link #PROTOCOL_VERSION_HEADER} header.
     */
    public static final String PROTOCOL_VERSION_META = "io.modelcontextprotocol/protocolVersion";

    /**
     * The {@code _meta} key carrying what the stateless client can do.
     */
    public static final String CLIENT_CAPABILITIES_META = "io.modelcontextprotocol/clientCapabilities";

    /**
     * The {@code _meta} key carrying which stateless client is connecting.
     */
    public static final String CLIENT_INFO_META = "io.modelcontextprotocol/clientInfo";

    /**
     * The {@code _meta} key carrying the log level the stateless client wants to receive.
     */
    public static final String LOG_LEVEL_META = "io.modelcontextprotocol/logLevel";

    /**
     * The {@code _meta} key carrying the progress token of a stateless request.
     */
    public static final String PROGRESS_TOKEN_META = "io.modelcontextprotocol/progressToken";

    /**
     * The {@code _meta} key of a stateless result carrying the server identity.
     */
    public static final String SERVER_INFO_META = "io.modelcontextprotocol/serverInfo";

    /**
     * The {@code _meta} key of a stateless notification carrying the subscription it targets.
     */
    public static final String SUBSCRIPTION_ID_META = "io.modelcontextprotocol/subscriptionId";

    /**
     * The {@code _meta} key a stateless client echoes to resume a multi round-trip interaction, it is the
     * {@code requestState} a previous {@code input_required} result returned.
     */
    public static final String REQUEST_STATE_META = "io.modelcontextprotocol/requestState";

    /**
     * The {@code _meta} key carrying the answers of a client to the {@code inputRequests} of a previous
     * {@code input_required} result.
     */
    public static final String INPUT_RESPONSES_META = "io.modelcontextprotocol/inputResponses";

    /**
     * Request attribute the transport uses to expose the JSON-RPC id of the {@code subscriptions/listen} call to the
     * notifier, it is the {@code subscriptionId} the client correlates the notifications with.
     */
    public static final String SUBSCRIPTION_ID_ATTRIBUTE = "io.yupiik.fusion.mcp.subscriptionId";

    /**
     * Request attribute holding the parsed {@code _meta} envelope of a stateless request, exposed to the JSON-RPC
     * methods.
     */
    public static final String REQUEST_META_ATTRIBUTE = "io.yupiik.fusion.mcp.requestMeta";

    /**
     * Request attribute holding the state a client sent back with {@code _meta.io.modelcontextprotocol/requestState},
     * decoded with the configured {@code io.yupiik.fusion.mcp.spi.MCPRequestStateCodec}.
     */
    public static final String REQUEST_STATE_ATTRIBUTE = "io.yupiik.fusion.mcp.requestState";

    /**
     * Request attribute holding the {@code _meta.io.modelcontextprotocol/inputResponses} a client sent back to answer
     * a previous {@code input_required} result.
     */
    public static final String INPUT_RESPONSES_ATTRIBUTE = "io.yupiik.fusion.mcp.inputResponses";

    /**
     * The {@code Mcp-Method}/{@code Mcp-Name} headers do not match the body, or the {@code _meta} envelope contradicts
     * the headers.
     */
    public static final int HEADER_MISMATCH = -32020;

    /**
     * The client called a method requiring a capability it did not declare in its capabilities.
     */
    public static final int MISSING_CLIENT_CAPABILITY = -32021;

    /**
     * The {@code _meta} protocol version is not a version this server supports.
     */
    public static final int UNSUPPORTED_PROTOCOL_VERSION = -32022;

    /**
     * Internal code a tool throws with an {@code io.yupiik.fusion.mcp.exception.InputRequiredException} to ask for an
     * input over the multi round-trip protocol, it is never sent as is: the transport converts it to an
     * {@code input_required} result carrying {@code inputRequests} and {@code requestState}.
     */
    public static final int INPUT_REQUIRED = -32019;

    private MCPProtocol() {
        // no-op
    }

    /**
     * @param version the version the client asks for.
     * @return the version to negotiate: the requested one when it is supported, else {@link #LATEST_VERSION} as the
     * specification requires - the client then decides if it can go on or not.
     */
    public static String negotiate(final String version) {
        return SUPPORTED_VERSIONS.contains(version) ? version : LATEST_VERSION;
    }
}
