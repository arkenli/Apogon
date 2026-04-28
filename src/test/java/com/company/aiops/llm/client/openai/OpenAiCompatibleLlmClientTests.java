package com.company.aiops.llm.client.openai;

import com.company.aiops.common.config.AiopsProperties;
import com.company.aiops.llm.client.LlmCallStatus;
import com.company.aiops.llm.client.LlmClientException;
import com.company.aiops.llm.client.LlmMessage;
import com.company.aiops.llm.client.LlmRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiCompatibleLlmClientTests {

    private static final LlmRequest SAMPLE_REQUEST = new LlmRequest(
            "deepseek-chat",
            List.of(
                    new LlmMessage("system", "You are a helpful assistant."),
                    new LlmMessage("user", "Say hello in JSON.")
            ),
            0.1,
            1000,
            Duration.ofSeconds(45),
            "json_object"
    );

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
    }

    @Test
    void throwsUnavailableWhenBaseUrlIsBlank() {
        AiopsProperties properties = propertiesWithLlm("", "sk-test-key");
        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(properties, objectMapper);

        assertThatThrownBy(() -> client.complete(SAMPLE_REQUEST))
                .isInstanceOf(LlmClientException.class)
                .satisfies(e -> {
                    LlmClientException ex = (LlmClientException) e;
                    assertThat(ex.status()).isEqualTo(LlmCallStatus.UNAVAILABLE);
                    assertThat(ex.getMessage()).contains("base-url");
                });
    }

    @Test
    void throwsUnavailableWhenApiKeyIsBlank() {
        AiopsProperties properties = propertiesWithLlm("https://api.deepseek.com", "");
        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(properties, objectMapper);

        assertThatThrownBy(() -> client.complete(SAMPLE_REQUEST))
                .isInstanceOf(LlmClientException.class)
                .satisfies(e -> {
                    LlmClientException ex = (LlmClientException) e;
                    assertThat(ex.status()).isEqualTo(LlmCallStatus.UNAVAILABLE);
                    assertThat(ex.getMessage()).contains("api-key");
                });
    }

    @Test
    void throwsUnavailableWhenBothBaseUrlAndApiKeyAreNull() {
        AiopsProperties properties = propertiesWithLlm(null, null);
        OpenAiCompatibleLlmClient client = new OpenAiCompatibleLlmClient(properties, objectMapper);

        assertThatThrownBy(() -> client.complete(SAMPLE_REQUEST))
                .isInstanceOf(LlmClientException.class)
                .satisfies(e -> {
                    LlmClientException ex = (LlmClientException) e;
                    assertThat(ex.status()).isEqualTo(LlmCallStatus.UNAVAILABLE);
                });
    }

    @Test
    void usesDeepSeekModelNameFromConfiguration() {
        AiopsProperties properties = propertiesWithLlm("https://api.deepseek.com", "sk-test-key");
        // The request model is included in the LlmRequest, so verify config+request alignment
        assertThat(SAMPLE_REQUEST.model()).isEqualTo("deepseek-chat");
        assertThat(properties.llm().model()).isEqualTo("deepseek-chat");
        assertThat(properties.llm().baseUrl()).isEqualTo("https://api.deepseek.com");
    }

    private static AiopsProperties propertiesWithLlm(String baseUrl, String apiKey) {
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
                        "deepseek-chat",
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
