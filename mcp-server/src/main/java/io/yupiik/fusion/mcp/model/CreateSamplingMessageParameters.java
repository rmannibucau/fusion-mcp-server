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

import java.util.List;
import java.util.Map;

// sampling/createMessage params
/**
 * {@code sampling/createMessage} parameters, i.e. a LLM completion the server asks the client to run.
 *
 * @param includeContext   which servers context the client should add to the prompt.
 * @param maxTokens        the maximum amount of tokens to generate, it is required.
 * @param messages         the conversation to complete, it is required.
 * @param metadata         provider specific parameters.
 * @param modelPreferences how to select the model.
 * @param stopSequences    sequences stopping the generation.
 * @param systemPrompt     the system prompt the client may use.
 * @param temperature      the sampling temperature.
 */
@JsonModel
public record CreateSamplingMessageParameters(
        SamplingServer includeContext,
        Integer maxTokens,
        List<SamplingMessage> messages,
        Map<String, Object> metadata,
        ModelPreferences modelPreferences,
        List<String> stopSequences,
        String systemPrompt,
        Double temperature
) {
}
