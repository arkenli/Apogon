package com.company.aiops.alert.parser;

import com.company.aiops.alert.model.AlertCategory;
import com.company.aiops.alert.model.AlertEvent;
import com.company.aiops.alert.model.AlertTriggerValues;
import com.company.aiops.common.exception.AiopsException;
import com.company.aiops.common.exception.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class WiseEyeAlertParser implements AlertParser {

    private static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter OCCUR_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssXXX");

    private final ObjectMapper objectMapper;

    public WiseEyeAlertParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public AlertEvent parse(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            throw new AiopsException(ErrorCode.BAD_REQUEST, "告警原文不能为空");
        }

        HostFields hostFields = parseHostFields(rawText);
        AlertTriggerValues triggerValues = parseTriggerValues(rawText);
        Instant metricOccurTime = parseMetricOccurTime(rawText);

        return new AlertEvent(
                extractAlertId(rawText),
                AlertCategory.UNKNOWN,
                extractSingleLineValue(rawText, "Status"),
                extractSingleLineValue(rawText, "Severity"),
                firstNonBlank(hostFields.serviceName(), extractByPatterns(rawText,
                        "serviceName='([^']+)'",
                        "服务名称[：:]?\\s*(.*?)(?=\\s*[,，]|\\s*\\d+、|\\r?\\n|$)")),
                firstNonBlank(hostFields.interfaceName(), extractByPatterns(rawText,
                        "interfaceName='([^']+)'",
                        "接口名称为(.*?)(?=\\s*[,，]|\\s*\\d+、|\\r?\\n|$)",
                        "接口[：:]\\s*(.*?)(?=\\s*[,，]|\\s*\\d+、|\\r?\\n|$)")),
                firstNonBlank(hostFields.cluster(), extractByPatterns(rawText,
                        "集群信息[：:]\\s*(.*?)(?=\\s*[,，]|\\s*\\d+、|\\r?\\n|$)",
                        "集群[：:]\\s*(.*?)(?=\\s*[,，]|\\s*\\d+、|\\r?\\n|$)")),
                firstNonBlank(extractLastSingleLineValue(rawText, "Site"), extractMetadataValue(rawText, "Site")),
                extractSingleLineValue(rawText, "Tenant"),
                extractSingleLineValue(rawText, "Application"),
                firstNonBlank(
                        extractMetadataValue(rawText, "AlertName"),
                        extractByPatterns(rawText, "告警名称[：:]\\s*(.*?)(?=\\s*\\d+、|\\s*(?:AlertName|Site|MetricName|Category)\\s*:|$)")
                ),
                extractMetadataValue(rawText, "MetricName"),
                extractRawCategory(rawText),
                rawText,
                metricOccurTime,
                Instant.now(),
                triggerValues
        );
    }

    private HostFields parseHostFields(String rawText) {
        String hostLine = extractSingleLineValue(rawText, "Host");
        if (hostLine == null) {
            return HostFields.empty();
        }

        int jsonStart = hostLine.indexOf('{');
        int jsonEnd = hostLine.lastIndexOf('}');
        if (jsonStart < 0 || jsonEnd <= jsonStart) {
            return HostFields.empty();
        }

        try {
            JsonNode hostJson = objectMapper.readTree(hostLine.substring(jsonStart, jsonEnd + 1));
            return new HostFields(
                    jsonText(hostJson, "servicename", "serviceName", "服务名称"),
                    jsonText(hostJson, "interfacename", "interfaceName", "接口", "接口名称"),
                    jsonText(hostJson, "cluster", "clusterName", "集群")
            );
        } catch (Exception exception) {
            throw new AiopsException(ErrorCode.BAD_REQUEST, "Host 字段 JSON 解析失败");
        }
    }

    private AlertTriggerValues parseTriggerValues(String rawText) {
        String failRateText = extractByPatterns(rawText,
                "失败率\\s*[=：:]\\s*([0-9]+(?:\\.[0-9]+)?\\s*%?)");
        String logMessage = normalizeLogMessage(extractByPatterns(rawText,
                "日志\\s*[=：:]\\s*(.*?)(?=\\s*(?:[,，]\\s*(?:发生时间|告警发生时间|失败数|请求总数|失败率|平均时延|P99|p99|最大时延)\\s*[=：:]|\\d+、|AlertName\\s*:|Site\\s*:|MetricName\\s*:|Category\\s*:|\\|\\s*(?:alert_id|Source_ID)\\s*:|\\r?\\n|$))",
                "log\\s*[=：:]\\s*(.*?)(?=\\s*(?:[,，]\\s*(?:occurTime|failureCount|requestCount|failRate)\\s*[=：:]|\\d+、|AlertName\\s*:|Site\\s*:|MetricName\\s*:|Category\\s*:|\\|\\s*(?:alert_id|Source_ID)\\s*:|\\r?\\n|$))"));

        return new AlertTriggerValues(
                parseDouble(extractByPatterns(rawText,
                        "请求总数\\s*[=：:]\\s*([0-9]+(?:\\.[0-9]+)?)")),
                parseRate(failRateText),
                parseLong(extractByPatterns(rawText,
                        "平均时延\\s*[=：:]\\s*([0-9]+)")),
                parseLong(extractByPatterns(rawText,
                        "(?:P99|p99|99线)\\s*[=：:]\\s*([0-9]+)")),
                parseLong(extractByPatterns(rawText,
                        "(?:最大时延|maxLatency|max)\\s*[=：:]\\s*([0-9]+)")),
                parseDouble(extractByPatterns(rawText,
                        "失败数\\s*[=：:]\\s*([0-9]+(?:\\.[0-9]+)?)",
                        "failureCount\\s*[=：:]\\s*([0-9]+(?:\\.[0-9]+)?)")),
                logMessage == null ? null : "ERROR",
                logMessage,
                normalizeLogQuery(logMessage),
                null,
                extractAlertInfo(rawText)
        );
    }

    private Instant parseMetricOccurTime(String rawText) {
        String detailsTime = extractByPatterns(rawText,
                "(?:发生时间|告警发生时间)\\s*[=：:]\\s*([0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2})",
                "occurTime='([^']+)'");
        Instant parsedDetailsTime = parseInstant(detailsTime);
        if (parsedDetailsTime != null) {
            return parsedDetailsTime;
        }

        return parseInstant(extractSingleLineValue(rawText, "Occur Time"));
    }

    private String extractAlertId(String rawText) {
        return firstNonBlank(
                extractSingleLineValue(rawText, "Alert ID"),
                extractByPatterns(rawText, "(?:^|[|,\\s])alert_id\\s*[:=]\\s*([^|,\\s]+)")
        );
    }

    private String extractRawCategory(String rawText) {
        return metadataToken(firstNonBlank(
                extractSingleLineValue(rawText, "Category"),
                extractMetadataValue(rawText, "Category")
        ));
    }

    private String extractMetadataValue(String rawText, String label) {
        return extractByPatterns(rawText, "(?:^|[|,\\s])" + Pattern.quote(label) + "\\s*:\\s*([^,|\\n\\r]+)");
    }

    private String extractAlertInfo(String rawText) {
        return extractByPatterns(rawText,
                "告警信息[：:]\\s*(.*?)(?=\\s*\\d+、|\\s*(?:AlertName|Site|MetricName|Category)\\s*:|\\s*\\|\\s*(?:alert_id|Source_ID)\\s*:|\\r?\\n|$)");
    }

    private String extractSingleLineValue(String rawText, String label) {
        Matcher matcher = Pattern.compile("(?m)^" + Pattern.quote(label) + "\\s*:\\s*(.+?)\\s*$")
                .matcher(rawText);
        return matcher.find() ? blankToNull(matcher.group(1)) : null;
    }

    private String extractLastSingleLineValue(String rawText, String label) {
        Matcher matcher = Pattern.compile("(?m)^" + Pattern.quote(label) + "\\s*:\\s*(.+?)\\s*$")
                .matcher(rawText);
        String value = null;
        while (matcher.find()) {
            value = blankToNull(matcher.group(1));
        }
        return value;
    }

    private String extractByPatterns(String rawText, String... patterns) {
        for (String pattern : patterns) {
            Matcher matcher = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
                    .matcher(rawText);
            if (matcher.find()) {
                return blankToNull(matcher.group(1));
            }
        }
        return null;
    }

    private String jsonText(JsonNode jsonNode, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode value = jsonNode.get(fieldName);
            if (value != null && !value.isNull()) {
                return blankToNull(value.asText());
            }
        }
        return null;
    }

    private Instant parseInstant(String value) {
        if (value == null) {
            return null;
        }
        try {
            if (value.contains("T") && !value.endsWith("Z") && !value.matches(".*[+-][0-9]{2}:[0-9]{2}$")) {
                return LocalDateTime.parse(value).atZone(DEFAULT_ZONE).toInstant();
            }
            if (value.contains(" ")) {
                return OffsetDateTime.parse(value, OCCUR_TIME_FORMATTER).toInstant();
            }
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private Double parseDouble(String value) {
        return value == null ? null : Double.valueOf(value);
    }

    private Long parseLong(String value) {
        return value == null ? null : Long.valueOf(value);
    }

    private Double parseRate(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        boolean percent = normalized.endsWith("%");
        double parsed = Double.parseDouble(normalized.replace("%", "").trim());
        if (percent || parsed > 1.0) {
            return parsed / 100.0;
        }
        return parsed;
    }

    private String normalizeLogMessage(String logMessage) {
        if (logMessage == null) {
            return null;
        }
        String normalized = logMessage.replaceAll("\\s+", " ");
        return blankToNull(normalized.replaceFirst("(?i)^\\s*DEFAULT\\b\\s*", ""));
    }

    private String normalizeLogQuery(String logMessage) {
        if (logMessage == null) {
            return null;
        }
        return normalizeLogMessage(logMessage);
    }

    private String metadataToken(String value) {
        if (value == null) {
            return null;
        }
        int pipeIndex = value.indexOf('|');
        String token = pipeIndex >= 0 ? value.substring(0, pipeIndex) : value;
        int commaIndex = token.indexOf(',');
        token = commaIndex >= 0 ? token.substring(0, commaIndex) : token;
        return blankToNull(token);
    }

    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() || "null".equalsIgnoreCase(trimmed) ? null : trimmed;
    }

    private record HostFields(String serviceName, String interfaceName, String cluster) {
        static HostFields empty() {
            return new HostFields(null, null, null);
        }
    }
}
