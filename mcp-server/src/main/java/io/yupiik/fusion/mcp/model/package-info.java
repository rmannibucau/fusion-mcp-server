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
/**
 * The MCP wire model, mapped from the official
 * <a href="https://raw.githubusercontent.com/modelcontextprotocol/modelcontextprotocol/175a52036c73385047a85bfb996f24e5f1f51c80/schema/2025-06-18/schema.json">2025-06-18 schema</a>.
 * <p>
 * Naming convention: a {@code XxxResponse}/{@code XxxResult} is a JSON-RPC {@code result}, a
 * {@code XxxParameters} is a JSON-RPC {@code params} and a {@code XxxNotification} is the {@code params} of a
 * notification. Every type is a {@code @JsonModel} record so the Fusion annotation processor generates its codec at
 * build time - no reflection at runtime, GraalVM friendly.
 * <p>
 * The request envelopes themselves are not mapped for the client to server direction: the Fusion JSON-RPC stack
 * binds {@code params} to the {@code @JsonRpc} method arguments. Only
 * {@link io.yupiik.fusion.mcp.model.JsonRpcMessage} - the server to client direction - is mapped.
 */
package io.yupiik.fusion.mcp.model;
