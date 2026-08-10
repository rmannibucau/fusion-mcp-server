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
import io.yupiik.fusion.mcp.model.CompleteResult;
import io.yupiik.fusion.mcp.model.CompletionArgument;
import io.yupiik.fusion.mcp.model.CompletionContext;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.List;

/**
 * Adds {@code completion/complete} suggestions without a dedicated {@link MCPCompletions} implementation: put it on a
 * JSON-RPC method taking the completion input and returning the candidates:
 * <pre>{@code
 * @MCPCompletion
 * @JsonRpc(value = "complete/code", documentation = "Suggests completion values.")
 * public CompleteResult.Completion complete(@JsonRpcParam final CompletionArgument argument,
 *                                           @JsonRpcParam final CompletionContext context) {
 *     return MCPCompletions.matching(List.of("fr", "en"), argument.value());
 * }
 * }</pre>
 * <p>
 * The method must declare a {@link CompletionArgument} parameter (and {@link CompletionContext} when it needs the
 * already resolved arguments), and return a {@link CompleteResult.Completion} or a {@link List} of strings - there is
 * no {@code ref}: the completion applies to any prompt/resource argument, the user decides which argument it servers
 * by looking at {@link CompletionArgument#name()}. Such methods are auto-appended to the registered
 * {@link MCPCompletions} implementations, so they are visited in bean order after them.
 * <p>
 * IMPORTANT: this is a marker, the flag is stored in the bean metadata ({@code mcp.type=completion}) at build time so
 * it does not need to be kept at runtime.
 *
 * @see MCPCompletions for the programmatic counterpart.
 */
@Target(METHOD)
@Retention(SOURCE)
@BeanMetadataAlias(name = "mcp.type", value = "completion")
public @interface MCPCompletion {}
