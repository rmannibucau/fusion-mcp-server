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

/**
 * Encodes and decodes the {@code requestState} of a multi round-trip interaction over the stateless protocol: the
 * server encodes the state it wants to resume a {@code input_required} tool with, the client echoes the resulting
 * token with its next invocation, the server decodes it back.
 * <p>
 * Register a {@code io.yupiik.fusion.mcp.spi.MCPRequestStateCodec} implementation as a Fusion bean to replace the
 * default {@code io.yupiik.fusion.mcp.spi.OpaqueRequestStateCodec} - the first {@link #isActive()} one wins.
 */
public interface MCPRequestStateCodec {
    /**
     * @param state the server side state to send back to the client, may be {@code null}.
     * @return the opaque token the client echoes, {@link #empty()} when there is nothing to carry.
     */
    String encode(Object state);

    /**
     * @param token the token a client echoed.
     * @return the decoded server side state, {@code null} when the token was {@link #empty()}.
     */
    Object decode(String token);

    /**
     * @return {@code true} when this codec can be used, it lets an implementation be registered even when it is not
     * configured - e.g. the HMAC one without a secret.
     */
    boolean isActive();

    /**
     * @return the token meaning "no state", it is what a client echoes when it has nothing to send back.
     */
    String empty();
}
