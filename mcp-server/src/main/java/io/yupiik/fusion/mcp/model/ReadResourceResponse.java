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

/**
 * {@code resources/read} result, a single uri can expand to several contents.
 * <p>
 * The {@code resultType}, {@code ttlMs} and {@code cacheScope} fields belong to the {@code 2026-07-28} protocol
 * version: they stay {@code null} - and are omitted on the wire - for the legacy versions.
 */
@JsonModel
public record ReadResourceResponse(
        @Property(documentation = "Optional free form metadata.") @JsonProperty("_meta")
        Metadata metadata,

        @Property(documentation = "The contents of the resource, a single uri can expand to several.")
        List<ResourceContents> contents,

        @Property(documentation = "The type of this result, see the {@code 2026-07-28} specification.")
        ResultType resultType,

        @Property(documentation = "How long, in milliseconds, the client may cache this result.")
        Long ttlMs,

        @Property(documentation = "The scope the client may share this cache entry at.")
        String cacheScope) {
    public static ReadResourceResponse of(final ResourceContents... contents) {
        return new ReadResourceResponse(null, List.of(contents), null, null, null);
    }
}
