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
 * {@code tools/list} result, it is computed from the JSON-RPC methods flagged with
 * {@code io.yupiik.fusion.mcp.api.MCPTool}.
 */
@JsonModel
public record ListToolsResponse(
        @Property(documentation = "The callable tools.") List<Tool> tools,

        @Property(documentation = "Cursor to pass to the next call, absent when everything was returned.")
        String nextCursor) {
    @JsonModel
    public record Tool(
            @Property(documentation = "Optional free form metadata.") @JsonProperty("_meta")
            Metadata metadata,

            @Property(documentation = "Optional hints for the client.")
            Annotations annotations,

            @Property(documentation = "Human oriented name of the tool.")
            String title,

            @Property(documentation = "Programmatic name of the tool, what tools/call takes.")
            String name,

            @Property(documentation = "What the tool does, this is what the model reads to decide to call it.")
            String description,

            @Property(documentation = "Schema of the arguments.")
            JsonSchema inputSchema,

            @Property(documentation = "Schema of the structured result, absent when there is none.")
            JsonSchema outputSchema) {}
}
