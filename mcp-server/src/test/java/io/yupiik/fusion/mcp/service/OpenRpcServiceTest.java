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

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.mcp.model.fusion.OpenRpc;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        assertTrue(document.methods().containsKey("initialize"), () -> document.methods().keySet().toString());
        assertTrue(document.methods().containsKey("tools/call"), () -> document.methods().keySet().toString());
        assertNotNull(document.schemas().get("io.yupiik.fusion.mcp.model.InitializeResponse"));
    }

    @Test
    void inlineNestedObject(@Fusion final JsonMapper jsons) {
        final var service = new OpenRpcService(jsons);
        final var world = Map.of("Child", object(Map.of("name", primitive("string"))));

        final var resolved = service.resolveRefs(world, object(Map.of("child", ref("#/schemas/Child"))));

        assertNotNull(resolved);
        assertEquals("object", resolved.properties().get("child").type());
        assertEquals("string", resolved.properties().get("child").properties().get("name").type());
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
                null, null, "object", null, null, null, null, null,
                Map.of("$ref", "#/schemas/Value"), null, null);

        final var resolved = service.resolveRefs(world, map);

        assertNotNull(resolved);
        assertTrue(resolved.additionalProperties() instanceof OpenRpc.JsonSchema, () -> String.valueOf(resolved.additionalProperties()));
        assertEquals("string", ((OpenRpc.JsonSchema) resolved.additionalProperties()).properties().get("v").type());
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
                null, "Node", "object", null, null, null, null,
                Map.of("name", primitive("string"), "child", ref("#/schemas/Node")),
                null, null, null);

        final var resolved = assertTimeoutPreemptively(
                java.time.Duration.ofSeconds(5),
                () -> service.resolveRefs(Map.of("Node", node), node));

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
                java.time.Duration.ofSeconds(5),
                () -> service.resolveSchemas(new OpenRpc(world, Map.of())));

        assertEquals(2, resolved.size());
        assertNull(resolved.get("Parent").properties().get("child").ref(), "the child must be inlined");
        assertEquals("object", resolved.get("Parent").properties().get("child").properties().get("parent").type());
    }

    @Test
    void resolveSchemasKeepsEveryEntry(@Fusion final JsonMapper jsons) {
        final var service = new OpenRpcService(jsons);
        final var world = Map.of(
                "Flat", object(Map.of("name", primitive("string"))),
                "Wrapper", object(Map.of("flat", ref("#/schemas/Flat"))));

        final var resolved = service.resolveSchemas(new OpenRpc(world, Map.of()));

        assertEquals(List.of("Flat", "Wrapper"), resolved.keySet().stream().sorted().toList());
        assertEquals("string", resolved.get("Wrapper").properties().get("flat").properties().get("name").type());
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

    private OpenRpc.JsonSchema primitive(final String type) {
        return new OpenRpc.JsonSchema(null, null, type, true, null, null, null, null, null, null, null);
    }

    private OpenRpc.JsonSchema ref(final String ref) {
        return new OpenRpc.JsonSchema(ref, null, null, null, null, null, null, null, null, null, null);
    }

    private OpenRpc.JsonSchema object(final Map<String, OpenRpc.JsonSchema> properties) {
        return new OpenRpc.JsonSchema(null, null, "object", null, null, null, null, properties, null, null, null);
    }

    private OpenRpc.JsonSchema array(final OpenRpc.JsonSchema items) {
        return new OpenRpc.JsonSchema(null, null, "array", null, null, null, null, null, null, items, null);
    }
}
