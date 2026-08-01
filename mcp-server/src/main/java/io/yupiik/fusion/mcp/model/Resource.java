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
        @JsonProperty("_meta") Metadata metadata,
        Annotations annotations,
        String description,
        String mimeType,
        String name,
        String title,
        String uri,
        Long size
) {
    public static Resource of(final String uri, final String name, final String mimeType, final String description) {
        return new Resource(null, null, description, mimeType, name, name, uri, null);
    }
}
