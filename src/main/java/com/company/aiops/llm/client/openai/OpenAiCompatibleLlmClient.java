package com.company.aiops.llm.client.openai;

import com.company.aiops.common.config.AiopsProperties;
import com.company.aiops.llm.client.LlmCallStatus;
import com.company.aiops.llm.client.LlmClient;
import com.company.aiops.llm.client.LlmClientException;
import com.company.aiops.llm.client.LlmRequest;
import com.company.aiops.llm.client.LlmResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

/**
 * OpenAI-compatible adapter that works with OpenAI, DeepSeek, and other compatible providers.
 *
 * <p>The adapter uses JDK 21 {@link HttpClient} with connect and read timeouts controlled by
 * configuration. DeepSeek uses the same {@code /v1/chat/completions} endpoint and Bearer-token
 * auth as OpenAI.</p>
 */
@Component("openai-compatible")
public class OpenAiCompatibleLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleLlmClient.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final String CHAT_COMPLETIONS_PATH = "/v1/chat/completions";

    private final AiopsProperties aiopsProperties;
    private final ObjectMapper objectMapper;

    public OpenAiCompatibleLlmClient(AiopsProperties aiopsProperties, ObjectMapper objectMapper) {
        this.aiopsProperties = aiopsProperties;
        this.objectMapper = objectMapper;
    }

    @Override
    public LlmResponse complete(LlmRequest request) {
        AiopsProperties.Llm llm = aiopsProperties.llm();
        if (isBlank(llm.baseUrl()) || isBlank(llm.apiKey())) {
            throw new LlmClientException(
                    LlmCallStatus.UNAVAILABLE,
                    "openai-compatible client requires aiops.llm.base-url and aiops.llm.api-key"
            );
        }

        long startNanos = System.nanoTime();

        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(request.timeout());

        RestClient restClient = RestClient.builder()
                .baseUrl(llm.baseUrl())
                .requestFactory(requestFactory)
                .build();

        Map<String, Object> body = Map.of(
                "model", request.model(),
                "messages", request.messages(),
                "temperature", request.temperature(),
                "max_tokens", request.maxOutputTokens(),
                "response_format", Map.of("type", request.responseFormat())
        );

        try {
            JsonNode response = restClient.post()
                    .uri(CHAT_COMPLETIONS_PATH)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + llm.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null) {
                throw new LlmClientException(LlmCallStatus.FAILED, "empty response body");
            }

            JsonNode choice = response.path("choices").isArray() && !response.path("choices").isEmpty()
                    ? response.path("choices").get(0)
                    : null;
            String content = choice == null ? null : choice.path("message").path("content").asText(null);
            if (content == null || content.isBlank()) {
                throw new LlmClientException(LlmCallStatus.FAILED, "no message content in response");
            }

            int inputTokens = response.path("usage").path("prompt_tokens").asInt(0);
            int outputTokens = response.path("usage").path("completion_tokens").asInt(0);
            String responseModel = response.path("model").asText(request.model());
            String finishReason = choice == null ? "unknown" : choice.path("finish_reason").asText("unknown");
            Duration latency = Duration.ofNanos(System.nanoTime() - startNanos);

            log.info("LLM call completed model={} inputTokens={} outputTokens={} latency={} finishReason={}",
                    responseModel, inputTokens, outputTokens, latency, finishReason);

            return new LlmResponse(content, responseModel, inputTokens, outputTokens, latency, finishReason);
        } catch (ResourceAccessException exception) {
            throw new LlmClientException(
                    LlmCallStatus.TIMEOUT,
                    "llm request timed out after %s".formatted(request.timeout()),
                    exception
            );
        } catch (RestClientResponseException exception) {
            String errorDetail = extractApiErrorMessage(exception);
            log.warn("LLM endpoint returned HTTP {}: {}", exception.getStatusCode().value(), errorDetail);
            throw new LlmClientException(
                    LlmCallStatus.UNAVAILABLE,
                    "llm endpoint returned HTTP %d%s".formatted(
                            exception.getStatusCode().value(),
                            errorDetail.isEmpty() ? "" : ": " + errorDetail
                    ),
                    exception
            );
        } catch (LlmClientException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new LlmClientException(
                    LlmCallStatus.FAILED,
                    "llm request failed: " + summarize(exception),
                    exception
            );
        }
    }

    /**
     * Attempts to extract a human-readable error message from the API error response body.
     */
    private String extractApiErrorMessage(RestClientResponseException exception) {
        try {
            byte[] body = exception.getResponseBodyAsByteArray();
            if (body == null || body.length == 0) {
                return "";
            }
            JsonNode errorNode = objectMapper.readTree(body);
            JsonNode error = errorNode.path("error");
            if (!error.isMissingNode()) {
                String message = error.path("message").asText(null);
                if (message != null && !message.isBlank()) {
                    return message;
                }
            }
            return "";
        } catch (IOException e) {
            return "";
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String summarize(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
