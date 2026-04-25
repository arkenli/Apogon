/**
 * Phase-6 evidence-compression types.
 *
 * <p>This package owns the structured evidence bundle that is persisted after rule analysis and
 * later becomes the bounded input to LLM and rule-fallback stages. Raw provider snapshots stay in
 * {@code analysis.evidence}; deterministic rule outputs stay in {@code analysis.rule}.</p>
 */
package com.company.aiops.analysis.bundle;
