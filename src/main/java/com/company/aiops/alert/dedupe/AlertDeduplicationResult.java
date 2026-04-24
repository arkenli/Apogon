package com.company.aiops.alert.dedupe;

import com.company.aiops.analysis.job.AnalysisJob;
import com.company.aiops.analysis.result.HistoricalReference;

import java.util.Optional;

/**
 * 告警去重后的决策结果，二选一返回复用任务或历史参考。
 */
public record AlertDeduplicationResult(
        Optional<AnalysisJob> duplicateTask,
        Optional<HistoricalReference> historicalReference
) {
}
