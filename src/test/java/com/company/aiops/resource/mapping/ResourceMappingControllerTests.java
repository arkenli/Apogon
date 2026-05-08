package com.company.aiops.resource.mapping;

import com.company.aiops.common.exception.GlobalExceptionHandler;
import com.company.aiops.resource.mapping.controller.ResourceMappingController;
import com.company.aiops.resource.mapping.model.ResourceMappingGroupResponse;
import com.company.aiops.resource.mapping.model.ResourceMappingItemResponse;
import com.company.aiops.resource.mapping.model.ResourceMappingItemUpsertRequest;
import com.company.aiops.resource.mapping.service.ResourceMappingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ResourceMappingController.class)
@Import(GlobalExceptionHandler.class)
class ResourceMappingControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ResourceMappingService resourceMappingService;

    @Test
    void listReturnsGroupedMappings() throws Exception {
        when(resourceMappingService.list("ams_front", "cluster1")).thenReturn(List.of(
                new ResourceMappingGroupResponse(
                        "ams_front",
                        "cluster1",
                        List.of(itemResponse(1L, "DATABASE", "db_ams_activity"))
                )
        ));

        mockMvc.perform(get("/api/v1/resource-mappings")
                        .param("serviceName", "ams_front")
                        .param("clusterName", "cluster1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].serviceName").value("ams_front"))
                .andExpect(jsonPath("$.data[0].items[0].resourceType").value("DATABASE"));

        verify(resourceMappingService).list("ams_front", "cluster1");
    }

    @Test
    void createReturnsCreatedItem() throws Exception {
        when(resourceMappingService.create(eq(request()))).thenReturn(itemResponse(11L, "DCS", "dcs_ams_cache"));

        mockMvc.perform(post("/api/v1/resource-mappings/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "serviceName": "ams_front",
                                  "clusterName": "cluster1",
                                  "resourceType": "DCS",
                                  "resourceId": "dcs_ams_cache",
                                  "resourceName": "ams cache redis",
                                  "metadataJson": "{\\"env\\":\\"local\\"}"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(11))
                .andExpect(jsonPath("$.data.resourceId").value("dcs_ams_cache"));
    }

    @Test
    void updateDelegatesToService() throws Exception {
        when(resourceMappingService.update(eq(5L), eq(request()))).thenReturn(itemResponse(5L, "DCS", "dcs_ams_cache"));

        mockMvc.perform(put("/api/v1/resource-mappings/items/5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "serviceName": "ams_front",
                                  "clusterName": "cluster1",
                                  "resourceType": "DCS",
                                  "resourceId": "dcs_ams_cache",
                                  "resourceName": "ams cache redis",
                                  "metadataJson": "{\\"env\\":\\"local\\"}"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(5));
    }

    @Test
    void deleteDelegatesToService() throws Exception {
        mockMvc.perform(delete("/api/v1/resource-mappings/items/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(resourceMappingService).delete(7L);
    }

    @Test
    void createRejectsBlankServiceName() throws Exception {
        mockMvc.perform(post("/api/v1/resource-mappings/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "serviceName": "",
                                  "clusterName": "cluster1",
                                  "resourceType": "DCS",
                                  "resourceId": "dcs_ams_cache"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    private ResourceMappingItemUpsertRequest request() {
        return new ResourceMappingItemUpsertRequest(
                "ams_front",
                "cluster1",
                "DCS",
                "dcs_ams_cache",
                "ams cache redis",
                "{\"env\":\"local\"}"
        );
    }

    private ResourceMappingItemResponse itemResponse(long id, String resourceType, String resourceId) {
        Instant now = Instant.parse("2026-05-07T13:00:00Z");
        return new ResourceMappingItemResponse(
                id,
                resourceType,
                resourceId,
                "resource-name",
                "{\"env\":\"local\"}",
                true,
                "resource-mapping-api",
                "resource-mapping-api",
                now,
                now
        );
    }
}
