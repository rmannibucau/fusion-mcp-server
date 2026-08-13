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
import io.yupiik.fusion.mcp.api.MCPCompletions;
import io.yupiik.fusion.mcp.model.CompleteResult;
import io.yupiik.fusion.mcp.model.CompletionArgument;
import io.yupiik.fusion.mcp.model.CompletionContext;
import io.yupiik.fusion.mcp.model.CompletionRef;
import java.util.List;

/**
 * Completion of the {@code name} argument of the {@code greeting} prompt - one of the two default prompt names.
 */
@ApplicationScoped
public class ConformanceCompletions implements MCPCompletions {
    private static final List<String> NAMES = List.of("fusion", "mcp", "conformance");

    @Override
    public CompleteResult.Completion complete(
            final CompletionRef ref, final CompletionArgument argument, final CompletionContext context) {
        if (!"ref/prompt".equals(ref.type()) || !"greeting".equals(ref.name()) || !"name".equals(argument.name())) {
            return null; // not for us, another implementation - or the empty default - handles it
        }
        return MCPCompletions.matching(NAMES, argument.value());
    }
}
