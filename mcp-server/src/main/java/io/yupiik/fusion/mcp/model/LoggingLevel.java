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

/**
 * Log severities, they map the syslog ones (RFC 5424).
 * <p>
 * IMPORTANT: the declaration order is the JSON/MCP one (alphabetical) so use {@link #severity()} - and not
 * {@link #ordinal()} - to compare two levels.
 */
@JsonModel
public enum LoggingLevel {
    alert(1),
    critical(2),
    debug(7),
    emergency(0),
    error(3),
    info(6),
    notice(5),
    warning(4);

    private final int severity;

    LoggingLevel(final int severity) {
        this.severity = severity;
    }

    /**
     * @return the syslog severity, the lower the more critical.
     */
    public int severity() {
        return severity;
    }

    /**
     * @param minimum the level the client set with {@code logging/setLevel}.
     * @return {@code true} if a record at this level must be sent to a client which asked for {@code minimum}.
     */
    public boolean isEnabled(final LoggingLevel minimum) {
        return minimum != null && severity <= minimum.severity;
    }
}
