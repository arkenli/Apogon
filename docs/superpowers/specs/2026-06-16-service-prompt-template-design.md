# Service Prompt Template Design

## Goal

Allow each alerting service to use its own analysis prompt for a given alert category, while preserving the existing JSON output contract and validation-retry instructions in code.

## Current Behavior

`DefaultLlmAnalysisService` receives a `ProviderCollectionResult` and a `CompressedEvidenceBundle`, then selects a `PromptBuilder` only by `AlertCategory`. `RpcErrorRatePromptBuilder` and `RpcLatencyPromptBuilder` each contain the category-specific system prompt, user prompt, JSON output schema, enum constraints, evidence-id constraints, and retry repair prompt.

`serviceName` is already available in both `AlertEvent.serviceName()` and `CompressedEvidenceBundle.alert().serviceName()`. The implementation should not parse the raw alert text again.

## Required Behavior

Prompt selection becomes `serviceName + alertCategory` aware.

When an enabled database template exists for the alert service and category:

- Replace the category builder's default role/task/analysis instructions with the service-specific database template.
- Keep the compressed evidence JSON in the final user message.
- Keep JSON output schema, enum constraints, evidence-id constraints, confidence constraints, and "JSON only" output rules from code.
- Keep validation retry repair instructions from code.

When no enabled template exists:

- Preserve the current default prompt behavior for that alert category.

The database template must never be treated as a complete final prompt. It is only the replaceable analysis-instruction portion. The final prompt is still assembled by code so output validation requirements cannot be bypassed by configuration.

## Database Model

Add a migration for `aiops_service_prompt_template`.

Suggested columns:

- `id BIGINT PRIMARY KEY AUTO_INCREMENT`
- `service_name VARCHAR(256) NOT NULL`
- `alert_category VARCHAR(64) NOT NULL`
- `system_prompt MEDIUMTEXT NOT NULL`
- `user_prompt MEDIUMTEXT NOT NULL`
- `enabled TINYINT(1) NOT NULL DEFAULT 1`
- `version VARCHAR(128)`
- `metadata_json JSON`
- `created_by VARCHAR(128)`
- `updated_by VARCHAR(128)`
- `created_at DATETIME(3) NOT NULL`
- `updated_at DATETIME(3) NOT NULL`

Indexes:

- `UNIQUE KEY uk_service_prompt_template (service_name, alert_category)`
- `KEY idx_service_category_enabled (service_name, alert_category, enabled)`
- `KEY idx_updated_at (updated_at)`

`alert_category` stores the existing `AlertCategory.name()` value, such as `RPC_ERROR_RATE` or `RPC_LATENCY`.

## Code Structure

Add a small prompt-template persistence slice following the existing provider-query-mapping style:

- `ServicePromptTemplateRecord`: immutable model for one row.
- `ServicePromptTemplateRepository`: repository interface with lookup by `serviceName + AlertCategory`.
- `JdbcServicePromptTemplateRepository`: JDBC implementation.

Add a prompt-template resolver:

- `ServicePromptTemplateResolver` receives the repository.
- It returns `Optional<ServicePromptTemplateRecord>` for enabled templates.
- It normalizes blank service names by returning empty, so missing alert service names use the existing default prompt.

Refactor prompt builders so replaceable and fixed portions are separated:

- `RpcErrorRatePromptBuilder` and `RpcLatencyPromptBuilder` keep their supported category.
- Each builder asks the resolver for a service template using `compressedEvidence.alert().serviceName()`.
- If a template exists, use `template.systemPrompt()` and `template.userPrompt()` for the replaceable prompt sections.
- If not, use the current default system and user prompt sections.
- Always append the fixed JSON output contract and fixed repair prompt from code.

## Prompt Assembly

Each generated request should still contain:

- A system message with replaceable analysis instructions plus fixed JSON schema and constraints.
- A user message with replaceable category/service analysis focus plus compressed evidence JSON plus a fixed instruction to return only JSON.
- On retry, a second user message with the validation error and allowed evidence IDs, as today.

This keeps service customization flexible without allowing database configuration to remove the machine-readable output contract.

## Testing

Add unit tests before implementation:

- Template hit: for `serviceName=ams_front` and `category=RPC_ERROR_RATE`, prompt messages contain the database `systemPrompt` and `userPrompt`.
- Template hit still contains fixed JSON schema and enum/evidence constraints.
- Template miss: existing default prompt content is still used.
- Retry with template hit: validation repair prompt is still appended and lists existing evidence IDs.
- Repository lookup returns only enabled matching templates and ignores disabled rows.

Run the narrow prompt/repository tests first, then the LLM service test suite.

## Out Of Scope

- Management APIs or UI for prompt templates.
- Multi-version rollout selection beyond storing a `version` string.
- Full prompt replacement that can alter the JSON schema or validation retry instructions.
