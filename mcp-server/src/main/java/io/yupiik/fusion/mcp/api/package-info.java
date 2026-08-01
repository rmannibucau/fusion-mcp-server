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
 * What an application uses to expose its features to a MCP client:
 * <ul>
 *     <li>{@link io.yupiik.fusion.mcp.api.MCPTool} and {@link io.yupiik.fusion.mcp.api.MCPPrompt} flag a
 *     {@code @JsonRpc} method as a tool/prompt - build time only markers,</li>
 *     <li>{@link io.yupiik.fusion.mcp.api.MCPResources} provides resources at runtime,</li>
 *     <li>{@link io.yupiik.fusion.mcp.api.MCPCompletions} suggests argument values,</li>
 *     <li>{@link io.yupiik.fusion.mcp.api.MCPNotifier} pushes notifications to the connected clients.</li>
 * </ul>
 * The remaining direction - asking something to the client: sampling, elicitation and roots - is done through
 * {@link io.yupiik.fusion.mcp.protocol.MCPSession}, obtained from
 * {@link io.yupiik.fusion.mcp.protocol.MCPSessions#of(io.yupiik.fusion.http.server.api.Request)}.
 */
package io.yupiik.fusion.mcp.api;
