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
package io.yupiik.fusion.mcp.conformance;

import static io.yupiik.fusion.mcp.model.Content.text;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.jsonrpc.JsonRpc;
import io.yupiik.fusion.framework.build.api.jsonrpc.JsonRpcParam;
import io.yupiik.fusion.http.server.api.Request;
import io.yupiik.fusion.mcp.api.MCPPrompt;
import io.yupiik.fusion.mcp.exception.InputRequiredException;
import io.yupiik.fusion.mcp.model.Content;
import io.yupiik.fusion.mcp.model.InputRequest;
import io.yupiik.fusion.mcp.model.PromptResponse;
import io.yupiik.fusion.mcp.model.ResourceContents;
import io.yupiik.fusion.mcp.model.Role;
import io.yupiik.fusion.mcp.protocol.MCPProtocol;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * The prompts the conformance suite drives by name.
 */
@ApplicationScoped
public class ConformancePrompts {
    @MCPPrompt
    @JsonRpc(value = "test_simple_prompt", documentation = "A simple prompt with no arguments.")
    public PromptResponse testSimplePrompt() {
        return new PromptResponse(
                null,
                "Simple prompt",
                List.of(new PromptResponse.Message(Role.user, text("This is a simple prompt for testing."))),
                null);
    }

    @MCPPrompt
    @JsonRpc(value = "test_prompt_with_arguments", documentation = "A prompt substituting its arguments.")
    public PromptResponse testPromptWithArguments(
            @JsonRpcParam(required = true, documentation = "First value.") final String arg1,
            @JsonRpcParam(required = true, documentation = "Second value.") final String arg2) {
        return new PromptResponse(
                null,
                "With arguments",
                List.of(new PromptResponse.Message(Role.user, text("Here is your prompt: " + arg1 + " " + arg2))),
                null);
    }

    @MCPPrompt
    @JsonRpc(value = "test_prompt_with_embedded_resource", documentation = "Embeds a resource in the message.")
    public PromptResponse testPromptWithEmbeddedResource(
            @JsonRpcParam(required = true, documentation = "The resource uri to embed.") final String resourceUri) {
        return new PromptResponse(
                null,
                "Embedded resource",
                List.of(
                        new PromptResponse.Message(
                                Role.user,
                                Content.resource(ResourceContents.text(resourceUri, "text/plain", "Embedded content"))),
                        new PromptResponse.Message(Role.user, text("Which resource did you receive?"))),
                null);
    }

    @MCPPrompt
    @JsonRpc(value = "test_prompt_with_image", documentation = "A prompt containing an image.")
    public PromptResponse testPromptWithImage() {
        final var png = Base64.getDecoder()
                .decode(
                        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");
        return new PromptResponse(
                null,
                "With image",
                List.of(
                        new PromptResponse.Message(Role.user, text("Here is an image:")),
                        new PromptResponse.Message(Role.user, Content.image("image/png", png))),
                null);
    }

    @MCPPrompt
    @JsonRpc(
            value = "test_prompt_with_image_and_arguments",
            documentation = "A prompt with an image and substituted arguments.")
    public PromptResponse testPromptWithImageAndArguments(
            @JsonRpcParam(required = true, documentation = "The caption.") final String caption) {
        final var png = Base64.getDecoder()
                .decode(
                        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");
        return new PromptResponse(
                null,
                "With image and args",
                List.of(
                        new PromptResponse.Message(Role.user, text("Caption: " + caption)),
                        new PromptResponse.Message(Role.user, Content.image("image/png", png))),
                null);
    }

    @MCPPrompt
    @JsonRpc(
            value = "test_input_required_result_prompt",
            documentation = "Asks for input when generating the prompt, then completes when answered.")
    public PromptResponse testInputRequiredResultPrompt(final Request request) {
        final var responses =
                request == null ? null : request.attribute(MCPProtocol.INPUT_RESPONSES_ATTRIBUTE, Map.class);
        if (responses == null || responses.isEmpty()) {
            throw new InputRequiredException(
                    Map.of(
                            "context",
                            new InputRequest(
                                    "elicitation/create",
                                    Map.of("message", "What context should the prompt include?"))),
                    "prompt-state");
        }
        return new PromptResponse(
                null,
                "Answered",
                List.of(new PromptResponse.Message(Role.user, text("Thanks! Here is the prompt."))),
                null);
    }
}
