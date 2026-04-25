/**
 * Stage-7 rule-based result generation.
 *
 * <p>This package converts phase-5 rule evidence and phase-6 compressed evidence into final
 * {@code AnalysisResult} payloads for rule-only and degraded fallback modes. Keeping this logic
 * outside the worker avoids mixing lifecycle orchestration with result-construction rules.</p>
 */
package com.company.aiops.analysis.fallback;
