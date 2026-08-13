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
 * The type of a JSON-RPC result, it tells the client how to parse it.
 * <p>
 * The {@code 2026-07-28} protocol version makes it mandatory on every result - a client talking to an older server
 * treats its absence as {@link #complete}. A {@link #complete} result carries the final content, an {@link #error} one
 * is a failed tool execution the model must read and react to, and an {@link #input_required} one is a tool which
 * needs an input the server cannot gather by itself - the client must run the {@code inputRequests} and call the tool
 * again with {@code _meta.inputResponses} and the echoed {@code _meta.requestState}.
 */
@JsonModel
public enum ResultType {
    complete,
    error,
    input_required
}
