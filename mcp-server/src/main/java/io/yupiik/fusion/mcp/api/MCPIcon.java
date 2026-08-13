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

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.SOURCE;

import io.yupiik.fusion.framework.build.api.metadata.BeanMetadataAlias;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/**
 * Attaches a visual identifier to a {@link MCPTool} or {@link MCPPrompt}, declared next to the method:
 * <pre>{@code
 * @MCPTool
 * @MCPIcon("https://example.com/weather.svg")
 * @JsonRpc(value = "weather/current", documentation = "Current weather.")
 * public String current() { ... }
 * }</pre>
 * <p>
 * The {@code src} is stored in the bean metadata ({@code mcp.icon}) at build time, from which the
 * {@code io.yupiik.fusion.mcp.service.DescriptorService} builds the {@code icons} the client reads in
 * {@code tools/list} and {@code prompts/list}. The URI must be {@code https:} or {@code data:}.
 *
 * @see io.yupiik.fusion.mcp.model.Icon
 */
@Target(METHOD)
@Retention(SOURCE)
@BeanMetadataAlias(name = "mcp.icon")
public @interface MCPIcon {
    /**
     * @return the icon source URI, {@code https:} or {@code data:}.
     */
    String value();
}
