package com.company.aiops.provider.mock;

/**
 * Service-name markers that allow tests to force specific mock-provider failure paths without
 * wiring custom beans into the Spring context.
 */
public final class MockProviderTokens {

    public static final String FAIL_INTERFACE_LOG = "mock-fail-interface-log";
    public static final String FAIL_TRACE = "mock-fail-trace";
    public static final String FAIL_RUNTIME_LOG = "mock-fail-runtime-log";
    public static final String FAIL_BASELINE = "mock-fail-baseline";

    private MockProviderTokens() {
    }
}
