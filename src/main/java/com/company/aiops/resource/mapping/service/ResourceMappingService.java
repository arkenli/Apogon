package com.company.aiops.resource.mapping.service;

import com.company.aiops.common.exception.AiopsException;
import com.company.aiops.common.exception.ErrorCode;
import com.company.aiops.persistence.repository.ResourceMappingRepository;
import com.company.aiops.resource.mapping.model.ResourceMappingGroupResponse;
import com.company.aiops.resource.mapping.model.ResourceMappingItemResponse;
import com.company.aiops.resource.mapping.model.ResourceMappingItemUpsertRequest;
import com.company.aiops.resource.mapping.model.ResourceMappingRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class ResourceMappingService {

    private static final String UPDATED_BY = "resource-mapping-api";
    private static final List<String> ALLOWED_RESOURCE_TYPES = List.of("DATABASE", "DCS", "MQ");

    private final ResourceMappingRepository resourceMappingRepository;
    private final ObjectMapper objectMapper;

    public ResourceMappingService(
            ResourceMappingRepository resourceMappingRepository,
            ObjectMapper objectMapper
    ) {
        this.resourceMappingRepository = resourceMappingRepository;
        this.objectMapper = objectMapper;
    }

    public List<ResourceMappingGroupResponse> list(String serviceName, String clusterName) {
        List<ResourceMappingRecord> rows = resourceMappingRepository.findAll(normalizeFilter(serviceName), normalizeFilter(clusterName));
        Map<String, List<ResourceMappingItemResponse>> groupedItems = new LinkedHashMap<>();
        Map<String, String[]> groupedKeys = new LinkedHashMap<>();
        for (ResourceMappingRecord row : rows) {
            String key = row.serviceName() + "\u0000" + row.clusterName();
            groupedKeys.putIfAbsent(key, new String[]{row.serviceName(), row.clusterName()});
            groupedItems.computeIfAbsent(key, ignored -> new ArrayList<>()).add(toResponse(row));
        }

        List<ResourceMappingGroupResponse> responses = new ArrayList<>(groupedItems.size());
        for (Map.Entry<String, List<ResourceMappingItemResponse>> entry : groupedItems.entrySet()) {
            String[] keyParts = groupedKeys.get(entry.getKey());
            responses.add(new ResourceMappingGroupResponse(
                    keyParts[0],
                    keyParts[1],
                    List.copyOf(entry.getValue())
            ));
        }
        return responses;
    }

    public ResourceMappingItemResponse create(ResourceMappingItemUpsertRequest request) {
        ResourceMappingRecord draft = buildNewRecord(request);
        try {
            return toResponse(resourceMappingRepository.create(draft));
        } catch (DuplicateKeyException exception) {
            throw duplicate(request, exception);
        }
    }

    public ResourceMappingItemResponse update(long id, ResourceMappingItemUpsertRequest request) {
        ResourceMappingRecord existing = resourceMappingRepository.findById(id)
                .orElseThrow(() -> notFound(id));
        ResourceMappingRecord updated = new ResourceMappingRecord(
                existing.id(),
                normalizeRequired(request.serviceName()),
                normalizeRequired(request.clusterName()),
                normalizeResourceType(request.resourceType()),
                normalizeRequired(request.resourceId()),
                normalizeOptional(request.resourceName()),
                true,
                normalizeMetadataJson(request.metadataJson()),
                existing.createdBy(),
                UPDATED_BY,
                existing.createdAt(),
                Instant.now()
        );
        try {
            return toResponse(resourceMappingRepository.update(updated));
        } catch (DuplicateKeyException exception) {
            throw duplicate(request, exception);
        }
    }

    public void delete(long id) {
        resourceMappingRepository.findById(id)
                .orElseThrow(() -> notFound(id));
        resourceMappingRepository.deleteById(id);
    }

    private ResourceMappingRecord buildNewRecord(ResourceMappingItemUpsertRequest request) {
        String metadataJson = normalizeMetadataJson(request.metadataJson());
        return new ResourceMappingRecord(
                null,
                normalizeRequired(request.serviceName()),
                normalizeRequired(request.clusterName()),
                normalizeResourceType(request.resourceType()),
                normalizeRequired(request.resourceId()),
                normalizeOptional(request.resourceName()),
                true,
                metadataJson,
                UPDATED_BY,
                UPDATED_BY,
                null,
                null
        );
    }

    private ResourceMappingItemResponse toResponse(ResourceMappingRecord record) {
        return new ResourceMappingItemResponse(
                record.id(),
                record.resourceType(),
                record.resourceId(),
                record.resourceName(),
                record.metadataJson(),
                record.enabled(),
                record.createdBy(),
                record.updatedBy(),
                record.createdAt(),
                record.updatedAt()
        );
    }

    private String normalizeFilter(String value) {
        String normalized = normalizeOptional(value);
        return normalized == null ? null : normalized;
    }

    private String normalizeRequired(String value) {
        String normalized = normalizeOptional(value);
        if (normalized == null) {
            throw new AiopsException(ErrorCode.BAD_REQUEST, "字段不能为空白");
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

    private String normalizeResourceType(String value) {
        String normalized = normalizeRequired(value).toUpperCase(Locale.ROOT);
        if (!ALLOWED_RESOURCE_TYPES.contains(normalized)) {
            throw new AiopsException(
                    ErrorCode.BAD_REQUEST,
                    "resourceType must be one of DATABASE, DCS, MQ"
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
        return new AiopsException(ErrorCode.NOT_FOUND, "Resource mapping item not found: id=" + id);
    }

    private AiopsException duplicate(ResourceMappingItemUpsertRequest request, DuplicateKeyException exception) {
        return new AiopsException(
                ErrorCode.BAD_REQUEST,
                "Resource mapping item already exists for serviceName=%s, clusterName=%s, resourceType=%s, resourceId=%s"
                        .formatted(
                                normalizeRequired(request.serviceName()),
                                normalizeRequired(request.clusterName()),
                                normalizeResourceType(request.resourceType()),
                                normalizeRequired(request.resourceId())
                        )
        );
    }
}
