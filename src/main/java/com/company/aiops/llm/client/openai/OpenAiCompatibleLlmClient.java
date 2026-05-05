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
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * OpenAI-compatible adapter that works with OpenAI, MiniMax, DeepSeek, and other compatible
 * providers.
 *
 * <p>The adapter uses JDK 21 {@link HttpClient} with connect and read timeouts controlled by
 * configuration. MiniMax uses the same chat-completions endpoint shape and Bearer-token auth, but
 * its documented request body differs slightly from the generic OpenAI-compatible dialect.</p>
 */
@Component("openai-compatible")
public class OpenAiCompatibleLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleLlmClient.class);
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final String CHAT_COMPLETIONS_PATH = "/v1/chat/completions";
    private static final double MIN_MINIMAX_TEMPERATURE = 0.01d;
    private static final double MAX_MINIMAX_TEMPERATURE = 1.0d;
    private static final String THINK_TAG_START = "<think>";
    private static final String THINK_TAG_END = "</think>";

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

        boolean miniMaxCompatible = isMiniMaxCompatible(llm.baseUrl());
        Map<String, Object> body = buildRequestBody(request, miniMaxCompatible);

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
            String content = extractResponseContent(choice, miniMaxCompatible);
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

    Map<String, Object> buildRequestBody(LlmRequest request, boolean miniMaxCompatible) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.model());
        body.put("messages", request.messages());
        body.put("temperature", miniMaxCompatible
                ? normalizeMiniMaxTemperature(request.temperature())
                : request.temperature());
        if (miniMaxCompatible) {
            body.put("max_completion_tokens", request.maxOutputTokens());
            body.put("reasoning_split", true);
        } else {
            body.put("max_tokens", request.maxOutputTokens());
            body.put("response_format", Map.of("type", request.responseFormat()));
        }
        return body;
    }

    String extractResponseContent(JsonNode choice, boolean miniMaxCompatible) {
        String content = choice == null ? null : choice.path("message").path("content").asText(null);
        if (content == null || content.isBlank()) {
            return content;
        }
        return miniMaxCompatible ? stripThinkingContent(content) : content;
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

    private boolean isMiniMaxCompatible(String baseUrl) {
        return !isBlank(baseUrl) && baseUrl.toLowerCase(Locale.ROOT).contains("minimax");
    }

    private double normalizeMiniMaxTemperature(double temperature) {
        return Math.max(MIN_MINIMAX_TEMPERATURE, Math.min(MAX_MINIMAX_TEMPERATURE, temperature));
    }

    private String stripThinkingContent(String content) {
        String normalized = content.trim();
        while (normalized.startsWith(THINK_TAG_START)) {
            int endIndex = normalized.indexOf(THINK_TAG_END);
            if (endIndex < 0) {
                return normalized;
            }
            normalized = normalized.substring(endIndex + THINK_TAG_END.length()).trim();
        }
        return normalized;
    }

    private String summarize(RuntimeException exception) {
        String message = exception.getMessage();
        if (message == null || message.isBlank()) {
            return exception.getClass().getSimpleName();
        }
        return message.length() > 500 ? message.substring(0, 500) : message;
    }
}
