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

import io.yupiik.fusion.mcp.model.ListPromptsResponse;
import io.yupiik.fusion.mcp.model.ListToolsResponse;
import io.yupiik.fusion.mcp.service.DescriptorService;
import io.yupiik.fusion.mcp.service.OpenRpcService;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the descriptors are built from <b>all</b> the OpenRPC documents of the classpath, this module having one and
 * the {@code mcp-server} one having another.
 */
@FusionSupport
class DescriptorsTest {
    @Test
    void allDocumentsAreMerged(@Fusion final OpenRpcService openRpcService) {
        final var methods = openRpcService.load().methods().keySet();

        assertTrue(methods.contains("demo/tool"), methods::toString); // this module
        assertTrue(methods.contains("initialize"), methods::toString); // the mcp-server module
        assertTrue(methods.contains("tools/call"), methods::toString);
    }

    @Test
    void onlyFlaggedMethodsAreExposed(@Fusion final DescriptorService descriptors) {
        assertEquals(
                List.of("demo/ask", "demo/greet", "demo/log", "demo/tool"),
                descriptors.tools().tools().stream().map(ListToolsResponse.Tool::name).toList());
        assertEquals(
                List.of("demo/prompt"),
                descriptors.prompts().prompts().stream().map(ListPromptsResponse.Prompt::name).toList());

        assertTrue(descriptors.isTool("demo/tool"));
        assertFalse(descriptors.isTool("demo/prompt"), "a prompt must not be callable as a tool");
        assertFalse(descriptors.isTool("initialize"), "a protocol method must not be callable as a tool");
        assertTrue(descriptors.isPrompt("demo/prompt"));
        assertFalse(descriptors.isPrompt("demo/tool"));
    }

    @Test
    void promptArguments(@Fusion final DescriptorService descriptors) {
        assertEquals(Set.of("code"), descriptors.promptArguments("demo/prompt"));
        assertEquals(Set.of(), descriptors.promptArguments("demo/tool"));
    }
}
