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
package io.yupiik.fusion.mcp.api;

import io.yupiik.fusion.mcp.model.JsonSchema;
import java.util.Map;

/**
 * Lets an application override the {@code inputSchema} of a tool.
 * <p>
 * The key is the tool name as advertised in {@code tools/list}, the value is the exact schema to expose. It is useful
 * to declare JSON-Schema 2020-12 keywords - {@code $schema}, {@code $defs}, {@code $ref}, ... - or transport hints
 * like {@code x-mcp-header} which cannot be derived from a Java method signature.
 */
public interface MCPToolSchemas {
    /**
     * @return the tool name to {@link JsonSchema} overrides, empty by default.
     */
    default Map<String, JsonSchema> toolSchemas() {
        return Map.of();
    }
}
