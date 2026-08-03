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

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.SOURCE;

import io.yupiik.fusion.framework.build.api.metadata.BeanMetadataAlias;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Exposes a JSON-RPC method as a MCP tool, i.e. as something the model can call:
 * <pre>{@code
 * @ApplicationScoped
 * public class MyTools {
 *     @MCPTool
 *     @JsonRpc(value = "my/tool", documentation = "What the model reads to decide to call it.")
 *     public MyResult myTool(@JsonRpcParam(required = true, documentation = "...") final String input) {
 *         return new MyResult(input);
 *     }
 * }
 * }</pre>
 * The JSON-RPC method name is the tool name, its {@code documentation} is the tool description and the
 * {@code inputSchema}/{@code outputSchema} the client gets are derived from the method signature - both come from
 * the OpenRPC document the Fusion annotation processor generates, so there is nothing to declare twice.
 * <p>
 * The method can return any {@code @JsonModel} type - it is then sent as {@code structuredContent} plus its JSON
 * representation as text - or a {@link io.yupiik.fusion.mcp.model.ToolResponse} to control the content blocks. It
 * can also return a {@code CompletionStage} to stay asynchronous and take a
 * {@code io.yupiik.fusion.http.server.api.Request} parameter to reach the
 * {@link io.yupiik.fusion.mcp.protocol.MCPSession}.
 * <p>
 * IMPORTANT: this is a marker, the flag is stored in the bean metadata ({@code mcp.type=tool}) at build time so it
 * does not need to be kept at runtime.
 *
 * @see MCPPrompt for the prompt counterpart.
 */
@Target(METHOD)
@Retention(SOURCE)
@BeanMetadataAlias(name = "mcp.type", value = "tool")
public @interface MCPTool {}
