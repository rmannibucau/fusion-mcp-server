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
package io.yupiik.fusion.mcp.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.yupiik.fusion.mcp.protocol.MCPProtocol;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InputRequiredExceptionTest {
    @Test
    void theSingleArgConstructorDefaultsTheMessage() {
        final var exception = new InputRequiredException(Map.of(), "state");

        assertEquals(MCPProtocol.INPUT_REQUIRED, exception.code());
        assertEquals("Input required", exception.getMessage());
        assertEquals(Map.of(), ((Map<?, ?>) exception.data()).get("inputRequests"));
        assertEquals("state", ((Map<?, ?>) exception.data()).get("state"));
    }

    @Test
    void aNullStateIsOmittedAndANullMessageIsDefaulted() {
        final var exception = new InputRequiredException(null, null, null);

        assertEquals("Input required", exception.getMessage());
        final var data = (Map<?, ?>) exception.data();
        assertTrue(data.containsKey("inputRequests"));
        assertFalse(data.containsKey("state"), "no state means no state key");
        assertEquals(Map.of(), data.get("inputRequests"), "a null inputRequests map becomes empty");
    }
}
