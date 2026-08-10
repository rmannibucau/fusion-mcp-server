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

import static java.util.Optional.ofNullable;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.jsonrpc.JsonRpcRegistry;
import io.yupiik.fusion.jsonrpc.impl.JsonRpcMethod;
import io.yupiik.fusion.mcp.api.MCPCompletions;
import io.yupiik.fusion.mcp.api.MCPIcon;
import io.yupiik.fusion.mcp.api.MCPJsonSchema202012;
import io.yupiik.fusion.mcp.api.MCPPrompt;
import io.yupiik.fusion.mcp.api.MCPTool;
import io.yupiik.fusion.mcp.api.MCPToolSchemas;
import io.yupiik.fusion.mcp.model.CompleteResult;
import io.yupiik.fusion.mcp.model.CompletionArgument;
import io.yupiik.fusion.mcp.model.CompletionContext;
import io.yupiik.fusion.mcp.model.CompletionRef;
import io.yupiik.fusion.mcp.model.Icon;
import io.yupiik.fusion.mcp.model.JsonSchema;
import io.yupiik.fusion.mcp.model.ListPromptsResponse;
import io.yupiik.fusion.mcp.model.ListToolsResponse;
import io.yupiik.fusion.mcp.model.fusion.OpenRpc;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Turns the JSON-RPC methods flagged with {@link MCPTool}/{@link MCPPrompt} into MCP descriptors.
 * <p>
 * Everything is computed once at startup: the flag comes from the bean metadata the Fusion annotation processor
 * generates ({@code mcp.type}) and the documentation/schemas come from the generated OpenRPC document, so a tool
 * is fully described for the model without any additional annotation.
 */
@ApplicationScoped
public class DescriptorService {
    /**
     * Bean metadata key {@link MCPTool}/{@link MCPPrompt} set, see {@code BeanMetadataAlias}.
     */
    public static final String TYPE_METADATA = "mcp.type";

    /**
     * Bean metadata key {@link MCPJsonSchema202012} sets, the JSON-Schema dialect of the tool {@code inputSchema}.
     */
    public static final String SCHEMA_DIALECT_METADATA = "mcp.schema.dialect";

    /**
     * The first protocol version whose specification makes JSON-Schema 2020-12 mandatory for tool {@code inputSchema}s
     * ({@code $schema}, {@code $defs}, {@code $ref}, {@code additionalProperties:false}). Since then the conversion is
     * implicit and the {@code @MCPJsonSchema202012} annotation is only an explicit - legacy forcing - opt-in.
     */
    public static final String JSON_SCHEMA_2020_12_SINCE = "2025-11-25";

    /**
     * The {@code $schema} value of the JSON-Schema 2020-12 dialect.
     */
    public static final String JSON_SCHEMA_2020_12_DIALECT = "https://json-schema.org/draft/2020-12/schema";

    /**
     * Bean metadata key {@link MCPIcon} sets, the icon source uri.
     */
    public static final String ICON_METADATA = "mcp.icon";

    public static final String TOOL = "tool";
    public static final String PROMPT = "prompt";
    public static final String COMPLETION = "completion";

    private final ListToolsResponse tools;
    private final ListPromptsResponse prompts;
    private final Set<String> toolNames;
    private final Set<String> promptNames;
    private final List<MCPCompletions> completionProviders;
    private final Map<String, JsonSchema> toolSchemas;
    private final JsonMapper jsons;

    protected DescriptorService() {
        this.tools = null;
        this.prompts = null;
        this.toolNames = Set.of();
        this.promptNames = Set.of();
        this.completionProviders = List.of();
        this.toolSchemas = Map.of();
        this.jsons = null;
    }

    public DescriptorService(
            final OpenRpcService openRpcService,
            final JsonRpcRegistry registry,
            final JsonMapper jsons,
            final List<MCPToolSchemas> toolSchemas) {
        this.jsons = jsons;
        this.toolSchemas = toolSchemas.stream()
                .flatMap(it -> it.toolSchemas().entrySet().stream())
                .collect(toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> b, LinkedHashMap::new));

        final var openrpc = openRpcService.load();
        final var schemas = openRpcService.resolveSchemas(openrpc);

        this.tools = new ListToolsResponse(
                methods(openrpc, registry, TOOL)
                        .map(it -> toTool(
                                openRpcService, schemas, registry.methods().get(it.name()), it))
                        .toList(),
                // no pagination, all the descriptors are computed at startup and sent at once
                null,
                null,
                null,
                null,
                null);
        this.prompts = new ListPromptsResponse(
                methods(openrpc, registry, PROMPT)
                        .map(it -> toPrompt(registry.methods().get(it.name()), it))
                        .toList(),
                null,
                null,
                null,
                null,
                null);
        this.toolNames =
                tools.tools().stream().map(ListToolsResponse.Tool::name).collect(toSet());
        this.promptNames =
                prompts.prompts().stream().map(ListPromptsResponse.Prompt::name).collect(toSet());
        // the @MCPCompletion methods are turned into MCPCompletions providers, appended after the programmatic ones
        this.completionProviders = registry.methods().values().stream()
                .filter(it -> COMPLETION.equals(it.metadata().getOrDefault(TYPE_METADATA, "")))
                .sorted(Comparator.comparing(JsonRpcMethod::name))
                .map(it -> (MCPCompletions) new RefIgnoringCompletion(jsons, it))
                .toList();
    }

    /**
     * @return the {@code @MCPCompletion} methods adapted to {@link MCPCompletions}, they complete any argument without
     * a {@code ref} binding.
     */
    public List<MCPCompletions> completionProviders() {
        return completionProviders;
    }

    /**
     * @return the {@code tools/list} result.
     */
    public ListToolsResponse tools() {
        return tools;
    }

    /**
     * @param protocolVersion the negotiated protocol version, {@code null} when unknown.
     * @return the {@code tools/list} result for that version: since {@code 2025-11-25} the JSON-Schema 2020-12
     * specification makes the {@code $schema}/{@code $defs}/{@code $ref}/{@code additionalProperties} conversion
     * implicit, so the input schemas are converted on the fly when the version requests it.
     */
    public ListToolsResponse tools(final String protocolVersion) {
        if (protocolVersion == null || protocolVersion.compareTo(JSON_SCHEMA_2020_12_SINCE) < 0) {
            return tools;
        }
        return new ListToolsResponse(
                tools.tools().stream()
                        .map(it -> new ListToolsResponse.Tool(
                                it.metadata(),
                                it.annotations(),
                                it.title(),
                                it.name(),
                                it.description(),
                                toJsonSchema202012(it.inputSchema(), JSON_SCHEMA_2020_12_DIALECT),
                                it.outputSchema(),
                                it.icons()))
                        .toList(),
                tools.nextCursor(),
                tools.resultType(),
                tools.ttlMs(),
                tools.cacheScope(),
                tools.metadata());
    }

    /**
     * @return the {@code prompts/list} result.
     */
    public ListPromptsResponse prompts() {
        return prompts;
    }

    /**
     * @param name a JSON-RPC method name.
     * @return {@code true} if {@code name} is a tool, i.e. if {@code tools/call} can invoke it.
     */
    public boolean isTool(final String name) {
        return toolNames.contains(name);
    }

    /**
     * @param name a JSON-RPC method name.
     * @return {@code true} if {@code name} is a prompt, i.e. if {@code prompts/get} can invoke it.
     */
    public boolean isPrompt(final String name) {
        return promptNames.contains(name);
    }

    /**
     * @return the arguments of a prompt, empty if {@code name} is not a prompt.
     */
    public Set<String> promptArguments(final String name) {
        return prompts.prompts().stream()
                .filter(it -> it.name().equals(name))
                .findFirst()
                .map(it -> it.arguments().stream()
                        .map(ListPromptsResponse.Prompt.Argument::name)
                        .collect(toSet()))
                .orElseGet(Set::of);
    }

    private Stream<OpenRpc.JsonRpcMethod> methods(
            final OpenRpc openrpc, final JsonRpcRegistry registry, final String type) {
        return openrpc.methods().values().stream()
                // a document can describe a method which is not deployed - a module on the classpath but not used -
                // so ignore the ones the registry does not know
                .filter(it -> registry.methods().containsKey(it.name()))
                .filter(it ->
                        type.equals(registry.methods().get(it.name()).metadata().getOrDefault(TYPE_METADATA, "")))
                .sorted(Comparator.comparing(OpenRpc.JsonRpcMethod::name));
    }

    private ListToolsResponse.Tool toTool(
            final OpenRpcService openRpcService,
            final Map<String, OpenRpc.JsonSchema> schemas,
            final JsonRpcMethod method,
            final OpenRpc.JsonRpcMethod descriptor) {
        final var result = descriptor.result();
        // the OpenRPC document describes what is serialized, so an asynchronous tool - CompletionStage<T> - has the
        // schema of T (needs Fusion >= 1.0.38)
        final boolean noOutput = method.isNotification()
                || result == null
                || result.schema() == null
                || "null".equals(result.schema().type());
        final var derivedInput = JsonSchema.object(
                "Input request for " + descriptor.name(),
                // keep the declaration order, it is both nicer to read and stable across runs
                descriptor.params().stream()
                        .collect(toMap(
                                OpenRpc.JsonRpcMethod.Parameter::name,
                                p -> toParameterSchema(openRpcService, schemas, p),
                                (a, b) -> a,
                                LinkedHashMap::new)),
                descriptor.params().stream()
                        .filter(DescriptorService::isRequired)
                        .map(OpenRpc.JsonRpcMethod.Parameter::name)
                        .sorted()
                        .toList());
        final var dialect = method.metadata().get(SCHEMA_DIALECT_METADATA);
        final var customSchema = toolSchemas.get(descriptor.name());
        final var inputSchema = customSchema != null
                ? customSchema // an explicit MCPToolSchemas entry wins, it is already in its final dialect
                : dialect == null ? derivedInput : toJsonSchema202012(derivedInput, dialect);
        return new ListToolsResponse.Tool(
                null,
                null,
                descriptor.name(),
                descriptor.name(),
                descriptor.description(),
                inputSchema,
                noOutput
                        ? null
                        : toMcpSchema(ofNullable(openRpcService.resolveRefs(schemas, result.schema()))
                                .orElseGet(result::schema)),
                icons(method.metadata()));
    }

    /**
     * @param metadata the bean metadata of a tool or prompt method.
     * @return the {@code @MCPIcon} sources as {@link Icon} blocks, empty when none is declared.
     */
    private List<Icon> icons(final Map<String, String> metadata) {
        final var raw = metadata.get(ICON_METADATA);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        // the annotation processor stores a string attribute literal as-is, a surrounding quote is expected
        final var src = raw.length() >= 2 && raw.startsWith("\"") && raw.endsWith("\"")
                ? raw.substring(1, raw.length() - 1)
                : raw;
        return List.of(Icon.of(src));
    }

    /**
     * @return the schema of a tool parameter, never {@code null}: a parameter which has none - which a handcrafted
     * document can have, Fusion always sets one - keeps its entry in the enclosing {@code inputSchema} with an empty
     * schema, i.e. one accepting anything.
     */
    private JsonSchema toParameterSchema(
            final OpenRpcService openRpcService,
            final Map<String, OpenRpc.JsonSchema> schemas,
            final OpenRpc.JsonRpcMethod.Parameter parameter) {
        final var description = description(parameter);
        final var schema = ofNullable(openRpcService.resolveRefs(schemas, parameter.schema()))
                .orElseGet(parameter::schema);
        return ofNullable(toMcpSchema(schema, description)).orElseGet(() -> JsonSchema.of(null, description));
    }

    private ListPromptsResponse.Prompt toPrompt(final JsonRpcMethod method, final OpenRpc.JsonRpcMethod descriptor) {
        return new ListPromptsResponse.Prompt(
                null,
                descriptor.name(),
                descriptor.name(),
                descriptor.description(),
                // MCP prompt arguments are always strings
                descriptor.params().stream()
                        .map(p -> new ListPromptsResponse.Prompt.Argument(
                                p.name(), p.name(), description(p), isRequired(p)))
                        .toList(),
                icons(method.metadata()));
    }

    /**
     * @return the {@code @JsonRpcParam} documentation, Fusion sets it on the OpenRPC parameter and not on its schema.
     */
    private static String description(final OpenRpc.JsonRpcMethod.Parameter parameter) {
        if (parameter.description() != null && !parameter.description().isBlank()) {
            return parameter.description();
        }
        return parameter.schema() == null ? null : parameter.schema().description();
    }

    private static boolean isRequired(final OpenRpc.JsonRpcMethod.Parameter parameter) {
        if (parameter.required() != null) {
            return parameter.required();
        }
        // Fusion marks the schema of a required parameter - and of a primitive one - as not nullable
        final var schema = parameter.schema();
        return schema != null && schema.nullable() != null && !schema.nullable();
    }

    /**
     * Converts a Fusion OpenRPC schema to a MCP one.
     * <p>
     * The main difference is optionality: OpenRPC/OpenAPI uses a {@code nullable} flag per schema whereas
     * JSON-Schema - so MCP - uses a {@code required} list on the enclosing object, so {@code nullable} is dropped
     * and folded into the parent {@code required}.
     */
    private JsonSchema toMcpSchema(final OpenRpc.JsonSchema schema) {
        return toMcpSchema(schema, null);
    }

    /**
     * Converts a derived MCP tool {@code inputSchema} to a JSON-Schema 2020-12 document: the {@code $schema} keyword
     * is added with {@code dialect}, the nested object properties are moved to {@code $defs} and referenced through
     * {@code $ref}, and {@code additionalProperties} is set to {@code false}. This is the schema a
     * {@code @MCPJsonSchema202012} tool advertises in {@code tools/list}.
     *
     * @param input   the schema derived from the Java signature.
     * @param dialect the JSON-Schema dialect, the {@code $schema} value.
     * @return the 2020-12 equivalent.
     */
    private JsonSchema toJsonSchema202012(final JsonSchema input, final String dialect) {
        if (input == null || input.schema() != null) { // already 2020-12 - or nothing to convert
            return input;
        }
        final var defs = new LinkedHashMap<String, JsonSchema>();
        final var properties = new LinkedHashMap<String, JsonSchema>();
        for (final var entry : input.properties().entrySet()) {
            final var value = entry.getValue();
            if ("object".equals(value.type())) { // a nested model becomes a named definition
                defs.put(entry.getKey(), value);
                properties.put(entry.getKey(), JsonSchema.of("object", null).withRef("#/$defs/" + entry.getKey()));
            } else {
                properties.put(entry.getKey(), value);
            }
        }
        return JsonSchema.object(input.description(), properties, false, input.required())
                .withDialect(dialect)
                .withDefs(defs);
    }

    /**
     * @param schema      the schema to convert.
     * @param description the description to use, it overrides the schema one - a {@code @JsonRpcParam} documentation
     *                    is carried by the OpenRPC parameter and not by its schema.
     */
    private JsonSchema toMcpSchema(final OpenRpc.JsonSchema schema, final String description) {
        if (schema == null) {
            return null;
        }
        return new JsonSchema(
                schema.type(),
                null,
                description == null ? schema.description() : description,
                schema.format(),
                schema.pattern(),
                schema.properties() == null
                        ? null
                        : schema.properties().entrySet().stream()
                                .collect(toMap(
                                        Map.Entry::getKey,
                                        it -> toMcpSchema(it.getValue()),
                                        (a, b) -> a,
                                        LinkedHashMap::new)),
                schema.additionalProperties() instanceof Map<?, ?>
                        ? toMcpSchema(jsons.fromString(
                                OpenRpc.JsonSchema.class, jsons.toString(schema.additionalProperties())))
                        : schema.additionalProperties(),
                toMcpSchema(schema.items()),
                schema.enumeration(),
                null,
                schema.properties() == null
                        ? null
                        : schema.properties().entrySet().stream()
                                .filter(it -> it.getValue().nullable() != null
                                        && !it.getValue().nullable())
                                .map(Map.Entry::getKey)
                                .sorted()
                                .toList(),
                null,
                null,
                null,
                null,
                null,
                schema.schema(),
                schema.defs() == null
                        ? null
                        : schema.defs().entrySet().stream()
                                .collect(toMap(
                                        Map.Entry::getKey,
                                        it -> toMcpSchema(it.getValue()),
                                        (a, b) -> a,
                                        LinkedHashMap::new)),
                schema.ref(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    /**
     * Adapts a {@code @MCPCompletion} JSON-RPC method to an {@link MCPCompletions}: the {@code ref} is ignored, the
     * method only receives the {@link CompletionArgument} and the {@link CompletionContext}.
     */
    private static final class RefIgnoringCompletion implements MCPCompletions {
        private final JsonMapper jsons;
        private final JsonRpcMethod method;

        private RefIgnoringCompletion(final JsonMapper jsons, final JsonRpcMethod method) {
            this.jsons = jsons;
            this.method = method;
        }

        @Override
        public CompleteResult.Completion complete(
                final CompletionRef ref, final CompletionArgument argument, final CompletionContext context) {
            final var params = new LinkedHashMap<String, Object>();
            if (argument != null) {
                params.put("argument", asMap(argument));
            }
            if (context != null) {
                params.put("context", asMap(context));
            }
            final Object result;
            try {
                result = method.invoke(new JsonRpcMethod.Context(null, params))
                        .toCompletableFuture()
                        .join();
            } catch (final RuntimeException re) {
                return null;
            }
            return toCompletion(result);
        }

        private Map<String, Object> asMap(final Object value) {
            return jsons.fromString(java.util.Map.class, jsons.toString(value));
        }

        private CompleteResult.Completion toCompletion(final Object result) {
            if (result instanceof CompleteResult.Completion completion) {
                return completion;
            }
            if (result instanceof List<?> values) {
                final var strings = values.stream().map(String::valueOf).toList();
                return new CompleteResult.Completion(false, strings.size(), List.copyOf(strings));
            }
            return null;
        }
    }
}
