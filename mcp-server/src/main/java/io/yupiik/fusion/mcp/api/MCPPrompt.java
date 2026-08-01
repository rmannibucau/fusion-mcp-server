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

import io.yupiik.fusion.framework.build.api.metadata.BeanMetadataAlias;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.SOURCE;

/**
 * Exposes a JSON-RPC method as a MCP prompt, i.e. as a reusable message template the user can pick:
 * <pre>{@code
 * @ApplicationScoped
 * public class MyPrompts {
 *     @MCPPrompt
 *     @JsonRpc(value = "my/prompt", documentation = "Reviews a code snippet.")
 *     public PromptResponse review(@JsonRpcParam(required = true) final String code) {
 *         return new PromptResponse(null, "Code review", List.of(new PromptResponse.Message(
 *                 Role.user, Content.text("Review this code: " + code))));
 *     }
 * }
 * }</pre>
 * The method must return a {@link io.yupiik.fusion.mcp.model.PromptResponse} - or a {@code CompletionStage} of it -
 * and, MCP prompt arguments being always strings, only take {@code String} parameters.
 * <p>
 * IMPORTANT: this is a marker, the flag is stored in the bean metadata ({@code mcp.type=prompt}) at build time so it
 * does not need to be kept at runtime.
 *
 * @see MCPTool for the tool counterpart.
 * @see MCPCompletions to suggest values for the arguments.
 */
@Target(METHOD)
@Retention(SOURCE)
@BeanMetadataAlias(name = "mcp.type", value = "prompt")
public @interface MCPPrompt {
}
