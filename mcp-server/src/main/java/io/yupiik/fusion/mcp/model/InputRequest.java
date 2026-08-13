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

/**
 * A request the client must run to feed an {@code input_required} result, i.e. one entry of the {@code inputRequests}
 * map of an {@code MCPResult}.
 * <p>
 * The client runs the {@code method} - {@code elicitation/create}, {@code sampling/createMessage} or
 * {@code roots/list} - and sends the answer back with the next invocation of the originating method through
 * {@code _meta.io.modelcontextprotocol/inputResponses}, keyed by the {@code inputRequests} key.
 */
@JsonModel
public record InputRequest(
        @Property(
                documentation = "The request method, one of elicitation/create, sampling/createMessage or roots/list.")
        String method,

        @Property(documentation = "The request parameters.") Object params) {}
