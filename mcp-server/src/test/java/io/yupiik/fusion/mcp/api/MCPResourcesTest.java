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
package io.yupiik.fusion.mcp.api;

import static io.yupiik.fusion.mcp.api.MCPResources.staticTextResources;
import static io.yupiik.fusion.mcp.api.MCPResources.text;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.mcp.model.Resource;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The {@link MCPResources} defaults: a static-text provider only declares its contents and its list, the
 * {@code read} comes for free, and the helpers build them.
 */
class MCPResourcesTest {
    @Test
    void theStaticContentsHelpersBuildBytesServingResponses() {
        assertEquals(
                "app://a",
                text("app://a", "text/plain", "content").contents().get(0).uri());

        // a default-provider with a static resource needs no read boilerplate
        final MCPResources provider = new MCPResources() {
            @Override
            public List<Resource> resources() {
                return List.of(Resource.of("app://a", "a", "text/plain", "A."));
            }

            @Override
            public java.util.Map<String, io.yupiik.fusion.mcp.model.ReadResourceResponse> staticContents() {
                return staticTextResources("app://a", "text/plain", "hello");
            }
        };

        final var read = provider.read("app://a");
        assertTrue(read.isPresent());
        assertEquals("hello", read.orElseThrow().contents().get(0).text());
        assertEquals(Optional.empty(), provider.read("app://missing"));

        // a provider which does not override read serves nothing
        @ApplicationScoped
        class Empty implements MCPResources {}
        assertEquals(Optional.empty(), new Empty().read("app://missing"));
    }
}
