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

/**
 * The actual content of a resource, i.e. what {@code resources/read} returns and what an embedded
 * resource content block carries. See {@link Resource} for the resource <em>descriptor</em>.
 *
 * @param metadata optional {@code _meta}.
 * @param uri      the resource uri.
 * @param mimeType the content mime type.
 * @param text     the content when it is textual, exclusive with {@code blob}.
 * @param blob     the base64 encoded content when it is binary, exclusive with {@code text}.
 */
@JsonModel
public record ResourceContents(
        @Property(documentation = "Optional free form metadata.") @JsonProperty("_meta")
        Metadata metadata,

        @Property(documentation = "Uri of the resource.") String uri,

        @Property(documentation = "Mime type of the content.")
        String mimeType,

        @Property(documentation = "The content when it is textual, exclusive with blob.")
        String text,

        @Property(documentation = "The base64 encoded content when it is binary, exclusive with text.")
        String blob) {
    public static ResourceContents text(final String uri, final String mimeType, final String text) {
        return text(null, uri, mimeType, text);
    }

    public static ResourceContents text(
            final Metadata metadata, final String uri, final String mimeType, final String text) {
        return new ResourceContents(metadata, uri, mimeType, text, null);
    }

    public static ResourceContents blob(final String uri, final String mimeType, final byte[] content) {
        return blob(null, uri, mimeType, content);
    }

    public static ResourceContents blob(
            final Metadata metadata, final String uri, final String mimeType, final byte[] content) {
        return new ResourceContents(
                metadata, uri, mimeType, null, Base64.getEncoder().encodeToString(content));
    }
}
