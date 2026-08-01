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

import io.yupiik.fusion.mcp.model.CompleteResult;
import io.yupiik.fusion.mcp.model.CompletionArgument;
import io.yupiik.fusion.mcp.model.CompletionContext;
import io.yupiik.fusion.mcp.model.CompletionRef;

import java.util.List;

/**
 * Suggests values for a prompt argument or a resource template variable, i.e. implements
 * {@code completion/complete}.
 * <p>
 * Any bean implementing this interface is picked up, they are visited in bean priority order ({@code @Order}) until
 * one returns a non {@code null} completion:
 * <pre>{@code
 * @ApplicationScoped
 * public class LanguageCompletions implements MCPCompletions {
 *     @Override
 *     public CompleteResult.Completion complete(final CompletionRef ref, final CompletionArgument argument,
 *                                               final CompletionContext context) {
 *         if (!"demo/prompt".equals(ref.name()) || !"lang".equals(argument.name())) {
 *             return null;
 *         }
 *         return matching(List.of("fr", "en"), argument.value());
 *     }
 * }
 * }</pre>
 * The {@code completions} capability is only advertised to the client when there is at least one implementation.
 */
public interface MCPCompletions {
    /**
     * Maximum amount of values MCP accepts in a single completion result.
     */
    int MAX_VALUES = 100;

    /**
     * @param ref      what is completed: a prompt ({@code ref/prompt}) or a resource template ({@code ref/resource}).
     * @param argument the argument being completed and its current value.
     * @param context  the arguments already resolved, it enables dependent completions.
     * @return the suggestions or {@code null} when this implementation does not handle {@code ref}/{@code argument}.
     */
    CompleteResult.Completion complete(CompletionRef ref, CompletionArgument argument, CompletionContext context);

    /**
     * Helper filtering a fixed set of values with what the user typed so far and truncating it to
     * {@link #MAX_VALUES}.
     *
     * @param candidates all the possible values.
     * @param prefix     what the user typed, it can be {@code null}.
     * @return the matching completion.
     */
    static CompleteResult.Completion matching(final List<String> candidates, final String prefix) {
        final var filtered = candidates.stream()
                .filter(it -> prefix == null || prefix.isEmpty() || it.startsWith(prefix))
                .toList();
        return new CompleteResult.Completion(
                filtered.size() > MAX_VALUES,
                filtered.size(),
                filtered.size() > MAX_VALUES ? filtered.subList(0, MAX_VALUES) : filtered);
    }
}
