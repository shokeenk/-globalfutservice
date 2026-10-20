package com.globalfutservice.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/** What the process and its container use, read the way Linux reports it; unknown off Linux. */
class MemoryReportTest {

    @TempDir
    Path dir;

    private MemoryReport report(String status, String cgroupV2Max, String cgroupV2Current) throws IOException {
        Path proc = dir.resolve("status");
        if (status != null) Files.writeString(proc, status);
        Path cgroup = Files.createDirectories(dir.resolve("cgroup"));
        if (cgroupV2Max != null) Files.writeString(cgroup.resolve("memory.max"), cgroupV2Max + "\n");
        if (cgroupV2Current != null) Files.writeString(cgroup.resolve("memory.current"), cgroupV2Current + "\n");
        return new MemoryReport(proc, cgroup);
    }

    @Test
    @DisplayName("resident size from /proc/self/status, and the container's limit and use from cgroup v2")
    void linuxV2() throws IOException {
        MemoryReport r = report("Name:\tjava\nVmPeak:\t 900000 kB\nVmRSS:\t  471040 kB\nThreads:\t42\n",
                "536870912", "402653184");
        assertThat(r.processRssMb()).isEqualTo(460L);
        assertThat(r.containerLimitMb()).isEqualTo(512L);
        assertThat(r.containerUsedMb()).isEqualTo(384L);
    }

    @Test
    @DisplayName("cgroup v1 when there is no v2; no limit is unknown, never a huge number")
    void linuxV1() throws IOException {
        MemoryReport r = report(null, null, null);
        Path v1 = Files.createDirectories(dir.resolve("cgroup").resolve("memory"));
        Files.writeString(v1.resolve("memory.limit_in_bytes"), "536870912\n");
        Files.writeString(v1.resolve("memory.usage_in_bytes"), "268435456\n");
        assertThat(r.containerLimitMb()).isEqualTo(512L);
        assertThat(r.containerUsedMb()).isEqualTo(256L);

        Files.writeString(v1.resolve("memory.limit_in_bytes"), "9223372036854771712\n");
        assertThat(r.containerLimitMb()).isNull();
    }

    @Test
    @DisplayName("v2 with no limit (\"max\"), or nothing to read at all: unknown, not zero")
    void unknown() throws IOException {
        assertThat(report(null, "max", null).containerLimitMb()).isNull();
        MemoryReport none = new MemoryReport(dir.resolve("missing"), dir.resolve("missing-cgroup"));
        assertThat(none.processRssMb()).isNull();
        assertThat(none.containerLimitMb()).isNull();
        assertThat(none.containerUsedMb()).isNull();
    }

    @Test
    @DisplayName("the JVM's own figures are always there, and the startup line says the max heap and the limits")
    void snapshotAndStartupLine() throws IOException {
        MemoryReport.Snapshot s = report("VmRSS:\t 204800 kB\n", "536870912", "300000000").now();
        assertThat(s.heapMaxMb()).isPositive();
        assertThat(s.heapUsedMb()).isLessThanOrEqualTo(s.heapCommittedMb());
        assertThat(s.metaspaceUsedMb()).isPositive();
        assertThat(s.threads()).isPositive();
        assertThat(s.gc()).isNotBlank();
        assertThat(s.processRssMb()).isEqualTo(200L);
        assertThat(s.containerLimitMb()).isEqualTo(512L);

        String line = MemoryReport.startupLine(s);
        assertThat(line).startsWith("JVM MEMORY: max heap " + s.heapMaxMb() + " MB")
                .contains("container limit 512 MB").contains("process resident 200 MB").contains("GC " + s.gc());
    }
}
