package com.company.aiops.analysis.rule;

import com.company.aiops.alert.model.AlertCategory;
import com.company.aiops.analysis.evidence.ProviderCollectionResult;
import com.company.aiops.analysis.rule.error.ErrorRateRuleAnalyzer;
import com.company.aiops.analysis.rule.latency.LatencyRuleAnalyzer;
import com.company.aiops.analysis.rule.model.RuleAnalysisEvidence;
import org.springframework.stereotype.Service;

/**
 * Selects the category-specific phase-5 rule analyzer.
 */
@Service
public class DefaultRuleAnalysisService implements RuleAnalysisService {

    private final ErrorRateRuleAnalyzer errorRateRuleAnalyzer;
    private final LatencyRuleAnalyzer latencyRuleAnalyzer;

    public DefaultRuleAnalysisService(
            ErrorRateRuleAnalyzer errorRateRuleAnalyzer,
            LatencyRuleAnalyzer latencyRuleAnalyzer
    ) {
        this.errorRateRuleAnalyzer = errorRateRuleAnalyzer;
        this.latencyRuleAnalyzer = latencyRuleAnalyzer;
    }

    @Override
    public RuleAnalysisEvidence analyze(ProviderCollectionResult collectionResult) {
        return switch (collectionResult.context().alert().category()) {
            case RPC_ERROR_RATE -> errorRateRuleAnalyzer.analyze(collectionResult);
            case RPC_LATENCY -> latencyRuleAnalyzer.analyze(collectionResult);
            case UNKNOWN -> new RuleAnalysisEvidence(
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    java.util.List.of("No rule analyzer is defined for UNKNOWN alert category.")
            );
        };
    }
}
