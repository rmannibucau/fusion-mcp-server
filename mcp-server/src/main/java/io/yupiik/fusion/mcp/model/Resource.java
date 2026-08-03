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

/**
 * A resource <em>descriptor</em>, i.e. what {@code resources/list} returns.
 * See {@link ResourceContents} for the resource content itself.
 *
 * @param metadata    optional {@code _meta}.
 * @param annotations optional hints for the client.
 * @param description human oriented description of the resource.
 * @param mimeType    the resource mime type when it is known.
 * @param name        the programmatic name of the resource.
 * @param title       the human oriented name of the resource.
 * @param uri         the resource uri, this is what {@code resources/read} takes.
 * @param size        the resource size in bytes when it is known.
 */
@JsonModel
public record Resource(
        @Property(documentation = "Optional free form metadata.") @JsonProperty("_meta")
        Metadata metadata,

        @Property(documentation = "Optional hints for the client.")
        Annotations annotations,

        @Property(documentation = "What the resource contains.")
        String description,

        @Property(documentation = "Mime type of the resource, when known.")
        String mimeType,

        @Property(documentation = "Programmatic name of the resource.")
        String name,

        @Property(documentation = "Human oriented name of the resource.")
        String title,

        @Property(documentation = "Uri of the resource, what resources/read takes.")
        String uri,

        @Property(documentation = "Size in bytes, when known.")
        Long size) {
    public static Resource of(final String uri, final String name, final String mimeType, final String description) {
        return new Resource(null, null, description, mimeType, name, name, uri, null);
    }
}
