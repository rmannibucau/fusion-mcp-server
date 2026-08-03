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
package io.yupiik.fusion.mcp.model;

import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.json.JsonModel;
import java.util.Map;

/**
 * The capabilities the client advertises in {@code initialize}.
 * <p>
 * They gate the server to client requests: sampling ({@code sampling/createMessage}), elicitation
 * ({@code elicitation/create}) and roots ({@code roots/list}) are only usable when the client declared them.
 *
 * @param roots        set when the client exposes filesystem roots.
 * @param sampling     set when the client can run a LLM completion for the server.
 * @param elicitation  set when the client can ask its user for a structured input.
 * @param experimental non standard capabilities.
 */
@JsonModel
public record Capabilities(
        @Property(documentation = "Set when the client exposes filesystem roots.")
        Roots roots,

        @Property(documentation = "Set when the client can run a LLM completion for the server.")
        Map<String, Object> sampling,

        @Property(documentation = "Set when the client can ask its user for a structured input.")
        Map<String, Object> elicitation,

        @Property(documentation = "Non standard capabilities, keyed by name.")
        Map<String, Object> experimental) {
    @JsonModel
    public record Roots(
            @Property(documentation = "Set when the client notifies the server the list changed.")
            boolean listChanged) {}
}
