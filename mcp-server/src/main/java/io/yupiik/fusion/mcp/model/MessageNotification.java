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

import io.yupiik.fusion.framework.build.api.configuration.Property;
import io.yupiik.fusion.framework.build.api.json.JsonModel;

/**
 * {@code notifications/message} parameters, i.e. a log record sent to the client.
 *
 * @param logger the logger name, it is free form.
 * @param level  the severity, the client only gets records at or above the level it asked for with {@code logging/setLevel}.
 * @param data   the payload, it can be a plain string or any JSON structure.
 */
@JsonModel
public record MessageNotification(
        @Property(documentation = "Free form logger name.")
        String logger,
        @Property(documentation = "Severity of the record.")
        LoggingLevel level,
        @Property(documentation = "The payload, a string or any JSON structure.")
        Object data
) {
}
