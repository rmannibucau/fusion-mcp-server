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
package io.yupiik.fusion.mcp.conformance;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.jsonrpc.JsonRpc;
import io.yupiik.fusion.framework.build.api.jsonrpc.JsonRpcParam;
import io.yupiik.fusion.mcp.api.MCPTool;
import io.yupiik.fusion.mcp.model.CreateTaskResult;
import io.yupiik.fusion.mcp.model.Task;
import io.yupiik.fusion.mcp.model.TaskStatus;
import io.yupiik.fusion.mcp.protocol.MCPJSONRPCProtocol;

/**
 * A tool which immediately wraps its work into a long-running {@code io.modelcontextprotocol/tasks} handle: the client
 * polls it with {@code tasks/get}, {@code tasks/update} and {@code tasks/cancel}.
 */
@ApplicationScoped
public class ConformanceLongTask {
    private final MCPJSONRPCProtocol protocol;

    public ConformanceLongTask(final MCPJSONRPCProtocol protocol) {
        this.protocol = protocol;
    }

    @MCPTool
    @JsonRpc(value = "long/running", documentation = "Returns a task handle instead of blocking.")
    public CreateTaskResult longRunning(
            @JsonRpcParam(documentation = "The payload the task will echo when done.") final String payload) {
        final var task = protocol.tasks()
                .create(new Task(
                        null, TaskStatus.working, "started", null, null, null, null, null, 60_000L, 1_000L, null));
        return CreateTaskResult.of(task.taskId(), task);
    }
}
