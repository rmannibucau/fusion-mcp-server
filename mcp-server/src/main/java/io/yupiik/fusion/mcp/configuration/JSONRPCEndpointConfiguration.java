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
package io.yupiik.fusion.mcp.configuration;

import io.yupiik.fusion.framework.api.configuration.ConfigurationSource;
import io.yupiik.fusion.framework.api.scope.DefaultScoped;

/**
 * Binds the Fusion JSON-RPC endpoint on {@code /mcp}, i.e. makes the MCP endpoint the default JSON-RPC one instead
 * of {@code /jsonrpc}.
 * <p>
 * This is only a default: a {@code ConfigurationSource} with a higher priority - or a
 * {@code -Dfusion.jsonrpc.binding=/jsonrpc} - overrides it, which is what to do to <em>also</em> expose the
 * JSON-RPC API as a plain JSON-RPC endpoint. The MCP transport itself stays on {@code /mcp} in all cases, see
 * {@link io.yupiik.fusion.mcp.protocol.MCPEndpoint}.
 */
@DefaultScoped
public class JSONRPCEndpointConfiguration implements ConfigurationSource {
    @Override
    public String get(final String key) {
        return "fusion.jsonrpc.binding".equals(key) ? "/mcp" : null;
    }
}
