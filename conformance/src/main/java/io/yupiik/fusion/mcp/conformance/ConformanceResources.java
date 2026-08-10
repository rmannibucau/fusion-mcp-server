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

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.mcp.api.MCPResources;
import io.yupiik.fusion.mcp.model.ReadResourceResponse;
import io.yupiik.fusion.mcp.model.Resource;
import io.yupiik.fusion.mcp.model.ResourceContents;
import io.yupiik.fusion.mcp.model.ResourceTemplate;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class ConformanceResources implements MCPResources {
    public static final String TEXT_URI = "test://static-text";
    public static final String BINARY_URI = "test://static-binary";
    public static final String WATCHED_URI = "test://watched-resource";
    private static final String TEMPLATE_PREFIX = "test://template/";

    @Override
    public List<Resource> resources() {
        return List.of(
                Resource.of(TEXT_URI, "static-text", "text/plain", "A conformance static text resource."),
                Resource.of(BINARY_URI, "static-binary", "image/png", "A conformance binary resource."),
                Resource.of(WATCHED_URI, "watched-resource", "text/plain", "A watched resource."));
    }

    @Override
    public List<ResourceTemplate> resourceTemplates() {
        return List.of(ResourceTemplate.of(
                TEMPLATE_PREFIX + "{id}/data", "echo", "text/plain", "Echoes the id of one of the uri segments."));
    }

    @Override
    public Optional<ReadResourceResponse> read(final String uri) {
        if (TEXT_URI.equals(uri)) {
            return Optional.of(
                    ReadResourceResponse.of(ResourceContents.text(TEXT_URI, "text/plain", "This is a text resource")));
        }
        if (BINARY_URI.equals(uri)) {
            final var png = Base64.getDecoder()
                    .decode(
                            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==");
            return Optional.of(ReadResourceResponse.of(ResourceContents.blob(BINARY_URI, "image/png", png)));
        }
        if (WATCHED_URI.equals(uri)) {
            return Optional.of(
                    ReadResourceResponse.of(ResourceContents.text(WATCHED_URI, "text/plain", "watched content")));
        }
        if (uri != null && uri.startsWith(TEMPLATE_PREFIX) && uri.endsWith("/data")) {
            // test://template/<id>/data structure parameter is echoed back, so substitution is observable
            final var id = uri.substring(TEMPLATE_PREFIX.length(), uri.length() - "/data".length());
            return Optional.of(ReadResourceResponse.of(ResourceContents.text(uri, "text/plain", id)));
        }
        return Optional.empty();
    }
}
