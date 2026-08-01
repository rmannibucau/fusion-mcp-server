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

import io.yupiik.fusion.framework.build.api.json.JsonModel;

/**
 * {@code elicitation/create} parameters, i.e. an input the server asks the client user for.
 *
 * @param message         what to show to the user.
 * @param requestedSchema a flat object JSON-Schema - primitive properties only - describing the expected answer.
 */
@JsonModel
public record ElicitRequestParameters(
        String message,
        JsonSchema requestedSchema
) {
}
