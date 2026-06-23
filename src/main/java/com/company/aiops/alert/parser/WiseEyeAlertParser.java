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
                extractSingleLineValue(rawText, "Alert ID"),
                AlertCategory.UNKNOWN,
                extractSingleLineValue(rawText, "Status"),
                extractSingleLineValue(rawText, "Severity"),
                firstNonBlank(hostFields.serviceName(), extractByPatterns(rawText,
                        "serviceName='([^']+)'",
                        "服务名称[：:]?\\s*([^,，\\n]+)",
                        "鏈嶅姟鍚嶇О[锛?]?\\s*([^,锛孿\\n]+)")),
                firstNonBlank(hostFields.interfaceName(), extractByPatterns(rawText,
                        "interfaceName='([^']+)'",
                        "接口名称为([^,，\\n]+)",
                        "接口[：:]\\s*([^,，\\n]+)",
                        "鎺ュ彛鍚嶇О涓?([^,锛孿\\n]+)",
                        "鎺ュ彛[锛?]\\s*([^,锛孿\\n]+)")),
                firstNonBlank(hostFields.cluster(), extractByPatterns(rawText,
                        "集群信息[：:]\\s*([^,，\\n]+)",
                        "集群[：:]\\s*([^,，\\n]+)",
                        "闆嗙兢[锛?]\\s*([^,锛孿\\n]+)")),
                extractLastSingleLineValue(rawText, "Site"),
                extractSingleLineValue(rawText, "Tenant"),
                extractSingleLineValue(rawText, "Application"),
                extractByPatterns(rawText,
                        "AlertName:\\s*([^,\\n]+)",
                        "告警名称[：:]\\s*([^\\n\\r]+)",
                        "鍛婅鍚嶇О[锛?]\\s*([^\\n\\r]+)"),
                extractByPatterns(rawText, "MetricName:\\s*([^,\\n]+)"),
                extractSingleLineValue(rawText, "Category"),
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
                    jsonText(hostJson, "servicename", "serviceName", "服务名称", "鏈嶅姟鍚嶇О"),
                    jsonText(hostJson, "interfacename", "interfaceName", "接口", "接口名称", "鎺ュ彛", "鎺ュ彛鍚嶇О"),
                    jsonText(hostJson, "cluster", "clusterName", "集群", "闆嗙兢")
            );
        } catch (Exception exception) {
            throw new AiopsException(ErrorCode.BAD_REQUEST, "Host 字段 JSON 解析失败");
        }
    }

    private AlertTriggerValues parseTriggerValues(String rawText) {
        String failRateText = extractByPatterns(rawText,
                "失败率\\s*[=：:]\\s*([0-9]+(?:\\.[0-9]+)?\\s*%?)",
                "澶辫触鐜.?\\s*=\\s*([0-9]+(?:\\.[0-9]+)?\\s*%?)");
        String logMessage = extractByPatterns(rawText,
                "日志\\s*[=：:]\\s*([^\\n\\r]*?)(?=\\s*[,，]\\s*(?:发生时间|告警发生时间|失败数|请求总数|失败率|平均时延|P99|p99|最大时延)\\s*[=：:]|\\s*(?:\\r?\\n|$))",
                "log\\s*[=：:]\\s*([^\\n\\r]*?)(?=\\s*[,，]\\s*(?:occurTime|failureCount|requestCount|failRate)\\s*[=：:]|\\s*(?:\\r?\\n|$))");

        return new AlertTriggerValues(
                parseDouble(extractByPatterns(rawText,
                        "请求总数\\s*[=：:]\\s*([0-9]+(?:\\.[0-9]+)?)",
                        "璇锋眰鎬绘暟\\s*=\\s*([0-9]+(?:\\.[0-9]+)?)")),
                parseRate(failRateText),
                parseLong(extractByPatterns(rawText,
                        "平均时延\\s*[=：:]\\s*([0-9]+)",
                        "骞冲潎鏃跺欢\\s*=\\s*([0-9]+)")),
                parseLong(extractByPatterns(rawText,
                        "(?:P99|p99|99线)\\s*[=：:]\\s*([0-9]+)",
                        "(?:P99|p99|99绾?)\\s*=\\s*([0-9]+)")),
                parseLong(extractByPatterns(rawText,
                        "(?:最大时延|maxLatency|max)\\s*[=：:]\\s*([0-9]+)",
                        "(?:鏈€澶ф椂寤|maxLatency|max)\\s*=\\s*([0-9]+)")),
                parseDouble(extractByPatterns(rawText,
                        "失败数\\s*[=：:]\\s*([0-9]+(?:\\.[0-9]+)?)",
                        "failureCount\\s*[=：:]\\s*([0-9]+(?:\\.[0-9]+)?)")),
                logMessage == null ? null : "ERROR",
                logMessage,
                normalizeLogQuery(logMessage),
                null,
                extractByPatterns(rawText,
                        "告警信息[：:]\\s*([^\\n\\r]+)",
                        "鍛婅淇℃伅[锛?]\\s*([^\\n\\r]+)")
        );
    }

    private Instant parseMetricOccurTime(String rawText) {
        String detailsTime = extractByPatterns(rawText,
                "(?:发生时间|告警发生时间)\\s*[=：:]\\s*([0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2})",
                "(?:鍙戠敓鏃堕棿|鍛婅鍙戠敓鏃堕棿)\\s*=\\s*([0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2})",
                "occurTime='([^']+)'");
        Instant parsedDetailsTime = parseInstant(detailsTime);
        if (parsedDetailsTime != null) {
            return parsedDetailsTime;
        }

        return parseInstant(extractSingleLineValue(rawText, "Occur Time"));
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
            Matcher matcher = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.DOTALL)
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

    private String normalizeLogQuery(String logMessage) {
        if (logMessage == null) {
            return null;
        }
        String withoutDefault = logMessage.replaceFirst("(?i)^\\s*DEFAULT\\b\\s*", "");
        return blankToNull(withoutDefault.replaceAll("\\s+", " "));
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
