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
package io.yupiik.fusion.mcp.model;

import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.fusion.framework.build.api.json.JsonProperty;
import java.util.List;

/**
 * {@code server/discover} result: what this server supports in the {@code 2026-07-28} protocol version.
 * <p>
 * Unlike {@code initialize} - which stays a legacy handshake - {@code server/discover} is the way a stateless client
 * learns the stateless protocol: its supported versions, capabilities and instructions. It is cacheable like the other
 * list results, hence the {@code ttlMs}/{@code cacheScope} fields, and carries the server identity in
 * {@code _meta.io.modelcontextprotocol/serverInfo}.
 */
@JsonModel
public record ServerDiscoverResponse(
        @Property(documentation = "The MCP protocol versions this server supports for stateless requests.")
        List<String> supportedVersions,

        @Property(documentation = "What this server can do.")
        ServerCapabilities capabilities,

        @Property(documentation = "Which server is answering.")
        InitializeResponse.ServerInfo serverInfo,

        @Property(documentation = "How the model should use this server.")
        String instructions,

        @Property(documentation = "The type of this result, see the {@code 2026-07-28} specification.")
        ResultType resultType,

        @Property(documentation = "How long, in milliseconds, the client may cache this result.")
        Long ttlMs,

        @Property(documentation = "The scope the client may share this cache entry at.")
        String cacheScope,

        @Property(documentation = "Optional free form metadata.") @JsonProperty("_meta")
        Metadata metadata) {}
