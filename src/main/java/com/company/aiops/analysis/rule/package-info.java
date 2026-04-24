/**
 * Phase-5 rule-analysis entrypoints.
 *
 * <p>This package owns deterministic pre-analysis that runs after provider collection and before
 * evidence compression / LLM stages. Category-specific analyzers live under {@code rule.error} and
 * {@code rule.latency}; shared metric and trace helpers live under {@code rule.metric} and
 * {@code rule.trace}; structured outputs live under {@code rule.model}.</p>
 */
package com.company.aiops.analysis.rule;
