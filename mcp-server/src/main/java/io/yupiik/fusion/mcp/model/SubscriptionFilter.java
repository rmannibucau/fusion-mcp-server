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
import java.util.List;

/**
 * The notifications a {@code subscriptions/listen} client opts in to, i.e. the {@code notifications} field of the
 * listen request parameters.
 * <p>
 * Every notification type is opt-in: the server sends a type only when the client asked for it here. A field which
 * is absent or {@code false} means the corresponding notifications are not wanted.
 */
@JsonModel
public record SubscriptionFilter(
        @Property(documentation = "Set when the client wants notifications/tools/list_changed.")
        Boolean toolsListChanged,

        @Property(documentation = "Set when the client wants notifications/prompts/list_changed.")
        Boolean promptsListChanged,

        @Property(documentation = "Set when the client wants notifications/resources/list_changed.")
        Boolean resourcesListChanged,

        @Property(documentation = "The resource uris the client wants notifications/resources/updated for.")
        List<String> resourceSubscriptions) {
    /**
     * @param field a boolean filter field.
     * @return {@code true} when the client opted in, {@code null} and {@code false} both mean "no".
     */
    public static boolean optedIn(final Boolean field) {
        return Boolean.TRUE.equals(field);
    }
}
