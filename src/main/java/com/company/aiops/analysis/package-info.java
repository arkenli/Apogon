/**
 * Analysis module.
 *
 * <p>The package is split by responsibility so task lifecycle state, execution context, result
 * payloads, collected evidence, and orchestration code do not accumulate in one directory. The
 * current implementation keeps those concerns under {@code analysis.job},
 * {@code analysis.context}, {@code analysis.evidence}, {@code analysis.result}, and
 * {@code analysis.orchestrator}.</p>
 */
package com.company.aiops.analysis;
