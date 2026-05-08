package com.company.aiops.resource.mapping.controller;

import com.company.aiops.common.api.ApiResponse;
import com.company.aiops.resource.mapping.model.ResourceMappingGroupResponse;
import com.company.aiops.resource.mapping.model.ResourceMappingItemResponse;
import com.company.aiops.resource.mapping.model.ResourceMappingItemUpsertRequest;
import com.company.aiops.resource.mapping.service.ResourceMappingService;
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
@RequestMapping("/api/v1/resource-mappings")
public class ResourceMappingController {

    private final ResourceMappingService resourceMappingService;

    public ResourceMappingController(ResourceMappingService resourceMappingService) {
        this.resourceMappingService = resourceMappingService;
    }

    @GetMapping
    public ApiResponse<List<ResourceMappingGroupResponse>> list(
            @RequestParam(required = false) String serviceName,
            @RequestParam(required = false) String clusterName
    ) {
        return ApiResponse.ok(resourceMappingService.list(serviceName, clusterName));
    }

    @PostMapping("/items")
    public ApiResponse<ResourceMappingItemResponse> create(
            @Valid @RequestBody ResourceMappingItemUpsertRequest request
    ) {
        return ApiResponse.ok(resourceMappingService.create(request));
    }

    @PutMapping("/items/{id}")
    public ApiResponse<ResourceMappingItemResponse> update(
            @PathVariable @Min(1) long id,
            @Valid @RequestBody ResourceMappingItemUpsertRequest request
    ) {
        return ApiResponse.ok(resourceMappingService.update(id, request));
    }

    @DeleteMapping("/items/{id}")
    public ApiResponse<Void> delete(@PathVariable @Min(1) long id) {
        resourceMappingService.delete(id);
        return ApiResponse.ok(null);
    }
}
