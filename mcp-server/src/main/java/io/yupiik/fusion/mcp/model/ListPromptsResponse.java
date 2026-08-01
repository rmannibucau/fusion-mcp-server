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

import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.fusion.framework.build.api.json.JsonProperty;

import java.util.List;

/**
 * {@code prompts/list} result, it is computed from the JSON-RPC methods flagged with
 * {@code io.yupiik.fusion.mcp.api.MCPPrompt}.
 */
@JsonModel
public record ListPromptsResponse(
        List<Prompt> prompts,
        String nextCursor
) {
    @JsonModel
    public record Prompt(
            @JsonProperty("_meta") Metadata metadata,
            String title,
            String name,
            String description,
            List<Argument> arguments
    ) {
        @JsonModel
        public record Argument(
                String title,
                String name,
                String description,
                Boolean required
        ) {
        }
    }
}
