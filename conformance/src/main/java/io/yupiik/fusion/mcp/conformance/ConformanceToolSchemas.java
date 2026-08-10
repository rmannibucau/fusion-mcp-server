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
import io.yupiik.fusion.mcp.api.MCPToolSchemas;
import io.yupiik.fusion.mcp.model.JsonSchema;
import java.util.List;
import java.util.Map;

/**
 * Advertises the conformance tools whose schema cannot be derived from a Java signature: the {@code x-mcp-header}
 * transport hint and the {@code json_schema_2020_12_tool} whose inputSchema exercises the JSON-Schema 2020-12
 * vocabulary (SEP-2106) - composition, conditional and reference keywords - that a Java signature cannot express.
 */
@ApplicationScoped
public class ConformanceToolSchemas implements MCPToolSchemas {
    private static final String DIALECT = "https://json-schema.org/draft/2020-12/schema";

    @Override
    public Map<String, JsonSchema> toolSchemas() {
        return Map.of(
                "test_x_mcp_header", xMcpHeaderTool(),
                "json_schema_2020_12_tool", jsonSchema202012Tool());
    }

    private JsonSchema xMcpHeaderTool() {
        return JsonSchema.object(
                "A tool receiving its message through an Mcp-Param-* header.",
                Map.of("message", JsonSchema.string("The message to echo.").withHeader("message")),
                false,
                List.of("message"));
    }

    private JsonSchema jsonSchema202012Tool() {
        final var phoneOrEmail = List.of(
                JsonSchema.object(null, Map.of(), List.of("phone")),
                JsonSchema.object(null, Map.of(), List.of("email")));
        return JsonSchema.object(
                        "Exercises the JSON-Schema 2020-12 vocabulary the suite checks survives tools/list.",
                        Map.of(
                                "name", JsonSchema.string("A display name."),
                                "address", JsonSchema.of("object", null).withRef("#/$defs/address"),
                                "contactMethod",
                                        JsonSchema.enumeration(
                                                "The preferred contact method.", List.of("phone", "email")),
                                "phone", JsonSchema.string("The phone number."),
                                "email", JsonSchema.string("The email address.")),
                        false,
                        null)
                .withDialect(DIALECT)
                .withDefs(Map.of(
                        "address",
                        JsonSchema.object(
                                        "A postal address.",
                                        Map.of(
                                                "street", JsonSchema.string("The street."),
                                                "city", JsonSchema.string("The city.")),
                                        null,
                                        null)
                                .withAnchor("addressDef")))
                .withAllOf(List.of(JsonSchema.of("object", null).withAnyOf(phoneOrEmail)))
                .withConditional(
                        JsonSchema.object(
                                "Only when the phone is preferred.",
                                Map.of("contactMethod", JsonSchema.string(null).withConst("phone")),
                                List.of("contactMethod")),
                        JsonSchema.object(null, Map.of(), List.of("phone")),
                        JsonSchema.object(null, Map.of(), List.of("email")));
    }
}
