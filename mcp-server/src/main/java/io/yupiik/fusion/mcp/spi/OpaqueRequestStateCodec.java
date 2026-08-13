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
package io.yupiik.fusion.mcp.spi;

import io.yupiik.fusion.framework.api.scope.ApplicationScoped;

/**
 * The default {@link MCPRequestStateCodec}: the token is the state itself, handy locally or when the state is small
 * and opaque enough. It does not protect the content - use a {@code HmacRequestStateCodec} for an integrity check.
 */
@ApplicationScoped
public class OpaqueRequestStateCodec implements MCPRequestStateCodec {
    public OpaqueRequestStateCodec() {
        // also used by the Fusion subclassing proxies
    }

    @Override
    public String encode(final Object state) {
        return state == null ? empty() : state.toString();
    }

    @Override
    public Object decode(final String token) {
        return token == null || token.isEmpty() ? null : token;
    }

    @Override
    public boolean isActive() {
        return true;
    }

    @Override
    public String empty() {
        return "";
    }
}
