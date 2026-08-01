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
package io.yupiik.fusion.mcp.demo;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;
import io.yupiik.fusion.mcp.api.MCPNotifier;
import io.yupiik.fusion.mcp.api.MCPResources;
import io.yupiik.fusion.mcp.model.ReadResourceResponse;
import io.yupiik.fusion.mcp.model.Resource;
import io.yupiik.fusion.mcp.model.ResourceContents;
import io.yupiik.fusion.mcp.model.ResourceTemplate;

import java.util.List;
import java.util.Optional;

/**
 * Exposes two resources: a static one and a parameterized one.
 */
@ApplicationScoped
public class DemoResources implements MCPResources {
    private static final String GREETING_URI = "demo://greeting";
    private static final String ECHO_PREFIX = "demo://echo/";

    private final MCPNotifier notifier;

    public DemoResources(final MCPNotifier notifier) {
        this.notifier = notifier;
    }

    @Override
    public List<Resource> resources() {
        return List.of(Resource.of(GREETING_URI, "greeting", "text/plain", "A greeting from Fusion."));
    }

    @Override
    public List<ResourceTemplate> resourceTemplates() {
        return List.of(ResourceTemplate.of(ECHO_PREFIX + "{message}", "echo", "text/plain", "Echoes the message of the uri."));
    }

    @Override
    public Optional<ReadResourceResponse> read(final String uri) {
        if (GREETING_URI.equals(uri)) {
            return Optional.of(ReadResourceResponse.of(ResourceContents.text(uri, "text/plain", "hello fusion!")));
        }
        if (uri != null && uri.startsWith(ECHO_PREFIX)) {
            return Optional.of(ReadResourceResponse.of(
                    ResourceContents.text(uri, "text/plain", uri.substring(ECHO_PREFIX.length()))));
        }
        return Optional.empty();
    }

    /**
     * How to tell the clients which subscribed with {@code resources/subscribe} that a resource changed.
     */
    public void onGreetingChanged() {
        notifier.resourceUpdated(GREETING_URI);
    }
}
