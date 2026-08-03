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
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Optional hints attached to a resource or a content block, they are advisory only.
 *
 * @param audience     who the annotated object is intended for.
 * @param lastModified last modification date of the annotated object.
 * @param priority     how important the annotated object is, between {@link #LEAST_IMPORTANT_PRIORITY}
 *                     and {@link #MOST_IMPORTANT_PRIORITY}.
 */
@JsonModel
public record Annotations(
        @Property(documentation = "Who the annotated object is intended for.")
        List<Role> audience,

        @Property(documentation = "When the annotated object was modified for the last time, ISO-8601.")
        OffsetDateTime lastModified,

        @Property(documentation = "How important the annotated object is, from 0 (least) to 1 (most).")
        Double priority) {
    public static final double MOST_IMPORTANT_PRIORITY = 1.;
    public static final double LEAST_IMPORTANT_PRIORITY = 0.;
}
