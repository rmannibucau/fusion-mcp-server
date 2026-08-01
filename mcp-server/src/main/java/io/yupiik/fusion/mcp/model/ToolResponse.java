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
import io.yupiik.fusion.json.JsonMapper;

import java.util.List;
import java.util.stream.Stream;

/**
 * {@code tools/call} result.
 * <p>
 * A tool can return this type directly to control the content blocks it sends back, else the JSON-RPC method
 * result is wrapped with {@link #structure(JsonMapper, Object)}.
 *
 * @param metadata          optional {@code _meta}.
 * @param isError           {@code true} when the tool execution failed - the model is expected to see the error.
 * @param content           the content blocks, they are what a model without structured output support reads.
 * @param structuredContent the structured result, it must match the tool {@code outputSchema} when there is one.
 */
@JsonModel
public record ToolResponse(
        @Property(documentation = "Optional free form metadata.")
        @JsonProperty("_meta") Metadata metadata,
        @Property(documentation = "Set when the tool failed, the model is expected to read the error.")
        boolean isError,
        @Property(documentation = "The content blocks, what a model without structured output support reads.")
        List<Content> content,
        @Property(documentation = "The structured result, it must match the tool outputSchema when there is one.")
        Object structuredContent
) {
    /**
     * Wraps a JSON-RPC result as a tool response, the JSON representation is sent as text content - for models
     * without structured output support - and as {@code structuredContent}.
     *
     * @param jsonMapper the mapper used to render {@code data}.
     * @param data       the tool result.
     * @return the matching tool response.
     */
    public static ToolResponse structure(final JsonMapper jsonMapper, final Object data) {
        return new ToolResponse(null, false, List.of(Content.text(jsonMapper.toString(data))), data);
    }

    /**
     * @param text the text content blocks.
     * @return a successful text only tool response.
     */
    public static ToolResponse text(final String... text) {
        return new ToolResponse(null, false, Stream.of(text).map(Content::text).toList(), null);
    }

    /**
     * @param message the error message the model will see.
     * @return a failed tool response - {@code isError=true}.
     */
    public static ToolResponse error(final String message) {
        return new ToolResponse(null, true, List.of(Content.text(message)), null);
    }
}
