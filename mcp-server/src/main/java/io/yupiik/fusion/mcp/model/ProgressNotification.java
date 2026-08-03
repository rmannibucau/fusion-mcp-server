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
 * {@code notifications/progress} parameters, in both directions.
 *
 * @param progressToken the token the peer sent in the originating request {@code _meta.progressToken}, a string or a number.
 * @param progress      how much of the work is done, it must increase on every notification.
 * @param total         the total amount of work when it is known.
 * @param message       a human oriented description of the current step.
 */
@JsonModel
public record ProgressNotification(
        @Property(documentation = "The token the peer sent in the originating request, a string or a number.")
        Object progressToken,

        @Property(documentation = "How much of the work is done, it must increase on every notification.")
        Double progress,

        @Property(documentation = "Total amount of work, when known.")
        Double total,

        @Property(documentation = "Human oriented description of the current step.")
        String message) {}
