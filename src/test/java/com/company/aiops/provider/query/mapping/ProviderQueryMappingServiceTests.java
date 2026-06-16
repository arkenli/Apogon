package com.company.aiops.provider.query.mapping;

import com.company.aiops.common.exception.AiopsException;
import com.company.aiops.common.exception.ErrorCode;
import com.company.aiops.persistence.repository.ProviderQueryMappingRepository;
import com.company.aiops.provider.ProviderNames;
import com.company.aiops.provider.query.mapping.model.ProviderQueryMappingItemResponse;
import com.company.aiops.provider.query.mapping.model.ProviderQueryMappingItemUpsertRequest;
import com.company.aiops.provider.query.mapping.model.ProviderQueryMappingRecord;
import com.company.aiops.provider.query.mapping.service.ProviderQueryMappingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ProviderQueryMappingServiceTests {

    private static final String UPDATED_BY = "provider-query-mapping-api";

    @Test
    void createTrimsNormalizesValuesAndReturnsMappedResponse() {
        ProviderQueryMappingRepository repository = mock(ProviderQueryMappingRepository.class);
        ProviderQueryMappingService service = service(repository);
        Instant createdAt = Instant.parse("2026-06-16T01:00:00Z");
        Instant updatedAt = Instant.parse("2026-06-16T01:05:00Z");
        when(repository.create(any())).thenAnswer(invocation -> {
            ProviderQueryMappingRecord draft = invocation.getArgument(0);
            return new ProviderQueryMappingRecord(
                    11L,
                    draft.serviceName(),
                    draft.alertClusterName(),
                    draft.providerName(),
                    draft.queryServiceName(),
                    draft.queryClusterName(),
                    draft.enabled(),
                    draft.metadataJson(),
                    draft.createdBy(),
                    draft.updatedBy(),
                    createdAt,
                    updatedAt
            );
        });

        ProviderQueryMappingItemResponse response = service.create(new ProviderQueryMappingItemUpsertRequest(
                "  ams_front  ",
                "  cluster1  ",
                "  baseline  ",
                "  query_ams_front  ",
                "  query_cluster_1  ",
                "  {\"env\":\"local\"}  "
        ));

        ArgumentCaptor<ProviderQueryMappingRecord> recordCaptor = ArgumentCaptor.forClass(ProviderQueryMappingRecord.class);
        verify(repository).create(recordCaptor.capture());
        ProviderQueryMappingRecord draft = recordCaptor.getValue();
        assertThat(draft.id()).isNull();
        assertThat(draft.serviceName()).isEqualTo("ams_front");
        assertThat(draft.alertClusterName()).isEqualTo("cluster1");
        assertThat(draft.providerName()).isEqualTo(ProviderNames.BASELINE);
        assertThat(draft.queryServiceName()).isEqualTo("query_ams_front");
        assertThat(draft.queryClusterName()).isEqualTo("query_cluster_1");
        assertThat(draft.metadataJson()).isEqualTo("{\"env\":\"local\"}");
        assertThat(draft.enabled()).isTrue();
        assertThat(draft.createdBy()).isEqualTo(UPDATED_BY);
        assertThat(draft.updatedBy()).isEqualTo(UPDATED_BY);
        assertThat(draft.createdAt()).isNull();
        assertThat(draft.updatedAt()).isNull();

        assertThat(response.id()).isEqualTo(11L);
        assertThat(response.serviceName()).isEqualTo("ams_front");
        assertThat(response.alertClusterName()).isEqualTo("cluster1");
        assertThat(response.providerName()).isEqualTo(ProviderNames.BASELINE);
        assertThat(response.queryServiceName()).isEqualTo("query_ams_front");
        assertThat(response.queryClusterName()).isEqualTo("query_cluster_1");
        assertThat(response.metadataJson()).isEqualTo("{\"env\":\"local\"}");
        assertThat(response.enabled()).isTrue();
        assertThat(response.createdBy()).isEqualTo(UPDATED_BY);
        assertThat(response.updatedBy()).isEqualTo(UPDATED_BY);
        assertThat(response.createdAt()).isEqualTo(createdAt);
        assertThat(response.updatedAt()).isEqualTo(updatedAt);
    }

    @Test
    void createAcceptsKnownProviderOutsideCurrentRuntimeQueryTargetSet() {
        ProviderQueryMappingRepository repository = mock(ProviderQueryMappingRepository.class);
        ProviderQueryMappingService service = service(repository);
        Instant createdAt = Instant.parse("2026-06-16T01:10:00Z");
        Instant updatedAt = Instant.parse("2026-06-16T01:15:00Z");
        when(repository.create(any())).thenAnswer(invocation -> {
            ProviderQueryMappingRecord draft = invocation.getArgument(0);
            return new ProviderQueryMappingRecord(
                    12L,
                    draft.serviceName(),
                    draft.alertClusterName(),
                    draft.providerName(),
                    draft.queryServiceName(),
                    draft.queryClusterName(),
                    draft.enabled(),
                    draft.metadataJson(),
                    draft.createdBy(),
                    draft.updatedBy(),
                    createdAt,
                    updatedAt
            );
        });

        ProviderQueryMappingItemResponse response = service.create(request(ProviderNames.METRIC_DATABASE, "{\"env\":\"local\"}"));

        ArgumentCaptor<ProviderQueryMappingRecord> recordCaptor = ArgumentCaptor.forClass(ProviderQueryMappingRecord.class);
        verify(repository).create(recordCaptor.capture());
        assertThat(recordCaptor.getValue().providerName()).isEqualTo(ProviderNames.METRIC_DATABASE);
        assertThat(response.providerName()).isEqualTo(ProviderNames.METRIC_DATABASE);
    }

    @Test
    void createRejectsUnknownProviderName() {
        ProviderQueryMappingRepository repository = mock(ProviderQueryMappingRepository.class);
        ProviderQueryMappingService service = service(repository);

        assertThatThrownBy(() -> service.create(request("metric.unknown", "{\"env\":\"local\"}")))
                .isInstanceOfSatisfying(AiopsException.class, exception -> {
                    assertThat(exception.errorCode()).isEqualTo(ErrorCode.BAD_REQUEST);
                    assertThat(exception).hasMessage(
                            "providerName is not supported for query mapping: metric.unknown"
                    );
                });

        verifyNoInteractions(repository);
    }

    @Test
    void createRejectsProviderQueryMapping() {
        ProviderQueryMappingRepository repository = mock(ProviderQueryMappingRepository.class);
        ProviderQueryMappingService service = service(repository);

        assertThatThrownBy(() -> service.create(request(ProviderNames.PROVIDER_QUERY_MAPPING, "{\"env\":\"local\"}")))
                .isInstanceOfSatisfying(AiopsException.class, exception -> {
                    assertThat(exception.errorCode()).isEqualTo(ErrorCode.BAD_REQUEST);
                    assertThat(exception).hasMessage(
                            "providerName is not supported for query mapping: "
                                    + ProviderNames.PROVIDER_QUERY_MAPPING
                    );
                });

        verifyNoInteractions(repository);
    }

    @Test
    void createRejectsInvalidMetadataJson() {
        ProviderQueryMappingRepository repository = mock(ProviderQueryMappingRepository.class);
        ProviderQueryMappingService service = service(repository);

        assertThatThrownBy(() -> service.create(request(ProviderNames.BASELINE, "{\"env\":")))
                .isInstanceOfSatisfying(AiopsException.class, exception -> {
                    assertThat(exception.errorCode()).isEqualTo(ErrorCode.BAD_REQUEST);
                    assertThat(exception).hasMessage("metadataJson must be valid JSON");
                });

        verifyNoInteractions(repository);
    }

    @Test
    void createTranslatesDuplicateKeyExceptionToBadRequest() {
        ProviderQueryMappingRepository repository = mock(ProviderQueryMappingRepository.class);
        ProviderQueryMappingService service = service(repository);
        when(repository.create(any())).thenThrow(new DuplicateKeyException("duplicate"));

        assertThatThrownBy(() -> service.create(new ProviderQueryMappingItemUpsertRequest(
                "  ams_front  ",
                "  cluster1  ",
                "  baseline  ",
                "  query_ams_front  ",
                "  query_cluster_1  ",
                null
        )))
                .isInstanceOfSatisfying(AiopsException.class, exception -> {
                    assertThat(exception.errorCode()).isEqualTo(ErrorCode.BAD_REQUEST);
                    assertThat(exception).hasMessage(
                            "Provider query mapping item already exists for serviceName=ams_front, "
                                    + "alertClusterName=cluster1, providerName=baseline"
                    );
                });
    }

    @Test
    void updatePreservesCreatedByAndCreatedAt() {
        ProviderQueryMappingRepository repository = mock(ProviderQueryMappingRepository.class);
        ProviderQueryMappingService service = service(repository);
        Instant createdAt = Instant.parse("2026-06-15T10:00:00Z");
        ProviderQueryMappingRecord existing = new ProviderQueryMappingRecord(
                5L,
                "old-service",
                "old-cluster",
                ProviderNames.BASELINE,
                "old-query-service",
                "old-query-cluster",
                false,
                "{\"old\":true}",
                "seed-user",
                "seed-user",
                createdAt,
                Instant.parse("2026-06-15T10:05:00Z")
        );
        when(repository.findById(5L)).thenReturn(Optional.of(existing));
        when(repository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ProviderQueryMappingItemResponse response = service.update(5L, new ProviderQueryMappingItemUpsertRequest(
                "  ams_front  ",
                "  cluster1  ",
                "  metric.container  ",
                "  query_ams_front  ",
                "  query_cluster_1  ",
                "  {\"env\":\"prod\"}  "
        ));

        ArgumentCaptor<ProviderQueryMappingRecord> recordCaptor = ArgumentCaptor.forClass(ProviderQueryMappingRecord.class);
        verify(repository).update(recordCaptor.capture());
        ProviderQueryMappingRecord updated = recordCaptor.getValue();
        assertThat(updated.id()).isEqualTo(5L);
        assertThat(updated.serviceName()).isEqualTo("ams_front");
        assertThat(updated.alertClusterName()).isEqualTo("cluster1");
        assertThat(updated.providerName()).isEqualTo(ProviderNames.METRIC_CONTAINER);
        assertThat(updated.queryServiceName()).isEqualTo("query_ams_front");
        assertThat(updated.queryClusterName()).isEqualTo("query_cluster_1");
        assertThat(updated.metadataJson()).isEqualTo("{\"env\":\"prod\"}");
        assertThat(updated.enabled()).isFalse();
        assertThat(updated.createdBy()).isEqualTo("seed-user");
        assertThat(updated.createdAt()).isEqualTo(createdAt);
        assertThat(updated.updatedBy()).isEqualTo(UPDATED_BY);
        assertThat(updated.updatedAt()).isNotNull();

        assertThat(response.createdBy()).isEqualTo("seed-user");
        assertThat(response.createdAt()).isEqualTo(createdAt);
        assertThat(response.updatedBy()).isEqualTo(UPDATED_BY);
    }

    @Test
    void updateTranslatesDuplicateKeyExceptionToBadRequest() {
        ProviderQueryMappingRepository repository = mock(ProviderQueryMappingRepository.class);
        ProviderQueryMappingService service = service(repository);
        Instant createdAt = Instant.parse("2026-06-15T10:00:00Z");
        ProviderQueryMappingRecord existing = new ProviderQueryMappingRecord(
                6L,
                "seed-service",
                "seed-cluster",
                ProviderNames.BASELINE,
                "seed-query-service",
                "seed-query-cluster",
                true,
                null,
                "seed-user",
                "seed-user",
                createdAt,
                createdAt
        );
        when(repository.findById(6L)).thenReturn(Optional.of(existing));
        when(repository.update(any())).thenThrow(new DuplicateKeyException("duplicate"));

        assertThatThrownBy(() -> service.update(6L, new ProviderQueryMappingItemUpsertRequest(
                "  ams_front  ",
                "  cluster1  ",
                "  metric.dcs  ",
                "  query_ams_front  ",
                "  query_cluster_1  ",
                null
        )))
                .isInstanceOfSatisfying(AiopsException.class, exception -> {
                    assertThat(exception.errorCode()).isEqualTo(ErrorCode.BAD_REQUEST);
                    assertThat(exception).hasMessage(
                            "Provider query mapping item already exists for serviceName=ams_front, "
                                    + "alertClusterName=cluster1, providerName=metric.dcs"
                    );
                });
    }

    @Test
    void deleteThrowsNotFoundWhenIdIsMissing() {
        ProviderQueryMappingRepository repository = mock(ProviderQueryMappingRepository.class);
        ProviderQueryMappingService service = service(repository);
        when(repository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.delete(99L))
                .isInstanceOfSatisfying(AiopsException.class, exception -> {
                    assertThat(exception.errorCode()).isEqualTo(ErrorCode.NOT_FOUND);
                    assertThat(exception).hasMessage("Provider query mapping item not found: id=99");
                });
    }

    @Test
    void deleteCallsDeleteByIdWhenRecordExists() {
        ProviderQueryMappingRepository repository = mock(ProviderQueryMappingRepository.class);
        ProviderQueryMappingService service = service(repository);
        when(repository.findById(7L)).thenReturn(Optional.of(new ProviderQueryMappingRecord(
                7L,
                "ams_front",
                "cluster1",
                ProviderNames.METRIC_MQ,
                "query_ams_front",
                "query_cluster_1",
                true,
                null,
                UPDATED_BY,
                UPDATED_BY,
                Instant.parse("2026-06-15T09:00:00Z"),
                Instant.parse("2026-06-15T09:05:00Z")
        )));

        service.delete(7L);

        verify(repository).deleteById(7L);
    }

    @Test
    void listNormalizesFiltersAndKnownProviderFilterAsIntended() {
        ProviderQueryMappingRepository repository = mock(ProviderQueryMappingRepository.class);
        ProviderQueryMappingService service = service(repository);
        Instant now = Instant.parse("2026-06-16T02:00:00Z");
        when(repository.findAll("ams_front", null, ProviderNames.METRIC_DATABASE)).thenReturn(List.of(
                new ProviderQueryMappingRecord(
                        7L,
                        "ams_front",
                        "cluster1",
                        ProviderNames.METRIC_DATABASE,
                        "query_ams_front",
                        "query_cluster_1",
                        true,
                        "{\"env\":\"local\"}",
                        UPDATED_BY,
                        UPDATED_BY,
                        now,
                        now
                )
        ));

        List<ProviderQueryMappingItemResponse> response = service.list("  ams_front  ", "   ", "  metric.database  ");

        verify(repository).findAll("ams_front", null, ProviderNames.METRIC_DATABASE);
        assertThat(response).singleElement().satisfies(item -> {
            assertThat(item.id()).isEqualTo(7L);
            assertThat(item.providerName()).isEqualTo(ProviderNames.METRIC_DATABASE);
            assertThat(item.queryServiceName()).isEqualTo("query_ams_front");
        });
    }

    @Test
    void listRejectsUnsupportedProviderFilter() {
        ProviderQueryMappingRepository repository = mock(ProviderQueryMappingRepository.class);
        ProviderQueryMappingService service = service(repository);

        assertThatThrownBy(() -> service.list("ams_front", "cluster1", " metric.unknown "))
                .isInstanceOfSatisfying(AiopsException.class, exception -> {
                    assertThat(exception.errorCode()).isEqualTo(ErrorCode.BAD_REQUEST);
                    assertThat(exception).hasMessage(
                            "providerName is not supported for query mapping: metric.unknown"
                    );
                });

        verifyNoInteractions(repository);
    }

    private ProviderQueryMappingService service(ProviderQueryMappingRepository repository) {
        return new ProviderQueryMappingService(repository, new ObjectMapper());
    }

    private ProviderQueryMappingItemUpsertRequest request(String providerName, String metadataJson) {
        return new ProviderQueryMappingItemUpsertRequest(
                "ams_front",
                "cluster1",
                providerName,
                "query_ams_front",
                "query_cluster_1",
                metadataJson
        );
    }
}
