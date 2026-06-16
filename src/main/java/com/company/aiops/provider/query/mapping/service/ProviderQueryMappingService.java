package com.company.aiops.provider.query.mapping.service;

import com.company.aiops.common.exception.AiopsException;
import com.company.aiops.common.exception.ErrorCode;
import com.company.aiops.persistence.repository.ProviderQueryMappingRepository;
import com.company.aiops.provider.ProviderNames;
import com.company.aiops.provider.query.mapping.model.ProviderQueryMappingItemResponse;
import com.company.aiops.provider.query.mapping.model.ProviderQueryMappingItemUpsertRequest;
import com.company.aiops.provider.query.mapping.model.ProviderQueryMappingRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

@Service
public class ProviderQueryMappingService {

    private static final String UPDATED_BY = "provider-query-mapping-api";

    private final ProviderQueryMappingRepository providerQueryMappingRepository;
    private final ObjectMapper objectMapper;

    public ProviderQueryMappingService(
            ProviderQueryMappingRepository providerQueryMappingRepository,
            ObjectMapper objectMapper
    ) {
        this.providerQueryMappingRepository = providerQueryMappingRepository;
        this.objectMapper = objectMapper;
    }

    public List<ProviderQueryMappingItemResponse> list(
            String serviceName,
            String alertClusterName,
            String providerName
    ) {
        return providerQueryMappingRepository.findAll(
                        normalizeFilter(serviceName),
                        normalizeFilter(alertClusterName),
                        normalizeProviderFilter(providerName)
                )
                .stream()
                .map(this::toResponse)
                .toList();
    }

    public ProviderQueryMappingItemResponse create(ProviderQueryMappingItemUpsertRequest request) {
        ProviderQueryMappingRecord draft = buildNewRecord(request);
        try {
            return toResponse(providerQueryMappingRepository.create(draft));
        } catch (DuplicateKeyException exception) {
            throw duplicate(request, exception);
        }
    }

    public ProviderQueryMappingItemResponse update(long id, ProviderQueryMappingItemUpsertRequest request) {
        ProviderQueryMappingRecord existing = providerQueryMappingRepository.findById(id)
                .orElseThrow(() -> notFound(id));
        ProviderQueryMappingRecord updated = new ProviderQueryMappingRecord(
                existing.id(),
                normalizeRequired(request.serviceName(), "serviceName"),
                normalizeRequired(request.alertClusterName(), "alertClusterName"),
                normalizeProviderName(request.providerName()),
                normalizeRequired(request.queryServiceName(), "queryServiceName"),
                normalizeRequired(request.queryClusterName(), "queryClusterName"),
                existing.enabled(),
                normalizeMetadataJson(request.metadataJson()),
                existing.createdBy(),
                UPDATED_BY,
                existing.createdAt(),
                Instant.now()
        );
        try {
            return toResponse(providerQueryMappingRepository.update(updated));
        } catch (DuplicateKeyException exception) {
            throw duplicate(request, exception);
        }
    }

    public void delete(long id) {
        providerQueryMappingRepository.findById(id)
                .orElseThrow(() -> notFound(id));
        providerQueryMappingRepository.deleteById(id);
    }

    private ProviderQueryMappingRecord buildNewRecord(ProviderQueryMappingItemUpsertRequest request) {
        return new ProviderQueryMappingRecord(
                null,
                normalizeRequired(request.serviceName(), "serviceName"),
                normalizeRequired(request.alertClusterName(), "alertClusterName"),
                normalizeProviderName(request.providerName()),
                normalizeRequired(request.queryServiceName(), "queryServiceName"),
                normalizeRequired(request.queryClusterName(), "queryClusterName"),
                true,
                normalizeMetadataJson(request.metadataJson()),
                UPDATED_BY,
                UPDATED_BY,
                null,
                null
        );
    }

    private ProviderQueryMappingItemResponse toResponse(ProviderQueryMappingRecord record) {
        return new ProviderQueryMappingItemResponse(
                record.id(),
                record.serviceName(),
                record.alertClusterName(),
                record.providerName(),
                record.queryServiceName(),
                record.queryClusterName(),
                record.metadataJson(),
                record.enabled(),
                record.createdBy(),
                record.updatedBy(),
                record.createdAt(),
                record.updatedAt()
        );
    }

    private String normalizeFilter(String value) {
        return normalizeOptional(value);
    }

    private String normalizeProviderFilter(String value) {
        String normalized = normalizeOptional(value);
        return normalized == null ? null : normalizeProviderName(normalized);
    }

    private String normalizeRequired(String value, String fieldName) {
        String normalized = normalizeOptional(value);
        if (normalized == null) {
            throw new AiopsException(ErrorCode.BAD_REQUEST, fieldName + " must not be blank");
        }
        return normalized;
    }

    private String normalizeOptional(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private String normalizeProviderName(String value) {
        String normalized = normalizeRequired(value, "providerName");
        if (ProviderNames.PROVIDER_QUERY_MAPPING.equals(normalized) || !ProviderNames.isKnown(normalized)) {
            throw new AiopsException(
                    ErrorCode.BAD_REQUEST,
                    "providerName is not supported for query mapping: " + normalized
            );
        }
        return normalized;
    }

    private String normalizeMetadataJson(String metadataJson) {
        String normalized = normalizeOptional(metadataJson);
        if (normalized == null) {
            return null;
        }
        try {
            objectMapper.readTree(normalized);
        } catch (IOException exception) {
            throw new AiopsException(ErrorCode.BAD_REQUEST, "metadataJson must be valid JSON");
        }
        return normalized;
    }

    private AiopsException notFound(long id) {
        return new AiopsException(ErrorCode.NOT_FOUND, "Provider query mapping item not found: id=" + id);
    }

    private AiopsException duplicate(
            ProviderQueryMappingItemUpsertRequest request,
            DuplicateKeyException exception
    ) {
        return new AiopsException(
                ErrorCode.BAD_REQUEST,
                "Provider query mapping item already exists for serviceName=%s, alertClusterName=%s, providerName=%s"
                        .formatted(
                                normalizeRequired(request.serviceName(), "serviceName"),
                                normalizeRequired(request.alertClusterName(), "alertClusterName"),
                                normalizeProviderName(request.providerName())
                        )
        );
    }
}
