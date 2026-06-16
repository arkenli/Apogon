package com.company.aiops.provider.query.mapping;

import com.company.aiops.provider.ProviderNames;
import com.company.aiops.provider.query.ProviderQueryTarget;
import com.company.aiops.provider.query.ProviderQueryTargetResolver;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProviderQueryMappingControllerIntegrationTests {

    private static final String SERVICE_NAME = "it-provider-query-service";
    private static final String ALERT_CLUSTER_NAME = "cluster-it";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ProviderQueryTargetResolver providerQueryTargetResolver;

    @BeforeEach
    void cleanProviderQueryMappings() {
        jdbcTemplate.update(
                "DELETE FROM aiops_provider_query_mapping WHERE service_name = ?",
                SERVICE_NAME
        );
    }

    @Test
    void createListUpdateAndDeleteRoundTrip() {
        ResponseEntity<JsonNode> createResponse = exchange(
                HttpMethod.POST,
                "/api/v1/provider-query-mappings/items",
                """
                        {
                          "serviceName": "it-provider-query-service",
                          "alertClusterName": "cluster-it",
                          "providerName": "metric.container",
                          "queryServiceName": "it-provider-query-service",
                          "queryClusterName": "cluster-it-front",
                          "metadataJson": "{\\"env\\":\\"test\\",\\"role\\":\\"front\\"}"
                        }
                        """
        );

        assertThat(createResponse.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(createResponse.getBody()).isNotNull();
        long id = createResponse.getBody().path("data").path("id").asLong();
        assertThat(createResponse.getBody().path("data").path("providerName").asText())
                .isEqualTo(ProviderNames.METRIC_CONTAINER);
        assertThat(createResponse.getBody().path("data").path("queryClusterName").asText())
                .isEqualTo("cluster-it-front");

        ResponseEntity<JsonNode> listResponse = restTemplate.getForEntity(
                "/api/v1/provider-query-mappings?serviceName={serviceName}&alertClusterName={alertClusterName}",
                JsonNode.class,
                SERVICE_NAME,
                ALERT_CLUSTER_NAME
        );
        assertThat(listResponse.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(listResponse.getBody()).isNotNull();
        assertThat(listResponse.getBody().path("data").size()).isEqualTo(1);
        assertThat(listResponse.getBody().path("data").get(0).path("id").asLong()).isEqualTo(id);
        assertThat(listResponse.getBody().path("data").get(0).path("providerName").asText())
                .isEqualTo(ProviderNames.METRIC_CONTAINER);

        ResponseEntity<JsonNode> updateResponse = exchange(
                HttpMethod.PUT,
                "/api/v1/provider-query-mappings/items/" + id,
                """
                        {
                          "serviceName": "it-provider-query-service",
                          "alertClusterName": "cluster-it",
                          "providerName": "metric.cse",
                          "queryServiceName": "it-provider-query-service-rpc",
                          "queryClusterName": "cluster-it-rpc",
                          "metadataJson": "{\\"env\\":\\"test\\",\\"role\\":\\"rpc\\"}"
                        }
                        """
        );
        assertThat(updateResponse.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(updateResponse.getBody()).isNotNull();
        assertThat(updateResponse.getBody().path("data").path("id").asLong()).isEqualTo(id);
        assertThat(updateResponse.getBody().path("data").path("providerName").asText())
                .isEqualTo(ProviderNames.METRIC_CSE);
        assertThat(updateResponse.getBody().path("data").path("queryServiceName").asText())
                .isEqualTo("it-provider-query-service-rpc");
        assertThat(updateResponse.getBody().path("data").path("queryClusterName").asText())
                .isEqualTo("cluster-it-rpc");

        ResponseEntity<JsonNode> updatedListResponse = restTemplate.getForEntity(
                "/api/v1/provider-query-mappings?serviceName={serviceName}&alertClusterName={alertClusterName}",
                JsonNode.class,
                SERVICE_NAME,
                ALERT_CLUSTER_NAME
        );
        assertThat(updatedListResponse.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(updatedListResponse.getBody()).isNotNull();
        assertThat(updatedListResponse.getBody().path("data").size()).isEqualTo(1);
        assertThat(updatedListResponse.getBody().path("data").get(0).path("id").asLong()).isEqualTo(id);
        assertThat(updatedListResponse.getBody().path("data").get(0).path("providerName").asText())
                .isEqualTo(ProviderNames.METRIC_CSE);
        assertThat(updatedListResponse.getBody().path("data").get(0).path("queryServiceName").asText())
                .isEqualTo("it-provider-query-service-rpc");
        assertThat(updatedListResponse.getBody().path("data").get(0).path("queryClusterName").asText())
                .isEqualTo("cluster-it-rpc");

        ResponseEntity<JsonNode> deleteResponse = restTemplate.exchange(
                "/api/v1/provider-query-mappings/items/{id}",
                HttpMethod.DELETE,
                HttpEntity.EMPTY,
                JsonNode.class,
                id
        );
        assertThat(deleteResponse.getStatusCode().is2xxSuccessful()).isTrue();
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM aiops_provider_query_mapping WHERE id = ?",
                Integer.class,
                id
        );
        assertThat(count).isZero();
    }

    @Test
    void createRejectsInvalidProviderName() {
        ResponseEntity<JsonNode> response = exchange(
                HttpMethod.POST,
                "/api/v1/provider-query-mappings/items",
                """
                        {
                          "serviceName": "it-provider-query-service",
                          "alertClusterName": "cluster-it",
                          "providerName": "metric.unknown",
                          "queryServiceName": "it-provider-query-service",
                          "queryClusterName": "cluster-it-front",
                          "metadataJson": "{\\"env\\":\\"test\\"}"
                        }
                        """
        );

        assertThat(response.getStatusCode().is4xxClientError()).isTrue();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("code").asText()).isEqualTo("BAD_REQUEST");
    }

    @Test
    void createRejectsInvalidMetadataJson() {
        ResponseEntity<JsonNode> response = exchange(
                HttpMethod.POST,
                "/api/v1/provider-query-mappings/items",
                """
                        {
                          "serviceName": "it-provider-query-service",
                          "alertClusterName": "cluster-it",
                          "providerName": "metric.container",
                          "queryServiceName": "it-provider-query-service",
                          "queryClusterName": "cluster-it-front",
                          "metadataJson": "{invalid-json}"
                        }
                        """
        );

        assertThat(response.getStatusCode().is4xxClientError()).isTrue();
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().path("code").asText()).isEqualTo("BAD_REQUEST");
    }

    @Test
    void createRejectsDuplicateProviderMapping() {
        ResponseEntity<JsonNode> firstCreateResponse = exchange(
                HttpMethod.POST,
                "/api/v1/provider-query-mappings/items",
                """
                        {
                          "serviceName": "it-provider-query-service",
                          "alertClusterName": "cluster-it",
                          "providerName": "metric.container",
                          "queryServiceName": "it-provider-query-service",
                          "queryClusterName": "cluster-it-front",
                          "metadataJson": "{\\"env\\":\\"test\\"}"
                        }
                        """
        );
        assertThat(firstCreateResponse.getStatusCode().is2xxSuccessful()).isTrue();

        ResponseEntity<JsonNode> duplicateResponse = exchange(
                HttpMethod.POST,
                "/api/v1/provider-query-mappings/items",
                """
                        {
                          "serviceName": "it-provider-query-service",
                          "alertClusterName": "cluster-it",
                          "providerName": "metric.container",
                          "queryServiceName": "it-provider-query-service",
                          "queryClusterName": "cluster-it-front",
                          "metadataJson": "{\\"env\\":\\"test\\"}"
                        }
                        """
        );

        assertThat(duplicateResponse.getStatusCode().is4xxClientError()).isTrue();
        assertThat(duplicateResponse.getBody()).isNotNull();
        assertThat(duplicateResponse.getBody().path("code").asText()).isEqualTo("BAD_REQUEST");
    }

    @Test
    void resolverIgnoresDisabledRowsAndFallsBackToAlertCluster() {
        jdbcTemplate.update(
                """
                        INSERT INTO aiops_provider_query_mapping (
                            service_name,
                            alert_cluster_name,
                            provider_name,
                            query_service_name,
                            query_cluster_name,
                            enabled,
                            metadata_json,
                            created_by,
                            updated_by,
                            created_at,
                            updated_at
                        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3))
                        """,
                SERVICE_NAME,
                ALERT_CLUSTER_NAME,
                ProviderNames.METRIC_CONTAINER,
                SERVICE_NAME,
                "cluster-it-disabled",
                false,
                "{\"seed\":\"disabled\"}",
                "integration-test",
                "integration-test"
        );

        ProviderQueryTarget target = providerQueryTargetResolver.resolveAll(
                SERVICE_NAME,
                ALERT_CLUSTER_NAME,
                Set.of(ProviderNames.METRIC_CONTAINER)
        ).targetFor(ProviderNames.METRIC_CONTAINER);

        assertThat(target.providerName()).isEqualTo(ProviderNames.METRIC_CONTAINER);
        assertThat(target.serviceName()).isEqualTo(SERVICE_NAME);
        assertThat(target.clusterName()).isEqualTo(ALERT_CLUSTER_NAME);
        assertThat(target.mapped()).isFalse();
    }

    private ResponseEntity<JsonNode> exchange(HttpMethod method, String path, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }
}
