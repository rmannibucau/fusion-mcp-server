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

import static io.yupiik.fusion.testing.assertion.JsonAsserts.assertJsonEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.mcp.model.CreateTaskResult;
import io.yupiik.fusion.mcp.model.Icon;
import io.yupiik.fusion.mcp.model.ListTasksResponse;
import io.yupiik.fusion.mcp.model.ResultType;
import io.yupiik.fusion.mcp.model.Task;
import io.yupiik.fusion.mcp.model.TaskStatus;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The task registry of the {@code io.modelcontextprotocol/tasks} extension and its wire model.
 */
@FusionSupport
class TaskRegistryTest {
    @Test
    void aTaskCanBeCreatedPolledUpdatedAndCancelled(@Fusion final JsonMapper jsons) {
        final var registry = new TaskRegistry();

        final var created = registry.create(
                new Task(null, TaskStatus.working, "starting", null, null, null, null, null, 300000L, 1000L, null));
        assertNotNull(created.taskId());
        assertEquals(
                TaskStatus.working, registry.get(created.taskId()).orElseThrow().status());

        // the client answers the input requests: the task goes back to working
        assertTrue(registry.update(
                        created.taskId(),
                        it -> new Task(
                                it.taskId(),
                                TaskStatus.input_required,
                                "need input",
                                Map.of("confirm", Map.of("method", "elicitation/create")),
                                null,
                                null,
                                it.createdAt(),
                                it.lastUpdatedAt(),
                                it.ttlMs(),
                                it.pollIntervalMs(),
                                it.parentTaskId()))
                .isPresent());
        assertTrue(registry.update(
                        created.taskId(),
                        it -> new Task(
                                it.taskId(),
                                TaskStatus.working,
                                null,
                                null,
                                "the final answer",
                                null,
                                it.createdAt(),
                                it.lastUpdatedAt(),
                                it.ttlMs(),
                                it.pollIntervalMs(),
                                it.parentTaskId()))
                .isPresent());

        final var completed = registry.get(created.taskId()).orElseThrow();
        assertEquals(TaskStatus.working, completed.status());
        assertEquals("the final answer", completed.result());

        // cooperatively cancelled (kept, status cancelled)
        assertTrue(registry.cancel(created.taskId()));
        assertEquals(0, registry.all().size());

        // unknown task lookups, updates and cancels are no-ops
        assertTrue(registry.get("nope").isEmpty());
        assertTrue(registry.update("nope", it -> it).isEmpty());
        assertFalse(registry.cancel("nope"));

        // a caller-provided id is kept, and an update returning null drops the task
        final var fixed = registry.create(
                new Task("my-task", TaskStatus.working, null, null, null, null, null, null, 1L, 1L, null));
        assertEquals("my-task", registry.get("my-task").orElseThrow().taskId());
        // an update stamps the missing timestamps when the initial state carried none
        assertTrue(registry.update(
                        "my-task",
                        it -> new Task(
                                it.taskId(),
                                TaskStatus.working,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                it.ttlMs(),
                                it.pollIntervalMs(),
                                null))
                .isPresent());
        assertNotNull(registry.get("my-task").orElseThrow().createdAt());
        assertTrue(registry.update("my-task", it -> null).isEmpty());
        assertTrue(registry.get("my-task").isEmpty());
    }

    @Test
    void theTaskWireModel(@Fusion final JsonMapper jsons) {
        assertJsonEquals(
                """
                {"resultType":"task","taskId":"1","status":"working","ttlMs":30000,"pollIntervalMs":1000}""", jsons.toString(new CreateTaskResult("task", "1", TaskStatus.working, 30000L, 1000L, null)));
        assertJsonEquals("""
                {"metadata":{},"tasks":[],"resultType":"complete"}""", jsons.toString(new ListTasksResponse(Map.of(), List.of(), ResultType.complete)));

        // the CreateTaskResult.of factory builds the handle from a task
        final var task = new Task("t1", TaskStatus.working, null, null, null, null, null, null, 30000L, 1000L, null);
        assertJsonEquals("""
                {"resultType":"task","taskId":"t1","status":"working","ttlMs":30000,"pollIntervalMs":1000}""", jsons.toString(CreateTaskResult.of("t1", task)));

        // the task enum values are part of the wire
        assertEquals("\"completed\"", jsons.toString(TaskStatus.completed));
    }

    @Test
    void theIconWireModel(@Fusion final JsonMapper jsons) {
        // icon variants
        assertJsonEquals(
                """
                {"src":"https://example.com/icon.png","mimeType":"image/png","sizes":["48x48"],"theme":"light"}""",
                jsons.toString(
                        new Icon("https://example.com/icon.png", "image/png", List.of("48x48"), Icon.Theme.light)));
        assertJsonEquals("""
                {"src":"data:image/png;base64,AAAA","sizes":["any"],"theme":"light"}""", jsons.toString(Icon.of("data:image/png;base64,AAAA")));
    }
}
