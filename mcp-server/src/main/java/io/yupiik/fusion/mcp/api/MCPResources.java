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

import io.yupiik.fusion.mcp.model.ReadResourceResponse;
import io.yupiik.fusion.mcp.model.Resource;
import io.yupiik.fusion.mcp.model.ResourceTemplate;
import java.util.List;
import java.util.Optional;

/**
 * Exposes resources - files, database rows, API payloads, ... - to the client.
 * <p>
 * Unlike tools and prompts, resources are dynamic by nature - they are data, not code - so they are not mapped on
 * JSON-RPC methods but provided at runtime by any bean implementing this interface:
 * <pre>{@code
 * @ApplicationScoped
 * public class MyResources implements MCPResources {
 *     @Override
 *     public List<Resource> resources() {
 *         return List.of(Resource.of("app://config", "config", "application/json", "The application configuration."));
 *     }
 *
 *     @Override
 *     public Optional<ReadResourceResponse> read(final String uri) {
 *         return "app://config".equals(uri) ?
 *             Optional.of(ReadResourceResponse.of(ResourceContents.text(uri, "application/json", "{}"))) :
 *             Optional.empty();
 *     }
 * }
 * }</pre>
 * All the implementations are used, they are visited in bean priority order ({@code @Order}) until one
 * {@link #read(String)} returns a value. The {@code resources} capability - and the related JSON-RPC methods - is
 * only advertised to the client when there is at least one implementation.
 * <p>
 * Use {@link MCPNotifier} to tell the clients a resource changed ({@code resourceUpdated}) or that the list itself
 * changed ({@code resourceListChanged}).
 */
public interface MCPResources {
    /**
     * @return the resources to list in {@code resources/list}, they must have a stable uri.
     */
    default List<Resource> resources() {
        return List.of();
    }

    /**
     * @return the parameterized resources to list in {@code resources/templates/list}.
     */
    default List<ResourceTemplate> resourceTemplates() {
        return List.of();
    }

    /**
     * Reads a resource, it can be one of {@link #resources()} or one an entry of {@link #resourceTemplates()}
     * expands to.
     *
     * @param uri the resource to read.
     * @return the resource contents or {@link Optional#empty()} when this provider does not know {@code uri}.
     */
    default Optional<ReadResourceResponse> read(final String uri) {
        return Optional.empty();
    }
}
