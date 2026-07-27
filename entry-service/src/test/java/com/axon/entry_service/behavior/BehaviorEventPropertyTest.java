package com.axon.entry_service.behavior;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.axon.messaging.behavior.BehaviorEventProperty;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BehaviorEventPropertyTest {

    @Test
    void separatesKnownPropertiesFromUnknownSdkAttributes() {
        BehaviorEventProperty.NormalizedProperties normalized = BehaviorEventProperty.normalize(Map.of(
                "activityId", "12",
                "depth", 75,
                "experimentVariant", "B"));

        assertThat(normalized.properties())
                .containsEntry("activityId", 12L)
                .containsEntry("depth", 75L);
        assertThat(normalized.attributes()).containsEntry("experimentVariant", "B");
    }

    @Test
    void rejectsMalformedKnownProperty() {
        assertThatThrownBy(() -> BehaviorEventProperty.normalize(Map.of("depth", "deep")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("depth");
    }

    @Test
    void allowsOnlyNumericMarketingRuleProperties() {
        BehaviorEventProperty.validateRuleConditions(Map.of("durationSec", 30));

        assertThatThrownBy(() -> BehaviorEventProperty.validateRuleConditions(Map.of("source", "sdk")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source");
        assertThatThrownBy(() -> BehaviorEventProperty.validateRuleConditions(Map.of("unknown", 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown");
    }
}
