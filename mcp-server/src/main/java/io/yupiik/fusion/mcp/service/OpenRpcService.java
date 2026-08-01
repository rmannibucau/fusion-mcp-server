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

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.mcp.model.fusion.OpenRpc;

import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Objects.requireNonNull;
import static java.util.Optional.ofNullable;

/**
 * Reads the OpenRPC documents the Fusion annotation processor generates and flattens their {@code $ref}s since
 * MCP clients get plain JSON-Schemas.
 */
@ApplicationScoped
public class OpenRpcService {
    private static final String OPENRPC_LOCATION = "META-INF/fusion/jsonrpc/openrpc.json";
    private static final String REF_PREFIX = "#/schemas/";

    private final JsonMapper jsons;

    protected OpenRpcService() {
        this(null);
    }

    public OpenRpcService(final JsonMapper jsonMapper) {
        this.jsons = jsonMapper;
    }

    /**
     * Loads and merges <b>all</b> the OpenRPC documents visible from the classpath.
     * <p>
     * There is one document per module using the Fusion processor - this library has one for the MCP protocol
     * methods and the application has one for its own methods - so reading a single resource would drop the
     * methods of all the other modules depending on the classpath ordering.
     *
     * @return the merged document.
     */
    public OpenRpc load() {
        final var loader = ofNullable(Thread.currentThread().getContextClassLoader())
                .orElseGet(OpenRpcService.class::getClassLoader);
        final var schemas = new HashMap<String, OpenRpc.JsonSchema>();
        final var methods = new HashMap<String, OpenRpc.JsonRpcMethod>();
        try {
            final var documents = loader.getResources(OPENRPC_LOCATION);
            while (documents.hasMoreElements()) {
                try (final var in = new InputStreamReader(documents.nextElement().openStream(), UTF_8)) {
                    final var document = requireNonNull(jsons.read(OpenRpc.class, in), () -> "Empty " + OPENRPC_LOCATION);
                    if (document.schemas() != null) {
                        schemas.putAll(document.schemas());
                    }
                    if (document.methods() != null) {
                        methods.putAll(document.methods());
                    }
                }
            }
        } catch (final IOException e) {
            throw new IllegalStateException(e);
        }
        return new OpenRpc(schemas, methods);
    }

    /**
     * Inlines the {@code $ref}s of the document shared schemas.
     *
     * @param openRpc the document to resolve.
     * @return the schemas by name, {@code $ref} free.
     */
    public Map<String, OpenRpc.JsonSchema> resolveSchemas(final OpenRpc openRpc) {
        final var world = openRpc.schemas() == null ? Map.<String, OpenRpc.JsonSchema>of() : openRpc.schemas();
        final var resolved = new HashMap<String, OpenRpc.JsonSchema>(world.size());
        for (final var schema : world.entrySet()) {
            resolved.put(
                    schema.getKey(),
                    ofNullable(resolveRefs(world, schema.getValue(), new HashSet<>(Set.of(schema.getKey()))))
                            .orElseGet(schema::getValue));
        }
        return resolved;
    }

    /**
     * Inlines the {@code $ref}s of a single schema.
     *
     * @param world  the schemas the {@code $ref}s point to.
     * @param schema the schema to resolve.
     * @return the resolved schema or {@code null} when there was nothing to inline.
     */
    public OpenRpc.JsonSchema resolveRefs(final Map<String, OpenRpc.JsonSchema> world, final OpenRpc.JsonSchema schema) {
        return resolveRefs(world, schema, new HashSet<>());
    }

    // visitedRefs breaks the recursion for self referencing models - a tree node with children of its own type
    // for example - which would else blow the stack up
    private OpenRpc.JsonSchema resolveRefs(final Map<String, OpenRpc.JsonSchema> world, final OpenRpc.JsonSchema schema,
                                           final Set<String> visitedRefs) {
        if (schema == null) {
            return null;
        }

        final var ref = schema.ref();
        if (ref != null && !Objects.equals(ref, schema.id())) {
            final var name = ref.startsWith(REF_PREFIX) ? ref.substring(REF_PREFIX.length()) : ref;
            if (!visitedRefs.add(name)) { // cycle, stop there with an opaque object
                return opaque(schema);
            }
            try {
                final var jsonSchema = world.get(name);
                if (jsonSchema == null) {
                    // an unresolvable reference - a document referencing a schema of a module which is not deployed -
                    // must not leak as a dangling $ref, MCP clients get no schema registry to look it up in
                    return opaque(schema);
                }
                return ofNullable(resolveRefs(world, jsonSchema, visitedRefs)).orElse(jsonSchema);
            } finally {
                visitedRefs.remove(name);
            }
        }

        if ("object".equals(schema.type())) {
            // allocate only if one nested schema resolves
            Map<String, OpenRpc.JsonSchema> newProperties = null;
            if (schema.properties() != null) {
                for (final var prop : schema.properties().entrySet()) {
                    final var resolved = resolveRefs(world, prop.getValue(), visitedRefs);
                    if (resolved != null) {
                        if (newProperties == null) {
                            newProperties = new HashMap<>(schema.properties());
                        }
                        newProperties.put(prop.getKey(), resolved);
                    }
                }
            }

            // additional properties, i.e. a Map<String, X> - note it has no property at all in that case
            Object additionalProps = schema.additionalProperties();
            if (additionalProps instanceof Map<?, ?>) {
                final var addPropSchema = jsons.fromString(OpenRpc.JsonSchema.class, jsons.toString(additionalProps));
                final var additionalPropsResolved = resolveRefs(world, addPropSchema, visitedRefs);
                additionalProps = additionalPropsResolved != null ? additionalPropsResolved : additionalProps;
            }

            if (newProperties != null || additionalProps != schema.additionalProperties()) {
                return new OpenRpc.JsonSchema(
                        null, null,
                        schema.type(), schema.nullable(), schema.description(), schema.format(), schema.pattern(),
                        newProperties == null ? schema.properties() : newProperties, additionalProps,
                        schema.items(), schema.enumeration());
            }
        } else if ("array".equals(schema.type()) && schema.items() != null) {
            final var newItems = resolveRefs(world, schema.items(), visitedRefs);
            if (newItems != null) {
                return new OpenRpc.JsonSchema(
                        null, null, schema.type(), schema.nullable(), schema.description(), schema.format(), schema.pattern(),
                        schema.properties(), schema.additionalProperties(), newItems, schema.enumeration());
            }
        }
        return null;
    }

    /**
     * @param schema the schema which cannot be expanded.
     * @return an object accepting anything, i.e. the most precise thing which can be said without the reference.
     */
    private OpenRpc.JsonSchema opaque(final OpenRpc.JsonSchema schema) {
        return new OpenRpc.JsonSchema(
                null, null, "object", schema.nullable(), schema.description(), null, null, null, true, null, null);
    }
}
