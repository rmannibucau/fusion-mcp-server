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
package io.yupiik.fusion.mcp.api;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.mcp.model.Task;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

/**
 * The in-memory, durable store of the {@code io.modelcontextprotocol/tasks} extension: a server creates a task with
 * {@link #create(UnaryOperator)}, the client polls it with {@code tasks/get}, answers its pending input with
 * {@code tasks/update} and stops it with {@code tasks/cancel}.
 * <p>
 * Tasks survive the single request which created them - they are keyed by a durable {@code taskId} - which is what
 * lets a stateless, reconnect-any-time client resume a long operation.
 */
@ApplicationScoped
public class TaskRegistry {
    private final Map<String, Task> tasks = new ConcurrentHashMap<>();

    public TaskRegistry() {
        // a no-op for the Fusion subclassing proxies and the default in-memory store
    }

    /**
     * Creates a task from an initial state and registers it.
     *
     * @param initial the initial state, its {@code status} is typically {@code working}.
     * @return the created task, with its {@code taskId} set by the registry when it was {@code null}.
     */
    public Task create(final Task initial) {
        final var id = initial.taskId() == null ? UUID.randomUUID().toString() : initial.taskId();
        final var now = System.currentTimeMillis();
        final var task = initial.taskId() == null
                ? new Task(
                        id,
                        initial.status(),
                        initial.statusMessage(),
                        initial.inputRequests(),
                        initial.result(),
                        initial.error(),
                        now,
                        now,
                        initial.ttlMs(),
                        initial.pollIntervalMs(),
                        initial.parentTaskId())
                : initial;
        tasks.put(id, task);
        return task;
    }

    /**
     * @param taskId the task identifier.
     * @return the current task state if any.
     */
    public Optional<Task> get(final String taskId) {
        return Optional.ofNullable(tasks.get(taskId));
    }

    /**
     * Atomically transforms the current state of a task.
     *
     * @param taskId     the task identifier.
     * @param transition how to compute the next state from the current one, {@code null} result drops the task.
     * @return the updated task, or empty when it does not exist.
     */
    public Optional<Task> update(final String taskId, final UnaryOperator<Task> transition) {
        final var updated = new Object() {
            Task value;
        };
        tasks.computeIfPresent(taskId, (id, current) -> {
            var next = transition.apply(current);
            if (next != null) {
                next = withTimestamp(next);
            }
            updated.value = next;
            return next;
        });
        return Optional.ofNullable(updated.value);
    }

    /**
     * @param taskId the task identifier.
     * @return {@code true} when such a task existed and was dropped.
     */
    public boolean cancel(final String taskId) {
        return tasks.remove(taskId) != null;
    }

    /**
     * @return all the live tasks, mostly for a {@code tasks/list}.
     */
    public List<Task> all() {
        return List.copyOf(tasks.values());
    }

    private Task withTimestamp(final Task task) {
        return new Task(
                task.taskId(),
                task.status(),
                task.statusMessage(),
                task.inputRequests(),
                task.result(),
                task.error(),
                task.createdAt() == null ? System.currentTimeMillis() : task.createdAt(),
                System.currentTimeMillis(),
                task.ttlMs(),
                task.pollIntervalMs(),
                task.parentTaskId());
    }
}
