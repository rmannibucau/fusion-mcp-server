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

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.SOURCE;

import io.yupiik.fusion.framework.build.api.metadata.BeanMetadataAlias;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Declares the {@code inputSchema} of a {@link MCPTool} with the JSON-Schema 2020-12 dialect.
 * <p>
 * The schema is derived from the Java method signature like any other tool, then converted at startup to a 2020-12
 * document: the {@code $schema} keyword is added with the {@code value()}, the nested object properties are moved
 * to {@code $defs} and referenced through {@code $ref}, and {@code additionalProperties} is set to {@code false}.
 * <pre>{@code
 * @MCPTool
 * @MCPJsonSchema202012
 * @JsonRpc(value = "weather/current", documentation = "Current weather.")
 * public String current(final Weather weather) { ... }
 * }</pre>
 * is exposed in {@code tools/list} as:
 * <pre>{@code
 * {
 *   "$schema": "https://json-schema.org/draft/2020-12/schema",
 *   "type": "object",
 *   "properties": { "weather": { "$ref": "#/$defs/weather" } },
 *   "required": ["weather"],
 *   "$defs": { "weather": { "type": "object", "properties": { ... } } },
 *   "additionalProperties": false
 * }
 * }</pre>
 * The dialect is stored in the bean metadata ({@code mcp.schema.dialect}) at build time.
 */
@Target(METHOD)
@Retention(SOURCE)
@BeanMetadataAlias(name = "mcp.schema.dialect")
public @interface MCPJsonSchema202012 {
    /**
     * @return the JSON-Schema dialect, the 2020-12 one by default.
     */
    String value() default "https://json-schema.org/draft/2020-12/schema";
}
