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
import java.util.Map;

/**
 * The union of the results {@code tools/call}, {@code prompts/get} and {@code resources/read} return over the
 * {@code 2026-07-28} protocol: the client reads {@link #resultType()} to know which fields are set - and which
 * parsing to apply.
 * <p>
 * Only the fields of the matching legacy result are set: a tool carries {@link #content()} and
 * {@link #structuredContent()}, a prompt {@link #description()} and {@link #messages()}, a resource
 * {@link #contents()} with {@link #ttlMs()}/{@link #cacheScope()}. An {@code input_required} result carries
 * {@link #inputRequests()} and {@link #requestState()} instead.
 */
@JsonModel
public record MCPResult(
        @Property(documentation = "Optional free form metadata.") @JsonProperty("_meta")
        Metadata metadata,

        @Property(documentation = "The type of this result, see the {@code 2026-07-28} specification.")
        ResultType resultType,

        @Property(documentation = "Set when the tool failed, the model is expected to read the error.")
        Boolean isError,

        @Property(documentation = "The tool content blocks, what a model without structured output support reads.")
        List<Content> content,

        @Property(documentation = "The structured tool result, it must match the tool outputSchema when there is one.")
        Object structuredContent,

        @Property(documentation = "What an expanded prompt is about.")
        String description,

        @Property(documentation = "The messages a prompt expands to.")
        List<PromptResponse.Message> messages,

        @Property(documentation = "The contents of a resource, a single uri can expand to several.")
        List<ResourceContents> contents,

        @Property(documentation = "The requests the client must run to feed this result, keyed by their method.")
        Map<String, InputRequest> inputRequests,

        @Property(documentation = "The token the client must echo with its next invocation to resume the interaction.")
        String requestState,

        @Property(documentation = "How long, in milliseconds, the client may cache this result.")
        Long ttlMs,

        @Property(documentation = "The scope the client may share this cache entry at.")
        String cacheScope) {
    /**
     * @param toolResponse the tool result to carry, its {@code resultType} is kept - it is {@code null} on the legacy
     *                     protocol.
     * @return the matching complete result.
     */
    public static MCPResult complete(final ToolResponse toolResponse) {
        return new MCPResult(
                toolResponse.metadata(),
                toolResponse.resultType(),
                toolResponse.isError(),
                toolResponse.content(),
                toolResponse.structuredContent(),
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    /**
     * @param promptResponse the expanded prompt to carry.
     * @return the matching complete result.
     */
    public static MCPResult complete(final PromptResponse promptResponse) {
        return new MCPResult(
                promptResponse.metadata(),
                promptResponse.resultType(),
                null,
                null,
                null,
                promptResponse.description(),
                promptResponse.messages(),
                null,
                null,
                null,
                null,
                null);
    }

    /**
     * @param readResourceResponse the resource contents to carry.
     * @return the matching complete result.
     */
    public static MCPResult complete(final ReadResourceResponse readResourceResponse) {
        return new MCPResult(
                readResourceResponse.metadata(),
                readResourceResponse.resultType(),
                null,
                null,
                null,
                null,
                null,
                readResourceResponse.contents(),
                null,
                null,
                readResourceResponse.ttlMs(),
                readResourceResponse.cacheScope());
    }

    /**
     * @param structuredContent the structured tool result.
     * @param json              the JSON form of {@code structuredContent}, sent as text content for models without
     *                          structured output support.
     * @param resultType        the type of this result, {@code null} on the legacy protocol.
     * @return the matching complete result.
     */
    public static MCPResult complete(final Object structuredContent, final String json, final ResultType resultType) {
        return new MCPResult(
                null,
                resultType,
                false,
                List.of(Content.text(json)),
                structuredContent,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    /**
     * @param isError the tool failed state.
     * @param content the content blocks.
     * @param structuredContent the structured error, e.g. {@code {"code": .., "message": ..}}.
     * @param resultType the type of this result, {@code error} on the stateless protocol.
     * @return a failed tool result - {@code isError=true}.
     */
    public static MCPResult error(
            final boolean isError,
            final List<Content> content,
            final Object structuredContent,
            final ResultType resultType) {
        return new MCPResult(
                null, resultType, isError, content, structuredContent, null, null, null, null, null, null, null);
    }

    /**
     * @param inputRequests the requests the client must run, keyed by their method.
     * @param requestState  the token the client must echo to resume the interaction.
     * @return an {@code input_required} result.
     */
    public static MCPResult inputRequired(final Map<String, InputRequest> inputRequests, final String requestState) {
        return new MCPResult(
                null,
                ResultType.input_required,
                null,
                null,
                null,
                null,
                null,
                null,
                inputRequests,
                requestState,
                null,
                null);
    }

    /**
     * @return an empty {@code complete} result, what an acknowledgment - e.g. the first message of a committed
     * {@code subscriptions/listen} stream - carries.
     */
    public static MCPResult ack() {
        return new MCPResult(null, ResultType.complete, null, null, null, null, null, null, null, null, null, null);
    }
}
