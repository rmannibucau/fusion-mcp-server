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
    public static final String LATEST_VERSION = "2025-06-18";

    /**
     * The versions {@code initialize} can negotiate, newest first.
     * <p>
     * Only revisions using the streamable HTTP transport are listed: {@code 2024-11-05} and older use the
     * deprecated HTTP+SSE transport - two endpoints - which this server does not implement.
     */
    public static final List<String> SUPPORTED_VERSIONS = List.of(LATEST_VERSION, "2025-03-26");

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
