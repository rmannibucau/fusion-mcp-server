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
 * The client side of the MCP streamable HTTP transport: {@link io.yupiik.fusion.mcp.client.MCPClient} drives a MCP
 * server - the handshake, the session, the requests and the SSE channel - which is what testing your own server needs
 * and also what calling another MCP server needs.
 * <p>
 * It has no test framework dependency and is not a bean, so it costs nothing at runtime and can be used from a test
 * or from application code indifferently.
 */
package io.yupiik.fusion.mcp.client;
