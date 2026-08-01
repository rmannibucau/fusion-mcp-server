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
 * {@code roots/list} result, i.e. the filesystem locations the client gives access to.
 */
@JsonModel
public record ListRootsResponse(
        @Property(documentation = "Optional free form metadata.")
        @JsonProperty("_meta") Metadata metadata,
        @Property(documentation = "The locations the client gives access to.")
        List<Root> roots
) {
    @JsonModel
    public record Root(
            @JsonProperty("_meta") Metadata metadata,
            @Property(documentation = "Human oriented name of the root.")
            String name,
            @Property(documentation = "Uri of the root, generally a file:// one.")
            String uri
    ) {
    }
}
