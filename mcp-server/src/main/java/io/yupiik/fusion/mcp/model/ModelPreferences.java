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
 * How the client should pick the model of a sampling request, all priorities are between 0 and 1.
 */
@JsonModel
public record ModelPreferences(
        @Property(documentation = "How much cost matters, from 0 to 1.")
        Integer costPriority,

        @Property(documentation = "Preferred models, in order.")
        List<ModelHint> hints,

        @Property(documentation = "How much capability matters, from 0 to 1.")
        Integer intelligencePriority,

        @Property(documentation = "How much latency matters, from 0 to 1.")
        Integer speedPriority) {
    public static final int NOT_IMPORTANT_COST = 0;
    public static final int MOST_IMPORTANT_COST = 1;

    public static final int NOT_IMPORTANT_INTELLIGENCE = 0;
    public static final int MOST_IMPORTANT_INTELLIGENCE = 1;

    public static final int NOT_IMPORTANT_SPEED = 0;
    public static final int MOST_IMPORTANT_SPEED = 1;
}
