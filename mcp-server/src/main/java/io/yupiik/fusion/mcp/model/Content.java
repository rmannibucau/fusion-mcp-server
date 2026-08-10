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
import java.util.Base64;
import java.util.List;

/**
 * A MCP content block, i.e. the union of all content types a tool, a prompt or a sampling message can carry.
 * <p>
 * Prefer the factory methods ({@link #text(String)}, {@link #image(String, byte[])}, ...) over the canonical
 * constructor, they guarantee a valid combination of {@link Type} and payload.
 */
@JsonModel
public record Content(
        @Property(documentation = "Optional free form metadata.") @JsonProperty("_meta")
        Metadata metadata,

        @Property(documentation = "Optional hints for the client.")
        Annotations annotations,

        @Property(documentation = "Which kind of content this block carries.")
        Type type,
        // type=text
        @Property(documentation = "The text, for a text block.")
        String text,
        // type=image/audio, base64 encoded
        @Property(documentation = "The base64 encoded payload, for an image or audio block.")
        String data,
        // type=image/audio/resource_link
        @Property(documentation = "Mime type of the payload.")
        String mimeType,
        // type=resource
        @Property(documentation = "The embedded resource contents, for a resource block.")
        ResourceContents resource,
        // type=resource_link
        @Property(documentation = "Uri of the linked resource, for a resource_link block.")
        String uri,

        @Property(documentation = "Programmatic name of the linked resource.")
        String name,

        @Property(documentation = "Human oriented name of the linked resource.")
        String title,

        @Property(documentation = "What the linked resource contains.")
        String description,

        @Property(documentation = "Size of the linked resource in bytes, when known.")
        Long size,
        // type=tool_use
        @Property(documentation = "The unique identifier of the tool use, for a tool_use block.")
        String id,
        // type=tool_use
        @Property(documentation = "The arguments of the tool call, for a tool_use block.")
        Object input,
        // type=tool_result
        @Property(documentation = "The identifier of the tool_use this result answers, for a tool_result block.")
        String toolUseId,
        // type=tool_result
        @Property(documentation = "Set when the tool call failed, for a tool_result block.")
        Boolean isError,
        // type=tool_result
        @Property(documentation = "The result blocks, for a tool_result block.")
        List<Content> content) {
    @JsonModel
    public enum Type {
        text,
        image,
        audio,
        resource_link,
        resource,
        tool_use,
        tool_result
    }

    public static Content text(final String text) {
        return new Content(
                null, null, Type.text, text, null, null, null, null, null, null, null, null, null, null, null, null,
                null);
    }

    public static Content image(final String mimeType, final byte[] content) {
        return new Content(
                null,
                null,
                Type.image,
                null,
                Base64.getEncoder().encodeToString(content),
                mimeType,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    public static Content audio(final String mimeType, final byte[] content) {
        return new Content(
                null,
                null,
                Type.audio,
                null,
                Base64.getEncoder().encodeToString(content),
                mimeType,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    public static Content resource(final ResourceContents resource) {
        return new Content(
                null,
                null,
                Type.resource,
                null,
                null,
                null,
                resource,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    /**
     * A reference to a resource the client can read later on with {@code resources/read}.
     *
     * @param uri      the resource uri.
     * @param name     the resource programmatic name.
     * @param mimeType the resource mime type, can be {@code null}.
     * @return the matching content block.
     */
    public static Content resourceLink(final String uri, final String name, final String mimeType) {
        return new Content(
                null,
                null,
                Type.resource_link,
                null,
                null,
                mimeType,
                null,
                uri,
                name,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    /**
     * @param id    the identifier the {@code tool_result} will reference.
     * @param name  the tool name.
     * @param input the tool arguments.
     * @return a {@code tool_use} block, as found in sampling messages and agentic conversations.
     */
    public static Content toolUse(final String id, final String name, final Object input) {
        return new Content(
                null,
                null,
                Type.tool_use,
                null,
                null,
                null,
                null,
                null,
                name,
                null,
                null,
                null,
                id,
                input,
                null,
                null,
                null);
    }

    /**
     * @param toolUseId the {@code toolUseId} of the {@link #toolUse(String, String, Object)} this answers.
     * @param content   the result blocks.
     * @return a {@code tool_result} block.
     */
    public static Content toolResult(final String toolUseId, final List<Content> content) {
        return new Content(
                null,
                null,
                Type.tool_result,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                toolUseId,
                false,
                content);
    }
}
