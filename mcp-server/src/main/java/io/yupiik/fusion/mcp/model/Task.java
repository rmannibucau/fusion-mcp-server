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
import java.util.Map;

/**
 * The full state of a long-running task, what {@code tasks/get} returns and what the push {@code notifications/tasks}
 * notifications carry.
 *
 * @param taskId            the unique durable identifier.
 * @param status            the current status.
 * @param statusMessage     an optional human readable progress note.
 * @param inputRequests     the requests the client must fulfill when the status is {@link TaskStatus#input_required}.
 * @param result            the final content, when the status is {@link TaskStatus#completed}.
 * @param error             an optional error, when the status is {@link TaskStatus#failed}.
 * @param createdAt         the instant (epoch ms) the task was created.
 * @param lastUpdatedAt     the instant (epoch ms) the task last changed.
 * @param ttlMs             how long, in milliseconds, the server keeps the task alive.
 * @param pollIntervalMs    how often, in milliseconds, the client should poll.
 * @param parentTaskId      the identifier of the parent task, if any.
 */
@JsonModel
public record Task(
        @Property(documentation = "The unique, durable identifier of the task.")
        String taskId,

        @Property(documentation = "The current status of the task.")
        TaskStatus status,

        @Property(documentation = "A human-readable note about the current progress.")
        String statusMessage,

        @Property(documentation = "The requests the client must answer when the task needs input.")
        Map<String, Object> inputRequests,

        @Property(documentation = "The final content, when the task completed.")
        Object result,

        @Property(documentation = "The error, when the task failed.")
        Object error,

        @Property(documentation = "When the task was created, epoch milliseconds.")
        Long createdAt,

        @Property(documentation = "When the task last changed, epoch milliseconds.")
        Long lastUpdatedAt,

        @Property(documentation = "How long, in milliseconds, the server keeps the task alive.")
        Long ttlMs,

        @Property(documentation = "How often, in milliseconds, the client should poll the task.")
        Long pollIntervalMs,

        @Property(documentation = "The identifier of the parent task, if any.")
        String parentTaskId) {}
