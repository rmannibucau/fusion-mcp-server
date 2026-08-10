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
import static java.util.concurrent.CompletableFuture.completedFuture;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.framework.build.api.jsonrpc.JsonRpc;
import io.yupiik.fusion.http.server.api.Request;
import io.yupiik.fusion.mcp.api.MCPNotifier;
import io.yupiik.fusion.mcp.api.MCPTool;
import io.yupiik.fusion.mcp.exception.InputRequiredException;
import io.yupiik.fusion.mcp.model.Content;
import io.yupiik.fusion.mcp.model.CreateSamplingMessageParameters;
import io.yupiik.fusion.mcp.model.ElicitRequestParameters;
import io.yupiik.fusion.mcp.model.ElicitResponse;
import io.yupiik.fusion.mcp.model.InputRequest;
import io.yupiik.fusion.mcp.model.JsonSchema;
import io.yupiik.fusion.mcp.model.LoggingLevel;
import io.yupiik.fusion.mcp.model.MessageNotification;
import io.yupiik.fusion.mcp.model.ProgressNotification;
import io.yupiik.fusion.mcp.model.ResourceContents;
import io.yupiik.fusion.mcp.model.Role;
import io.yupiik.fusion.mcp.model.SamplingMessage;
import io.yupiik.fusion.mcp.model.ToolResponse;
import io.yupiik.fusion.mcp.protocol.MCPProtocol;
import io.yupiik.fusion.mcp.protocol.MCPSessions;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;

/**
 * The tools the conformance suite drives by name: content-type tools, the MRTR tools, the progress one and the
 * SEP-2575 diagnostic hooks (capability gate, streaming and list-change triggers).
 */
@ApplicationScoped
public class ConformanceTools {
    // a 1x1 red pixel PNG
    private static final byte[] RED_PNG = Base64.getDecoder()
            .decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");

    private final MCPSessions sessions;
    private final MCPNotifier notifier;

    public ConformanceTools(final MCPSessions sessions, final MCPNotifier notifier) {
        this.sessions = sessions;
        this.notifier = notifier;
    }

    @MCPTool
    @JsonRpc(value = "test_simple_text", documentation = "Returns a simple text block.")
    public ToolResponse testSimpleText() {
        return ToolResponse.text("The simplest text content.");
    }

    @MCPTool
    @JsonRpc(value = "test_image_content", documentation = "Returns an image content block.")
    public ToolResponse testImageContent() {
        return blocks(Content.image("image/png", RED_PNG));
    }

    @MCPTool
    @JsonRpc(value = "test_audio_content", documentation = "Returns an audio content block.")
    public ToolResponse testAudioContent() {
        return blocks(Content.audio("audio/wav", new byte[] {0, 1, 2, 3}));
    }

    @MCPTool
    @JsonRpc(value = "test_embedded_resource", documentation = "Returns an embedded resource content block.")
    public ToolResponse testEmbeddedResource() {
        return blocks(
                Content.resource(ResourceContents.text("test://embedded-resource", "text/plain", "embedded text")));
    }

    @MCPTool
    @JsonRpc(
            value = "test_multiple_content_types",
            documentation = "Returns several content blocks of different types.")
    public ToolResponse testMultipleContentTypes() {
        return blocks(
                text("first block"),
                Content.image("image/png", RED_PNG),
                Content.resource(ResourceContents.text("test://nested", "text/plain", "a resource")));
    }

    @MCPTool
    @JsonRpc(value = "test_error_handling", documentation = "Always fails to exercise the isError tool result.")
    public ToolResponse testErrorHandling() {
        return ToolResponse.error("This tool intentionally returns an error for testing");
    }

    @MCPTool
    @JsonRpc(value = "test_tool_with_progress", documentation = "Reports progress notifications.")
    public ToolResponse testToolWithProgress(final Request request) {
        final var session = request == null ? null : sessions.of(request);
        if (session == null) {
            return ToolResponse.text("done");
        }
        final var token = String.valueOf(session.progressToken());
        session.progress(new ProgressNotification(token, 0.0, 100.0, "0%"));
        sleep(50);
        session.progress(new ProgressNotification(token, 50.0, 100.0, "50%"));
        sleep(50);
        session.progress(new ProgressNotification(token, 100.0, 100.0, "100%"));
        return ToolResponse.text("done");
    }

    @MCPTool
    @JsonRpc(
            value = "json_schema_2020_12_tool",
            documentation = "Exercises JSON-Schema 2020-12 keywords, its inputSchema is converted implicitly.")
    public ToolResponse jsonSchema202012Tool(final Address address) {
        return ToolResponse.text("ok");
    }

    @MCPTool
    @JsonRpc(
            value = "test_x_mcp_header",
            documentation = "Echoes a message which can be sent through the Mcp-Param-message header.")
    public ToolResponse testXMcpHeader(final String message) {
        return ToolResponse.text("echo: " + message);
    }

    @MCPTool
    @JsonRpc(
            value = "test_tool_with_logging",
            documentation = "Sends three info log records during its execution, with delays.")
    public ToolResponse testToolWithLogging(final Request request) {
        final var session = request == null ? null : sessions.of(request);
        if (session != null) {
            session.log(new MessageNotification("conformance", LoggingLevel.info, "Tool execution started"));
            sleep(50);
            session.log(new MessageNotification("conformance", LoggingLevel.info, "Tool processing data"));
            sleep(50);
            session.log(new MessageNotification("conformance", LoggingLevel.info, "Tool execution completed"));
        }
        return ToolResponse.text("logging tool executed");
    }

    @MCPTool
    @JsonRpc(value = "test_sampling", documentation = "Requests LLM sampling from the client.")
    public CompletionStage<ToolResponse> testSampling(final Request request, final String prompt) {
        final var session = request == null ? null : sessions.of(request);
        if (session == null) {
            return completedFuture(ToolResponse.error("No session to request sampling from"));
        }
        return session.createMessage(new CreateSamplingMessageParameters(
                        null,
                        100,
                        List.of(new SamplingMessage(Role.user, Content.text(prompt))),
                        null,
                        null,
                        null,
                        null,
                        null))
                .thenApply(response ->
                        ToolResponse.text("LLM response: " + response.content().text()));
    }

    @MCPTool
    @JsonRpc(value = "test_elicitation", documentation = "Requests user input (elicitation) from the client.")
    public CompletionStage<ToolResponse> testElicitation(final Request request, final String message) {
        return elicit(
                        request,
                        new ElicitRequestParameters(
                                message,
                                JsonSchema.object(
                                        null,
                                        Map.of(
                                                "username", JsonSchema.string("User's response"),
                                                "email", JsonSchema.string("User's email address")),
                                        List.of("username", "email"))))
                .thenApply(result -> ToolResponse.text("User response: " + describeElicitation(result)));
    }

    @MCPTool
    @JsonRpc(
            value = "test_elicitation_sep1034_defaults",
            documentation = "Requests elicitation with default values for every primitive type (SEP-1034).")
    public CompletionStage<ToolResponse> testElicitationSep1034Defaults(final Request request) {
        return elicit(
                        request,
                        new ElicitRequestParameters(
                                "Please provide a complete profile",
                                JsonSchema.object(
                                        null,
                                        Map.of(
                                                "name",
                                                JsonSchema.string(null).withDefault("John Doe"),
                                                "age",
                                                JsonSchema.integer(null).withDefault(30),
                                                "score",
                                                JsonSchema.number(null).withDefault(95.5),
                                                "status",
                                                JsonSchema.enumeration(null, List.of("active", "inactive", "pending"))
                                                        .withDefault("active"),
                                                "verified",
                                                JsonSchema.bool(null).withDefault(true)),
                                        List.of("name", "age", "score", "status", "verified"))))
                .thenApply(result -> ToolResponse.text(
                        "Elicitation completed: action=" + result.action() + ", content=" + result.content()));
    }

    @MCPTool
    @JsonRpc(
            value = "test_elicitation_sep1330_enums",
            documentation = "Requests elicitation with the five enum schema variants (SEP-1330).")
    public CompletionStage<ToolResponse> testElicitationSep1330Enums(final Request request) {
        final var schema = JsonSchema.object(
                null,
                Map.of(
                        "untitledSingle",
                        JsonSchema.enumeration(null, List.of("option1", "option2", "option3"))
                                .withTitle("Untitled single-select"),
                        "titledSingle",
                        JsonSchema.string(null)
                                .withOneOf(List.of(
                                        JsonSchema.of("string", null)
                                                .withConst("value1")
                                                .withTitle("First Option"),
                                        JsonSchema.of("string", null)
                                                .withConst("value2")
                                                .withTitle("Second Option"),
                                        JsonSchema.of("string", null)
                                                .withConst("value3")
                                                .withTitle("Third Option"))),
                        "legacyEnum",
                        JsonSchema.enumeration("Legacy titled (deprecated)", List.of("opt1", "opt2", "opt3"))
                                .withEnumNames(List.of("Option One", "Option Two", "Option Three")),
                        "untitledMulti",
                        JsonSchema.array(
                                "Untitled multi-select",
                                JsonSchema.enumeration(null, List.of("option1", "option2", "option3"))),
                        "titledMulti",
                        JsonSchema.array(
                                "Titled multi-select",
                                JsonSchema.of("object", null)
                                        .withAnyOf(List.of(
                                                JsonSchema.of("string", null)
                                                        .withConst("value1")
                                                        .withTitle("First Choice"),
                                                JsonSchema.of("string", null)
                                                        .withConst("value2")
                                                        .withTitle("Second Choice"),
                                                JsonSchema.of("string", null)
                                                        .withConst("value3")
                                                        .withTitle("Third Choice"))))),
                null);
        return elicit(request, new ElicitRequestParameters("Choose the enum values", schema))
                .thenApply(result -> ToolResponse.text(
                        "Elicitation completed: action=" + result.action() + ", content=" + result.content()));
    }

    private CompletionStage<ElicitResponse> elicit(final Request request, final ElicitRequestParameters parameters) {
        final var session = request == null ? null : sessions.of(request);
        if (session == null) {
            return completedFuture(new ElicitResponse(null, ElicitResponse.Action.cancel, Map.of()));
        }
        return session.elicit(parameters);
    }

    private static String describeElicitation(final ElicitResponse response) {
        return "action: " + response.action() + ", content: " + response.content();
    }

    private static void sleep(final long ms) {
        try {
            Thread.sleep(ms);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @MCPTool
    @JsonRpc(value = "test_mrtr_echo_state", documentation = "Echoes the decoded request state, if any.")
    public String testMrtrEchoState(final Request request) {
        return String.valueOf(
                request == null ? null : request.attribute(MCPProtocol.REQUEST_STATE_ATTRIBUTE, String.class));
    }

    @MCPTool
    @JsonRpc(value = "test_mrtr_no_state", documentation = "Returns a plain result without request state.")
    public String testMrtrNoState() {
        return "completed-without-state";
    }

    @MCPTool
    @JsonRpc(value = "test_mrtr_unrelated", documentation = "An unrelated tool.")
    public String testMrtrUnrelated() {
        return "unrelated";
    }

    @MCPTool
    @JsonRpc(
            value = "test_input_required_result_elicitation",
            documentation = "Asks for user input, then completes when answered.")
    public ToolResponse testInputRequiredElicitation(final Request request) {
        if (!answered(request)) {
            throw new InputRequiredException(
                    Map.of(
                            "user_name",
                            new InputRequest(
                                    "elicitation/create",
                                    Map.of(
                                            "message",
                                            "What is your name?",
                                            "requestedSchema",
                                            Map.of(
                                                    "type", "object",
                                                    "properties", Map.of("name", Map.of("type", "string")),
                                                    "required", List.of("name"))))),
                    "user-name-state");
        }
        return ToolResponse.text("hello from elicitation");
    }

    @MCPTool
    @JsonRpc(
            value = "test_input_required_result_sampling",
            documentation = "Asks for sampling, then completes when answered.")
    public ToolResponse testInputRequiredSampling(final Request request) {
        if (!answered(request)) {
            throw new InputRequiredException(
                    Map.of(
                            "sampling",
                            new InputRequest(
                                    "sampling/createMessage",
                                    Map.of(
                                            "messages",
                                            List.of(Map.of("role", "user", "content", text("What is 2+2?"))),
                                            "maxTokens",
                                            100))),
                    "sampling-state");
        }
        return ToolResponse.text("hello from sampling");
    }

    @MCPTool
    @JsonRpc(
            value = "test_input_required_result_list_roots",
            documentation = "Asks for roots, then completes when answered.")
    public ToolResponse testInputRequiredListRoots(final Request request) {
        if (!answered(request)) {
            throw new InputRequiredException(
                    Map.of("client_roots", new InputRequest("roots/list", Map.of())), "list-roots-state");
        }
        return ToolResponse.text("hello from roots/list");
    }

    @MCPTool
    @JsonRpc(
            value = "test_input_required_result_tampered_state",
            documentation = "Demonstrates rejection of a bad request state.")
    public ToolResponse testInputRequiredTamperedState(final Request request) {
        final var state = request == null ? null : request.attribute(MCPProtocol.REQUEST_STATE_ATTRIBUTE, String.class);
        if (state == null) {
            // round 1: ask for input with an integrity-protected requestState; a tampered retry is rejected by the
            // transport (-32602) before reaching here
            throw new InputRequiredException(
                    Map.of(
                            "user_name",
                            new InputRequest("elicitation/create", Map.of("message", "What is your name?"))),
                    "tamper-state");
        }
        return ToolResponse.text(state);
    }

    private static boolean answered(final Request request) {
        final var responses =
                request == null ? null : request.attribute(MCPProtocol.INPUT_RESPONSES_ATTRIBUTE, Map.class);
        return responses != null && !responses.isEmpty();
    }

    private static Object requestStateValue(final Request request) {
        return request == null ? null : request.attribute(MCPProtocol.REQUEST_STATE_ATTRIBUTE, String.class);
    }

    @MCPTool
    @JsonRpc(
            value = "test_input_required_result_multiple_inputs",
            documentation = "Asks for elicitation, sampling and roots, then completes when answered.")
    public ToolResponse testInputRequiredMultiple(final Request request) {
        if (!answered(request)) {
            throw new InputRequiredException(
                    Map.of(
                            "user_name",
                            new InputRequest(
                                    "elicitation/create",
                                    Map.of(
                                            "message",
                                            "What is your name?",
                                            "requestedSchema",
                                            Map.of("type", "object"))),
                            "sampling",
                            new InputRequest(
                                    "sampling/createMessage",
                                    Map.of(
                                            "messages",
                                            List.of(Map.of("role", "user", "content", text("hi"))),
                                            "maxTokens",
                                            10)),
                            "roots",
                            new InputRequest("roots/list", Map.of())),
                    "three-inputs-state");
        }
        return ToolResponse.text("three inputs answered");
    }

    @MCPTool
    @JsonRpc(
            value = "test_input_required_result_request_state",
            documentation = "Returns input_required with a string requestState, then completes.")
    public ToolResponse testInputRequiredRequestState(final Request request) {
        if (answered(request)) {
            return ToolResponse.text("state answered");
        }
        throw new InputRequiredException(
                Map.of("user_name", new InputRequest("elicitation/create", Map.of("message", "What is your name?"))),
                "some-server-state");
    }

    @MCPTool
    @JsonRpc(
            value = "test_input_required_result_multi_round",
            documentation = "Returns input_required twice with distinct states, then completes on the third round.")
    public ToolResponse testInputRequiredMultiRound(final Request request) {
        final var round = String.valueOf(requestStateValue(request));
        if (!answered(request)) {
            throw new InputRequiredException(
                    Map.of("r1", new InputRequest("elicitation/create", Map.of("message", "first"))), "round-1");
        }
        if ("round-1".equals(round)) {
            throw new InputRequiredException(
                    Map.of("r2", new InputRequest("sampling/createMessage", Map.of("maxTokens", 10))), "round-2");
        }
        return ToolResponse.text("multi-round completed");
    }

    @MCPTool
    @JsonRpc(
            value = "test_input_required_result_capabilities",
            documentation = "Asks for elicitation+sampling; the declared capabilities filter which one is kept.")
    public ToolResponse testInputRequiredCapabilities(final Request request) {
        if (!answered(request)) {
            throw new InputRequiredException(
                    Map.of(
                            "elicitation",
                            new InputRequest("elicitation/create", Map.of("message", "what?")),
                            "sampling",
                            new InputRequest("sampling/createMessage", Map.of("maxTokens", 10))),
                    "cap-state");
        }
        return ToolResponse.text("capability answered");
    }

    @MCPTool
    @JsonRpc(
            value = "test_missing_capability",
            documentation = "Requires the sampling capability; a client which did not declare it gets -32021.")
    public ToolResponse testMissingCapability(final Request request) {
        sessions.of(request)
                .requireClientCapability(sessions.of(request).capabilities().sampling(), "sampling");
        return ToolResponse.text("sampling capability present");
    }

    @MCPTool
    @JsonRpc(value = "test_streaming_elicitation", documentation = "Exposes the streaming_elicitation capability.")
    public ToolResponse testStreamingElicitation(final Request request) {
        final var capabilities = sessions.of(request).capabilities();
        return ToolResponse.text("streaming-elicitation="
                + (capabilities != null
                        && capabilities.experimental() != null
                        && capabilities.experimental().containsKey("streaming_elicitation")));
    }

    @MCPTool
    @JsonRpc(value = "test_logging_tool", documentation = "Returns a plain text, sends no logging records.")
    public ToolResponse testLoggingTool() {
        return ToolResponse.text("no logging sent");
    }

    @MCPTool
    @JsonRpc(value = "test_trigger_tool_change", documentation = "Pushes tools/list_changed to the client.")
    public ToolResponse testTriggerToolChange() {
        notifier.toolListChanged();
        return ToolResponse.text("tools/list_changed pushed");
    }

    @MCPTool
    @JsonRpc(value = "test_trigger_prompt_change", documentation = "Pushes prompts/list_changed to the client.")
    public ToolResponse testTriggerPromptChange() {
        notifier.promptListChanged();
        return ToolResponse.text("prompts/list_changed pushed");
    }

    private static ToolResponse blocks(final Content... content) {
        return new ToolResponse(null, false, List.of(content), null, null);
    }
}
