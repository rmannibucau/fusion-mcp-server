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
import java.util.Map;

/**
 * The subset of JSON-Schema (draft 2020-12) MCP uses: tool {@code inputSchema}/{@code outputSchema} and
 * elicitation {@code requestedSchema}.
 * <p>
 * Tool schemas are derived from the OpenRPC document Fusion generates so this type is mainly built by hand for
 * elicitation - prefer the {@code string()}/{@code integer()}/{@code object()}/{@code array()} factories over the
 * canonical constructor.
 * <p>
 * NOTE: optionality is expressed with {@code required} - the JSON-Schema way - and not with a per property flag.
 *
 * @param properties           the object properties, only set for {@code type=object}.
 * @param additionalProperties either a boolean or a nested {@link JsonSchema}, only set for {@code type=object}.
 * @param items                the item schema, only set for {@code type=array}.
 * @param enumeration          the closed set of allowed values.
 * @param enumNames            the human oriented labels of {@code enumeration}, same order and size.
 * @param required             the names of the mandatory properties.
 */
@JsonModel
public record JsonSchema(
        String type,
        String title,
        String description,
        String format,
        String pattern,
        Map<String, JsonSchema> properties,
        Object additionalProperties,
        JsonSchema items,
        @JsonProperty("enum") List<String> enumeration,
        List<String> enumNames,
        List<String> required,
        Double minimum,
        Double maximum,
        Integer minLength,
        Integer maxLength,
        @JsonProperty("default") Object defaultValue) {
    public static JsonSchema of(final String type, final String description) {
        return new JsonSchema(type, null, description, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    public static JsonSchema string(final String description) {
        return of("string", description);
    }

    public static JsonSchema string(final String description, final String format, final String pattern) {
        return new JsonSchema("string", null, description, format, pattern, null, null, null, null, null, null, null, null, null, null, null);
    }

    public static JsonSchema integer(final String description) {
        return of("integer", description);
    }

    public static JsonSchema number(final String description) {
        return of("number", description);
    }

    public static JsonSchema bool(final String description) {
        return of("boolean", description);
    }

    public static JsonSchema enumeration(final String description, final List<String> values) {
        return new JsonSchema("string", null, description, null, null, null, null, null, values, null, null, null, null, null, null, null);
    }

    public static JsonSchema array(final String description, final JsonSchema items) {
        return new JsonSchema("array", null, description, null, null, null, null, items, null, null, null, null, null, null, null, null);
    }

    public static JsonSchema object(final String description, final Map<String, JsonSchema> properties, final List<String> required) {
        return new JsonSchema("object", null, description, null, null, properties, null, null, null, null, required, null, null, null, null, null);
    }

    public static JsonSchema object(final String description, final Map<String, JsonSchema> properties,
                                    final Object additionalProperties, final List<String> required) {
        return new JsonSchema("object", null, description, null, null, properties, additionalProperties, null, null, null, required, null, null, null, null, null);
    }
}
