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
package io.yupiik.fusion.mcp.test;

import io.yupiik.fusion.http.server.api.Body;
import io.yupiik.fusion.http.server.api.Cookie;
import io.yupiik.fusion.http.server.api.Request;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * A {@link Request} with just enough behavior - headers, attributes and an in memory body - to unit test what only
 * needs those.
 */
public class StubRequest implements Request {
    private final Map<String, String> headers;
    private final Map<String, Object> attributes = new HashMap<>();
    private final String body;

    public StubRequest() {
        this(Map.of());
    }

    public StubRequest(final Map<String, String> headers) {
        this(headers, null);
    }

    /**
     * @param headers the request headers, matched case insensitively.
     * @param body    the payload {@link #fullBody()} serves, {@code null} to make it fail like a broken connection.
     */
    public StubRequest(final Map<String, String> headers, final String body) {
        this.headers = headers.entrySet().stream()
                .collect(
                        HashMap::new,
                        (map, entry) -> map.put(entry.getKey().toLowerCase(java.util.Locale.ROOT), entry.getValue()),
                        HashMap::putAll);
        this.body = body;
    }

    @Override
    public String scheme() {
        return "http";
    }

    @Override
    public String method() {
        return "POST";
    }

    @Override
    public String path() {
        return "/mcp";
    }

    @Override
    public String query() {
        return null;
    }

    @Override
    public Body fullBody() {
        if (body == null) {
            throw new UnsupportedOperationException("no body in this stub");
        }
        return new StubBody(body);
    }

    @Override
    public Stream<Cookie> cookies() {
        return Stream.empty();
    }

    @Override
    public String parameter(final String name) {
        return null;
    }

    @Override
    public Map<String, String[]> parameters() {
        return Map.of();
    }

    @Override
    public String header(final String name) {
        return headers.get(name.toLowerCase(java.util.Locale.ROOT));
    }

    @Override
    public Map<String, List<String>> headers() {
        return headers.entrySet().stream()
                .collect(
                        HashMap::new,
                        (map, entry) -> map.put(entry.getKey(), List.of(entry.getValue())),
                        HashMap::putAll);
    }

    @Override
    public <T> T attribute(final String key, final Class<T> type) {
        return type.cast(attributes.get(key));
    }

    @Override
    public <T> void setAttribute(final String key, final T value) {
        attributes.put(key, value);
    }
}
