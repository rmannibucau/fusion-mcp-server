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
package io.yupiik.fusion.mcp;

import io.yupiik.fusion.mcp.testing.MCPClient;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;

import static io.yupiik.fusion.testing.assertion.JsonAsserts.assertJsonEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The MCP methods without any application tool/prompt/resource: only the protocol ones are deployed here so the
 * advertised capabilities are the minimal ones.
 */
@FusionSupport
class MCPJSONRPCProtocolTest {
    @Test
    void initialize(@Fusion final URI mcpEndpoint, @Fusion final HttpClient http) throws Exception {
        try (final var client = new MCPClient(mcpEndpoint, http)) {
            final var res = client.initialize("""
                    {"roots": {"listChanged": true}, "sampling": {}, "elicitation": {}}""");

            assertEquals(200, res.statusCode());
            assertNotNull(client.session(), "initialize must return a Mcp-Session-Id header");
            // no tool, prompt, resource nor completion here, only logging is always available
            assertJsonEquals("""
                            {
                              "jsonrpc": "2.0",
                              "id": 1,
                              "result": {
                                "capabilities": {
                                  "logging": {}
                                },
                                "instructions": "Use the exposed tools to answer the user.",
                                "protocolVersion": "2025-06-18",
                                "serverInfo": {
                                  "name": "fusion-mcp-server",
                                  "title": "Fusion MCP Server",
                                  "version": "1.0.0"
                                }
                              }
                            }""",
                    res.body());
        }
    }

    @Test
    void initializeNegotiatesUnknownVersion(@Fusion final URI mcpEndpoint, @Fusion final HttpClient http) throws Exception {
        try (final var client = new MCPClient(mcpEndpoint, http)) {
            // the specification requires to answer a version the server supports - and not an error - the client then
            // decides if it can go on or not
            final var res = client.post("""
                    {
                      "jsonrpc": "2.0",
                      "id": 1,
                      "method": "initialize",
                      "params": {"protocolVersion": "2024-11-05", "capabilities": {}}
                    }""");

            assertEquals(200, res.statusCode());
            assertTrue(res.body().contains("\"protocolVersion\":\"2025-06-18\""), res.body());
        }
    }

    @Test
    void initializeKeepsSupportedVersion(@Fusion final URI mcpEndpoint, @Fusion final HttpClient http) throws Exception {
        try (final var client = new MCPClient(mcpEndpoint, http)) {
            final var res = client.post("""
                    {
                      "jsonrpc": "2.0",
                      "id": 1,
                      "method": "initialize",
                      "params": {"protocolVersion": "2025-03-26", "capabilities": {}}
                    }""");

            assertEquals(200, res.statusCode());
            assertTrue(res.body().contains("\"protocolVersion\":\"2025-03-26\""), res.body());
        }
    }

    @Test
    void ping(@Fusion final URI mcpEndpoint, @Fusion final HttpClient http) throws Exception {
        try (final var client = new MCPClient(mcpEndpoint, http)) {
            client.initialize();
            final var res = client.call(2, "ping", "{}");

            assertEquals(200, res.statusCode());
            assertJsonEquals("""
                    {"jsonrpc": "2.0", "id": 2, "result": {}}""", res.body());
        }
    }

    @Test
    void notificationIsAccepted(@Fusion final URI mcpEndpoint, @Fusion final HttpClient http) throws Exception {
        try (final var client = new MCPClient(mcpEndpoint, http)) {
            client.initialize();
            final var res = client.post("""
                    {"jsonrpc": "2.0", "method": "notifications/cancelled", "params": {"requestId": 5, "reason": "user"}}""");

            // a notification has no response, the transport must answer 202 with an empty body
            assertEquals(202, res.statusCode());
            assertEquals("", res.body());
        }
    }

    @Test
    void emptyListings(@Fusion final URI mcpEndpoint, @Fusion final HttpClient http) throws Exception {
        try (final var client = new MCPClient(mcpEndpoint, http)) {
            client.initialize();

            assertJsonEquals("""
                    {"jsonrpc": "2.0", "id": 2, "result": {"tools": []}}""", client.call(2, "tools/list", "{}").body());
            assertJsonEquals("""
                    {"jsonrpc": "2.0", "id": 3, "result": {"prompts": []}}""", client.call(3, "prompts/list", "{}").body());
            assertJsonEquals("""
                    {"jsonrpc": "2.0", "id": 4, "result": {"resources": []}}""", client.call(4, "resources/list", "{}").body());
            assertJsonEquals("""
                            {"jsonrpc": "2.0", "id": 5, "result": {"resourceTemplates": []}}""",
                    client.call(5, "resources/templates/list", "{}").body());
            assertJsonEquals("""
                            {"jsonrpc": "2.0", "id": 6, "result": {"completion": {"hasMore": false, "total": 0, "values": []}}}""",
                    client.call(6, "completion/complete", """
                            {"ref": {"type": "ref/prompt", "name": "nope"}, "argument": {"name": "a", "value": ""}}""").body());
        }
    }

    @Test
    void callUnknownTool(@Fusion final URI mcpEndpoint, @Fusion final HttpClient http) throws Exception {
        try (final var client = new MCPClient(mcpEndpoint, http)) {
            client.initialize();
            final var res = client.call(2, "tools/call", """
                    {"name": "ping", "arguments": {}}""");

            // ping is a JSON-RPC method but not a tool, it must not be reachable through tools/call
            assertEquals(200, res.statusCode());
            assertJsonEquals("""
                            {
                              "jsonrpc": "2.0",
                              "id": 2,
                              "error": {
                                "code": -32602,
                                "message": "Unknown tool 'ping'",
                                "data": {"name": "ping"}
                              }
                            }""",
                    res.body());
        }
    }

    @Test
    void unknownMethod(@Fusion final URI mcpEndpoint, @Fusion final HttpClient http) throws Exception {
        try (final var client = new MCPClient(mcpEndpoint, http)) {
            client.initialize();
            final var res = client.call(2, "does/notExist", "{}");

            assertEquals(200, res.statusCode());
            assertJsonEquals("""
                            {
                              "jsonrpc": "2.0",
                              "id": 2,
                              "error": {"code": -32601, "message": "Unknown method (does/notExist)"}
                            }""",
                    res.body());
        }
    }

    @Test
    void setLoggingLevel(@Fusion final URI mcpEndpoint, @Fusion final HttpClient http) throws Exception {
        try (final var client = new MCPClient(mcpEndpoint, http)) {
            client.initialize();

            assertEquals(202, client.post("""
                    {"jsonrpc": "2.0", "method": "logging/setLevel", "params": {"level": "debug"}}""").statusCode());

            final var invalid = client.call(2, "logging/setLevel", """
                    {"level": "oops"}""");
            assertEquals(200, invalid.statusCode());
            assertTrue(invalid.body().contains("Invalid logging level 'oops'"), invalid.body());
        }
    }

    @Test
    void readUnknownResource(@Fusion final URI mcpEndpoint, @Fusion final HttpClient http) throws Exception {
        try (final var client = new MCPClient(mcpEndpoint, http)) {
            client.initialize();
            final var res = client.call(2, "resources/read", """
                    {"uri": "demo://nope"}""");

            assertJsonEquals("""
                            {
                              "jsonrpc": "2.0",
                              "id": 2,
                              "error": {
                                "code": -32002,
                                "message": "Unknown resource 'demo://nope'",
                                "data": {"uri": "demo://nope"}
                              }
                            }""",
                    res.body());
        }
    }

    @Test
    void batch(@Fusion final URI mcpEndpoint, @Fusion final HttpClient http) throws Exception {
        try (final var client = new MCPClient(mcpEndpoint, http)) {
            client.initialize();
            final var res = client.post("""
                    [
                      {"jsonrpc": "2.0", "id": 2, "method": "ping", "params": {}},
                      {"jsonrpc": "2.0", "method": "notifications/roots/list_changed"},
                      {"jsonrpc": "2.0", "id": 3, "method": "tools/list", "params": {}}
                    ]""");

            assertEquals(200, res.statusCode());
            assertJsonEquals("""
                            [
                              {"jsonrpc": "2.0", "id": 2, "result": {}},
                              {"jsonrpc": "2.0", "id": 3, "result": {"tools": []}}
                            ]""",
                    res.body());
        }
    }
}
