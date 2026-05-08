package com.company.aiops.resource.mapping;

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

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ResourceMappingControllerIntegrationTests {

    private static final String SERVICE_NAME = "it-resource-service";
    private static final String CLUSTER_NAME = "cluster-it";

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanResourceMappings() {
        jdbcTemplate.update("DELETE FROM aiops_resource_mapping WHERE service_name = ?", SERVICE_NAME);
    }

    @Test
    void createListUpdateAndDeleteRoundTrip() {
        ResponseEntity<JsonNode> createResponse = exchange(
                HttpMethod.POST,
                "/api/v1/resource-mappings/items",
                """
                        {
                          "serviceName": "it-resource-service",
                          "clusterName": "cluster-it",
                          "resourceType": "DATABASE",
                          "resourceId": "db_it_primary",
                          "resourceName": "it mysql",
                          "metadataJson": "{\\"env\\":\\"test\\",\\"role\\":\\"primary\\"}"
                        }
                        """
        );

        assertThat(createResponse.getStatusCode().is2xxSuccessful()).isTrue();
        long id = createResponse.getBody().path("data").path("id").asLong();
        assertThat(createResponse.getBody().path("data").path("resourceType").asText()).isEqualTo("DATABASE");

        ResponseEntity<JsonNode> listResponse = restTemplate.getForEntity(
                "/api/v1/resource-mappings?serviceName={serviceName}&clusterName={clusterName}",
                JsonNode.class,
                SERVICE_NAME,
                CLUSTER_NAME
        );
        assertThat(listResponse.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(listResponse.getBody().path("data").get(0).path("items").get(0).path("id").asLong()).isEqualTo(id);

        ResponseEntity<JsonNode> updateResponse = exchange(
                HttpMethod.PUT,
                "/api/v1/resource-mappings/items/" + id,
                """
                        {
                          "serviceName": "it-resource-service",
                          "clusterName": "cluster-it",
                          "resourceType": "DCS",
                          "resourceId": "dcs_it_cache",
                          "resourceName": "it redis",
                          "metadataJson": "{\\"env\\":\\"test\\",\\"role\\":\\"cache\\"}"
                        }
                        """
        );
        assertThat(updateResponse.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(updateResponse.getBody().path("data").path("resourceType").asText()).isEqualTo("DCS");
        assertThat(updateResponse.getBody().path("data").path("resourceId").asText()).isEqualTo("dcs_it_cache");

        ResponseEntity<JsonNode> deleteResponse = restTemplate.exchange(
                "/api/v1/resource-mappings/items/{id}",
                HttpMethod.DELETE,
                HttpEntity.EMPTY,
                JsonNode.class,
                id
        );
        assertThat(deleteResponse.getStatusCode().is2xxSuccessful()).isTrue();
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM aiops_resource_mapping WHERE id = ?",
                Integer.class,
                id
        );
        assertThat(count).isZero();
    }

    @Test
    void createRejectsInvalidMetadataJson() {
        ResponseEntity<JsonNode> response = exchange(
                HttpMethod.POST,
                "/api/v1/resource-mappings/items",
                """
                        {
                          "serviceName": "it-resource-service",
                          "clusterName": "cluster-it",
                          "resourceType": "DATABASE",
                          "resourceId": "db_it_primary",
                          "resourceName": "it mysql",
                          "metadataJson": "{invalid-json}"
                        }
                        """
        );

        assertThat(response.getStatusCode().is4xxClientError()).isTrue();
        assertThat(response.getBody().path("code").asText()).isEqualTo("BAD_REQUEST");
    }

    private ResponseEntity<JsonNode> exchange(HttpMethod method, String path, String body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }
}
