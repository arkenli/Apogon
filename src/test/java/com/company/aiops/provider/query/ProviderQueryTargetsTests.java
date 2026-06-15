package com.company.aiops.provider.query;

import com.company.aiops.provider.ProviderNames;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProviderQueryTargetsTests {

    @Test
    void targetForReturnsMappedTargetWhenPresent() {
        ProviderQueryTargets targets = new ProviderQueryTargets(
                "demo-service",
                "cluster2",
                Map.of(
                        ProviderNames.METRIC_CONTAINER,
                        ProviderQueryTarget.mapped(
                                ProviderNames.METRIC_CONTAINER,
                                "demo-service",
                                "cluster2_front"
                        )
                )
        );

        ProviderQueryTarget target = targets.targetFor(ProviderNames.METRIC_CONTAINER);

        assertThat(target.serviceName()).isEqualTo("demo-service");
        assertThat(target.clusterName()).isEqualTo("cluster2_front");
        assertThat(target.mapped()).isTrue();
    }

    @Test
    void targetForReturnsFallbackTargetWhenMissing() {
        ProviderQueryTargets targets = ProviderQueryTargets.fallback(
                "demo-service",
                "cluster2",
                Set.of(ProviderNames.METRIC_CONTAINER)
        );

        ProviderQueryTarget target = targets.targetFor(ProviderNames.METRIC_CSE);

        assertThat(target.providerName()).isEqualTo(ProviderNames.METRIC_CSE);
        assertThat(target.serviceName()).isEqualTo("demo-service");
        assertThat(target.clusterName()).isEqualTo("cluster2");
        assertThat(target.mapped()).isFalse();
    }

    @Test
    void providerNamesRecognizesKnownProviders() {
        assertThat(ProviderNames.isKnown(ProviderNames.RESOURCE_MAPPING)).isTrue();
        assertThat(ProviderNames.isKnown(ProviderNames.BASELINE)).isTrue();
        assertThat(ProviderNames.isKnown(ProviderNames.INTERFACE_LOG)).isTrue();
        assertThat(ProviderNames.isKnown(ProviderNames.TRACE)).isTrue();
        assertThat(ProviderNames.isKnown(ProviderNames.RUNTIME_LOG)).isTrue();
        assertThat(ProviderNames.isKnown(ProviderNames.METRIC_CONTAINER)).isTrue();
        assertThat(ProviderNames.isKnown(ProviderNames.METRIC_CSE)).isTrue();
        assertThat(ProviderNames.isKnown(ProviderNames.METRIC_DATABASE)).isTrue();
        assertThat(ProviderNames.isKnown(ProviderNames.METRIC_DCS)).isTrue();
        assertThat(ProviderNames.isKnown(ProviderNames.METRIC_MQ)).isTrue();
        assertThat(ProviderNames.isKnown(ProviderNames.PROVIDER_QUERY_MAPPING)).isTrue();
        assertThat(ProviderNames.isKnown(null)).isFalse();
        assertThat(ProviderNames.isKnown("metric.unknown")).isFalse();
        assertThat(ProviderNames.queryMappableProviderNames())
                .containsExactlyInAnyOrder(
                        ProviderNames.BASELINE,
                        ProviderNames.METRIC_CONTAINER,
                        ProviderNames.METRIC_CSE
                );
    }

    @Test
    void targetForRejectsBlankProviderName() {
        ProviderQueryTargets targets = ProviderQueryTargets.fallback(
                "demo-service",
                "cluster2",
                Set.of(ProviderNames.METRIC_CONTAINER)
        );

        assertThatThrownBy(() -> targets.targetFor(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("providerName must not be blank");
        assertThatThrownBy(() -> targets.targetFor(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("providerName must not be blank");
    }

    @Test
    void targetRejectsBlankValues() {
        assertThatThrownBy(() -> ProviderQueryTarget.mapped("", "demo-service", "cluster2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("providerName must not be blank");
    }
}
