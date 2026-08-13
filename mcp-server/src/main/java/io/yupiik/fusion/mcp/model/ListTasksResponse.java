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
import java.util.List;

/**
 * The {@code tasks/list} result: the live tasks, without their potentially large final content.
 *
 * @param metadata  optional free form metadata.
 * @param tasks     the live tasks.
 * @param resultType the type of this result ({@code 2026-07-28} only).
 */
@JsonModel
public record ListTasksResponse(
        @Property(documentation = "Optional free form metadata.")
        java.util.Map<String, Object> metadata,

        @Property(documentation = "The live tasks.") List<Task> tasks,

        @Property(documentation = "The type of this result, see the {@code 2026-07-28} specification.")
        ResultType resultType) {}
