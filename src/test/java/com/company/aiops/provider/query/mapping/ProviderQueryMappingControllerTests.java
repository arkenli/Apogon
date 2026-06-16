package com.company.aiops.provider.query.mapping;

import com.company.aiops.common.exception.GlobalExceptionHandler;
import com.company.aiops.provider.query.mapping.controller.ProviderQueryMappingController;
import com.company.aiops.provider.query.mapping.model.ProviderQueryMappingItemResponse;
import com.company.aiops.provider.query.mapping.model.ProviderQueryMappingItemUpsertRequest;
import com.company.aiops.provider.query.mapping.service.ProviderQueryMappingService;
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

@WebMvcTest(controllers = ProviderQueryMappingController.class)
@Import(GlobalExceptionHandler.class)
class ProviderQueryMappingControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ProviderQueryMappingService providerQueryMappingService;

    @Test
    void listReturnsMappingItems() throws Exception {
        when(providerQueryMappingService.list("ams_front", "cluster1", "baseline")).thenReturn(List.of(
                itemResponse(1L)
        ));

        mockMvc.perform(get("/api/v1/provider-query-mappings")
                        .param("serviceName", "ams_front")
                        .param("alertClusterName", "cluster1")
                        .param("providerName", "baseline"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].serviceName").value("ams_front"))
                .andExpect(jsonPath("$.data[0].providerName").value("baseline"))
                .andExpect(jsonPath("$.data[0].queryServiceName").value("query_ams_front"));

        verify(providerQueryMappingService).list("ams_front", "cluster1", "baseline");
    }

    @Test
    void createReturnsCreatedItem() throws Exception {
        when(providerQueryMappingService.create(eq(request()))).thenReturn(itemResponse(11L));

        mockMvc.perform(post("/api/v1/provider-query-mappings/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "serviceName": "ams_front",
                                  "alertClusterName": "cluster1",
                                  "providerName": "baseline",
                                  "queryServiceName": "query_ams_front",
                                  "queryClusterName": "query_cluster_1",
                                  "metadataJson": "{\\"env\\":\\"local\\"}"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(11))
                .andExpect(jsonPath("$.data.providerName").value("baseline"));
    }

    @Test
    void updateDelegatesToService() throws Exception {
        when(providerQueryMappingService.update(eq(5L), eq(request()))).thenReturn(itemResponse(5L));

        mockMvc.perform(put("/api/v1/provider-query-mappings/items/5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "serviceName": "ams_front",
                                  "alertClusterName": "cluster1",
                                  "providerName": "baseline",
                                  "queryServiceName": "query_ams_front",
                                  "queryClusterName": "query_cluster_1",
                                  "metadataJson": "{\\"env\\":\\"local\\"}"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(5));
    }

    @Test
    void deleteDelegatesToService() throws Exception {
        mockMvc.perform(delete("/api/v1/provider-query-mappings/items/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(providerQueryMappingService).delete(7L);
    }

    @Test
    void createRejectsBlankServiceName() throws Exception {
        mockMvc.perform(post("/api/v1/provider-query-mappings/items")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "serviceName": "",
                                  "alertClusterName": "cluster1",
                                  "providerName": "baseline",
                                  "queryServiceName": "query_ams_front",
                                  "queryClusterName": "query_cluster_1"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    private ProviderQueryMappingItemUpsertRequest request() {
        return new ProviderQueryMappingItemUpsertRequest(
                "ams_front",
                "cluster1",
                "baseline",
                "query_ams_front",
                "query_cluster_1",
                "{\"env\":\"local\"}"
        );
    }

    private ProviderQueryMappingItemResponse itemResponse(long id) {
        Instant now = Instant.parse("2026-05-07T13:00:00Z");
        return new ProviderQueryMappingItemResponse(
                id,
                "ams_front",
                "cluster1",
                "baseline",
                "query_ams_front",
                "query_cluster_1",
                "{\"env\":\"local\"}",
                true,
                "provider-query-mapping-api",
                "provider-query-mapping-api",
                now,
                now
        );
    }
}
