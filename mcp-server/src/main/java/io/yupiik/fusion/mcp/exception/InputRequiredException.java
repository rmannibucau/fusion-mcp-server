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
package io.yupiik.fusion.mcp.exception;

import io.yupiik.fusion.jsonrpc.JsonRpcException;
import io.yupiik.fusion.mcp.model.InputRequest;
import io.yupiik.fusion.mcp.model.MCPResult;
import io.yupiik.fusion.mcp.protocol.MCPProtocol;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A tool throws this to ask the client for an input the server cannot gather by itself - an elicitation, a sampling or
 * a roots listing. The transport converts it to an {@code input_required} {@link MCPResult} carrying the
 * {@link InputRequest}s and a {@code requestState} token the client echoes with its next invocation.
 * <p>
 * The {@code state} is server side only: it is not sent as is to the client, the configured
 * {@code io.yupiik.fusion.mcp.spi.MCPRequestStateCodec} encodes it into the opaque {@code requestState} token - a
 * client which can keep the state itself (the {@code optional.state} client capability) can also send it back, the
 * transport then only has to decode it.
 */
public class InputRequiredException extends JsonRpcException {
    public InputRequiredException(final Map<String, InputRequest> inputRequests, final Object state) {
        this(inputRequests, state, "Input required");
    }

    /**
     * @param inputRequests the requests the client must run, keyed by their method.
     * @param state         the server side state to resume with, {@code null} when there is none - it is encoded in the
     *                      returned {@code requestState}.
     * @param message       the reason, what the model will see.
     */
    public InputRequiredException(
            final Map<String, InputRequest> inputRequests, final Object state, final String message) {
        super(
                MCPProtocol.INPUT_REQUIRED,
                message == null ? "Input required" : message,
                data(inputRequests, state),
                null);
    }

    private static Map<String, Object> data(final Map<String, InputRequest> inputRequests, final Object state) {
        final var data = new LinkedHashMap<String, Object>();
        data.put("inputRequests", inputRequests == null ? Map.of() : inputRequests);
        if (state != null) {
            data.put("state", state);
        }
        return data;
    }
}
