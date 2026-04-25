package com.company.aiops.llm.client.openai;

import com.company.aiops.common.config.AiopsProperties;
import com.company.aiops.llm.client.LlmCallStatus;
import com.company.aiops.llm.client.LlmClient;
import com.company.aiops.llm.client.LlmClientException;
import com.company.aiops.llm.client.LlmRequest;
import com.company.aiops.llm.client.LlmResponse;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Minimal OpenAI-compatible adapter. It is intentionally not the default runtime provider because
 * stage-8 delivery only wires the interface plus mock-backed local behavior.
 */
@Component("openai-compatible")
public class OpenAiCompatibleLlmClient implements LlmClient {

    private final AiopsProperties aiopsProperties;
    private final RestClient.Builder restClientBuilder;

    public OpenAiCompatibleLlmClient(AiopsProperties aiopsProperties, RestClient.Builder restClientBuilder) {
        this.aiopsProperties = aiopsProperties;
        this.restClientBuilder = restClientBuilder;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        AiopsProperties.Llm llm = aiopsProperties.llm();
        if (llm.baseUrl() == null || llm.baseUrl().isBlank() || llm.apiKey() == null || llm.apiKey().isBlank()) {
            throw new LlmClientException(
                    LlmCallStatus.UNAVAILABLE,
                    "openai-compatible client requires aiops.llm.base-url and aiops.llm.api-key"
            );
        }

        long startNanos = System.nanoTime();
        RestClient restClient = restClientBuilder.baseUrl(llm.baseUrl()).build();
        Map<String, Object> body = Map.of(
                "model", request.model(),
                "messages", request.messages(),
                "temperature", request.temperature(),
                "max_tokens", request.maxOutputTokens(),
                "response_format", Map.of("type", request.responseFormat())
        );

        try {
            JsonNode response = restClient.post()
                    .uri("/v1/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + llm.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null) {
                throw new LlmClientException(LlmCallStatus.FAILED, "openai-compatible client returned an empty response body");
            }

            JsonNode choice = response.path("choices").isArray() && !response.path("choices").isEmpty()
                    ? response.path("choices").get(0)
                    : null;
            String content = choice == null ? null : choice.path("message").path("content").asText(null);
            if (content == null || content.isBlank()) {
                throw new LlmClientException(LlmCallStatus.FAILED, "openai-compatible client did not return message content");
            }
            int inputTokens = response.path("usage").path("prompt_tokens").asInt(0);
            int outputTokens = response.path("usage").path("completion_tokens").asInt(0);
            String responseModel = response.path("model").asText(request.model());
            String finishReason = choice == null ? "unknown" : choice.path("finish_reason").asText("unknown");
            return new LlmResponse(
                    content,
                    responseModel,
                    inputTokens,
                    outputTokens,
                    Duration.ofNanos(System.nanoTime() - startNanos),
                    finishReason
            );
        } catch (ResourceAccessException exception) {
            throw new LlmClientException(
                    LlmCallStatus.TIMEOUT,
                    "openai-compatible llm request timed out or the endpoint was unreachable",
                    exception
            );
        } catch (RestClientResponseException exception) {
            throw new LlmClientException(
                    LlmCallStatus.UNAVAILABLE,
                    "openai-compatible llm endpoint returned HTTP %d".formatted(exception.getStatusCode().value()),
                    exception
            );
        } catch (LlmClientException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new LlmClientException(
                    LlmCallStatus.FAILED,
                    "openai-compatible llm request failed: " + summarize(exception),
                    exception
            );
        }
    }

    private String summarize(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
