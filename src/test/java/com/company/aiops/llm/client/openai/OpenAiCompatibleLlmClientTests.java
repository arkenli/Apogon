package com.company.aiops.llm.client.openai;

import com.company.aiops.common.config.AiopsProperties;
import com.company.aiops.llm.client.LlmCallStatus;
import com.company.aiops.llm.client.LlmClientException;
import com.company.aiops.llm.client.LlmMessage;
import com.company.aiops.llm.client.LlmRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiCompatibleLlmClientTests {

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    @Test
    void throwsUnavailableWhenBaseUrlIsBlank() {
        AiopsProperties properties = propertiesWithLlm("", "sk-test-key", "MiniMax-M2.7");
        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(properties, objectMapper);

        assertThatThrownBy(() -> client.complete(sampleRequest("MiniMax-M2.7", 0.1)))
                .isInstanceOf(LlmClientException.class)
                .satisfies(e -> {
                    LlmClientException ex = (LlmClientException) e;
                    assertThat(ex.status()).isEqualTo(LlmCallStatus.UNAVAILABLE);
                    assertThat(ex.getMessage()).contains("base-url");
                });
    }

    @Test
    void throwsUnavailableWhenApiKeyIsBlank() {
        AiopsProperties properties = propertiesWithLlm("https://api.minimaxi.com/v1", "", "MiniMax-M2.7");
        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(properties, objectMapper);

        assertThatThrownBy(() -> client.complete(sampleRequest("MiniMax-M2.7", 0.1)))
                .isInstanceOf(LlmClientException.class)
                .satisfies(e -> {
                    LlmClientException ex = (LlmClientException) e;
                    assertThat(ex.status()).isEqualTo(LlmCallStatus.UNAVAILABLE);
                    assertThat(ex.getMessage()).contains("api-key");
                });
    }

    @Test
    void throwsUnavailableWhenBothBaseUrlAndApiKeyAreNull() {
        AiopsProperties properties = propertiesWithLlm(null, null, "MiniMax-M2.7");
        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(properties, objectMapper);

        assertThatThrownBy(() -> client.complete(sampleRequest("MiniMax-M2.7", 0.1)))
                .isInstanceOf(LlmClientException.class)
                .satisfies(e -> {
                    LlmClientException ex = (LlmClientException) e;
                    assertThat(ex.status()).isEqualTo(LlmCallStatus.UNAVAILABLE);
                });
    }

    @Test
    void buildsMiniMaxCompatibleRequestBody() {
        AiopsProperties properties = propertiesWithLlm("https://api.minimaxi.com/v1", "sk-test-key", "MiniMax-M2.7");
        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(properties, objectMapper);

        Map<String, Object> body = client.buildRequestBody(sampleRequest("MiniMax-M2.7", 0.1), true);

        assertThat(body).containsEntry("model", "MiniMax-M2.7");
        assertThat(body).containsEntry("max_completion_tokens", 1000);
        assertThat(body).containsEntry("reasoning_split", true);
        assertThat(body).doesNotContainKey("max_tokens");
        assertThat(body).doesNotContainKey("response_format");
        assertThat(body).containsEntry("temperature", 0.1d);
    }

    @Test
    void clampsMiniMaxTemperatureIntoDocumentedRange() {
        AiopsProperties properties = propertiesWithLlm("https://api.minimaxi.com/v1", "sk-test-key", "MiniMax-M2.7");
        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(properties, objectMapper);

        Map<String, Object> lowTemperatureBody = client.buildRequestBody(sampleRequest("MiniMax-M2.7", 0.0), true);
        Map<String, Object> highTemperatureBody = client.buildRequestBody(sampleRequest("MiniMax-M2.7", 1.8), true);

        assertThat(lowTemperatureBody).containsEntry("temperature", 0.01d);
        assertThat(highTemperatureBody).containsEntry("temperature", 1.0d);
    }

    @Test
    void keepsGenericOpenAiCompatibleRequestBodyForNonMiniMaxProviders() {
        AiopsProperties properties = propertiesWithLlm("https://api.deepseek.com", "sk-test-key", "deepseek-chat");
        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(properties, objectMapper);

        Map<String, Object> body = client.buildRequestBody(sampleRequest("deepseek-chat", 0.1), false);

        assertThat(body).containsEntry("max_tokens", 1000);
        assertThat(body).containsEntry("temperature", 0.1d);
        assertThat(body).containsKey("response_format");
        assertThat(body).doesNotContainKey("max_completion_tokens");
        assertThat(body).doesNotContainKey("reasoning_split");
    }

    @Test
    void stripsMiniMaxThinkingContentBeforeValidation() throws Exception {
        AiopsProperties properties = propertiesWithLlm("https://api.minimaxi.com/v1", "sk-test-key", "MiniMax-M2.7");
        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(properties, objectMapper);
        JsonNode choice = objectMapper.readTree("""
                {
                  "message": {
                    "content": "<think>hidden reasoning</think>\\n{\\\"summary\\\":\\\"json body\\\"}"
                  }
                }
                """);

        String content = client.extractResponseContent(choice, true);

        assertThat(content).isEqualTo("{\"summary\":\"json body\"}");
    }

    @Test
    void usesMiniMaxModelNameFromConfiguration() {
        AiopsProperties properties = propertiesWithLlm("https://api.minimaxi.com/v1", "sk-test-key", "MiniMax-M2.7");

        assertThat(properties.llm().model()).isEqualTo("MiniMax-M2.7");
        assertThat(properties.llm().baseUrl()).isEqualTo("https://api.minimaxi.com/v1");
    }

    private static LlmRequest sampleRequest(String model, double temperature) {
        return new LlmRequest(
                model,
                List.of(
                        new LlmMessage("system", "You are a helpful assistant."),
                        new LlmMessage("user", "Say hello in JSON.")
                ),
                temperature,
                1000,
                Duration.ofSeconds(45),
                "json_object"
        );
    }

    private static AiopsProperties propertiesWithLlm(String baseUrl, String apiKey, String model) {
        return new AiopsProperties(
                new AiopsProperties.Analysis(
                        Duration.ofMinutes(5),
                        Duration.ofDays(1),
                        30,
                        30,
                        Duration.ofMinutes(3),
                        10,
                        1,
                        Duration.ofMinutes(10),
                        2000,
                        20,
                        20,
                        50,
                        500,
                        10,
                        10,
                        5,
                        8,
                        2,
                        "ERROR",
                        5
                ),
                new AiopsProperties.Provider(
                        Duration.ofSeconds(10),
                        1,
                        Duration.ofSeconds(1),
                        true,
                        true
                ),
                new AiopsProperties.Llm(
                        true,
                        "openai-compatible",
                        model,
                        0.1,
                        4000,
                        Duration.ofSeconds(45),
                        true,
                        "fallback-rules-v1",
                        baseUrl,
                        apiKey
                ),
                new AiopsProperties.Thresholds(
                        0.80,
                        0.85,
                        0.80,
                        0.80,
                        0.80,
                        0.80,
                        0.85
                )
        );
    }
}
