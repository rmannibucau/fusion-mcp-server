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
import io.yupiik.fusion.framework.build.api.json.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * The subset of JSON-Schema (draft 2020-12) MCP uses: tool {@code inputSchema}/{@code outputSchema} and
 * elicitation {@code requestedSchema}.
 * <p>
 * Tool schemas are derived from the OpenRPC document Fusion generates so this type is mainly built by hand for
 * elicitation - prefer the {@code string()}/{@code integer()}/{@code object()}/{@code array()} factories over the
 * canonical constructor.
 * <p>
 * NOTE: optionality is expressed with {@code required} - the JSON-Schema way - and not with a per property flag.
 *
 * @param properties           the object properties, only set for {@code type=object}.
 * @param additionalProperties either a boolean or a nested {@link JsonSchema}, only set for {@code type=object}.
 * @param items                the item schema, only set for {@code type=array}.
 * @param enumeration          the closed set of allowed values.
 * @param enumNames            the human oriented labels of {@code enumeration}, same order and size.
 * @param required             the names of the mandatory properties.
 * @param allOf                sub-schemas that must all validate (SEP-2106 {@code allOf}).
 * @param anyOf                sub-schemas, at least one of which must validate (SEP-2106 {@code anyOf}).
 * @param oneOf                sub-schemas, exactly one of which must validate (SEP-2106 {@code oneOf}).
 * @param not                  schema the value must not validate against (SEP-2106 {@code not}).
 * @param ifCondition          conditional schema (SEP-2106 {@code if}).
 * @param thenSchema           schema applied when the conditional schema validates (SEP-2106 {@code then}).
 * @param elseSchema           schema applied when the conditional schema does not validate (SEP-2106 {@code else}).
 * @param anchor               plain-name fragment identifier inside the schema (SEP-2106 {@code $anchor}).
 */
@JsonModel
public record JsonSchema(
        @Property(documentation = "JSON-Schema type: object, array, string, integer, number or boolean.")
        String type,

        @Property(documentation = "Human oriented name of the schema.")
        String title,

        @Property(documentation = "What the described value means.")
        String description,

        @Property(documentation = "Semantic format of a string, date-time for example.")
        String format,

        @Property(documentation = "Regular expression a string must match.")
        String pattern,

        @Property(documentation = "The properties, by name, for an object.")
        Map<String, JsonSchema> properties,

        @Property(documentation = "Either a boolean or the schema of the free form properties.")
        Object additionalProperties,

        @Property(documentation = "Schema of the items, for an array.")
        JsonSchema items,

        @Property(documentation = "The closed set of allowed values.") @JsonProperty("enum")
        List<String> enumeration,

        @Property(documentation = "Human oriented labels of the allowed values, same order.")
        List<String> enumNames,

        @Property(documentation = "Names of the mandatory properties.")
        List<String> required,

        @Property(documentation = "Minimum value of a number.")
        Double minimum,

        @Property(documentation = "Maximum value of a number.")
        Double maximum,

        @Property(documentation = "Minimum length of a string.")
        Integer minLength,

        @Property(documentation = "Maximum length of a string.")
        Integer maxLength,

        @Property(documentation = "Value used when the property is absent.") @JsonProperty("default")
        Object defaultValue,

        @Property(documentation = "The JSON-Schema dialect, 2020-12 when absent.") @JsonProperty("$schema")
        String schema,

        @Property(documentation = "Named reusable definitions, e.g. an address.") @JsonProperty("$defs")
        Map<String, JsonSchema> defs,

        @Property(documentation = "Reference to a definition in {@code $defs}.") @JsonProperty("$ref")
        String ref,

        @Property(documentation = "Transport hint: the parameter can be sent through the Mcp-Param-<suffix> header.")
        @JsonProperty("x-mcp-header")
        String xMcpHeader,

        @Property(documentation = "Sub-schemas that must all validate.")
        List<JsonSchema> allOf,

        @Property(documentation = "Sub-schemas, at least one of which must validate.")
        List<JsonSchema> anyOf,

        @Property(documentation = "Sub-schemas, exactly one of which must validate.")
        List<JsonSchema> oneOf,

        @Property(documentation = "Schema the value must not validate against.")
        JsonSchema not,

        @Property(documentation = "Conditional schema.") @JsonProperty("if")
        JsonSchema ifCondition,

        @Property(documentation = "Schema applied when the conditional schema validates.") @JsonProperty("then")
        JsonSchema thenSchema,

        @Property(documentation = "Schema applied when the conditional schema does not validate.") @JsonProperty("else")
        JsonSchema elseSchema,

        @Property(documentation = "Plain-name fragment identifier inside the schema.") @JsonProperty("$anchor")
        String anchor,

        @Property(documentation = "A literal value the instance must equal.") @JsonProperty("const")
        Object constant) {
    public static JsonSchema of(final String type, final String description) {
        return new JsonSchema(
                type,
                null,
                description,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    public static JsonSchema string(final String description) {
        return of("string", description);
    }

    public static JsonSchema string(final String description, final String format, final String pattern) {
        return new JsonSchema(
                "string",
                null,
                description,
                format,
                pattern,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    public static JsonSchema integer(final String description) {
        return of("integer", description);
    }

    public static JsonSchema number(final String description) {
        return of("number", description);
    }

    public static JsonSchema bool(final String description) {
        return of("boolean", description);
    }

    public static JsonSchema enumeration(final String description, final List<String> values) {
        return new JsonSchema(
                "string",
                null,
                description,
                null,
                null,
                null,
                null,
                null,
                values,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    public static JsonSchema array(final String description, final JsonSchema items) {
        return new JsonSchema(
                "array",
                null,
                description,
                null,
                null,
                null,
                null,
                items,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    public static JsonSchema object(
            final String description, final Map<String, JsonSchema> properties, final List<String> required) {
        return new JsonSchema(
                "object",
                null,
                description,
                null,
                null,
                properties,
                null,
                null,
                null,
                null,
                required,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    public static JsonSchema object(
            final String description,
            final Map<String, JsonSchema> properties,
            final Object additionalProperties,
            final List<String> required) {
        return new JsonSchema(
                "object",
                null,
                description,
                null,
                null,
                properties,
                additionalProperties,
                null,
                null,
                null,
                required,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    /**
     * @param suffix the {@code Mcp-Param-<suffix>} header the parameter is sent through.
     * @return a copy carrying the {@code x-mcp-header} transport hint.
     */
    public JsonSchema withHeader(final String suffix) {
        return new JsonSchema(
                type,
                title,
                description,
                format,
                pattern,
                properties,
                additionalProperties,
                items,
                enumeration,
                enumNames,
                required,
                minimum,
                maximum,
                minLength,
                maxLength,
                defaultValue,
                schema,
                defs,
                ref,
                suffix,
                allOf,
                anyOf,
                oneOf,
                not,
                ifCondition,
                thenSchema,
                elseSchema,
                anchor,
                null);
    }

    /**
     * @param dialect the JSON-Schema dialect, {@code https://json-schema.org/draft/2020-12/schema} for example.
     * @return a copy carrying the {@code $schema} keyword.
     */
    public JsonSchema withDialect(final String dialect) {
        return new JsonSchema(
                type,
                title,
                description,
                format,
                pattern,
                properties,
                additionalProperties,
                items,
                enumeration,
                enumNames,
                required,
                minimum,
                maximum,
                minLength,
                maxLength,
                defaultValue,
                dialect,
                defs,
                ref,
                xMcpHeader,
                allOf,
                anyOf,
                oneOf,
                not,
                ifCondition,
                thenSchema,
                elseSchema,
                anchor,
                null);
    }

    /**
     * @param namedDefs the reusable definitions, keyed by name.
     * @return a copy carrying the {@code $defs} keyword.
     */
    public JsonSchema withDefs(final Map<String, JsonSchema> namedDefs) {
        return new JsonSchema(
                type,
                title,
                description,
                format,
                pattern,
                properties,
                additionalProperties,
                items,
                enumeration,
                enumNames,
                required,
                minimum,
                maximum,
                minLength,
                maxLength,
                defaultValue,
                schema,
                namedDefs,
                ref,
                xMcpHeader,
                allOf,
                anyOf,
                oneOf,
                not,
                ifCondition,
                thenSchema,
                elseSchema,
                anchor,
                null);
    }

    /**
     * @param reference a JSON pointer to a definition in {@code $defs}, {@code #/$defs/address} for example.
     * @return a copy carrying the {@code $ref} keyword.
     */
    /**
     * @param value the value used when the property is absent - the {@code default} keyword.
     * @return a copy carrying the default value.
     */
    public JsonSchema withDefault(final Object value) {
        return new JsonSchema(
                type,
                title,
                description,
                format,
                pattern,
                properties,
                additionalProperties,
                items,
                enumeration,
                enumNames,
                required,
                minimum,
                maximum,
                minLength,
                maxLength,
                value,
                schema,
                defs,
                ref,
                xMcpHeader,
                allOf,
                anyOf,
                oneOf,
                not,
                ifCondition,
                thenSchema,
                elseSchema,
                anchor,
                constant);
    }

    public JsonSchema withRef(final String reference) {
        return new JsonSchema(
                type,
                title,
                description,
                format,
                pattern,
                properties,
                additionalProperties,
                items,
                enumeration,
                enumNames,
                required,
                minimum,
                maximum,
                minLength,
                maxLength,
                defaultValue,
                schema,
                defs,
                reference,
                xMcpHeader,
                allOf,
                anyOf,
                oneOf,
                not,
                ifCondition,
                thenSchema,
                elseSchema,
                anchor,
                null);
    }

    /**
     * @param value the human oriented name of the schema.
     * @return a copy carrying the {@code title}.
     */
    public JsonSchema withTitle(final String value) {
        return new JsonSchema(
                type,
                value,
                description,
                format,
                pattern,
                properties,
                additionalProperties,
                items,
                enumeration,
                enumNames,
                required,
                minimum,
                maximum,
                minLength,
                maxLength,
                defaultValue,
                schema,
                defs,
                ref,
                xMcpHeader,
                allOf,
                anyOf,
                oneOf,
                not,
                ifCondition,
                thenSchema,
                elseSchema,
                anchor,
                constant);
    }

    /**
     * @param names the human oriented labels of the allowed values, same order as {@code enum}.
     * @return a copy carrying the {@code enumNames}.
     */
    public JsonSchema withEnumNames(final List<String> names) {
        return new JsonSchema(
                type,
                title,
                description,
                format,
                pattern,
                properties,
                additionalProperties,
                items,
                enumeration,
                names,
                required,
                minimum,
                maximum,
                minLength,
                maxLength,
                defaultValue,
                schema,
                defs,
                ref,
                xMcpHeader,
                allOf,
                anyOf,
                oneOf,
                not,
                ifCondition,
                thenSchema,
                elseSchema,
                anchor,
                constant);
    }

    /**
     * @param alternatives the sub-schemas, exactly one of which must validate.
     * @return a copy carrying the {@code oneOf} keyword.
     */
    public JsonSchema withOneOf(final List<JsonSchema> alternatives) {
        return new JsonSchema(
                type,
                title,
                description,
                format,
                pattern,
                properties,
                additionalProperties,
                items,
                enumeration,
                enumNames,
                required,
                minimum,
                maximum,
                minLength,
                maxLength,
                defaultValue,
                schema,
                defs,
                ref,
                xMcpHeader,
                allOf,
                anyOf,
                alternatives,
                not,
                ifCondition,
                thenSchema,
                elseSchema,
                anchor,
                constant);
    }

    /**
     * @param literal a literal value the instance must equal.
     * @return a copy carrying the {@code const} keyword.
     */
    public JsonSchema withConst(final Object literal) {
        return new JsonSchema(
                type,
                title,
                description,
                format,
                pattern,
                properties,
                additionalProperties,
                items,
                enumeration,
                enumNames,
                required,
                minimum,
                maximum,
                minLength,
                maxLength,
                defaultValue,
                schema,
                defs,
                ref,
                xMcpHeader,
                allOf,
                anyOf,
                oneOf,
                not,
                ifCondition,
                thenSchema,
                elseSchema,
                anchor,
                literal);
    }

    /**
     * @param composition the sub-schemas that must all validate.
     * @return a copy carrying the {@code allOf} keyword.
     */
    public JsonSchema withAllOf(final List<JsonSchema> composition) {
        return new JsonSchema(
                type,
                title,
                description,
                format,
                pattern,
                properties,
                additionalProperties,
                items,
                enumeration,
                enumNames,
                required,
                minimum,
                maximum,
                minLength,
                maxLength,
                defaultValue,
                schema,
                defs,
                ref,
                xMcpHeader,
                composition,
                anyOf,
                oneOf,
                not,
                ifCondition,
                thenSchema,
                elseSchema,
                anchor,
                constant);
    }

    /**
     * @param alternatives the sub-schemas, at least one of which must validate.
     * @return a copy carrying the {@code anyOf} keyword.
     */
    public JsonSchema withAnyOf(final List<JsonSchema> alternatives) {
        return new JsonSchema(
                type,
                title,
                description,
                format,
                pattern,
                properties,
                additionalProperties,
                items,
                enumeration,
                enumNames,
                required,
                minimum,
                maximum,
                minLength,
                maxLength,
                defaultValue,
                schema,
                defs,
                ref,
                xMcpHeader,
                allOf,
                alternatives,
                oneOf,
                not,
                ifCondition,
                thenSchema,
                elseSchema,
                anchor,
                constant);
    }

    /**
     * @param conditional the conditional schema.
     * @param consequent  the schema applied when the conditional one validates.
     * @param alternate   the schema applied when the conditional one does not validate.
     * @return a copy carrying the {@code if}/{@code then}/{@code else} keywords.
     */
    public JsonSchema withConditional(
            final JsonSchema conditional, final JsonSchema consequent, final JsonSchema alternate) {
        return new JsonSchema(
                type,
                title,
                description,
                format,
                pattern,
                properties,
                additionalProperties,
                items,
                enumeration,
                enumNames,
                required,
                minimum,
                maximum,
                minLength,
                maxLength,
                defaultValue,
                schema,
                defs,
                ref,
                xMcpHeader,
                allOf,
                anyOf,
                oneOf,
                not,
                conditional,
                consequent,
                alternate,
                anchor,
                constant);
    }

    /**
     * @param plainName a plain-name fragment identifier inside the schema.
     * @return a copy carrying the {@code $anchor} keyword.
     */
    public JsonSchema withAnchor(final String plainName) {
        return new JsonSchema(
                type,
                title,
                description,
                format,
                pattern,
                properties,
                additionalProperties,
                items,
                enumeration,
                enumNames,
                required,
                minimum,
                maximum,
                minLength,
                maxLength,
                defaultValue,
                schema,
                defs,
                ref,
                xMcpHeader,
                allOf,
                anyOf,
                oneOf,
                not,
                ifCondition,
                thenSchema,
                elseSchema,
                plainName,
                constant);
    }
}
