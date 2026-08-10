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
import java.util.List;

/**
 * A visual identifier for a server implementation, tool, prompt or resource - see the {@code icons} property of the
 * {@code 2026-07-28} specification.
 * <p>
 * Clients <b>MUST</b> support {@code image/png} and {@code image/jpeg}, and <b>SHOULD</b> treat {@code image/svg+xml}
 * and {@code image/webp} with care. The {@code src} must be an {@code https:} or {@code data:} URI.
 *
 * @param src      a URI pointing to the icon, {@code https:} or base64 {@code data:}.
 * @param mimeType the MIME type, when the extension is missing or ambiguous.
 * @param sizes    the display sizes, e.g. {@code ["48x48"]} or {@code ["any"]}.
 * @param theme    the background theme this icon fits, {@code light} or {@code dark}.
 */
@JsonModel
public record Icon(
        @Property(documentation = "The URI of the icon, https: or a base64 data: URI.")
        String src,

        @Property(documentation = "The MIME type of the icon image.")
        String mimeType,

        @Property(documentation = "The display sizes this icon is rendered for.")
        List<String> sizes,

        @Property(documentation = "Which theme this icon is meant for.")
        Theme theme) {
    @JsonModel
    public enum Theme {
        light,
        dark
    }

    /**
     * @param src the icon URI, {@code https:} or {@code data:}.
     * @return a simple {@code light} icon.
     */
    public static Icon of(final String src) {
        return new Icon(src, null, List.of("any"), Theme.light);
    }
}
