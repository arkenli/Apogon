package com.company.aiops.analysis.rule;

import com.company.aiops.alert.model.AlertCategory;
import com.company.aiops.alert.model.AlertEvent;
import com.company.aiops.analysis.evidence.BaselineEvidence;
import com.company.aiops.analysis.evidence.InterfaceLogEvidence;
import com.company.aiops.analysis.evidence.MetricEvidence;
import com.company.aiops.analysis.evidence.ProviderCollectionResult;
import com.company.aiops.analysis.evidence.RuntimeLogEvidence;
import com.company.aiops.analysis.evidence.TraceEvidence;
import com.company.aiops.analysis.rule.error.ErrorRateRuleAnalyzer;
import com.company.aiops.analysis.rule.latency.LatencyRuleAnalyzer;
import com.company.aiops.analysis.rule.log.ErrorLogRuleAnalyzer;
import com.company.aiops.analysis.rule.model.CandidateRootCause;
import com.company.aiops.analysis.rule.model.RuleAnalysisEvidence;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultRuleAnalysisServiceTests {

    @Test
    void analyzeRoutesServiceErrorLogToErrorLogAnalyzer() {
        RuleAnalysisEvidence routedEvidence = new RuleAnalysisEvidence(
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(new CandidateRootCause(
                        "CURRENT_SERVICE_RUNTIME_EXCEPTION",
                        90,
                        0.9,
                        List.of("runtime.log.1"),
                        "routed"
                )),
                List.of(),
                List.of()
        );
        DefaultRuleAnalysisService service = new DefaultRuleAnalysisService(
                unexpectedErrorRateAnalyzer(),
                unexpectedLatencyAnalyzer(),
                collectionResult -> {
                    assertThat(collectionResult.context().alert().category()).isEqualTo(AlertCategory.SERVICE_ERROR_LOG);
                    return routedEvidence;
                }
        );

        RuleAnalysisEvidence result = service.analyze(collection(RuleAnalysisTestFixtures.serviceErrorLogAlert()));

        assertThat(result).isSameAs(routedEvidence);
    }

    @Test
    void analyzeKeepsUnknownCategoryOnEmptyFallback() {
        DefaultRuleAnalysisService service = new DefaultRuleAnalysisService(
                unexpectedErrorRateAnalyzer(),
                unexpectedLatencyAnalyzer(),
                unexpectedErrorLogAnalyzer()
        );

        RuleAnalysisEvidence result = service.analyze(collection(
                RuleAnalysisTestFixtures.errorRateAlert(1).withCategory(AlertCategory.UNKNOWN)
        ));

        assertThat(result.ruleFindings()).isEmpty();
        assertThat(result.candidateRootCauses()).isEmpty();
        assertThat(result.insufficientEvidence())
                .anySatisfy(message -> assertThat(message).contains("UNKNOWN alert category"));
    }

    private ProviderCollectionResult collection(AlertEvent alert) {
        return RuleAnalysisTestFixtures.collection(
                alert,
                BaselineEvidence.missing("baseline not required"),
                InterfaceLogEvidence.missing("interface logs not required"),
                TraceEvidence.missing("trace not required"),
                RuntimeLogEvidence.missing("runtime logs not required"),
                MetricEvidence.missing("metrics not required")
        );
    }

    private ErrorRateRuleAnalyzer unexpectedErrorRateAnalyzer() {
        return collectionResult -> {
            throw new AssertionError("RPC error-rate analyzer should not be called.");
        };
    }

    private LatencyRuleAnalyzer unexpectedLatencyAnalyzer() {
        return collectionResult -> {
            throw new AssertionError("RPC latency analyzer should not be called.");
        };
    }

    private ErrorLogRuleAnalyzer unexpectedErrorLogAnalyzer() {
        return collectionResult -> {
            throw new AssertionError("Service error-log analyzer should not be called.");
        };
    }
}
