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
package io.yupiik.fusion.mcp.model.fusion;

import io.yupiik.fusion.framework.build.api.json.JsonModel;
import io.yupiik.fusion.framework.build.api.json.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * The subset of the OpenRPC document the Fusion annotation processor generates in
 * {@code META-INF/fusion/jsonrpc/openrpc.json} we need to describe MCP tools and prompts.
 * <p>
 * It is intentionally partial: only the attributes MCP maps to a tool/prompt descriptor are read.
 */
@JsonModel
public record OpenRpc(Map<String, JsonSchema> schemas, Map<String, JsonRpcMethod> methods) {
    @JsonModel
    public record JsonRpcMethod(String name, String description, List<Parameter> params, Result result) {
        /**
         * @param name        the parameter name.
         * @param description the {@code @JsonRpcParam} documentation, note that it is set on the parameter and not on
         *                    its schema.
         * @param schema      the parameter schema.
         * @param required    whether the parameter is mandatory.
         */
        @JsonModel
        public record Parameter(String name, String description, JsonSchema schema, Boolean required) {}
    }

    @JsonModel
    public record Result(JsonSchema schema) {}

    @JsonModel
    public record JsonSchema(
            @JsonProperty("$ref") String ref,
            @JsonProperty("$id") String id,
            @JsonProperty("$schema") String schema,
            @JsonProperty("$defs") Map<String, JsonSchema> defs,
            String type,
            Boolean nullable,
            String description,
            String format,
            String pattern,
            Map<String, JsonSchema> properties,
            Object additionalProperties,
            JsonSchema items,
            @JsonProperty("enum") List<String> enumeration) {}
}
