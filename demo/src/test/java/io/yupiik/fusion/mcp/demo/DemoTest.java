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

import io.yupiik.fusion.json.JsonMapper;
import io.yupiik.fusion.mcp.client.MCPClient;
import io.yupiik.fusion.testing.Fusion;
import io.yupiik.fusion.testing.FusionSupport;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;

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
    void listToolNames(@Fusion final URI mcpEndpoint, @Fusion final HttpClient http) {
        try (final var client = new MCPClient(mcpEndpoint, http)) {
            client.initialize().toCompletableFuture().join();
            final var res = client.call(2, "tools/list", "{}").toCompletableFuture().join();

            assertEquals(200, res.statusCode());
            // only the @MCPTool methods, in a stable order
            assertEquals(
                    List.of("demo/ask", "demo/confirm", "demo/greet", "demo/log", "demo/roots", "demo/search", "demo/tool"),
                    names(res.body()));
        }
    }

    @Test
    void toolDescriptor(@Fusion final URI mcpEndpoint, @Fusion final HttpClient http, @Fusion final JsonMapper jsons) {
        try (final var client = new MCPClient(mcpEndpoint, http)) {
            client.initialize().toCompletableFuture().join();
            final var res = client.call(2, "tools/list", "{}").toCompletableFuture().join();

            // the description, the parameter documentation and both schemas come from the OpenRPC document Fusion
            // generates out of the method signature
            assertJsonEquals("""
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
                                "properties": {
                                  "something": {
                                    "description": "What the tool has to say, a plain sentence.",
                                    "type": "string"
                                  }
                                },
                                "required": [],
                                "type": "object"
                              },
                              "title": "demo/greet"
                            }""",
                    tool(jsons, res.body(), "demo/greet"));
        }
    }

    @Test
    void richInputSchema(@Fusion final URI mcpEndpoint, @Fusion final HttpClient http, @Fusion final JsonMapper jsons) {
        try (final var client = new MCPClient(mcpEndpoint, http)) {
            client.initialize().toCompletableFuture().join();
            final var res = client.call(2, "tools/list", "{}").toCompletableFuture().join();

            // a nested model is inlined - no $ref a client could not resolve - a collection becomes items, a map
            // becomes additionalProperties, an enum becomes enum and a primitive is required
            assertJsonEquals("""
                            {
                              "description": "Searches the demo catalog.",
                              "inputSchema": {
                                "description": "Input request for demo/search",
                                "properties": {
                                  "query": {
                                    "description": "The query.",
                                    "properties": {
                                      "fuzzy": {
                                        "description": "Should approximate matches be returned too.",
                                        "type": "boolean"
                                      },
                                      "text": {
                                        "description": "The text to search for, mandatory.",
                                        "type": "string"
                                      }
                                    },
                                    "required": ["fuzzy"],
                                    "type": "object"
                                  },
                                  "tags": {
                                    "description": "Tags to filter on.",
                                    "items": {"type": "string"},
                                    "type": "array"
                                  },
                                  "options": {
                                    "additionalProperties": {"type": "string"},
                                    "description": "Extra options.",
                                    "type": "object"
                                  },
                                  "limit": {
                                    "description": "How many results at most.",
                                    "format": "int32",
                                    "type": "integer"
                                  },
                                  "direction": {
                                    "description": "Sort direction.",
                                    "enum": ["asc", "desc"],
                                    "type": "string"
                                  }
                                },
                                "required": ["limit", "query"],
                                "type": "object"
                              },
                              "name": "demo/search",
                              "outputSchema": {
                                "properties": {
                                  "something": {
                                    "description": "What the tool has to say, a plain sentence.",
                                    "type": "string"
                                  }
                                },
                                "required": [],
                                "type": "object"
                              },
                              "title": "demo/search"
                            }""",
                    tool(jsons, res.body(), "demo/search"));
        }
    }

    @Test
    void callToolWithRichArguments(@Fusion final URI mcpEndpoint, @Fusion final HttpClient http) {
        try (final var client = new MCPClient(mcpEndpoint, http)) {
            client.initialize().toCompletableFuture().join();

            final var res = client.call(2, "tools/call", """
                    {"name": "demo/search", "arguments": {
                       "query": {"text": "fusion", "fuzzy": true},
                       "tags": ["a", "b"],
                       "options": {"k": "v"},
                       "limit": 10,
                       "direction": "desc"
                     }}""").toCompletableFuture().join();

            assertJsonEquals("""
                            {
                              "jsonrpc": "2.0",
                              "id": 2,
                              "result": {
                                "content": [{"text": "{\\"something\\":\\"fusion/2/1/10/desc\\"}", "type": "text"}],
                                "isError": false,
                                "structuredContent": {"something": "fusion/2/1/10/desc"}
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

    /**
     * The elicitation round trip: the tool asks the user, the client answers on the same endpoint.
     */
    @Test
    void elicitation(@Fusion final URI mcpEndpoint, @Fusion final HttpClient http) throws Exception {
        try (final var client = new MCPClient(mcpEndpoint, http)) {
            client.initialize("""
                    {"elicitation": {}}""").toCompletableFuture().join();

            final var call = client.call(2, "tools/call", """
                    {"name": "demo/confirm", "arguments": {"action": "deploy"}}""");

            final var request = client.openSse().thenCompose(client::nextMessage).toCompletableFuture().join();
            assertTrue(request.contains("\"method\":\"elicitation/create\""), request);
            assertTrue(request.contains("\"Really deploy?\""), request);
            // the requested schema is a flat object of primitives, as the specification requires
            assertTrue(request.contains("\"requestedSchema\":{"), request);
            assertTrue(request.contains("\"confirm\":{\"description\":\"Confirm the action?\",\"type\":\"boolean\"}"), request);
            assertTrue(request.contains("\"required\":[\"confirm\"]"), request);

            client.respond(requestId(request), """
                    {"action": "accept", "content": {"confirm": true}}""").toCompletableFuture().join();

            assertTrue(call.toCompletableFuture().get(30, SECONDS).body().contains("confirmed: true"));
        }
    }

    @Test
    void elicitationCanBeDeclined(@Fusion final URI mcpEndpoint, @Fusion final HttpClient http) throws Exception {
        try (final var client = new MCPClient(mcpEndpoint, http)) {
            client.initialize("""
                    {"elicitation": {}}""").toCompletableFuture().join();

            final var call = client.call(2, "tools/call", """
                    {"name": "demo/confirm", "arguments": {"action": "deploy"}}""");
            final var request = client.openSse().thenCompose(client::nextMessage).toCompletableFuture().join();

            client.respond(requestId(request), """
                    {"action": "decline"}""").toCompletableFuture().join();

            // the tool must read the action before the content, which is only set on accept
            assertTrue(call.toCompletableFuture().get(30, SECONDS).body().contains("declined"));
        }
    }

    /**
     * The roots round trip: the tool asks what the client gives access to.
     */
    @Test
    void roots(@Fusion final URI mcpEndpoint, @Fusion final HttpClient http) throws Exception {
        try (final var client = new MCPClient(mcpEndpoint, http)) {
            client.initialize("""
                    {"roots": {"listChanged": true}}""").toCompletableFuture().join();

            final var call = client.call(2, "tools/call", """
                    {"name": "demo/roots", "arguments": {}}""");

            final var request = client.openSse().thenCompose(client::nextMessage).toCompletableFuture().join();
            assertTrue(request.contains("\"method\":\"roots/list\""), request);

            client.respond(requestId(request), """
                    {"roots": [{"uri": "file:///work", "name": "work"}, {"uri": "file:///tmp", "name": "tmp"}]}""")
                    .toCompletableFuture()
                    .join();

            final var res = call.toCompletableFuture().get(30, SECONDS);
            assertJsonEquals("""
                            {
                              "jsonrpc": "2.0",
                              "id": 2,
                              "result": {
                                "content": [{"text": "{\\"something\\":\\"file:///work, file:///tmp\\"}", "type": "text"}],
                                "isError": false,
                                "structuredContent": {"something": "file:///work, file:///tmp"}
                              }
                            }""",
                    res.body());
        }
    }

    @Test
    void serverRequestsNeedTheirCapability(@Fusion final URI mcpEndpoint, @Fusion final HttpClient http) {
        try (final var client = new MCPClient(mcpEndpoint, http)) {
            client.initialize().toCompletableFuture().join(); // no capability at all

            assertTrue(client.call(2, "tools/call", """
                    {"name": "demo/confirm", "arguments": {"action": "deploy"}}""")
                    .toCompletableFuture().join().body().contains("Client does not support 'elicitation'"));
            assertTrue(client.call(3, "tools/call", """
                    {"name": "demo/roots", "arguments": {}}""")
                    .toCompletableFuture().join().body().contains("Client does not support 'roots'"));
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

    @SuppressWarnings("unchecked")
    private List<String> names(final String body) {
        return tools(body).stream().map(it -> String.valueOf(it.get("name"))).toList();
    }

    /**
     * @return the descriptor of one tool, re-serialized, so a test can assert on it alone.
     */
    private String tool(final JsonMapper jsons, final String body, final String name) {
        return jsons.toString(tools(body).stream()
                .filter(it -> name.equals(it.get("name")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No tool '" + name + "' in " + body)));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> tools(final String body) {
        try (final var mapper = new io.yupiik.fusion.json.internal.JsonMapperImpl(List.of(), key -> java.util.Optional.empty())) {
            final var response = (Map<String, Object>) mapper.fromString(Object.class, body);
            final var result = (Map<String, Object>) response.get("result");
            return (List<Map<String, Object>>) result.get("tools");
        }
    }
}
