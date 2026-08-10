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
import io.yupiik.fusion.mcp.protocol.MCPProtocol;
import java.util.Map;

/**
 * The {@code _meta} envelope a {@code 2026-07-28} client attaches to its requests: the protocol version, what it can
 * do, who it is and the state of the interaction - the token of a multi round-trip request and the inputs it gathered.
 * <p>
 * Every field is optional and read from the envelope only when present - an absent {@link #protocolVersion()} lets the
 * transport fall back on the {@code MCP-Protocol-Version} header.
 */
@JsonModel
public record MCPRequestMetadata(
        @JsonProperty(MCPProtocol.PROTOCOL_VERSION_META) String protocolVersion,
        @JsonProperty(MCPProtocol.CLIENT_CAPABILITIES_META) Capabilities clientCapabilities,
        @JsonProperty(MCPProtocol.CLIENT_INFO_META) ClientInfo clientInfo,
        @JsonProperty(MCPProtocol.LOG_LEVEL_META) LoggingLevel logLevel,
        @JsonProperty(MCPProtocol.PROGRESS_TOKEN_META) Object progressToken,
        @JsonProperty(MCPProtocol.SUBSCRIPTION_ID_META) String subscriptionId,
        @JsonProperty(MCPProtocol.REQUEST_STATE_META) String requestState,
        @JsonProperty(MCPProtocol.INPUT_RESPONSES_META) Map<String, Object> inputResponses) {}
