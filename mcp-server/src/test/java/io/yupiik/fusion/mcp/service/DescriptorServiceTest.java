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
package io.yupiik.fusion.mcp.service;

import static io.yupiik.fusion.mcp.service.DescriptorService.TOOL;
import static io.yupiik.fusion.mcp.test.StubJsonRpcMethod.prompt;
import static io.yupiik.fusion.mcp.test.StubJsonRpcMethod.tool;
import static java.util.concurrent.CompletableFuture.completedFuture;
import static java.util.concurrent.CompletableFuture.failedFuture;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.mcp.model.CompleteResult;
import io.yupiik.fusion.mcp.model.CompletionArgument;
import io.yupiik.fusion.mcp.model.CompletionContext;
import io.yupiik.fusion.mcp.model.CompletionRef;
import io.yupiik.fusion.mcp.model.JsonSchema;
import io.yupiik.fusion.mcp.model.ListPromptsResponse;
import io.yupiik.fusion.mcp.model.fusion.OpenRpc;
import io.yupiik.fusion.mcp.test.StubJsonRpcMethod;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The mapping of the JSON-RPC methods flagged with {@code @MCPTool}/{@code @MCPPrompt} onto MCP descriptors: which
 * methods are picked, and how a Fusion OpenRPC schema becomes a MCP JSON-Schema.
 * <p>
 * The OpenRPC document is built by hand here - instead of relying on the generated one - so every shape a tool
 * signature can have is covered, including the ones this module has no method for.
 */
@FusionSupport
class DescriptorServiceTest {
    @Test
    void metadataSplitsToolsFromPromptsAndIgnoresPlainMethods(@Fusion final JsonMapper jsons) {
        final var service = descriptors(
                jsons,
                Map.of(),
                List.of(method("a/tool"), method("a/prompt"), method("a/plain")),
                List.of(tool("a/tool"), prompt("a/prompt"), StubJsonRpcMethod.plain("a/plain")));

        assertEquals(List.of("a/tool"), names(service));
        assertEquals(
                List.of("a/prompt"),
                service.prompts().prompts().stream()
                        .map(ListPromptsResponse.Prompt::name)
                        .toList());
        assertTrue(service.isTool("a/tool"));
        assertFalse(service.isTool("a/prompt"));
        assertTrue(service.isPrompt("a/prompt"));
        assertFalse(service.isPrompt("a/plain"));

        // there is no pagination, everything is computed at startup and sent at once
        assertNull(service.tools().nextCursor());
        assertNull(service.prompts().nextCursor());
    }

    @Test
    void anIconMetadataBecomesTheToolIcon(@Fusion final JsonMapper jsons) {
        final var service = descriptors(
                jsons,
                Map.of(),
                List.of(method("a/tool")),
                List.of(tool(
                        "a/tool",
                        Map.of(DescriptorService.ICON_METADATA, "https://example.com/weather.svg"),
                        params -> {
                            throw new java.util.concurrent.CompletionException(new AssertionError());
                        })));

        final var icons = service.tools().tools().get(0).icons();
        assertEquals(1, icons.size());
        assertEquals("https://example.com/weather.svg", icons.get(0).src());
        // a tool without an icon carries none - the icons field is then omitted from the wire
        assertNull(descriptors(jsons, Map.of(), List.of(method("b/tool")), List.of(tool("b/tool")))
                .tools()
                .tools()
                .get(0)
                .icons());

        // a quoted source - how the build-time metadata of a String attribute arrives - is unquoted before the wire
        final var quoted = descriptors(
                        jsons,
                        Map.of(),
                        List.of(method("c/tool")),
                        List.of(tool(
                                "c/tool",
                                Map.of(DescriptorService.ICON_METADATA, "\"https://example.com/logo.png\""),
                                params -> {
                                    throw new java.util.concurrent.CompletionException(new AssertionError());
                                })))
                .tools()
                .tools()
                .get(0)
                .icons();
        assertEquals(1, quoted.size());
        assertEquals("https://example.com/logo.png", quoted.get(0).src());
    }

    @Test
    void anMCPCompletionMethodIsExposedAsACompletion(@Fusion final JsonMapper jsons) {
        final var service = descriptors(
                jsons,
                Map.of(),
                List.of(method("complete/lang")),
                List.of(StubJsonRpcMethod.tool(
                        "complete/lang",
                        Map.of(DescriptorService.TYPE_METADATA, DescriptorService.COMPLETION),
                        params -> java.util.concurrent.CompletableFuture.completedFuture(
                                new CompleteResult.Completion(false, 2, List.of("fr", "en"))))));

        assertEquals(1, service.completionProviders().size());
        // the ref is ignored, the provider completes whatever argument it gets
        final var completion = service.completionProviders()
                .get(0)
                .complete(
                        new CompletionRef("ref/prompt", "anything", null, null),
                        new io.yupiik.fusion.mcp.model.CompletionArgument("lang", ""),
                        new CompletionContext(Map.of()));
        assertEquals(List.of("fr", "en"), completion.values());
        // a method without the completion metadata is not picked up
        assertEquals(
                0,
                descriptors(jsons, Map.of(), List.of(method("a/tool")), List.of(tool("a/tool")))
                        .completionProviders()
                        .size());

        // a completion method returning a plain list is wrapped, one which fails yields no completion
        final var listService = descriptors(
                jsons,
                Map.of(),
                List.of(method("complete/plain")),
                List.of(StubJsonRpcMethod.tool(
                        "complete/plain",
                        Map.of(DescriptorService.TYPE_METADATA, DescriptorService.COMPLETION),
                        params -> completedFuture(List.of("x", "y")))));
        final var listed = listService
                .completionProviders()
                .get(0)
                .complete(
                        new CompletionRef("ref/prompt", "anything", null, null),
                        new CompletionArgument("a", ""),
                        new CompletionContext(Map.of()));
        assertEquals(List.of("x", "y"), listed.values());

        final var failingService = descriptors(
                jsons,
                Map.of(),
                List.of(method("complete/fail")),
                List.of(StubJsonRpcMethod.tool(
                        "complete/fail",
                        Map.of(DescriptorService.TYPE_METADATA, DescriptorService.COMPLETION),
                        params -> failedFuture(new IllegalStateException("boom")))));
        assertNull(failingService
                .completionProviders()
                .get(0)
                .complete(
                        new CompletionRef("ref/prompt", "anything", null, null),
                        new CompletionArgument("a", ""),
                        new CompletionContext(Map.of())));

        // null argument/context are tolerated (the provider just calls the method without them)
        final var nullArgs =
                service.completionProviders().get(0).complete(new CompletionRef("r", "p", null, null), null, null);
        assertEquals(List.of("fr", "en"), nullArgs.values());

        // a method whose result is neither a Completion nor a list yields no completion
        final var stringService = descriptors(
                jsons,
                Map.of(),
                List.of(method("complete/string")),
                List.of(StubJsonRpcMethod.tool(
                        "complete/string",
                        Map.of(DescriptorService.TYPE_METADATA, DescriptorService.COMPLETION),
                        params -> completedFuture("a single string"))));
        assertNull(stringService
                .completionProviders()
                .get(0)
                .complete(
                        new CompletionRef("ref/prompt", "anything", null, null),
                        new CompletionArgument("a", ""),
                        new CompletionContext(Map.of())));
    }

    @Test
    void methodsMissingFromTheRegistryAreIgnored(@Fusion final JsonMapper jsons) {
        // a document can describe a method of a module which is on the classpath but not deployed
        final var service = descriptors(
                jsons, Map.of(), List.of(method("deployed"), method("described/only")), List.of(tool("deployed")));

        assertEquals(List.of("deployed"), names(service));
    }

    @Test
    void toolsAndPromptsAreSortedByName(@Fusion final JsonMapper jsons) {
        // a stable order across runs, so a client cache - and a test - can rely on it
        final var service = descriptors(
                jsons,
                Map.of(),
                List.of(method("c"), method("a"), method("b"), method("z/prompt"), method("k/prompt")),
                List.of(tool("c"), tool("a"), tool("b"), prompt("z/prompt"), prompt("k/prompt")));

        assertEquals(List.of("a", "b", "c"), names(service));
        assertEquals(
                List.of("k/prompt", "z/prompt"),
                service.prompts().prompts().stream()
                        .map(ListPromptsResponse.Prompt::name)
                        .toList());
    }

    @Test
    void toolDescriptor(@Fusion final JsonMapper jsons) {
        final var service = descriptors(
                jsons,
                Map.of(),
                List.of(new OpenRpc.JsonRpcMethod(
                        "demo/greet",
                        "Greets someone.",
                        List.of(param("name", "Who to greet.", primitive("string", false), null)),
                        new OpenRpc.Result(primitive("string", null)))),
                List.of(tool("demo/greet")));

        final var descriptor = service.tools().tools().getFirst();
        assertEquals("demo/greet", descriptor.name());
        // the name doubles as the title, there is no other source for a human oriented one
        assertEquals("demo/greet", descriptor.title());
        assertEquals("Greets someone.", descriptor.description());
        assertNull(descriptor.metadata());
        assertNull(descriptor.annotations());

        final var input = descriptor.inputSchema();
        assertEquals("object", input.type());
        assertEquals("Input request for demo/greet", input.description());
        assertEquals("string", input.properties().get("name").type());
        assertEquals("Who to greet.", input.properties().get("name").description());
        assertEquals(List.of("name"), input.required());

        assertEquals("string", descriptor.outputSchema().type());
    }

    @Test
    void inputSchemaKeepsTheDeclarationOrder(@Fusion final JsonMapper jsons) {
        // both nicer to read for the model and stable across runs
        final var service = descriptors(
                jsons,
                Map.of(),
                List.of(new OpenRpc.JsonRpcMethod(
                        "ordered",
                        null,
                        List.of(
                                param("zzz", null, primitive("string", true), null),
                                param("aaa", null, primitive("string", true), null),
                                param("mmm", null, primitive("string", true), null)),
                        null)),
                List.of(tool("ordered")));

        assertEquals(
                List.of("zzz", "aaa", "mmm"),
                List.copyOf(service.tools()
                        .tools()
                        .getFirst()
                        .inputSchema()
                        .properties()
                        .keySet()));
    }

    @Test
    void requiredIsTakenFromTheFlagThenFromNullability(@Fusion final JsonMapper jsons) {
        final var service = descriptors(
                jsons,
                Map.of(),
                List.of(new OpenRpc.JsonRpcMethod(
                        "required",
                        null,
                        List.of(
                                // the explicit flag wins, whatever the schema says
                                param("explicitlyRequired", null, primitive("string", true), true),
                                param("explicitlyOptional", null, primitive("string", false), false),
                                // else Fusion marks the schema of a required - or primitive - parameter as not nullable
                                param("notNullable", null, primitive("int", false), null),
                                param("nullable", null, primitive("string", true), null),
                                // nothing to say at all: not required
                                param("unknownNullability", null, primitive("string", null), null),
                                param("noSchema", null, null, null)),
                        null)),
                List.of(tool("required")));

        final var input = service.tools().tools().getFirst().inputSchema();
        // sorted, so the list does not depend on the declaration order
        assertEquals(List.of("explicitlyRequired", "notNullable"), input.required());
        // a parameter without any schema keeps its entry, with an empty schema accepting anything
        assertNotNull(input.properties().get("noSchema"));
        assertNull(input.properties().get("noSchema").type());
    }

    @Test
    void parameterDescriptionFallsBackOnTheSchemaOne(@Fusion final JsonMapper jsons) {
        // Fusion sets the @JsonRpcParam documentation on the OpenRPC parameter and not on its schema
        final var service = descriptors(
                jsons,
                Map.of(),
                List.of(new OpenRpc.JsonRpcMethod(
                        "described",
                        null,
                        List.of(
                                param("fromParameter", "the parameter one", described("from the schema"), null),
                                param("blankParameter", "  ", described("from the schema"), null),
                                param("fromSchema", null, described("from the schema"), null),
                                param("none", null, primitive("string", true), null)),
                        null)),
                List.of(tool("described")));

        final var properties = service.tools().tools().getFirst().inputSchema().properties();
        assertEquals("the parameter one", properties.get("fromParameter").description());
        assertEquals("from the schema", properties.get("blankParameter").description());
        assertEquals("from the schema", properties.get("fromSchema").description());
        assertNull(properties.get("none").description());
    }

    @Test
    void noOutputSchemaWhenThereIsNothingToDescribe(@Fusion final JsonMapper jsons) {
        final var service = descriptors(
                jsons,
                Map.of(),
                List.of(
                        // a notification never answers
                        new OpenRpc.JsonRpcMethod(
                                "notification", null, List.of(), new OpenRpc.Result(primitive("string", null))),
                        new OpenRpc.JsonRpcMethod("noResult", null, List.of(), null),
                        new OpenRpc.JsonRpcMethod("noResultSchema", null, List.of(), new OpenRpc.Result(null)),
                        // a void method: Fusion describes it with the "null" type
                        new OpenRpc.JsonRpcMethod(
                                "voidResult", null, List.of(), new OpenRpc.Result(primitive("null", null)))),
                List.of(
                        StubJsonRpcMethod.notification("notification", TOOL),
                        tool("noResult"),
                        tool("noResultSchema"),
                        tool("voidResult")));

        service.tools().tools().forEach(it -> assertNull(it.outputSchema(), it::name));
        // the input schema is always there, even for a tool taking nothing
        service.tools().tools().forEach(it -> assertNotNull(it.inputSchema(), it::name));
    }

    @Test
    void refsAreInlinedInTheToolSchemas(@Fusion final JsonMapper jsons) {
        final var service = descriptors(
                jsons,
                Map.of("Query", object(Map.of("text", primitive("string", false)))),
                List.of(new OpenRpc.JsonRpcMethod(
                        "search",
                        null,
                        List.of(param("query", null, ref("#/schemas/Query"), true)),
                        new OpenRpc.Result(ref("#/schemas/Query")))),
                List.of(tool("search")));

        final var descriptor = service.tools().tools().getFirst();
        final var query = descriptor.inputSchema().properties().get("query");
        assertEquals("object", query.type());
        assertEquals("string", query.properties().get("text").type());
        // MCP has no nullable flag, optionality is folded into the required list of the enclosing object
        assertEquals(List.of("text"), query.required());
        assertEquals(
                "string", descriptor.outputSchema().properties().get("text").type());
    }

    @Test
    void recentProtocolVersionConvertsToolSchemas(@Fusion final JsonMapper jsons) {
        final var service = descriptors(
                jsons,
                Map.of("Query", object(Map.of("text", primitive("string", false)))),
                List.of(new OpenRpc.JsonRpcMethod(
                        "search",
                        null,
                        List.of(
                                param("query", null, ref("#/schemas/Query"), true),
                                param("q", null, primitive("string", false), null)),
                        new OpenRpc.Result(ref("#/schemas/Query")))),
                List.of(tool("search")));

        // unknown or older protocol versions keep the raw derived schema
        assertSame(service.tools(), service.tools(null));
        assertSame(service.tools(), service.tools("2025-11-24"));
        // since 2025-11-25 the 2020-12 conversion is implicit
        final var converted = service.tools(DescriptorService.JSON_SCHEMA_2020_12_SINCE)
                .tools()
                .getFirst();
        assertEquals(
                DescriptorService.JSON_SCHEMA_2020_12_DIALECT,
                converted.inputSchema().schema());
        assertFalse((Boolean) converted.inputSchema().additionalProperties());
        assertEquals(
                "#/$defs/query",
                converted.inputSchema().properties().get("query").ref());
        // a primitive parameter is left as-is
        assertEquals("string", converted.inputSchema().properties().get("q").type());
        assertEquals(
                "string",
                converted
                        .inputSchema()
                        .defs()
                        .get("query")
                        .properties()
                        .get("text")
                        .type());
        // the output schema is converted the same way
        assertEquals("string", converted.outputSchema().properties().get("text").type());
    }

    @Test
    void recentProtocolVersionKeepsAnAlready202012Schema(@Fusion final JsonMapper jsons) {
        final var override = JsonSchema.string("search").withDialect(DescriptorService.JSON_SCHEMA_2020_12_DIALECT);
        final var service = descriptors(
                jsons,
                Map.of(),
                List.of(new OpenRpc.JsonRpcMethod("search", null, List.of(), null)),
                List.of(tool("search")),
                Map.of("search", override));

        final var converted = service.tools(DescriptorService.JSON_SCHEMA_2020_12_SINCE)
                .tools()
                .getFirst()
                .inputSchema();
        assertSame(override, converted);
        assertEquals(DescriptorService.JSON_SCHEMA_2020_12_DIALECT, converted.schema());
    }

    @Test
    void explicit202012DialectConvertsTheDerivedSchema(@Fusion final JsonMapper jsons) {
        final var service = descriptors(
                jsons,
                Map.of("Query", object(Map.of("text", primitive("string", false)))),
                List.of(new OpenRpc.JsonRpcMethod(
                        "search", null, List.of(param("query", null, ref("#/schemas/Query"), true)), null)),
                List.of(tool(
                        "search",
                        Map.of(
                                DescriptorService.SCHEMA_DIALECT_METADATA,
                                DescriptorService.JSON_SCHEMA_2020_12_DIALECT),
                        params -> completedFuture(null))));

        final var converted = service.tools().tools().getFirst().inputSchema();
        assertEquals(DescriptorService.JSON_SCHEMA_2020_12_DIALECT, converted.schema());
        assertEquals("#/$defs/query", converted.properties().get("query").ref());
    }

    @Test
    void anExplicitToolSchemaWinsOverTheDialectConversion(@Fusion final JsonMapper jsons) {
        // a @MCPJsonSchema202012 tool whose schema is provided by hand: the hand-built one is already 2020-12, the
        // dialect conversion of the derived schema would only lose the SEP-2106 keywords it carries
        final var override = JsonSchema.object("Overridden", Map.of("q", JsonSchema.string("Q")), List.of());
        final var service = descriptors(
                jsons,
                Map.of(),
                List.of(new OpenRpc.JsonRpcMethod(
                        "search", null, List.of(param("query", null, primitive("string", false), null)), null)),
                List.of(tool(
                        "search",
                        Map.of(
                                DescriptorService.SCHEMA_DIALECT_METADATA,
                                DescriptorService.JSON_SCHEMA_2020_12_DIALECT),
                        params -> completedFuture(null))),
                Map.of("search", override));

        assertSame(override, service.tools().tools().getFirst().inputSchema());
    }

    @Test
    void laterMCPToolSchemasWinOnDuplicateNames(@Fusion final JsonMapper jsons) {
        final var document =
                new OpenRpc(Map.of(), Map.of("search", new OpenRpc.JsonRpcMethod("search", null, List.of(), null)));
        final var service = new DescriptorService(
                new OpenRpcService(jsons) {
                    @Override
                    public OpenRpc load() {
                        return document;
                    }
                },
                new io.yupiik.fusion.jsonrpc.JsonRpcRegistry(List.of(tool("search"))),
                jsons,
                List.of(
                        new io.yupiik.fusion.mcp.api.MCPToolSchemas() {
                            @Override
                            public Map<String, JsonSchema> toolSchemas() {
                                return Map.of("search", JsonSchema.string("first"));
                            }
                        },
                        new io.yupiik.fusion.mcp.api.MCPToolSchemas() {
                            @Override
                            public Map<String, JsonSchema> toolSchemas() {
                                return Map.of("search", JsonSchema.string("second"));
                            }
                        }));

        assertEquals("second", service.tools().tools().getFirst().inputSchema().description());
    }

    @Test
    void mcpToolSchemasOverrideTheDerivedSchema(@Fusion final JsonMapper jsons) {
        final var service = descriptors(
                jsons,
                Map.of(),
                List.of(new OpenRpc.JsonRpcMethod(
                        "search", null, List.of(param("query", null, primitive("string", false), null)), null)),
                List.of(tool("search")),
                Map.of("search", JsonSchema.object("Overridden", Map.of("q", JsonSchema.string("Q")), List.of())));

        final var input = service.tools().tools().getFirst().inputSchema();
        assertEquals("Overridden", input.description());
        assertEquals("Q", input.properties().get("q").description());
    }

    @Test
    void jsonSchemaKeywordsAreCarriedToTheMCPModel(@Fusion final JsonMapper jsons) {
        final var service = descriptors(
                jsons,
                Map.of(),
                List.of(new OpenRpc.JsonRpcMethod(
                        "rich",
                        null,
                        List.of(param(
                                "thing",
                                null,
                                new OpenRpc.JsonSchema(
                                        null,
                                        null,
                                        DescriptorService.JSON_SCHEMA_2020_12_DIALECT,
                                        Map.of("T", object(Map.of("x", primitive("string", true)))),
                                        "object",
                                        true,
                                        null,
                                        null,
                                        null,
                                        Map.of("x", primitive("string", true)),
                                        null,
                                        null,
                                        null),
                                null)),
                        null)),
                List.of(tool("rich")));

        final var thing =
                service.tools().tools().getFirst().inputSchema().properties().get("thing");
        assertEquals(DescriptorService.JSON_SCHEMA_2020_12_DIALECT, thing.schema());
        assertEquals("string", thing.defs().get("T").properties().get("x").type());
    }

    @Test
    void completionProvidersAreExposed(@Fusion final JsonMapper jsons) {
        final var service = descriptors(
                jsons,
                Map.of(),
                List.of(
                        method("complete/list"),
                        method("complete/map"),
                        method("complete/other"),
                        method("complete/ko")),
                List.of(
                        StubJsonRpcMethod.completion("complete/list", params -> completedFuture(List.of("a", "b"))),
                        StubJsonRpcMethod.completion(
                                "complete/map",
                                params -> completedFuture(new CompleteResult.Completion(true, 42, List.of("x")))),
                        StubJsonRpcMethod.completion("complete/other", params -> completedFuture("not-a-completion")),
                        StubJsonRpcMethod.completion(
                                "complete/ko",
                                params -> completedFuture(new java.util.concurrent.CompletableFuture<String>()
                                        .completeExceptionally(new IllegalStateException("boom"))))));

        assertEquals(4, service.completionProviders().size());
        final var ref = new CompletionRef(null, null, null, null);
        final var argument = new CompletionArgument("name", "val");
        final var context = new CompletionContext(Map.of("name", "val"));
        // providers are sorted by method name: ko, list, map, other
        assertNull(service.completionProviders().get(0).complete(ref, argument, context));
        final var asList = service.completionProviders().get(1).complete(ref, argument, context);
        assertFalse(asList.hasMore());
        assertEquals(List.of("a", "b"), asList.values());
        final var asCompletion = service.completionProviders().get(2).complete(ref, argument, context);
        assertTrue(asCompletion.hasMore());
        assertEquals(42, asCompletion.total());
        assertEquals(List.of("x"), asCompletion.values());
        assertNull(service.completionProviders().get(3).complete(ref, argument, context));
    }

    @Test
    void arrayItemsEnumFormatAndPatternAreCarried(@Fusion final JsonMapper jsons) {
        final var service = descriptors(
                jsons,
                Map.of(),
                List.of(new OpenRpc.JsonRpcMethod(
                        "rich",
                        null,
                        List.of(
                                param("tags", null, array(primitive("string", true)), null),
                                param(
                                        "direction",
                                        null,
                                        new OpenRpc.JsonSchema(
                                                null,
                                                null,
                                                null,
                                                null,
                                                "string",
                                                true,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                null,
                                                List.of("asc", "desc")),
                                        null),
                                param(
                                        "when",
                                        null,
                                        new OpenRpc.JsonSchema(
                                                null,
                                                null,
                                                null,
                                                null,
                                                "string",
                                                true,
                                                null,
                                                "date-time",
                                                "[0-9].*",
                                                null,
                                                null,
                                                null,
                                                null),
                                        null)),
                        null)),
                List.of(tool("rich")));

        final var properties = service.tools().tools().getFirst().inputSchema().properties();
        assertEquals("array", properties.get("tags").type());
        assertEquals("string", properties.get("tags").items().type());
        assertEquals(List.of("asc", "desc"), properties.get("direction").enumeration());
        assertEquals("date-time", properties.get("when").format());
        assertEquals("[0-9].*", properties.get("when").pattern());
    }

    @Test
    void additionalPropertiesAreConverted(@Fusion final JsonMapper jsons) {
        final var service = descriptors(
                jsons,
                Map.of(),
                List.of(new OpenRpc.JsonRpcMethod(
                        "maps",
                        null,
                        List.of(
                                // a Map<String, String>: additionalProperties is a nested schema, read as a plain
                                // JSON structure by the OpenRPC model
                                param(
                                        "typed",
                                        null,
                                        new OpenRpc.JsonSchema(
                                                null,
                                                null,
                                                null,
                                                null,
                                                "object",
                                                true,
                                                null,
                                                null,
                                                null,
                                                null,
                                                Map.of("type", "string"),
                                                null,
                                                null),
                                        null),
                                // a free form object
                                param(
                                        "free",
                                        null,
                                        new OpenRpc.JsonSchema(
                                                null, null, null, null, "object", true, null, null, null, null, true,
                                                null, null),
                                        null)),
                        null)),
                List.of(tool("maps")));

        final var properties = service.tools().tools().getFirst().inputSchema().properties();
        final var typed = properties.get("typed").additionalProperties();
        assertTrue(typed instanceof io.yupiik.fusion.mcp.model.JsonSchema, () -> String.valueOf(typed));
        assertEquals("string", ((io.yupiik.fusion.mcp.model.JsonSchema) typed).type());
        assertEquals(Boolean.TRUE, properties.get("free").additionalProperties());
    }

    @Test
    void nestedRequiredIsFoldedInTheEnclosingObject(@Fusion final JsonMapper jsons) {
        final var service = descriptors(
                jsons,
                Map.of(),
                List.of(new OpenRpc.JsonRpcMethod(
                        "nested",
                        null,
                        List.of(param(
                                "payload",
                                null,
                                object(Map.of(
                                        "mandatory", primitive("string", false),
                                        "optional", primitive("string", true),
                                        "unknown", primitive("string", null))),
                                null)),
                        null)),
                List.of(tool("nested")));

        final var payload =
                service.tools().tools().getFirst().inputSchema().properties().get("payload");
        assertEquals(List.of("mandatory"), payload.required());
        // nullable has no MCP counterpart, it must not leak in the schema the client gets
        assertNull(payload.properties().get("optional").title());
    }

    @Test
    void promptDescriptor(@Fusion final JsonMapper jsons) {
        final var service = descriptors(
                jsons,
                Map.of(),
                List.of(new OpenRpc.JsonRpcMethod(
                        "demo/prompt",
                        "Reviews some code.",
                        List.of(
                                param("code", "The code to review.", primitive("string", false), null),
                                param("lang", null, primitive("string", true), null)),
                        new OpenRpc.Result(primitive("object", null)))),
                List.of(prompt("demo/prompt")));

        final var descriptor = service.prompts().prompts().getFirst();
        assertEquals("demo/prompt", descriptor.name());
        assertEquals("demo/prompt", descriptor.title());
        assertEquals("Reviews some code.", descriptor.description());
        assertNull(descriptor.metadata());

        assertEquals(
                List.of("code", "lang"),
                descriptor.arguments().stream()
                        .map(ListPromptsResponse.Prompt.Argument::name)
                        .toList());
        final var code = descriptor.arguments().getFirst();
        assertEquals("code", code.title());
        assertEquals("The code to review.", code.description());
        assertEquals(Boolean.TRUE, code.required());
        assertEquals(Boolean.FALSE, descriptor.arguments().get(1).required());
    }

    @Test
    void promptArguments(@Fusion final JsonMapper jsons) {
        final var service = descriptors(
                jsons,
                Map.of(),
                List.of(
                        new OpenRpc.JsonRpcMethod(
                                "p1",
                                null,
                                List.of(
                                        param("a", null, primitive("string", true), null),
                                        param("b", null, primitive("string", true), null)),
                                null),
                        new OpenRpc.JsonRpcMethod("p2", null, List.of(), null)),
                List.of(prompt("p1"), prompt("p2")));

        assertEquals(Set.of("a", "b"), service.promptArguments("p1"));
        assertEquals(Set.of(), service.promptArguments("p2"));
        // not a prompt at all
        assertEquals(Set.of(), service.promptArguments("nope"));
    }

    @Test
    void emptyWhenNothingIsFlagged(@Fusion final JsonMapper jsons) {
        final var service =
                descriptors(jsons, Map.of(), List.of(method("plain")), List.of(StubJsonRpcMethod.plain("plain")));

        assertEquals(List.of(), service.tools().tools());
        assertEquals(List.of(), service.prompts().prompts());
        assertFalse(service.isTool("plain"));
        assertFalse(service.isPrompt("plain"));
    }

    @Test
    void theSubclassingConstructorHoldsNothing() {
        // the no-arg constructor only exists for the Fusion subclassing proxies, it must not blow up
        final var service = new DescriptorService() {};

        assertNull(service.tools());
        assertNull(service.prompts());
        assertFalse(service.isTool("whatever"));
        assertFalse(service.isPrompt("whatever"));
    }

    @Test
    void duplicatedParameterNamesKeepTheFirstSchema(@Fusion final JsonMapper jsons) {
        // a handcrafted document can declare the same name twice, the input schema must stay a valid object
        final var service = descriptors(
                jsons,
                Map.of(),
                List.of(new OpenRpc.JsonRpcMethod(
                        "duplicated",
                        null,
                        List.of(
                                param("name", null, primitive("string", true), null),
                                param("name", null, primitive("integer", true), null)),
                        null)),
                List.of(tool("duplicated")));

        final var properties = service.tools().tools().getFirst().inputSchema().properties();
        assertEquals(1, properties.size());
        assertEquals("string", properties.get("name").type());
    }

    private List<String> names(final DescriptorService service) {
        return service.tools().tools().stream()
                .map(io.yupiik.fusion.mcp.model.ListToolsResponse.Tool::name)
                .toList();
    }

    private DescriptorService descriptors(
            final JsonMapper jsons,
            final Map<String, OpenRpc.JsonSchema> schemas,
            final List<OpenRpc.JsonRpcMethod> descriptors,
            final List<io.yupiik.fusion.jsonrpc.impl.JsonRpcMethod> deployed) {
        return descriptors(jsons, schemas, descriptors, deployed, Map.of());
    }

    private DescriptorService descriptors(
            final JsonMapper jsons,
            final Map<String, OpenRpc.JsonSchema> schemas,
            final List<OpenRpc.JsonRpcMethod> descriptors,
            final List<io.yupiik.fusion.jsonrpc.impl.JsonRpcMethod> deployed,
            final Map<String, JsonSchema> toolSchemaOverrides) {
        final var document = new OpenRpc(
                schemas,
                descriptors.stream().collect(java.util.stream.Collectors.toMap(OpenRpc.JsonRpcMethod::name, it -> it)));
        return new DescriptorService(
                new OpenRpcService(jsons) {
                    @Override
                    public OpenRpc load() { // the classpath one would bring the MCP protocol methods in
                        return document;
                    }
                },
                new io.yupiik.fusion.jsonrpc.JsonRpcRegistry(deployed),
                jsons,
                List.of(new io.yupiik.fusion.mcp.api.MCPToolSchemas() {
                    @Override
                    public Map<String, JsonSchema> toolSchemas() {
                        return toolSchemaOverrides;
                    }
                }));
    }

    private OpenRpc.JsonRpcMethod method(final String name) {
        return new OpenRpc.JsonRpcMethod(name, null, List.of(), null);
    }

    private OpenRpc.JsonRpcMethod.Parameter param(
            final String name, final String description, final OpenRpc.JsonSchema schema, final Boolean required) {
        return new OpenRpc.JsonRpcMethod.Parameter(name, description, schema, required);
    }

    private OpenRpc.JsonSchema primitive(final String type, final Boolean nullable) {
        return new OpenRpc.JsonSchema(null, null, null, null, type, nullable, null, null, null, null, null, null, null);
    }

    private OpenRpc.JsonSchema described(final String description) {
        return new OpenRpc.JsonSchema(
                null, null, null, null, "string", true, description, null, null, null, null, null, null);
    }

    private OpenRpc.JsonSchema ref(final String ref) {
        return new OpenRpc.JsonSchema(ref, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private OpenRpc.JsonSchema object(final Map<String, OpenRpc.JsonSchema> properties) {
        return new OpenRpc.JsonSchema(
                null, null, null, null, "object", true, null, null, null, properties, null, null, null);
    }

    private OpenRpc.JsonSchema array(final OpenRpc.JsonSchema items) {
        return new OpenRpc.JsonSchema(null, null, null, null, "array", true, null, null, null, null, null, items, null);
    }
}
