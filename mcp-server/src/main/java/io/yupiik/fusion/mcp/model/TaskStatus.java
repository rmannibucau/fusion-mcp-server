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

import io.yupiik.fusion.framework.build.api.json.JsonModel;

/**
 * The status of a long-running {@code io.modelcontextprotocol/tasks} operation.
 */
@JsonModel
public enum TaskStatus {
    /**
     * The operation is in progress.
     */
    working,

    /**
     * The operation needs client input before continuing, see {@code tasks/get} {@code inputRequests}.
     */
    input_required,

    /**
     * The operation finished, the result is in {@code tasks.result}.
     */
    completed,

    /**
     * The operation failed, the error is in {@code tasks.error}.
     */
    failed,

    /**
     * The operation was cancelled.
     */
    cancelled
}
