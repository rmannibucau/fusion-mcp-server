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

import java.util.Base64;

/**
 * A MCP content block, i.e. the union of all content types a tool, a prompt or a sampling message can carry.
 * <p>
 * Prefer the factory methods ({@link #text(String)}, {@link #image(String, byte[])}, ...) over the canonical
 * constructor, they guarantee a valid combination of {@link Type} and payload.
 */
@JsonModel
public record Content(
        @JsonProperty("_meta") Metadata metadata,
        Annotations annotations,
        Type type,
        // type=text
        String text,
        // type=image/audio, base64 encoded
        String data,
        // type=image/audio/resource_link
        String mimeType,
        // type=resource
        ResourceContents resource,
        // type=resource_link
        String uri,
        String name,
        String title,
        String description,
        Long size) {
    @JsonModel
    public enum Type {
        text, image, audio, resource_link, resource
    }

    public static Content text(final String text) {
        return new Content(null, null, Type.text, text, null, null, null, null, null, null, null, null);
    }

    public static Content image(final String mimeType, final byte[] content) {
        return new Content(null, null, Type.image, null, Base64.getEncoder().encodeToString(content), mimeType, null, null, null, null, null, null);
    }

    public static Content audio(final String mimeType, final byte[] content) {
        return new Content(null, null, Type.audio, null, Base64.getEncoder().encodeToString(content), mimeType, null, null, null, null, null, null);
    }

    public static Content resource(final ResourceContents resource) {
        return new Content(null, null, Type.resource, null, null, null, resource, null, null, null, null, null);
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
        return new Content(null, null, Type.resource_link, null, null, mimeType, null, uri, name, null, null, null);
    }
}