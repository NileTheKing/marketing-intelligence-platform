package com.axon.messaging.behavior;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Stable, queryable behavior-event fields. Unknown SDK metadata belongs in attributes.
 */
public enum BehaviorEventProperty {

    ACTIVITY_ID("activityId", ValueType.LONG, false),
    CAMPAIGN_ID("campaignId", ValueType.LONG, false),
    PRODUCT_ID("productId", ValueType.LONG, false),
    DEPTH("depth", ValueType.LONG, true),
    DURATION_SEC("durationSec", ValueType.LONG, true),
    SOURCE("source", ValueType.STRING, false),
    ORDER("order", ValueType.LONG, false);

    private final String key;
    private final ValueType valueType;
    private final boolean ruleConditionAllowed;

    BehaviorEventProperty(String key, ValueType valueType, boolean ruleConditionAllowed) {
        this.key = key;
        this.valueType = valueType;
        this.ruleConditionAllowed = ruleConditionAllowed;
    }

    public String key() {
        return key;
    }

    public static NormalizedProperties normalize(Map<String, Object> input) {
        if (input == null || input.isEmpty()) {
            return new NormalizedProperties(Map.of(), Map.of());
        }

        Map<String, Object> properties = new LinkedHashMap<>();
        Map<String, Object> attributes = new LinkedHashMap<>();
        input.forEach((key, value) -> {
            BehaviorEventProperty property = fromKey(key);
            if (property == null) {
                attributes.put(key, value);
                return;
            }
            properties.put(key, property.valueType.normalize(key, value));
        });
        return new NormalizedProperties(
                Collections.unmodifiableMap(properties), Collections.unmodifiableMap(attributes));
    }

    /** Marketing rules may only filter numeric fields with a stable ES mapping. */
    public static void validateRuleConditions(Map<String, Object> conditions) {
        if (conditions == null || conditions.isEmpty()) {
            return;
        }
        conditions.forEach((key, value) -> {
            BehaviorEventProperty property = fromKey(key);
            if (property == null || !property.ruleConditionAllowed) {
                throw new IllegalArgumentException("Unsupported marketing rule property: " + key);
            }
            property.valueType.normalize(key, value);
        });
    }

    private static BehaviorEventProperty fromKey(String key) {
        for (BehaviorEventProperty property : values()) {
            if (property.key.equals(key)) {
                return property;
            }
        }
        return null;
    }

    public record NormalizedProperties(Map<String, Object> properties, Map<String, Object> attributes) {
    }

    private enum ValueType {
        LONG {
            @Override
            Object normalize(String key, Object value) {
                if (value instanceof Number number) {
                    return number.longValue();
                }
                if (value instanceof String text) {
                    try {
                        return Long.parseLong(text);
                    } catch (NumberFormatException ignored) {
                        // The common error below identifies the malformed field.
                    }
                }
                throw invalidType(key, "integer");
            }
        },
        STRING {
            @Override
            Object normalize(String key, Object value) {
                if (value instanceof String text && !text.isBlank()) {
                    return text;
                }
                throw invalidType(key, "non-blank string");
            }
        };

        abstract Object normalize(String key, Object value);

        private static IllegalArgumentException invalidType(String key, String expected) {
            return new IllegalArgumentException("Behavior property '" + key + "' must be a " + expected);
        }
    }
}
