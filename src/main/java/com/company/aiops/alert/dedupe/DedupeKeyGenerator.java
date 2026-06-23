package com.company.aiops.alert.dedupe;

import com.company.aiops.alert.model.AlertCategory;
import com.company.aiops.alert.model.AlertEvent;
import com.company.aiops.alert.model.AlertTriggerValues;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Locale;
import java.util.stream.Collectors;

@Component
public class DedupeKeyGenerator {

    public String generate(AlertEvent alert) {
        if (alert.category() == AlertCategory.SERVICE_ERROR_LOG) {
            return String.join("|",
                    alert.category().name(),
                    normalize(alert.serviceName()),
                    normalize(alert.cluster()),
                    logSignature(alert.triggerValues())
            );
        }

        return String.join("|",
                alert.category().name(),
                normalize(alert.serviceName()),
                normalize(alert.interfaceName()),
                normalize(alert.cluster())
        );
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private String logSignature(AlertTriggerValues triggerValues) {
        if (triggerValues == null) {
            return "";
        }
        String source = firstNonBlank(
                triggerValues.normalizedLogQuery(),
                firstNonBlank(triggerValues.logMessage(), triggerValues.rawMetricValueText())
        );
        if (source == null) {
            return "";
        }

        String[] tokens = source.toLowerCase(Locale.ROOT).trim().split("\\s+");
        return Arrays.stream(filterDynamicTokens(tokens))
                .collect(Collectors.joining(" "));
    }

    private String[] filterDynamicTokens(String[] tokens) {
        java.util.List<String> stableTokens = new java.util.ArrayList<>();
        boolean skipNext = false;
        for (String token : tokens) {
            if (skipNext) {
                skipNext = false;
                continue;
            }
            String comparable = stripOuterPunctuation(token);
            if (comparable.isBlank() || isDynamicValue(comparable)) {
                continue;
            }
            if (isInlineDynamicField(comparable)) {
                continue;
            }
            if (isSeparatedDynamicField(comparable)) {
                skipNext = true;
                continue;
            }
            stableTokens.add(comparable);
        }
        return stableTokens.toArray(String[]::new);
    }

    private boolean isInlineDynamicField(String token) {
        String[] parts = token.split("[:=]", 2);
        return parts.length == 2 && isDynamicFieldName(parts[0]);
    }

    private boolean isSeparatedDynamicField(String token) {
        return normalizedFieldName(token).matches("(?:traceid|spanid|userid|requestid)");
    }

    private boolean isDynamicFieldName(String token) {
        return normalizedFieldName(token).matches("(?:trace|span|uid|traceid|spanid|userid|requestid)");
    }

    private String normalizedFieldName(String token) {
        return token.replace("_", "").replace("-", "");
    }

    private boolean isDynamicValue(String comparable) {
        return comparable.matches("\\d+")
                || comparable.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    private String stripOuterPunctuation(String token) {
        String comparable = token.replaceAll("^[\\p{Punct}]+|[\\p{Punct}]+$", "");
        return comparable;
    }

    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }
}
