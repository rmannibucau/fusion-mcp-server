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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.mcp.model.fusion.OpenRpc;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The {@code $ref} inlining: MCP clients get plain JSON-Schemas, so the shared schemas of the OpenRPC document must be
 * expanded - without looping on the recursive models.
 */
@FusionSupport
class OpenRpcServiceTest {
    @Test
    void loadMergesTheClasspathDocuments(@Fusion final OpenRpcService service) {
        final var document = service.load();

        // this module only has the MCP protocol methods, the application ones come from its own document
        assertTrue(
                document.methods().containsKey("initialize"),
                () -> document.methods().keySet().toString());
        assertTrue(
                document.methods().containsKey("tools/call"),
                () -> document.methods().keySet().toString());
        assertNotNull(document.schemas().get("io.yupiik.fusion.mcp.model.InitializeResponse"));
    }

    @Test
    void inlineNestedObject(@Fusion final JsonMapper jsons) {
        final var service = new OpenRpcService(jsons);
        final var world = Map.of("Child", object(Map.of("name", primitive("string"))));

        final var resolved = service.resolveRefs(world, object(Map.of("child", ref("#/schemas/Child"))));

        assertNotNull(resolved);
        assertEquals("object", resolved.properties().get("child").type());
        assertEquals(
                "string",
                resolved.properties().get("child").properties().get("name").type());
    }

    @Test
    void inlineArrayItems(@Fusion final JsonMapper jsons) {
        final var service = new OpenRpcService(jsons);
        final var world = Map.of("Item", object(Map.of("id", primitive("integer"))));

        final var resolved = service.resolveRefs(world, array(ref("#/schemas/Item")));

        assertNotNull(resolved);
        assertEquals("object", resolved.items().type());
        assertEquals("integer", resolved.items().properties().get("id").type());
    }

    @Test
    void inlineAdditionalProperties(@Fusion final JsonMapper jsons) {
        final var service = new OpenRpcService(jsons);
        final var world = Map.of("Value", object(Map.of("v", primitive("string"))));
        // a Map<String, Value> parameter: additionalProperties is read as a plain JSON structure
        final var map = new OpenRpc.JsonSchema(
                null,
                null,
                null,
                null,
                "object",
                null,
                null,
                null,
                null,
                null,
                Map.of("$ref", "#/schemas/Value"),
                null,
                null);

        final var resolved = service.resolveRefs(world, map);

        assertNotNull(resolved);
        assertTrue(
                resolved.additionalProperties() instanceof OpenRpc.JsonSchema,
                () -> String.valueOf(resolved.additionalProperties()));
        assertEquals(
                "string",
                ((OpenRpc.JsonSchema) resolved.additionalProperties())
                        .properties()
                        .get("v")
                        .type());
    }

    @Test
    void nothingToInline(@Fusion final JsonMapper jsons) {
        final var service = new OpenRpcService(jsons);

        // the contract is to return null when the schema is already flat, so callers can keep the original instance
        assertNull(service.resolveRefs(Map.of(), object(Map.of("name", primitive("string")))));
        assertNull(service.resolveRefs(Map.of(), primitive("string")));
        assertNull(service.resolveRefs(Map.of(), null));
    }

    @Test
    void selfReferencingModelDoesNotLoop(@Fusion final JsonMapper jsons) {
        final var service = new OpenRpcService(jsons);
        // a tree: Node { name, child: Node }
        final var node = new OpenRpc.JsonSchema(
                null,
                "Node",
                null,
                null,
                "object",
                null,
                null,
                null,
                null,
                Map.of("name", primitive("string"), "child", ref("#/schemas/Node")),
                null,
                null,
                null);

        final var resolved = assertTimeoutPreemptively(
                java.time.Duration.ofSeconds(5), () -> service.resolveRefs(Map.of("Node", node), node));

        assertNotNull(resolved);
        assertEquals("string", resolved.properties().get("name").type());

        // one level is expanded...
        final var child = resolved.properties().get("child");
        assertEquals("object", child.type());
        assertEquals("string", child.properties().get("name").type());

        // ...then the cycle is cut with an opaque object, which stays a valid JSON-Schema for a nested node
        final var grandChild = child.properties().get("child");
        assertEquals("object", grandChild.type());
        assertEquals(Boolean.TRUE, grandChild.additionalProperties());
        assertNull(grandChild.properties());
    }

    @Test
    void mutuallyRecursiveModelsDoNotLoop(@Fusion final JsonMapper jsons) {
        final var service = new OpenRpcService(jsons);
        // Parent { child: Child } and Child { parent: Parent }
        final var world = Map.of(
                "Parent", object(Map.of("child", ref("#/schemas/Child"))),
                "Child", object(Map.of("parent", ref("#/schemas/Parent"))));

        final var resolved = assertTimeoutPreemptively(
                java.time.Duration.ofSeconds(5), () -> service.resolveSchemas(new OpenRpc(world, Map.of())));

        assertEquals(2, resolved.size());
        assertNull(resolved.get("Parent").properties().get("child").ref(), "the child must be inlined");
        assertEquals(
                "object",
                resolved.get("Parent")
                        .properties()
                        .get("child")
                        .properties()
                        .get("parent")
                        .type());
    }

    @Test
    void resolveSchemasKeepsEveryEntry(@Fusion final JsonMapper jsons) {
        final var service = new OpenRpcService(jsons);
        final var world = Map.of(
                "Flat", object(Map.of("name", primitive("string"))),
                "Wrapper", object(Map.of("flat", ref("#/schemas/Flat"))));

        final var resolved = service.resolveSchemas(new OpenRpc(world, Map.of()));

        assertEquals(
                List.of("Flat", "Wrapper"), resolved.keySet().stream().sorted().toList());
        assertEquals(
                "string",
                resolved.get("Wrapper")
                        .properties()
                        .get("flat")
                        .properties()
                        .get("name")
                        .type());
    }

    @Test
    void missingRefBecomesOpaque(@Fusion final JsonMapper jsons) {
        final var service = new OpenRpcService(jsons);

        // an unresolvable reference must not leak as a dangling $ref: the client has no registry to look it up in
        final var resolved = service.resolveRefs(Map.of(), object(Map.of("gone", ref("#/schemas/Nope"))));

        assertNotNull(resolved);
        final var gone = resolved.properties().get("gone");
        assertEquals("object", gone.type());
        assertEquals(Boolean.TRUE, gone.additionalProperties());
        assertNull(gone.ref());
    }

    @Test
    void loadReadsEveryDocumentOfTheClassLoader(@Fusion final JsonMapper jsons) {
        // two modules using the Fusion processor, so two documents to merge - reading a single resource would drop
        // the methods of one of them depending on the classpath ordering
        final var loaded = withContextClassLoader(documents("""
                                {"schemas": {"A": {"type": "object"}}, "methods": {"a": {"name": "a"}}}""", """
                                {"schemas": {"B": {"type": "object"}}, "methods": {"b": {"name": "b"}}}"""), () -> new OpenRpcService(jsons).load());

        assertEquals(Set.of("A", "B"), loaded.schemas().keySet());
        assertEquals(Set.of("a", "b"), loaded.methods().keySet());
    }

    @Test
    void loadToleratesAPartialDocument(@Fusion final JsonMapper jsons) {
        // only schemas, only methods, and neither: a document does not have to have both
        final var loaded = withContextClassLoader(documents("""
                        {"schemas": {"A": {"type": "object"}}}""", """
                        {"methods": {"b": {"name": "b"}}}""", "{}"), () -> new OpenRpcService(jsons).load());

        assertEquals(Set.of("A"), loaded.schemas().keySet());
        assertEquals(Set.of("b"), loaded.methods().keySet());
    }

    @Test
    void anEmptyDocumentIsRejected(@Fusion final JsonMapper jsons) {
        // it must fail loudly: silently ignoring a document would drop all the methods of that module, and the
        // client would then be told a deployed tool does not exist
        assertThrows(
                IllegalStateException.class,
                () -> withContextClassLoader(documents("null"), () -> new OpenRpcService(jsons).load()));
        assertThrows(
                RuntimeException.class,
                () -> withContextClassLoader(documents(""), () -> new OpenRpcService(jsons).load()));
    }

    @Test
    void anUnreadableClassPathFails(@Fusion final JsonMapper jsons) {
        final var loader = new ClassLoader(null) {
            @Override
            public java.util.Enumeration<java.net.URL> getResources(final String name) throws IOException {
                throw new IOException("the jar is gone");
            }
        };

        final var error = assertThrows(
                IllegalStateException.class,
                () -> withContextClassLoader(loader, () -> new OpenRpcService(jsons).load()));

        assertInstanceOf(IOException.class, error.getCause());
        assertEquals("the jar is gone", error.getCause().getMessage());
    }

    @Test
    void loadFallsBackOnItsOwnClassLoader(@Fusion final JsonMapper jsons) {
        // no context class loader - a plain thread, or a native image - the document of this module must still be read
        final var loaded = withContextClassLoader(null, () -> new OpenRpcService(jsons).load());

        assertTrue(
                loaded.methods().containsKey("initialize"),
                () -> loaded.methods().keySet().toString());
    }

    @Test
    void aSchemaLessDocumentResolvesToNothing(@Fusion final JsonMapper jsons) {
        assertEquals(Map.of(), new OpenRpcService(jsons).resolveSchemas(new OpenRpc(null, Map.of())));
    }

    @Test
    void aSelfIdentifyingSchemaIsNotAReference(@Fusion final JsonMapper jsons) {
        final var service = new OpenRpcService(jsons);
        // Fusion sets both $id and $ref to the same value on the schema declaring a model, it is a definition and
        // not a reference to somewhere else
        final var declaration = new OpenRpc.JsonSchema(
                "#/schemas/Self",
                "#/schemas/Self",
                null,
                null,
                "object",
                null,
                null,
                null,
                null,
                Map.of("name", primitive("string")),
                null,
                null,
                null);

        assertNull(service.resolveRefs(Map.of("Self", declaration), declaration));
    }

    @Test
    void aReferenceWithoutThePrefixIsUsedAsIs(@Fusion final JsonMapper jsons) {
        final var service = new OpenRpcService(jsons);
        final var world = Map.of("Bare", object(Map.of("name", primitive("string"))));

        final var resolved = service.resolveRefs(world, object(Map.of("bare", ref("Bare"))));

        assertNotNull(resolved);
        assertEquals(
                "string",
                resolved.properties().get("bare").properties().get("name").type());
    }

    @Test
    void anArrayWithoutItemsHasNothingToInline(@Fusion final JsonMapper jsons) {
        assertNull(new OpenRpcService(jsons).resolveRefs(Map.of(), array(null)));
    }

    /**
     * @param bodies the content of one {@code openrpc.json} per module.
     * @return a loader serving them all under the location the processor generates.
     */
    private ClassLoader documents(final String... bodies) {
        return new ClassLoader(null) {
            @Override
            public java.util.Enumeration<java.net.URL> getResources(final String name) {
                assertEquals("META-INF/fusion/jsonrpc/openrpc.json", name);
                return java.util.Collections.enumeration(java.util.stream.IntStream.range(0, bodies.length)
                        .mapToObj(i -> inMemory(bodies[i]))
                        .toList());
            }
        };
    }

    private java.net.URL inMemory(final String body) {
        try {
            return java.net.URL.of(java.net.URI.create("memory:///openrpc.json"), new java.net.URLStreamHandler() {
                @Override
                protected java.net.URLConnection openConnection(final java.net.URL u) {
                    return new java.net.URLConnection(u) {
                        @Override
                        public void connect() {
                            // nothing to connect to
                        }

                        @Override
                        public java.io.InputStream getInputStream() {
                            return new java.io.ByteArrayInputStream(
                                    body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        }
                    };
                }
            });
        } catch (final java.net.MalformedURLException e) {
            throw new IllegalStateException(e);
        }
    }

    private <T> T withContextClassLoader(final ClassLoader loader, final java.util.function.Supplier<T> task) {
        final var thread = Thread.currentThread();
        final var previous = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
        try {
            return task.get();
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    private OpenRpc.JsonSchema primitive(final String type) {
        return new OpenRpc.JsonSchema(null, null, null, null, type, true, null, null, null, null, null, null, null);
    }

    private OpenRpc.JsonSchema ref(final String ref) {
        return new OpenRpc.JsonSchema(ref, null, null, null, null, null, null, null, null, null, null, null, null);
    }

    private OpenRpc.JsonSchema object(final Map<String, OpenRpc.JsonSchema> properties) {
        return new OpenRpc.JsonSchema(
                null, null, null, null, "object", null, null, null, null, properties, null, null, null);
    }

    private OpenRpc.JsonSchema array(final OpenRpc.JsonSchema items) {
        return new OpenRpc.JsonSchema(null, null, null, null, "array", null, null, null, null, null, null, items, null);
    }
}
