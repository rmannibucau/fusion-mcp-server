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
package io.yupiik.fusion.mcp.test;

import static io.yupiik.fusion.mcp.service.DescriptorService.PROMPT;
import static io.yupiik.fusion.mcp.service.DescriptorService.TOOL;
import static java.util.concurrent.CompletableFuture.completedFuture;

import io.yupiik.fusion.jsonrpc.impl.JsonRpcMethod;
import io.yupiik.fusion.mcp.service.DescriptorService;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

/**
 * A {@link JsonRpcMethod} built inline, it stands for what the Fusion annotation processor generates for a
 * {@code @JsonRpc} method: a name, the {@code mcp.type} metadata {@code @MCPTool}/{@code @MCPPrompt} set and the
 * invocation itself.
 *
 * @param name         the JSON-RPC method name.
 * @param metadata     the bean metadata, i.e. {@link DescriptorService#TYPE_METADATA} for MCP.
 * @param notification whether Fusion sees the method as a notification, i.e. whether it answers nothing.
 * @param invocation   what the method does, it gets the {@code params} of the call.
 */
public record StubJsonRpcMethod(
        String name,
        Map<String, String> metadata,
        boolean notification,
        Function<Object, CompletionStage<?>> invocation)
        implements JsonRpcMethod {
    @Override
    public boolean isNotification() {
        return notification;
    }

    @Override
    public CompletionStage<?> invoke(final Context context) {
        return invocation.apply(context.params());
    }

    public static StubJsonRpcMethod tool(final String name) {
        return tool(name, params -> completedFuture(null));
    }

    public static StubJsonRpcMethod tool(final String name, final Function<Object, CompletionStage<?>> invocation) {
        return new StubJsonRpcMethod(name, Map.of(DescriptorService.TYPE_METADATA, TOOL), false, invocation);
    }

    public static StubJsonRpcMethod prompt(final String name) {
        return prompt(name, params -> completedFuture(null));
    }

    public static StubJsonRpcMethod prompt(final String name, final Function<Object, CompletionStage<?>> invocation) {
        return new StubJsonRpcMethod(name, Map.of(DescriptorService.TYPE_METADATA, PROMPT), false, invocation);
    }

    /**
     * @param name a JSON-RPC method name.
     * @return a method which is not a MCP one, so neither {@code tools/call} nor {@code prompts/get} can reach it.
     */
    public static StubJsonRpcMethod plain(final String name) {
        return new StubJsonRpcMethod(name, Map.of(), false, params -> completedFuture(null));
    }

    /**
     * @param name a JSON-RPC method name.
     * @param type the {@code mcp.type} value.
     * @return a method Fusion sees as a notification, i.e. a {@code void} one.
     */
    public static StubJsonRpcMethod notification(final String name, final String type) {
        return new StubJsonRpcMethod(
                name, Map.of(DescriptorService.TYPE_METADATA, type), true, params -> completedFuture(null));
    }
}
