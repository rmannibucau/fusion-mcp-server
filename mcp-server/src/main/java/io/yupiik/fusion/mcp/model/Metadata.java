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
import io.yupiik.fusion.framework.build.api.json.JsonOthers;

import java.util.Map;

/**
 * The MCP {@code _meta} attribute: a free form object, {@code others} captures everything but the
 * reserved {@code name}/{@code title} attributes.
 */
@JsonModel
public record Metadata(
        @Property(documentation = "Reserved name attribute.")
        String name,
        @Property(documentation = "Reserved title attribute.")
        String title,
        @Property(documentation = "Every other attribute, this object being free form.")
        @JsonOthers Map<String, Object> others
) {
}
