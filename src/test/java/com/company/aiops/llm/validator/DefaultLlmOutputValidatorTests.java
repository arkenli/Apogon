package com.company.aiops.llm.validator;

import com.company.aiops.alert.model.AlertCategory;
import com.company.aiops.analysis.bundle.CompressedEvidenceBundle;
import com.company.aiops.analysis.result.EvidenceReference;
import com.company.aiops.llm.client.LlmCallStatus;
import com.company.aiops.llm.schema.LlmAnalysisOutput;
import com.company.aiops.provider.model.ContainerMetrics;
import com.company.aiops.provider.model.CseMetrics;
import com.company.aiops.provider.model.DatabaseMetrics;
import com.company.aiops.provider.model.DcsMetrics;
import com.company.aiops.provider.model.InterfaceBaseline;
import com.company.aiops.provider.model.MqMetrics;
import com.company.aiops.provider.model.ResourceMapping;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DefaultLlmOutputValidatorTests {

    private final DefaultLlmOutputValidator validator = new DefaultLlmOutputValidator(new ObjectMapper().findAndRegisterModules());

    @Test
    void validateMapsUnknownCauseTypeToUnknownWhenJsonIsOtherwiseValid() {
        LlmAnalysisOutput output = validator.validate("""
                {
                  "summary": "valid summary",
                  "impact": {
                    "level": "LOW",
                    "description": "low impact"
                  },
                  "rootCauses": [
                    {
                      "causeType": "NOT_IN_SCHEMA",
                      "description": "unknown cause from model",
                      "confidence": 0.66,
                      "evidenceIds": ["alert.request.count"],
                      "reasoning": "model reasoning"
                    }
                  ],
                  "recommendations": [
                    {
                      "priority": "P1",
                      "actionType": "CHECK_LOG",
                      "description": "check logs",
                      "target": "demo.Interface.query"
                    }
                  ],
                  "overallConfidence": 0.66,
                  "insufficientEvidence": []
                }
                """, compressedEvidence());

        assertThat(output.rootCauses()).singleElement().satisfies(rootCause -> {
            assertThat(rootCause.causeType()).isEqualTo("UNKNOWN");
            assertThat(rootCause.evidenceIds()).containsExactly("alert.request.count");
        });
    }

    @Test
    void validateRejectsUnknownEvidenceIds() {
        assertThatThrownBy(() -> validator.validate("""
                {
                  "summary": "invalid summary",
                  "impact": {
                    "level": "LOW",
                    "description": "low impact"
                  },
                  "rootCauses": [
                    {
                      "causeType": "BUSINESS_ERROR_CODE_NOT_MASKED",
                      "description": "invalid evidence cause",
                      "confidence": 0.66,
                      "evidenceIds": ["missing.evidence.1"],
                      "reasoning": "model reasoning"
                    }
                  ],
                  "recommendations": [
                    {
                      "priority": "P1",
                      "actionType": "CHECK_LOG",
                      "description": "check logs",
                      "target": "demo.Interface.query"
                    }
                  ],
                  "overallConfidence": 0.66,
                  "insufficientEvidence": []
                }
                """, compressedEvidence()))
                .isInstanceOf(LlmOutputValidationException.class)
                .satisfies(exception -> assertThat(((LlmOutputValidationException) exception).status())
                        .isEqualTo(LlmCallStatus.INVALID_EVIDENCE_REFERENCE));
    }

    private CompressedEvidenceBundle compressedEvidence() {
        return new CompressedEvidenceBundle(
                new CompressedEvidenceBundle.AlertSnapshot(
                        AlertCategory.RPC_ERROR_RATE,
                        "demo-service",
                        "demo.Interface.query",
                        "cluster1",
                        Instant.parse("2026-04-21T02:33:00Z"),
                        "alert.request.count",
                        6.0,
                        "alert.fail.rate",
                        0.8,
                        "alert.avg.latency",
                        null,
                        "alert.p99.latency",
                        null
                ),
                new CompressedEvidenceBundle.BaselineSnapshot(true, null, "baseline.request.count", "baseline.fail.rate", "baseline.avg.latency", "baseline.p99.latency", InterfaceBaseline.empty()),
                new CompressedEvidenceBundle.InterfaceLogSnapshot(true, null, "interface.request.count", 6, "interface.failure.rate", 5, 0.8, "interface.latency.profile", 0L, 0L, 0L, 0L, List.of(), List.of(), List.of()),
                new CompressedEvidenceBundle.TraceSnapshot(true, null, List.of(), List.of(), List.of()),
                new CompressedEvidenceBundle.RuntimeLogSnapshot(true, null, List.of()),
                new CompressedEvidenceBundle.MetricSnapshot(true, null, ContainerMetrics.empty(), CseMetrics.empty(), DatabaseMetrics.empty(), DcsMetrics.empty(), MqMetrics.empty(), List.of()),
                ResourceMapping.empty("demo-service", "cluster1"),
                new CompressedEvidenceBundle.RuleSnapshot(List.of(), List.of()),
                List.of(
                        new EvidenceReference("alert.request.count", "ALERT", "request count"),
                        new EvidenceReference("error.top.1", "ERROR_CODE", "top error code")
                ),
                List.of(),
                List.of()
        );
    }
}
