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
    void resourceContents(@Fusion final JsonMapper jsons) {
        assertJsonEquals(
                """
                        {"uri": "app://logo", "mimeType": "image/png", "blob": "aGVsbG8="}""", jsons.toString(ResourceContents.blob("app://logo", "image/png", "hello".getBytes(UTF_8))));
    }

    @Test
    void metaIsSerializedAsUnderscoreMeta(@Fusion final JsonMapper jsons) {
        final var response = new ReadResourceResponse(
                new Metadata("name", "title", Map.of("custom", "value")),
                List.of(ResourceContents.text("app://x", "text/plain", "x")));

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
                        jsons, new Resource(null, null, null, null, null, null, "app://x", null))));
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
                        List.of(new PromptResponse.Message(Role.user, Content.text("review this"))))));
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
                        List.of(ResourceTemplate.of("demo://echo/{message}", "echo", "text/plain", "Echoes.")), null)));
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
}
