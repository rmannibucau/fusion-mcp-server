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
 * The lifecycle of a long-running operation exposed by the {@code io.modelcontextprotocol/tasks} extension: a server
 * wraps a long-running request in a {@link Task}, the client polls it with {@code tasks/get} and, when the task needs
 * input, answers the {@code inputRequests} with {@code tasks/update}.
 */
@JsonModel
public record CreateTaskResult(
        @Property(documentation = "The type of this result, always 'task' for a task handle.")
        String resultType,

        @Property(documentation = "The unique, durable identifier of the task.")
        String taskId,

        @Property(documentation = "The current status of the task.")
        TaskStatus status,

        @Property(documentation = "How long, in milliseconds, the server keeps the task alive.")
        Long ttlMs,

        @Property(documentation = "How often, in milliseconds, the client should poll the task.")
        Long pollIntervalMs,

        @Property(documentation = "Optional free form metadata.") @JsonProperty("_meta")
        Metadata metadata) {
    /**
     * @param taskId the durable task identifier.
     * @param task   the task state.
     * @return the {@code resultType:"task"} handle the client polls.
     */
    public static CreateTaskResult of(final String taskId, final Task task) {
        return new CreateTaskResult("task", taskId, task.status(), task.ttlMs(), task.pollIntervalMs(), null);
    }
}
