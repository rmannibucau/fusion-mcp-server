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
package io.yupiik.fusion.mcp.demo;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.jsonrpc.JsonRpc;
import io.yupiik.fusion.framework.build.api.jsonrpc.JsonRpcParam;
import io.yupiik.fusion.http.server.api.Request;
import io.yupiik.fusion.mcp.api.MCPNotifier;
import io.yupiik.fusion.mcp.api.MCPPrompt;
import io.yupiik.fusion.mcp.api.MCPTool;
import io.yupiik.fusion.mcp.demo.model.Demo;
import io.yupiik.fusion.mcp.demo.model.DemoResponse;
import io.yupiik.fusion.mcp.model.CreateSamplingMessageParameters;
import io.yupiik.fusion.mcp.model.LoggingLevel;
import io.yupiik.fusion.mcp.model.PromptResponse;
import io.yupiik.fusion.mcp.model.Role;
import io.yupiik.fusion.mcp.model.SamplingMessage;
import io.yupiik.fusion.mcp.protocol.MCPSessions;

import java.util.List;
import java.util.concurrent.CompletionStage;

import static io.yupiik.fusion.mcp.model.Content.text;

/**
 * A tour of what a MCP server built with Fusion looks like: tools, a prompt, a tool failure, a log record and a
 * sampling request - i.e. a tool delegating to the client LLM.
 */
@ApplicationScoped
public class DemoTools {
    private final MCPSessions sessions;
    private final MCPNotifier notifier;

    public DemoTools(final MCPSessions sessions, final MCPNotifier notifier) {
        this.sessions = sessions;
        this.notifier = notifier;
    }

    @MCPTool
    @JsonRpc(value = "demo/tool", documentation = "Demo.")
    public Demo demoTool() {
        return new Demo("hello fusion!");
    }

    @MCPTool
    @JsonRpc(value = "demo/greet", documentation = "Greets someone by name.")
    public DemoResponse greet(@JsonRpcParam(required = true, documentation = "Who to greet.") final String name,
                              @JsonRpcParam(documentation = "How many times to greet, defaults to 1.") final Integer times) {
        if (name.isBlank()) {
            // any exception becomes a tool failure the model can read - isError=true - and not a protocol error
            throw new IllegalArgumentException("name should not be blank");
        }
        return new DemoResponse("hello " + name + "!".repeat(times == null ? 1 : Math.max(1, times)));
    }

    @MCPTool
    @JsonRpc(value = "demo/log", documentation = "Sends a log record to the client over the SSE channel.")
    public Demo log(@JsonRpcParam(documentation = "What to log.") final String message) {
        notifier.log(LoggingLevel.info, "demo", message == null ? "hello fusion!" : message);
        return new Demo("logged");
    }

    /**
     * Shows the server to client direction: the tool asks the client LLM to answer, which requires the client to
     * have declared the {@code sampling} capability.
     */
    @MCPTool
    @JsonRpc(value = "demo/ask", documentation = "Asks the client model to answer a question.")
    public CompletionStage<Demo> ask(@JsonRpcParam(required = true, documentation = "The question to forward to the client model.") final String question,
                                    final Request request) {
        return sessions.of(request)
                .createMessage(new CreateSamplingMessageParameters(
                        null, 512,
                        List.of(new SamplingMessage(Role.user, text(question))),
                        null, null, null, "You are a demo assistant.", null))
                .thenApply(response -> new Demo(response.content() == null ? null : response.content().text()));
    }

    @MCPPrompt
    @JsonRpc(value = "demo/prompt", documentation = "Demo.")
    public PromptResponse demoPrompt(@JsonRpcParam(documentation = "The code to inject in the prompt.") final String code) {
        return new PromptResponse(
                null,
                "hello fusion!",
                List.of(new PromptResponse.Message(
                        Role.user,
                        text("hello sir! your code is <" + code + '>'))));
    }
}
