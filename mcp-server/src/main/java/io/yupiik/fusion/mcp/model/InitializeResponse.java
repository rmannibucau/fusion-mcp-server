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
import java.util.Map;

/**
 * {@code initialize} result: the negotiated protocol version and what the server can do.
 */
@JsonModel
public record InitializeResponse(
        @Property(documentation = "The negotiated protocol version.")
        String protocolVersion,

        @Property(documentation = "What this server can do.")
        Capabilities capabilities,

        @Property(documentation = "Which server is answering.")
        ServerInfo serverInfo,

        @Property(documentation = "How the model should use this server.")
        String instructions) {
    @JsonModel
    public record Capabilities(
            @Property(documentation = "Set when the server can send log records.")
            Map<String, Object> logging,

            @Property(documentation = "Set when the server exposes prompts.")
            Prompts prompts,

            @Property(documentation = "Set when the server exposes resources.")
            Resources resources,

            @Property(documentation = "Set when the server exposes tools.")
            Tools tools,

            @Property(documentation = "Set when the server can suggest argument values.")
            Map<String, Object> completions,

            @Property(documentation = "Non standard capabilities, keyed by name.")
            Map<String, Object> experimental) {}

    @JsonModel
    public record Prompts(
            @Property(documentation = "Set when the server notifies the client the prompt list changed.")
            boolean listChanged) {}

    @JsonModel
    public record Resources(
            @Property(documentation = "Set when the client can subscribe to the changes of a single resource.")
            boolean subscribe,

            @Property(documentation = "Set when the server notifies the client the resource list changed.")
            boolean listChanged) {}

    @JsonModel
    public record Tools(
            @Property(documentation = "Set when the server notifies the client the tool list changed.")
            boolean listChanged) {}

    @JsonModel
    public record ServerInfo(
            @Property(documentation = "Programmatic name of the server.")
            String name,

            @Property(documentation = "Human oriented name of the server.")
            String title,

            @Property(documentation = "Version of the server.")
            String version) {}
}
