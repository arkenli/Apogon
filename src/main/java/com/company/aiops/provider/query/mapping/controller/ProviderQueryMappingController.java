package com.company.aiops.provider.query.mapping.controller;

import com.company.aiops.common.api.ApiResponse;
import com.company.aiops.provider.query.mapping.model.ProviderQueryMappingItemResponse;
import com.company.aiops.provider.query.mapping.model.ProviderQueryMappingItemUpsertRequest;
import com.company.aiops.provider.query.mapping.service.ProviderQueryMappingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/v1/provider-query-mappings")
public class ProviderQueryMappingController {

    private final ProviderQueryMappingService providerQueryMappingService;

    public ProviderQueryMappingController(ProviderQueryMappingService providerQueryMappingService) {
        this.providerQueryMappingService = providerQueryMappingService;
    }

    @GetMapping
    public ApiResponse<List<ProviderQueryMappingItemResponse>> list(
            @RequestParam(required = false) String serviceName,
            @RequestParam(required = false) String alertClusterName,
            @RequestParam(required = false) String providerName
    ) {
        return ApiResponse.ok(providerQueryMappingService.list(serviceName, alertClusterName, providerName));
    }

    @PostMapping("/items")
    public ApiResponse<ProviderQueryMappingItemResponse> create(
            @Valid @RequestBody ProviderQueryMappingItemUpsertRequest request
    ) {
        return ApiResponse.ok(providerQueryMappingService.create(request));
    }

    @PutMapping("/items/{id}")
    public ApiResponse<ProviderQueryMappingItemResponse> update(
            @PathVariable @Min(1) long id,
            @Valid @RequestBody ProviderQueryMappingItemUpsertRequest request
    ) {
        return ApiResponse.ok(providerQueryMappingService.update(id, request));
    }

    @DeleteMapping("/items/{id}")
    public ApiResponse<Void> delete(@PathVariable @Min(1) long id) {
        providerQueryMappingService.delete(id);
        return ApiResponse.ok(null);
    }
}
