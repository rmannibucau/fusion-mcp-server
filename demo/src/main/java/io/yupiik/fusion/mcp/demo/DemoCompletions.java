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
import io.yupiik.fusion.mcp.api.MCPCompletions;
import io.yupiik.fusion.mcp.model.CompleteResult;
import io.yupiik.fusion.mcp.model.CompletionArgument;
import io.yupiik.fusion.mcp.model.CompletionContext;
import io.yupiik.fusion.mcp.model.CompletionRef;
import java.util.List;

/**
 * Suggests the values of the {@code code} argument of {@code demo/prompt}.
 */
@ApplicationScoped
public class DemoCompletions implements MCPCompletions {
    private static final List<String> CODES = List.of("fusion", "mcp", "yupiik");

    @Override
    public CompleteResult.Completion complete(
            final CompletionRef ref, final CompletionArgument argument, final CompletionContext context) {
        if (!"ref/prompt".equals(ref.type()) || !"demo/prompt".equals(ref.name()) || !"code".equals(argument.name())) {
            return null; // not for us, another implementation - or the empty default - will handle it
        }
        return MCPCompletions.matching(CODES, argument.value());
    }
}
