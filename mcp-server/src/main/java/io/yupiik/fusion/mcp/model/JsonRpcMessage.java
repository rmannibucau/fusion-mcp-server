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
 * A JSON-RPC message the server sends to the client over the SSE channel.
 * <p>
 * Incoming messages are handled by the Fusion JSON-RPC stack so this envelope is only about the
 * server to client direction: notifications ({@code id == null}) and requests ({@code id != null}).
 *
 * @param jsonrpc always {@code 2.0}.
 * @param id      the request identifier, {@code null} for a notification.
 * @param method  the invoked method.
 * @param params  the method parameters, any {@code @JsonModel} instance, map or list.
 */
@JsonModel
public record JsonRpcMessage(
        String jsonrpc,
        Long id,
        String method,
        Object params
) {
    public static JsonRpcMessage notification(final String method, final Object params) {
        return new JsonRpcMessage("2.0", null, method, params);
    }

    public static JsonRpcMessage request(final long id, final String method, final Object params) {
        return new JsonRpcMessage("2.0", id, method, params);
    }
}
