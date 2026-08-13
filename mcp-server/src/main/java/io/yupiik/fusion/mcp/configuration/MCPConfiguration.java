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
package io.yupiik.fusion.mcp.configuration;

import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.configuration.RootConfiguration;

/**
 * The MCP server configuration, all the keys are prefixed with {@code fusion.mcp.} and can be set with a system
 * property, an environment variable ({@code FUSION_MCP_...}) or any Fusion {@code ConfigurationSource}.
 *
 * @param name                 the programmatic server name the client sees in {@code initialize}.
 * @param title                the human oriented server name.
 * @param version              the server version.
 * @param instructions         how the model should use this server, it is sent in the {@code initialize} result.
 * @param sessionTimeout       idle duration, in seconds, after which a session is dropped - {@code 0} disables the
 *                             expiration.
 * @param clientRequestTimeout how long, in seconds, the server awaits a client response for a sampling, elicitation
 *                             or roots request.
 * @param requireSession       when {@code true} every request but {@code initialize} must carry a valid
 *                             {@code Mcp-Session-Id} header, else the server answers a {@code 400}. Keep it
 *                             {@code false} to also accept the clients ignoring sessions.
 * @param cacheTtlMs           how long, in milliseconds, a stateless client may cache a list result, it is what the
 *                             {@code ttlMs} result field advertises.
 * @param cacheScope           the scope a stateless client may share a cached result at ({@code private},
 *                             {@code server} or {@code global}), it is what the {@code cacheScope} result field
 *                             advertises.
 * @param requestStateSecret   the optional secret signing the {@code requestState} tokens of the multi round-trip
 *                             interactions with HMAC-SHA256, leave it empty for the opaque - unsigned - codec.
 * @param rejectNonLocalHosts  when {@code true} the transport answers a {@code 403} to the requests whose
 *                             {@code Host}/{@code Origin} headers are not loopback addresses, the DNS rebinding
 *                             protection.
 */
@RootConfiguration("fusion.mcp")
public record MCPConfiguration(
        @Property(defaultValue = "\"fusion-mcp-server\"", documentation = "Programmatic server name.")
        String name,

        @Property(defaultValue = "\"Fusion MCP Server\"", documentation = "Human readable server name.")
        String title,

        @Property(defaultValue = "\"1.0.0\"", documentation = "Server version.")
        String version,

        @Property(
                defaultValue = "\"Use the exposed tools to answer the user.\"",
                documentation = "Instructions sent to the client/model.")
        String instructions,

        @Property(
                defaultValue = "1800",
                documentation = "Session idle timeout in seconds, 0 to disable the expiration.")
        int sessionTimeout,

        @Property(
                defaultValue = "30",
                documentation =
                        "Timeout, in seconds, of the requests sent to the client (sampling, elicitation, roots).")
        int clientRequestTimeout,

        @Property(
                defaultValue = "false",
                documentation = "Should a valid Mcp-Session-Id header be mandatory once the session was created.")
        boolean requireSession,

        @Property(
                defaultValue = "30000L",
                documentation =
                        "How long, in milliseconds, a stateless client may cache a list result (the ttlMs field).")
        long cacheTtlMs,

        @Property(
                defaultValue = "\"private\"",
                documentation = "The scope a stateless client may share a cached result at (the cacheScope field).")
        String cacheScope,

        @Property(
                defaultValue = "\"\"",
                documentation =
                        "Secret signing the requestState tokens with HMAC-SHA256, empty to use the opaque codec.")
        String requestStateSecret,

        @Property(
                defaultValue = "true",
                documentation = "Reject the requests whose Host/Origin headers are not loopback addresses, the DNS "
                        + "rebinding protection.")
        boolean rejectNonLocalHosts) {
    /**
     * Rejects a negative {@code cacheTtlMs}: a result already expired the moment it is sent cannot be cached.
     */
    public MCPConfiguration {
        if (cacheTtlMs < 0) {
            throw new IllegalArgumentException("fusion.mcp.cacheTtlMs must be positive, got " + cacheTtlMs);
        }
    }
}
