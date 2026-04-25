package com.company.aiops.llm.prompt;

import com.company.aiops.alert.model.AlertCategory;
import com.company.aiops.analysis.bundle.CompressedEvidenceBundle;
import com.company.aiops.llm.client.LlmMessage;

import java.util.List;

/**
 * Builds category-specific prompt messages from the bounded phase-6 evidence bundle.
 */
public interface PromptBuilder {

    /**
     * Which alert category this builder supports.
     */
    AlertCategory supportedCategory();

    /**
     * Builds the message list for the current attempt. When {@code validationError} is present the
     * builder appends a repair instruction for the single allowed retry.
     */
    List<LlmMessage> buildMessages(CompressedEvidenceBundle compressedEvidence, String validationError);
}
