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
import io.yupiik.fusion.framework.build.api.json.JsonProperty;

/**
 * A parameterized resource, i.e. what {@code resources/templates/list} returns.
 *
 * @param metadata    optional {@code _meta}.
 * @param annotations optional hints for the client.
 * @param description human oriented description of the resources the template generates.
 * @param mimeType    the mime type of the resources the template generates when it is constant.
 * @param name        the programmatic name of the template.
 * @param title       the human oriented name of the template.
 * @param uriTemplate a RFC 6570 uri template, {@code file:///logs/{name}.log} for example.
 */
@JsonModel
public record ResourceTemplate(
        @JsonProperty("_meta") Metadata metadata,
        Annotations annotations,
        String description,
        String mimeType,
        String name,
        String title,
        String uriTemplate
) {
    public static ResourceTemplate of(final String uriTemplate, final String name, final String mimeType, final String description) {
        return new ResourceTemplate(null, null, description, mimeType, name, name, uriTemplate);
    }
}
