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
package io.yupiik.fusion.mcp.demo;

import io.yupiik.fusion.mcp.client.MCPClient;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;

import static io.yupiik.fusion.testing.assertion.JsonAsserts.assertJsonEquals;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End to end tests of a real MCP server: the tools, prompts, resources and completions the demo module exposes.
 */
@FusionSupport
class DemoTest {
    @Test
    void initialize(@Fusion final URI mcpEndpoint, @Fusion final HttpClient http) throws Exception {
        try (final var client = new MCPClient(mcpEndpoint, http)) {
            final var res = client.initialize().toCompletableFuture().join();

            assertEquals(200, res.statusCode());
            assertJsonEquals("""
                            {
                              "jsonrpc": "2.0",
                              "id": 1,
                              "result": {
                                "capabilities": {
                                  "completions": {},
                                  "logging": {},
                                  "prompts": {"listChanged": false},
                                  "resources": {"listChanged": true, "subscribe": true},
                                  "tools": {"listChanged": false}
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
    void listTools(@Fusion final URI mcpEndpoint, @Fusion final HttpClient http) throws Exception {
        try (final var client = new MCPClient(mcpEndpoint, http)) {
            client.initialize().toCompletableFuture().join();
            final var res = client.call(2, "tools/list", "{}").toCompletableFuture().join();

            assertEquals(200, res.statusCode());
            // the descriptions and the schemas come from the OpenRPC document Fusion generates from the signatures
            assertJsonEquals("""
                            {
                              "jsonrpc": "2.0",
                              "id": 2,
                              "result": {
                                "tools": [
                                  {
                                    "description": "Asks the client model to answer a question.",
                                    "inputSchema": {
                                      "description": "Input request for demo/ask",
                                      "properties": {
                                        "question": {
                                          "description": "The question to forward to the client model.",
                                          "type": "string"
                                        }
                                      },
                                      "required": ["question"],
                                      "type": "object"
                                    },
                                    "name": "demo/ask",
                                    "outputSchema": {
                                      "properties": {"greeting": {"type": "string"}},
                                      "required": [],
                                      "type": "object"
                                    },
                                    "title": "demo/ask"
                                  },
                                  {
                                    "description": "Greets someone by name.",
                                    "inputSchema": {
                                      "description": "Input request for demo/greet",
                                      "properties": {
                                        "name": {"description": "Who to greet.", "type": "string"},
                                        "times": {
                                          "description": "How many times to greet, defaults to 1.",
                                          "format": "int32",
                                          "type": "integer"
                                        }
                                      },
                                      "required": ["name"],
                                      "type": "object"
                                    },
                                    "name": "demo/greet",
                                    "outputSchema": {
                                      "properties": {"something": {"type": "string"}},
                                      "required": [],
                                      "type": "object"
                                    },
                                    "title": "demo/greet"
                                  },
                                  {
                                    "description": "Sends a log record to the client over the SSE channel.",
                                    "inputSchema": {
                                      "description": "Input request for demo/log",
                                      "properties": {"message": {"description": "What to log.", "type": "string"}},
                                      "required": [],
                                      "type": "object"
                                    },
                                    "name": "demo/log",
                                    "outputSchema": {
                                      "properties": {"greeting": {"type": "string"}},
                                      "required": [],
                                      "type": "object"
                                    },
                                    "title": "demo/log"
                                  },
                                  {
                                    "description": "Demo.",
                                    "inputSchema": {
                                      "description": "Input request for demo/tool",
                                      "properties": {},
                                      "required": [],
                                      "type": "object"
                                    },
                                    "name": "demo/tool",
                                    "outputSchema": {
                                      "properties": {"greeting": {"type": "string"}},
                                      "required": [],
                                      "type": "object"
                                    },
                                    "title": "demo/tool"
                                  }
                                ]
                              }
                            }""",
                    res.body());
        }
    }

    @Test
    void callTool(@Fusion final URI mcpEndpoint, @Fusion final HttpClient http) throws Exception {
        try (final var client = new MCPClient(mcpEndpoint, http)) {
            client.initialize().toCompletableFuture().join();
            final var res = client.call(2, "tools/call", """
                    {"name": "demo/tool", "arguments": {}}""").toCompletableFuture().join();

            assertEquals(200, res.statusCode());
            assertJsonEquals("""
                            {
                              "jsonrpc": "2.0",
                              "id": 2,
                              "result": {
                                "content": [
                                  {"text": "{\\"greeting\\":\\"hello fusion!\\"}", "type": "text"}
                                ],
                                "isError": false,
                                "structuredContent": {"greeting": "hello fusion!"}
                              }
                            }""",
                    res.body());
        }
    }

    @Test
    void callToolWithArguments(@Fusion final URI mcpEndpoint, @Fusion final HttpClient http) throws Exception {
        try (final var client = new MCPClient(mcpEndpoint, http)) {
            client.initialize().toCompletableFuture().join();
            final var res = client.call(2, "tools/call", """
                    {"name": "demo/greet", "arguments": {"name": "fusion", "times": 3}}""").toCompletableFuture().join();

            assertEquals(200, res.statusCode());
            assertJsonEquals("""
                            {
                              "jsonrpc": "2.0",
                              "id": 2,
                              "result": {
                                "content": [
                                  {"text": "{\\"something\\":\\"hello fusion!!!\\"}", "type": "text"}
                                ],
                                "isError": false,
                                "structuredContent": {"something": "hello fusion!!!"}
                              }
                            }""",
                    res.body());
        }
    }

    @Test
    void toolFailureIsAResultNotAnError(@Fusion final URI mcpEndpoint, @Fusion final HttpClient http) throws Exception {
        try (final var client = new MCPClient(mcpEndpoint, http)) {
            client.initialize().toCompletableFuture().join();
            final var res = client.call(2, "tools/call", """
                    {"name": "demo/greet", "arguments": {"name": " "}}""").toCompletableFuture().join();

            // the specification wants tool failures reported with isError so the model can read and react to them
            assertEquals(200, res.statusCode());
            assertJsonEquals("""
                            {
                              "jsonrpc": "2.0",
                              "id": 2,
                              "result": {
                                "content": [{"text": "name should not be blank", "type": "text"}],
                                "isError": true,
                                "structuredContent": {"code": -2, "message": "name should not be blank"}
                              }
                            }""",
                    res.body());
        }
    }

    @Test
    void invalidToolArgumentsIsAProtocolError(@Fusion final URI mcpEndpoint, @Fusion final HttpClient http) throws Exception {
        try (final var client = new MCPClient(mcpEndpoint, http)) {
            client.initialize().toCompletableFuture().join();
            final var res = client.call(2, "tools/call", """
                    {"name": "demo/greet", "arguments": {}}""").toCompletableFuture().join();

            // a missing required argument is not a tool failure, the call itself is invalid
            assertEquals(200, res.statusCode());
            assertJsonEquals("""
                            {
                              "jsonrpc": "2.0",
                              "id": 2,
                              "error": {"code": -32601, "message": "Missing 'name' parameter."}
                            }""",
                    res.body());
        }
    }

    @Test
    void listPrompts(@Fusion final URI mcpEndpoint, @Fusion final HttpClient http) throws Exception {
        try (final var client = new MCPClient(mcpEndpoint, http)) {
            client.initialize().toCompletableFuture().join();
            final var res = client.call(2, "prompts/list", "{}").toCompletableFuture().join();

            assertJsonEquals("""
                            {
                              "jsonrpc": "2.0",
                              "id": 2,
                              "result": {
                                "prompts": [
                                  {
                                    "arguments": [
                                      {
                                        "description": "The code to inject in the prompt.",
                                        "name": "code",
                                        "required": false,
                                        "title": "code"
                                      }
                                    ],
                                    "description": "Demo.",
                                    "name": "demo/prompt",
                                    "title": "demo/prompt"
                                  }
                                ]
                              }
                            }""",
                    res.body());
        }
    }

    @Test
    void getPrompt(@Fusion final URI mcpEndpoint, @Fusion final HttpClient http) throws Exception {
        try (final var client = new MCPClient(mcpEndpoint, http)) {
            client.initialize().toCompletableFuture().join();
            final var res = client.call(2, "prompts/get", """
                    {"name": "demo/prompt", "arguments": {"code": "1234"}}""").toCompletableFuture().join();

            assertJsonEquals("""
                            {
                              "jsonrpc": "2.0",
                              "id": 2,
                              "result": {
                                "description": "hello fusion!",
                                "messages": [
                                  {
                                    "content": {"text": "hello sir! your code is <1234>", "type": "text"},
                                    "role": "user"
                                  }
                                ]
                              }
                            }""",
                    res.body());
        }
    }

    @Test
    void getUnknownPrompt(@Fusion final URI mcpEndpoint, @Fusion final HttpClient http) throws Exception {
        try (final var client = new MCPClient(mcpEndpoint, http)) {
            client.initialize().toCompletableFuture().join();
            final var res = client.call(2, "prompts/get", """
                    {"name": "demo/tool", "arguments": {}}""").toCompletableFuture().join();

            // demo/tool is a tool, not a prompt
            assertTrue(res.body().contains("Unknown prompt 'demo/tool'"), res.body());
        }
    }

    @Test
    void listResources(@Fusion final URI mcpEndpoint, @Fusion final HttpClient http) throws Exception {
        try (final var client = new MCPClient(mcpEndpoint, http)) {
            client.initialize().toCompletableFuture().join();

            assertJsonEquals("""
                            {
                              "jsonrpc": "2.0",
                              "id": 2,
                              "result": {
                                "resources": [
                                  {
                                    "description": "A greeting from Fusion.",
                                    "mimeType": "text/plain",
                                    "name": "greeting",
                                    "title": "greeting",
                                    "uri": "demo://greeting"
                                  }
                                ]
                              }
                            }""",
                    client.call(2, "resources/list", "{}").toCompletableFuture().join().body());
            assertJsonEquals("""
                            {
                              "jsonrpc": "2.0",
                              "id": 3,
                              "result": {
                                "resourceTemplates": [
                                  {
                                    "description": "Echoes the message of the uri.",
                                    "mimeType": "text/plain",
                                    "name": "echo",
                                    "title": "echo",
                                    "uriTemplate": "demo://echo/{message}"
                                  }
                                ]
                              }
                            }""",
                    client.call(3, "resources/templates/list", "{}").toCompletableFuture().join().body());
        }
    }

    @Test
    void readResource(@Fusion final URI mcpEndpoint, @Fusion final HttpClient http) throws Exception {
        try (final var client = new MCPClient(mcpEndpoint, http)) {
            client.initialize().toCompletableFuture().join();

            assertJsonEquals("""
                            {
                              "jsonrpc": "2.0",
                              "id": 2,
                              "result": {
                                "contents": [
                                  {"mimeType": "text/plain", "text": "hello fusion!", "uri": "demo://greeting"}
                                ]
                              }
                            }""",
                    client.call(2, "resources/read", """
                            {"uri": "demo://greeting"}""").toCompletableFuture().join().body());
            assertJsonEquals("""
                            {
                              "jsonrpc": "2.0",
                              "id": 3,
                              "result": {
                                "contents": [
                                  {"mimeType": "text/plain", "text": "hi", "uri": "demo://echo/hi"}
                                ]
                              }
                            }""",
                    client.call(3, "resources/read", """
                            {"uri": "demo://echo/hi"}""").toCompletableFuture().join().body());
        }
    }

    @Test
    void complete(@Fusion final URI mcpEndpoint, @Fusion final HttpClient http) throws Exception {
        try (final var client = new MCPClient(mcpEndpoint, http)) {
            client.initialize().toCompletableFuture().join();
            final var res = client.call(2, "completion/complete", """
                    {
                      "ref": {"type": "ref/prompt", "name": "demo/prompt"},
                      "argument": {"name": "code", "value": "f"}
                    }""").toCompletableFuture().join();

            assertJsonEquals("""
                            {
                              "jsonrpc": "2.0",
                              "id": 2,
                              "result": {
                                "completion": {"hasMore": false, "total": 1, "values": ["fusion"]}
                              }
                            }""",
                    res.body());
        }
    }

    @Test
    void logOverSse(@Fusion final URI mcpEndpoint, @Fusion final HttpClient http) throws Exception {
        try (final var client = new MCPClient(mcpEndpoint, http)) {
            client.initialize().toCompletableFuture().join();
            assertEquals(200, client.call(2, "tools/call", """
                    {"name": "demo/log", "arguments": {"message": "from the tool"}}""").toCompletableFuture().join().statusCode());

            final var stream = client.openSse().toCompletableFuture().join();
            assertJsonEquals("""
                            {
                              "jsonrpc": "2.0",
                              "method": "notifications/message",
                              "params": {"level": "info", "logger": "demo", "data": "from the tool"}
                            }""",
                    client.nextMessage(stream).toCompletableFuture().join());
        }
    }

    /**
     * The full server to client round trip: a tool asks the client model to answer, the request goes out on the SSE
     * channel and the client posts its response back on the same endpoint.
     */
    @Test
    void sampling(@Fusion final URI mcpEndpoint, @Fusion final HttpClient http) throws Exception {
        try (final var client = new MCPClient(mcpEndpoint, http)) {
            client.initialize("""
                    {"sampling": {}}""").toCompletableFuture().join();

            // not awaited: the tool only returns once the client answered the sampling request
            final var call = client.call(2, "tools/call", """
                    {"name": "demo/ask", "arguments": {"question": "how are you?"}}""");

            final var request = client.openSse().thenCompose(client::nextMessage).toCompletableFuture().join();
            assertTrue(request.contains("\"method\":\"sampling/createMessage\""), request);
            assertTrue(request.contains("\"how are you?\""), request);
            assertTrue(request.contains("\"maxTokens\":512"), request);

            final var response = client.respond(requestId(request), """
                            {
                              "role": "assistant",
                              "model": "test-model",
                              "content": {"type": "text", "text": "fine, thanks!"}
                            }""")
                    .toCompletableFuture()
                    .join();
            assertEquals(202, response.statusCode());

            final var res = call.toCompletableFuture().get(30, SECONDS);
            assertEquals(200, res.statusCode());
            assertJsonEquals("""
                            {
                              "jsonrpc": "2.0",
                              "id": 2,
                              "result": {
                                "content": [{"text": "{\\"greeting\\":\\"fine, thanks!\\"}", "type": "text"}],
                                "isError": false,
                                "structuredContent": {"greeting": "fine, thanks!"}
                              }
                            }""",
                    res.body());
        }
    }

    @Test
    void samplingWithoutTheCapability(@Fusion final URI mcpEndpoint, @Fusion final HttpClient http) throws Exception {
        try (final var client = new MCPClient(mcpEndpoint, http)) {
            client.initialize().toCompletableFuture().join(); // no sampling capability
            final var res = client.call(2, "tools/call", """
                    {"name": "demo/ask", "arguments": {"question": "how are you?"}}""").toCompletableFuture().join();

            assertTrue(res.body().contains("Client does not support 'sampling'"), res.body());
        }
    }

    private long requestId(final String message) {
        final var marker = "\"id\":";
        final var start = message.indexOf(marker) + marker.length();
        int end = start;
        while (end < message.length() && Character.isDigit(message.charAt(end))) {
            end++;
        }
        return Long.parseLong(message.substring(start, end));
    }
}
