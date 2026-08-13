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
package io.yupiik.fusion.mcp.protocol;

import io.yupiik.fusion.http.server.api.Request;
import io.yupiik.fusion.http.server.api.Response;
import java.util.regex.Pattern;

/**
 * The DNS rebinding protection of the MCP transport: a local MCP server must not let a remote page drive it through
 * its DNS - a page served on {@code evil.example.com} resolving to {@code 127.0.0.1} would otherwise POST to it with
 * a non-loopback {@code Host}/{@code Origin} and still be accepted.
 * <p>
 * A request whose {@code Host} - and {@code Origin}, when present - is not a loopback address is rejected with a
 * {@code 403}. The {@code Origin} is allowed to be absent or the literal {@code null} value file:// pages send.
 */
final class HostGuard {
    private static final Pattern LOOPBACK_IPV4 = Pattern.compile("127\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");

    private HostGuard() {
        // no-op
    }

    /**
     * @param request the incoming request.
     * @return {@code true} when the request may be processed, {@code false} when it is a DNS rebinding attempt.
     */
    static boolean isAllowed(final Request request) {
        return isAllowedHost(request.header("Host")) && isAllowedOrigin(request.header("Origin"));
    }

    /**
     * @return the {@code 403} response the transport answers a rejected request with.
     */
    static Response forbidden() {
        return Response.of()
                .status(403)
                .header("content-type", "text/plain")
                .body("Forbidden: non-loopback Host/Origin header")
                .build();
    }

    private static boolean isAllowedHost(final String host) {
        // an absent header - HTTP/2 or a unit test stub - is not a rebinding vector, it is not rejected
        return host == null || host.isBlank() || isLoopback(hostWithoutPort(host.strip()));
    }

    private static boolean isAllowedOrigin(final String origin) {
        // file:// pages send the literal "null" value, it has no host to check
        if (origin == null || origin.isBlank() || "null".equals(origin)) {
            return true;
        }
        final var withoutScheme = origin.replaceFirst("^[a-zA-Z][a-zA-Z0-9+.-]*://", "");
        return isLoopback(hostWithoutPort(withoutScheme.strip()));
    }

    private static String hostWithoutPort(final String hostPort) {
        if (hostPort.startsWith("[")) { // [::1]:8080
            final var closing = hostPort.indexOf(']');
            return closing < 0 ? hostPort : hostPort.substring(0, closing + 1);
        }
        final var colon = hostPort.lastIndexOf(':');
        // a second colon means a raw IPv6 literal without brackets, there is no port to strip
        return colon < 0 || hostPort.indexOf(':') != colon ? hostPort : hostPort.substring(0, colon);
    }

    private static boolean isLoopback(final String host) {
        return "localhost".equalsIgnoreCase(host)
                || "::1".equals(host)
                || "[::1]".equals(host)
                || "[0:0:0:0:0:0:0:1]".equals(host)
                || LOOPBACK_IPV4.matcher(host).matches();
    }
}
