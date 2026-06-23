package com.company.aiops.provider.log.mock;

import com.company.aiops.analysis.context.TimeWindow;
import com.company.aiops.provider.model.RuntimeLogEntry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MockRuntimeLogProviderTests {

    private final MockRuntimeLogProvider provider = new MockRuntimeLogProvider();

    @Test
    void queryByMessageRequiresErrorLevelAndCanMissFullMessage() {
        List<RuntimeLogEntry> entries = provider.queryByMessage(
                "ams_front",
                "cluster2",
                "ERROR",
                "prebook loginAppointment failed",
                window(),
                10
        );

        assertThat(entries).isEmpty();
    }

    @Test
    void queryByMessageReturnsTraceIdsForCutMessage() {
        List<RuntimeLogEntry> entries = provider.queryByMessage(
                "ams_front",
                "cluster2",
                "ERROR",
                "loginAppointment failed",
                window(),
                10
        );

        assertThat(entries).hasSize(2);
        assertThat(entries)
                .extracting(
                        RuntimeLogEntry::level,
                        RuntimeLogEntry::serviceName,
                        RuntimeLogEntry::cluster,
                        RuntimeLogEntry::traceId
                )
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("ERROR", "ams_front", "cluster2", "trace-log-1"),
                        org.assertj.core.groups.Tuple.tuple("ERROR", "ams_front", "cluster2", "trace-log-2")
                );
        assertThat(entries)
                .extracting(RuntimeLogEntry::message)
                .allSatisfy(message -> assertThat(message).contains("loginAppointment failed"));
    }

    @Test
    void queryByMessageReturnsTwoEntriesWhenFiltersMatchAndLimitIsPositive() {
        List<RuntimeLogEntry> entries = provider.queryByMessage(
                "ams_front",
                "cluster2",
                "ERROR",
                "loginAppointment failed",
                window(),
                1
        );

        assertThat(entries).hasSize(2);
    }

    @Test
    void queryByMessageDoesNotReturnWarnEntriesForServiceErrorLogLookup() {
        List<RuntimeLogEntry> entries = provider.queryByMessage(
                "ams_front",
                "cluster2",
                "WARN",
                "loginAppointment failed",
                window(),
                10
        );

        assertThat(entries).isEmpty();
    }

    @Test
    void queryByMessageReturnsEmptyForWrongServiceName() {
        List<RuntimeLogEntry> entries = provider.queryByMessage(
                "ams_worker",
                "cluster2",
                "ERROR",
                "loginAppointment failed",
                window(),
                10
        );

        assertThat(entries).isEmpty();
    }

    @Test
    void queryByMessageReturnsEmptyForWrongCluster() {
        List<RuntimeLogEntry> entries = provider.queryByMessage(
                "ams_front",
                "cluster1",
                "ERROR",
                "loginAppointment failed",
                window(),
                10
        );

        assertThat(entries).isEmpty();
    }

    @Test
    void queryByMessageReturnsEmptyForBlankServiceName() {
        List<RuntimeLogEntry> entries = provider.queryByMessage(
                " ",
                "cluster2",
                "ERROR",
                "loginAppointment failed",
                window(),
                10
        );

        assertThat(entries).isEmpty();
    }

    @Test
    void queryByMessageReturnsEmptyForBlankCluster() {
        List<RuntimeLogEntry> entries = provider.queryByMessage(
                "ams_front",
                " ",
                "ERROR",
                "loginAppointment failed",
                window(),
                10
        );

        assertThat(entries).isEmpty();
    }

    private TimeWindow window() {
        Instant endTime = Instant.parse("2026-04-21T02:33:00Z");
        return new TimeWindow(endTime.minus(Duration.ofMinutes(5)), endTime);
    }
}
