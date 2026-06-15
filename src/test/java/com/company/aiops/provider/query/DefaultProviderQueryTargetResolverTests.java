package com.company.aiops.provider.query;

import com.company.aiops.persistence.repository.ProviderQueryMappingRepository;
import com.company.aiops.provider.ProviderNames;
import com.company.aiops.provider.query.mapping.model.ProviderQueryMappingRecord;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultProviderQueryTargetResolverTests {

    @Test
    void resolveAllReturnsMappedTargetsForRequestedProviders() {
        DefaultProviderQueryTargetResolver resolver = new DefaultProviderQueryTargetResolver(
                repository(List.of(
                        record(ProviderNames.METRIC_CONTAINER, "demo-service-container", "cluster2_front", true),
                        record(ProviderNames.METRIC_CSE, "demo-service", "cluster2_rpc", true),
                        record(ProviderNames.RUNTIME_LOG, "demo-service", "cluster2_logs", true)
                ))
        );

        ProviderQueryTargets targets = resolver.resolveAll(
                "demo-service",
                "cluster2",
                Set.of(ProviderNames.METRIC_CONTAINER, ProviderNames.METRIC_CSE)
        );

        assertThat(targets.targetFor(ProviderNames.METRIC_CONTAINER).serviceName()).isEqualTo("demo-service-container");
        assertThat(targets.targetFor(ProviderNames.METRIC_CONTAINER).clusterName()).isEqualTo("cluster2_front");
        assertThat(targets.targetFor(ProviderNames.METRIC_CONTAINER).mapped()).isTrue();
        assertThat(targets.targetFor(ProviderNames.METRIC_CSE).clusterName()).isEqualTo("cluster2_rpc");
        assertThat(targets.targetFor(ProviderNames.RUNTIME_LOG).clusterName()).isEqualTo("cluster2");
    }

    @Test
    void resolveAllFallsBackForMissingProviderRows() {
        DefaultProviderQueryTargetResolver resolver = new DefaultProviderQueryTargetResolver(repository(List.of()));

        ProviderQueryTargets targets = resolver.resolveAll(
                "demo-service",
                "cluster2",
                Set.of(ProviderNames.BASELINE)
        );

        ProviderQueryTarget target = targets.targetFor(ProviderNames.BASELINE);
        assertThat(target.serviceName()).isEqualTo("demo-service");
        assertThat(target.clusterName()).isEqualTo("cluster2");
        assertThat(target.mapped()).isFalse();
    }

    @Test
    void resolveAllLetsRepositoryFailureBubbleToOrchestrator() {
        ProviderQueryMappingRepository repository = new ThrowingProviderQueryMappingRepository();
        DefaultProviderQueryTargetResolver resolver = new DefaultProviderQueryTargetResolver(repository);

        assertThatThrownBy(() -> resolver.resolveAll(
                "demo-service",
                "cluster2",
                Set.of(ProviderNames.METRIC_CONTAINER)
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("mapping table unavailable");
    }

    private ProviderQueryMappingRepository repository(List<ProviderQueryMappingRecord> records) {
        return new ProviderQueryMappingRepository() {
            @Override
            public List<ProviderQueryMappingRecord> findEnabledByServiceAndAlertCluster(
                    String serviceName,
                    String alertClusterName
            ) {
                assertThat(serviceName).isEqualTo("demo-service");
                assertThat(alertClusterName).isEqualTo("cluster2");
                return records.stream().filter(ProviderQueryMappingRecord::enabled).toList();
            }

            @Override
            public List<ProviderQueryMappingRecord> findAll(String serviceName, String alertClusterName, String providerName) {
                return records;
            }

            @Override
            public Optional<ProviderQueryMappingRecord> findById(long id) {
                return Optional.empty();
            }

            @Override
            public ProviderQueryMappingRecord create(ProviderQueryMappingRecord record) {
                return record;
            }

            @Override
            public ProviderQueryMappingRecord update(ProviderQueryMappingRecord record) {
                return record;
            }

            @Override
            public boolean deleteById(long id) {
                return false;
            }
        };
    }

    private ProviderQueryMappingRecord record(
            String providerName,
            String queryServiceName,
            String queryClusterName,
            boolean enabled
    ) {
        Instant now = Instant.parse("2026-06-15T00:00:00Z");
        return new ProviderQueryMappingRecord(
                1L,
                "demo-service",
                "cluster2",
                providerName,
                queryServiceName,
                queryClusterName,
                enabled,
                null,
                "test",
                "test",
                now,
                now
        );
    }

    private static final class ThrowingProviderQueryMappingRepository implements ProviderQueryMappingRepository {

        @Override
        public List<ProviderQueryMappingRecord> findEnabledByServiceAndAlertCluster(
                String serviceName,
                String alertClusterName
        ) {
            throw new IllegalStateException("mapping table unavailable");
        }

        @Override
        public List<ProviderQueryMappingRecord> findAll(String serviceName, String alertClusterName, String providerName) {
            return List.of();
        }

        @Override
        public Optional<ProviderQueryMappingRecord> findById(long id) {
            return Optional.empty();
        }

        @Override
        public ProviderQueryMappingRecord create(ProviderQueryMappingRecord record) {
            return record;
        }

        @Override
        public ProviderQueryMappingRecord update(ProviderQueryMappingRecord record) {
            return record;
        }

        @Override
        public boolean deleteById(long id) {
            return false;
        }
    }
}
