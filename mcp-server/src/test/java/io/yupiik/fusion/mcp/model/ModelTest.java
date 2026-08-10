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
package io.yupiik.fusion.mcp.model;

import static io.yupiik.fusion.testing.assertion.JsonAsserts.assertJsonEquals;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.mcp.protocol.MCPProtocol;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The wire format of the model: these assertions are what a MCP client actually parses, so a rename or a reordering
 * must break here.
 */
@FusionSupport
class ModelTest {
    @Test
    void textContent(@Fusion final JsonMapper jsons) {
        assertJsonEquals("""
                {"type": "text", "text": "hello"}""", jsons.toString(Content.text("hello")));
    }

    @Test
    void binaryContentIsBase64(@Fusion final JsonMapper jsons) {
        assertJsonEquals("""
                        {"type": "image", "mimeType": "image/png", "data": "aGVsbG8="}""", jsons.toString(Content.image("image/png", "hello".getBytes(UTF_8))));
        assertJsonEquals("""
                        {"type": "audio", "mimeType": "audio/wav", "data": "aGVsbG8="}""", jsons.toString(Content.audio("audio/wav", "hello".getBytes(UTF_8))));
    }

    @Test
    void resourceLinkContent(@Fusion final JsonMapper jsons) {
        assertJsonEquals("""
                        {
                          "type": "resource_link",
                          "uri": "demo://greeting",
                          "name": "greeting",
                          "mimeType": "text/plain"
                        }""", jsons.toString(Content.resourceLink("demo://greeting", "greeting", "text/plain")));
    }

    @Test
    void embeddedResourceContent(@Fusion final JsonMapper jsons) {
        assertJsonEquals(
                """
                        {
                          "type": "resource",
                          "resource": {"uri": "demo://greeting", "mimeType": "text/plain", "text": "hello"}
                        }""", jsons.toString(Content.resource(ResourceContents.text("demo://greeting", "text/plain", "hello"))));
    }

    @Test
    void toolUseAndToolResultContent(@Fusion final JsonMapper jsons) {
        final var use = Content.toolUse("call_1", "get_weather", Map.of("city", "Paris"));
        assertJsonEquals("""
                        {"type": "tool_use", "name": "get_weather", "id": "call_1", "input": {"city": "Paris"}}""", jsons.toString(use));

        final var result = Content.toolResult("call_1", List.of(Content.text("18°C, sunny")));
        assertJsonEquals("""
                        {"type": "tool_result", "toolUseId": "call_1", "isError": false, "content": [{"type": "text", "text": "18°C, sunny"}]}""", jsons.toString(result));
    }

    @Test
    void resourceContents(@Fusion final JsonMapper jsons) {
        assertJsonEquals(
                """
                        {"uri": "app://logo", "mimeType": "image/png", "blob": "aGVsbG8="}""", jsons.toString(ResourceContents.blob("app://logo", "image/png", "hello".getBytes(UTF_8))));
    }

    @Test
    void metaIsSerializedAsUnderscoreMeta(@Fusion final JsonMapper jsons) {
        final var response = new ReadResourceResponse(
                new Metadata("name", "title", Map.of("custom", "value")),
                List.of(ResourceContents.text("app://x", "text/plain", "x")),
                null,
                null,
                null);

        final var json = jsons.toString(response);

        assertTrue(json.contains("\"_meta\""), json);
        assertTrue(json.contains("\"custom\":\"value\""), json);
    }

    @Test
    void toolResponseFactories(@Fusion final JsonMapper jsons) {
        assertJsonEquals("""
                        {"isError": false, "content": [{"type": "text", "text": "a"}, {"type": "text", "text": "b"}]}""", jsons.toString(ToolResponse.text("a", "b")));
        assertJsonEquals("""
                        {"isError": true, "content": [{"type": "text", "text": "oops"}]}""", jsons.toString(ToolResponse.error("oops")));
        assertJsonEquals(
                """
                        {
                          "isError": false,
                          "content": [{"type": "text", "text": "{\\"uri\\":\\"app://x\\"}"}],
                          "structuredContent": {"uri": "app://x"}
                        }""",
                jsons.toString(ToolResponse.structure(
                        jsons, new Resource(null, null, null, null, null, null, "app://x", null, null))));
    }

    @Test
    void roleSpelling(@Fusion final JsonMapper jsons) {
        // 'assistant', not 'assitant': it is a wire value
        assertEquals("\"assistant\"", jsons.toString(Role.assistant));
        assertEquals("\"user\"", jsons.toString(Role.user));
    }

    @Test
    void promptResponse(@Fusion final JsonMapper jsons) {
        assertJsonEquals(
                """
                        {
                          "description": "Code review",
                          "messages": [
                            {"role": "user", "content": {"type": "text", "text": "review this"}}
                          ]
                        }""",
                jsons.toString(new PromptResponse(
                        null,
                        "Code review",
                        List.of(new PromptResponse.Message(Role.user, Content.text("review this"))),
                        null)));
    }

    @Test
    void aPromptMessageMayCarryAnArrayOfContentBlocks(@Fusion final JsonMapper jsons) {
        // a message content can be a single block or an array of blocks
        assertJsonEquals(
                """
                {"role": "user", "content": [{"type": "text", "text": "a"}, {"type": "tool_use", "name": "get_weather", "id": "call_1", "input": {}}]}""",
                jsons.toString(PromptResponse.Message.of(
                        Role.user, List.of(Content.text("a"), Content.toolUse("call_1", "get_weather", Map.of())))));
    }

    @Test
    void aPromptMessageMayCarryASingleContentBlock(@Fusion final JsonMapper jsons) {
        assertJsonEquals("""
                {"role": "user", "content": {"type": "text", "text": "a"}}""", jsons.toString(PromptResponse.Message.of(Role.user, Content.text("a"))));
    }

    @Test
    void aSchemaMayCarryAHeaderHint(@Fusion final JsonMapper jsons) {
        // the transport hint is only a property of the schema, it must not leak into the JSON-Schema keywords
        assertJsonEquals("""
                {"type": "string", "description": "a name", "x-mcp-header": "query"}""", jsons.toString(JsonSchema.string("a name").withHeader("query")));
    }

    @Test
    void jsonSchema202012Keywords(@Fusion final JsonMapper jsons) {
        // the SEP-2106 vocabulary: composition, conditional and reference keywords must survive serialization
        final var schema = JsonSchema.object(
                        "a contact form",
                        Map.of(
                                "name", JsonSchema.string("a name"),
                                "contactMethod", JsonSchema.enumeration("how", List.of("phone", "email"))),
                        false,
                        null)
                .withDialect("https://json-schema.org/draft/2020-12/schema")
                .withDefs(Map.of(
                        "address",
                        JsonSchema.object("an address", Map.of("street", JsonSchema.string(null)), null, null)
                                .withAnchor("addressDef")))
                .withAllOf(List.of(JsonSchema.of("object", null)
                        .withAnyOf(List.of(
                                JsonSchema.object(null, Map.of(), List.of("phone")),
                                JsonSchema.object(null, Map.of(), List.of("email"))))))
                .withConditional(
                        JsonSchema.object(
                                null,
                                Map.of("contactMethod", JsonSchema.string(null).withConst("phone")),
                                List.of("contactMethod")),
                        JsonSchema.object(null, Map.of(), List.of("phone")),
                        JsonSchema.object(null, Map.of(), List.of("email")));

        assertJsonEquals("""
                        {
                          "$schema": "https://json-schema.org/draft/2020-12/schema",
                          "type": "object",
                          "description": "a contact form",
                          "properties": {
                            "name": {"type": "string", "description": "a name"},
                            "contactMethod": {"type": "string", "description": "how", "enum": ["phone", "email"]}
                          },
                          "additionalProperties": false,
                          "allOf": [
                            {
                              "type": "object",
                              "anyOf": [
                                {"type": "object", "properties": {}, "required": ["phone"]},
                                {"type": "object", "properties": {}, "required": ["email"]}
                              ]
                            }
                          ],
                          "if": {"type": "object", "properties": {"contactMethod": {"type": "string", "const": "phone"}}, "required": ["contactMethod"]},
                          "then": {"type": "object", "properties": {}, "required": ["phone"]},
                          "else": {"type": "object", "properties": {}, "required": ["email"]},
                          "$defs": {
                            "address": {"type": "object", "description": "an address", "properties": {"street": {"type": "string"}}, "$anchor": "addressDef"}
                          }
                        }""", jsons.toString(schema));
    }

    @Test
    void resourceTemplatesFieldName(@Fusion final JsonMapper jsons) {
        // the specification calls it resourceTemplates, not resources
        assertJsonEquals(
                """
                        {
                          "resourceTemplates": [
                            {
                              "uriTemplate": "demo://echo/{message}",
                              "name": "echo",
                              "title": "echo",
                              "mimeType": "text/plain",
                              "description": "Echoes."
                            }
                          ]
                        }""",
                jsons.toString(new ListResourceTemplatesResponse(
                        List.of(ResourceTemplate.of("demo://echo/{message}", "echo", "text/plain", "Echoes.")),
                        null,
                        null,
                        null,
                        null,
                        null)));
    }

    @Test
    void jsonSchemaFactories(@Fusion final JsonMapper jsons) {
        assertJsonEquals("""
                {"type": "string", "description": "a name"}""", jsons.toString(JsonSchema.string("a name")));
        assertJsonEquals("""
                        {"type": "string", "description": "an id", "format": "uuid", "pattern": "[a-f0-9-]+"}""", jsons.toString(JsonSchema.string("an id", "uuid", "[a-f0-9-]+")));
        assertJsonEquals("""
                        {"type": "string", "description": "lang", "enum": ["fr", "en"]}""", jsons.toString(JsonSchema.enumeration("lang", List.of("fr", "en"))));
        assertJsonEquals("""
                        {"type": "array", "description": "tags", "items": {"type": "string"}}""", jsons.toString(JsonSchema.array("tags", JsonSchema.string(null))));
        assertJsonEquals(
                """
                        {
                          "type": "object",
                          "description": "form",
                          "properties": {"confirm": {"type": "boolean"}},
                          "required": ["confirm"]
                        }""",
                jsons.toString(
                        JsonSchema.object("form", Map.of("confirm", JsonSchema.bool(null)), List.of("confirm"))));
        assertJsonEquals("""
                {"type": "integer", "description": "how many"}""", jsons.toString(JsonSchema.integer("how many")));
        assertJsonEquals("""
                {"type": "number", "description": "a ratio"}""", jsons.toString(JsonSchema.number("a ratio")));
        assertJsonEquals("""
                {"type": "boolean", "description": "yes or no"}""", jsons.toString(JsonSchema.bool("yes or no")));
        // a free form object, i.e. a Map<String, X> parameter
        assertJsonEquals(
                """
                        {
                          "type": "object",
                          "description": "options",
                          "properties": {"known": {"type": "string"}},
                          "additionalProperties": true,
                          "required": []
                        }""",
                jsons.toString(
                        JsonSchema.object("options", Map.of("known", JsonSchema.string(null)), true, List.of())));
        assertJsonEquals("""
                {"type": "null", "description": "nothing"}""", jsons.toString(JsonSchema.of("null", "nothing")));
    }

    @Test
    void jsonSchemaFluentKeywords(@Fusion final JsonMapper jsons) {
        assertJsonEquals("""
                        {"type": "string", "title": "a name", "description": "desc"}""", jsons.toString(JsonSchema.string("desc").withTitle("a name")));
        assertJsonEquals(
                """
                        {"type": "string", "enum": ["a", "b"], "enumNames": ["A", "B"]}""",
                jsons.toString(JsonSchema.enumeration(null, List.of("a", "b")).withEnumNames(List.of("A", "B"))));
        assertJsonEquals("""
                        {"type": "string", "default": "pending"}""", jsons.toString(JsonSchema.string(null).withDefault("pending")));
        assertJsonEquals(
                """
                        {"type": "string", "oneOf": [{"type": "string", "const": "x"}, {"type": "string", "const": "y"}]}""",
                jsons.toString(JsonSchema.string(null)
                        .withOneOf(List.of(
                                JsonSchema.of("string", null).withConst("x"),
                                JsonSchema.of("string", null).withConst("y")))));
    }

    @Test
    void loggingLevelSeverity() {
        // syslog severities, the lower the more critical - and not the declaration order
        assertTrue(LoggingLevel.emergency.severity() < LoggingLevel.debug.severity());
        assertTrue(LoggingLevel.error.isEnabled(LoggingLevel.info), "an error is sent to a client asking for info");
        assertFalse(LoggingLevel.debug.isEnabled(LoggingLevel.info), "a debug record is not");
        assertTrue(LoggingLevel.debug.isEnabled(LoggingLevel.debug));
        assertFalse(LoggingLevel.info.isEnabled(null), "no level set means nothing is sent");
    }

    @Test
    void loggingLevelIsReadableFromTheWire(@Fusion final JsonMapper jsons) {
        assertEquals(LoggingLevel.critical, jsons.fromString(LoggingLevel.class, "\"critical\""));
        assertEquals("\"warning\"", jsons.toString(LoggingLevel.warning));
    }

    @Test
    void notificationsParameters(@Fusion final JsonMapper jsons) {
        assertJsonEquals("""
                        {"logger": "demo", "level": "info", "data": "hello"}""", jsons.toString(new MessageNotification("demo", LoggingLevel.info, "hello")));
        assertJsonEquals("""
                        {"progressToken": "t1", "progress": 0.5, "total": 1.0, "message": "half"}""", jsons.toString(new ProgressNotification("t1", .5, 1., "half")));
        assertJsonEquals("""
                {"uri": "app://x"}""", jsons.toString(ResourceUpdatedNotification.of("app://x")));
        assertJsonEquals("{}", jsons.toString(MetadataParameters.EMPTY));
    }

    @Test
    void serverToClientEnvelope(@Fusion final JsonMapper jsons) {
        assertJsonEquals(
                """
                        {"jsonrpc": "2.0", "method": "notifications/message", "params": {"logger": "demo"}}""",
                jsons.toString(JsonRpcMessage.notification(
                        "notifications/message", new MessageNotification("demo", null, null))));
        assertJsonEquals("""
                        {"jsonrpc": "2.0", "id": 3, "method": "roots/list", "params": {}}""", jsons.toString(JsonRpcMessage.request(3, "roots/list", MetadataParameters.EMPTY)));
    }

    @Test
    void samplingRoundTripModel(@Fusion final JsonMapper jsons) {
        final var parameters = new CreateSamplingMessageParameters(
                SamplingServer.thisServer,
                512,
                List.of(new SamplingMessage(Role.user, Content.text("hi"))),
                null,
                new ModelPreferences(1, List.of(new ModelHint("claude")), 1, 0),
                List.of("STOP"),
                "be nice",
                .2);

        final var json = jsons.toString(parameters);
        assertTrue(json.contains("\"includeContext\":\"thisServer\""), json);
        assertTrue(json.contains("\"maxTokens\":512"), json);
        assertTrue(json.contains("\"hints\":[{\"name\":\"claude\"}]"), json);

        // and the client answer is readable back
        final var response = jsons.fromString(CreateMessageResponse.class, """
                {"role": "assistant", "model": "m", "stopReason": "endTurn",
                 "content": {"type": "text", "text": "hello"}}""");
        assertEquals(Role.assistant, response.role());
        assertEquals("hello", response.content().text());
        assertEquals("endTurn", response.stopReason());
    }

    @Test
    void elicitationModel(@Fusion final JsonMapper jsons) {
        final var response = jsons.fromString(ElicitResponse.class, """
                {"action": "accept", "content": {"environment": "prod", "confirm": true}}""");

        assertEquals(ElicitResponse.Action.accept, response.action());
        assertEquals("prod", response.content().get("environment"));
        assertEquals(Boolean.TRUE, response.content().get("confirm"));
    }

    @Test
    void rootsModel(@Fusion final JsonMapper jsons) {
        final var response = jsons.fromString(ListRootsResponse.class, """
                {"roots": [{"uri": "file:///work", "name": "work"}]}""");

        assertEquals(1, response.roots().size());
        assertEquals("file:///work", response.roots().get(0).uri());
        assertEquals("work", response.roots().get(0).name());
    }

    @Test
    void clientCapabilitiesModel(@Fusion final JsonMapper jsons) {
        final var capabilities = jsons.fromString(Capabilities.class, """
                {"roots": {"listChanged": true}, "sampling": {}, "elicitation": {}}""");

        assertTrue(capabilities.roots().listChanged());
        assertEquals(Map.of(), capabilities.sampling());
        assertEquals(Map.of(), capabilities.elicitation());
        assertEquals(null, capabilities.experimental());
    }

    @Test
    void annotationsPriorityIsANumber(@Fusion final JsonMapper jsons) {
        final var json = jsons.toString(new Annotations(List.of(Role.user), null, Annotations.MOST_IMPORTANT_PRIORITY));

        assertTrue(json.contains("\"audience\":[\"user\"]"), json);
        assertTrue(json.contains("\"priority\":1"), json);
    }

    @Test
    void implementationWireFormat(@Fusion final JsonMapper jsons) {
        assertJsonEquals(
                """
                        {"name": "fusion-mcp-server", "version": "1.0.0", "description": "An MCP server", "websiteUrl": "https://yupiik.com"}""",
                jsons.toString(
                        new Implementation("fusion-mcp-server", "1.0.0", "An MCP server", "https://yupiik.com")));

        // the legacy initialize identity stays a ServerInfo
        assertJsonEquals(
                """
                        {"name": "fusion-mcp-server", "title": "Fusion MCP Server", "version": "1.0.0"}""",
                jsons.toString(new InitializeResponse.ServerInfo("fusion-mcp-server", "Fusion MCP Server", "1.0.0")));
    }

    @Test
    void subscriptionFilterWireFormat(@Fusion final JsonMapper jsons) {
        assertJsonEquals("""
                        {"toolsListChanged": true, "resourcesListChanged": true, "resourceSubscriptions": ["app://x"]}""", jsons.toString(new SubscriptionFilter(true, null, true, List.of("app://x"))));

        assertTrue(SubscriptionFilter.optedIn(Boolean.TRUE));
        assertFalse(SubscriptionFilter.optedIn(Boolean.FALSE));
        assertFalse(SubscriptionFilter.optedIn(null));
    }

    @Test
    void serverDiscoverResponseWireFormat(@Fusion final JsonMapper jsons) {
        assertJsonEquals(
                """
                        {
                          "supportedVersions": ["2026-07-28"],
                          "capabilities": {"logging": {}, "tools": {"listChanged": true}},
                          "serverInfo": {"name": "fusion-mcp-server", "title": "Fusion MCP Server", "version": "1.0.0"},
                          "instructions": "Be nice.",
                          "resultType": "complete",
                          "ttlMs": 30000,
                          "cacheScope": "private",
                          "_meta": {"io.modelcontextprotocol/serverInfo": {"name": "fusion-mcp-server", "title": "Fusion MCP Server", "version": "1.0.0"}}
                        }""",
                jsons.toString(new ServerDiscoverResponse(
                        List.of("2026-07-28"),
                        new ServerCapabilities(
                                null, Map.of(), null, null, null, new ServerCapabilities.Tools(true), null),
                        new InitializeResponse.ServerInfo("fusion-mcp-server", "Fusion MCP Server", "1.0.0"),
                        "Be nice.",
                        ResultType.complete,
                        30000L,
                        "private",
                        new Metadata(
                                null,
                                null,
                                Map.of(
                                        MCPProtocol.SERVER_INFO_META,
                                        new InitializeResponse.ServerInfo(
                                                "fusion-mcp-server", "Fusion MCP Server", "1.0.0"))))));
    }

    @Test
    void resultTypeIsAWireValue(@Fusion final JsonMapper jsons) {
        assertEquals("\"complete\"", jsons.toString(ResultType.complete));
        assertEquals("\"error\"", jsons.toString(ResultType.error));
        assertEquals(ResultType.complete, jsons.fromString(ResultType.class, "\"complete\""));
        assertEquals(ResultType.error, jsons.fromString(ResultType.class, "\"error\""));
    }

    @Test
    void inputRequiredWireValue(@Fusion final JsonMapper jsons) {
        assertEquals("\"input_required\"", jsons.toString(ResultType.input_required));
        assertJsonEquals(
                """
                {"method": "elicitation/create", "params": {"prompt": "What is your name?"}}""", jsons.toString(new InputRequest("elicitation/create", Map.of("prompt", "What is your name?"))));
    }

    @Test
    void mcpResultUnions(@Fusion final JsonMapper jsons) {
        // a tool result
        assertJsonEquals(
                """
                        {"resultType":"complete","isError":false,"content":[{"type":"text","text":"a"}],"structuredContent":{"ok":true}}""",
                jsons.toString(MCPResult.complete(new ToolResponse(
                        null, false, List.of(Content.text("a")), Map.of("ok", true), ResultType.complete))));
        // a prompt result
        assertJsonEquals(
                """
                        {"description":"A description","messages":[{"role":"user","content":{"type":"text","text":"hello"}}]}""",
                jsons.toString(MCPResult.complete(new PromptResponse(
                        null,
                        "A description",
                        List.of(new PromptResponse.Message(Role.user, Content.text("hello"))),
                        null))));
        // a resource result
        assertJsonEquals(
                """
                        {"resultType":"complete","contents":[{"uri":"app://x","mimeType":"text/plain","text":"x"}],"ttlMs":30000,"cacheScope":"private"}""",
                jsons.toString(MCPResult.complete(new ReadResourceResponse(
                        null,
                        List.of(ResourceContents.text("app://x", "text/plain", "x")),
                        ResultType.complete,
                        30000L,
                        "private"))));
        // a failure
        assertJsonEquals(
                """
                        {"resultType":"error","isError":true,"content":[{"type":"text","text":"oops"}],"structuredContent":{"code":-1,"message":"oops"}}""",
                jsons.toString(MCPResult.error(
                        true, List.of(Content.text("oops")), Map.of("code", -1, "message", "oops"), ResultType.error)));
        // a multi round-trip request
        assertJsonEquals(
                """
                        {"resultType":"input_required","inputRequests":{"elicitation/create":{"method":"elicitation/create","params":{"prompt":"What?"}}},"requestState":"token"}""",
                jsons.toString(MCPResult.inputRequired(
                        Map.of("elicitation/create", new InputRequest("elicitation/create", Map.of("prompt", "What?"))),
                        "token")));
        // the ack
        assertJsonEquals("""
                {"resultType":"complete"}""", jsons.toString(MCPResult.ack()));
    }
}
