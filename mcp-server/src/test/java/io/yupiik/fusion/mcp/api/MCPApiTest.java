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

import static io.yupiik.fusion.mcp.api.MCPCompletions.MAX_VALUES;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * What the extension points give for free: the {@link MCPCompletions#matching(List, String)} helper and the
 * {@link MCPResources} defaults, i.e. what an implementation gets without overriding anything.
 */
class MCPApiTest {
    @Test
    void matchingFiltersOnWhatWasTyped() {
        final var completion = MCPCompletions.matching(List.of("fusion", "fun", "mcp"), "fu");

        assertFalse(completion.hasMore());
        assertEquals(2, completion.total());
        assertEquals(List.of("fusion", "fun"), completion.values());
    }

    @Test
    void everythingMatchesAnEmptyOrAbsentPrefix() {
        final var all = List.of("fusion", "mcp", "yupiik");

        // a client completing an argument the user did not type anything in yet
        assertEquals(all, MCPCompletions.matching(all, null).values());
        assertEquals(all, MCPCompletions.matching(all, "").values());
    }

    @Test
    void nothingMatches() {
        final var completion = MCPCompletions.matching(List.of("fusion", "mcp"), "zzz");

        assertFalse(completion.hasMore());
        assertEquals(0, completion.total());
        assertEquals(List.of(), completion.values());
    }

    @Test
    void resultsAreTruncatedToWhatMCPAccepts() {
        final var candidates =
                IntStream.range(0, MAX_VALUES + 10).mapToObj(i -> "value-" + i).toList();

        final var completion = MCPCompletions.matching(candidates, "value-");

        assertTrue(completion.hasMore(), "the client must know it did not get everything");
        // total stays the real count, only the values are capped
        assertEquals(MAX_VALUES + 10, completion.total());
        assertEquals(MAX_VALUES, completion.values().size());
        assertEquals(candidates.subList(0, MAX_VALUES), completion.values());
    }

    @Test
    void exactlyTheMaximumIsNotTruncated() {
        final var candidates =
                IntStream.range(0, MAX_VALUES).mapToObj(i -> "value-" + i).toList();

        final var completion = MCPCompletions.matching(candidates, null);

        assertFalse(completion.hasMore());
        assertEquals(MAX_VALUES, completion.values().size());
    }

    @Test
    void aResourceProviderCanImplementNothing() {
        // the defaults let an implementation only expose static resources, or only templates, or only reads
        final var resources = new MCPResources() {};

        assertEquals(List.of(), resources.resources());
        assertEquals(List.of(), resources.resourceTemplates());
        assertNotNull(resources.read("demo://whatever"));
        assertTrue(resources.read("demo://whatever").isEmpty());
    }
}
