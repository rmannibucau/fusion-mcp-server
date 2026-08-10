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

/**
 * Describes an MCP implementation, it is the {@code 2026-07-28} shape of the server/client identity carried in
 * {@code _meta.io.modelcontextprotocol/serverInfo} (and {@code .../clientInfo}) - the legacy {@code initialize}
 * uses {@link InitializeResponse.ServerInfo} instead.
 */
@JsonModel
public record Implementation(
        @Property(documentation = "Programmatic name of the implementation.")
        String name,

        @Property(documentation = "Version of the implementation.")
        String version,

        @Property(documentation = "Optional human readable description of what the implementation does.")
        String description,

        @Property(documentation = "Optional website of the implementation.")
        String websiteUrl) {}
