package com.globalfutservice.config;

import java.io.IOException;
import java.lang.management.BufferPoolMXBean;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * How much memory the process has, and uses: logged once at startup and read on demand for
 * the admin dashboard.
 *
 * <p>Render restarts the service when the <i>container</i> goes over its limit, and the
 * container counts everything -- heap, class metadata, JIT code, thread stacks, direct
 * buffers. So besides the JVM's own figures this reads the process's resident size
 * ({@code /proc/self/status}) and the container's limit and use (cgroup v2, or v1). Where a
 * figure cannot be read -- off Linux, in tests -- it is null, never a guess.
 *
 * <p>Numbers only: no JVM arguments, system properties or environment, which can carry secrets.
 */
@Component
public class MemoryReport {

    private static final Logger log = LoggerFactory.getLogger(MemoryReport.class);
    private static final long MB = 1024L * 1024L;

    /** Every figure in MB; null where it cannot be read or has no limit. */
    public record Snapshot(long heapUsedMb, long heapCommittedMb, long heapMaxMb,
                           long metaspaceUsedMb, Long metaspaceMaxMb, long codeCacheUsedMb, Long codeCacheMaxMb,
                           long directUsedMb, Long directMaxMb, long nonHeapUsedMb,
                           int threads, int peakThreads, Long processRssMb, Long containerLimitMb,
                           Long containerUsedMb, String gc, long uptimeSeconds) {
    }

    private final Path procStatus;
    private final Path cgroupRoot;

    public MemoryReport() {
        this(Path.of("/proc/self/status"), Path.of("/sys/fs/cgroup"));
    }

    MemoryReport(Path procStatus, Path cgroupRoot) {
        this.procStatus = procStatus;
        this.cgroupRoot = cgroupRoot;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void logAtStartup() {
        try {
            log.info(startupLine(now()));
        } catch (RuntimeException e) {
            log.warn("JVM MEMORY: could not be read ({})", e.getClass().getSimpleName());
        }
    }

    static String startupLine(Snapshot s) {
        return "JVM MEMORY: max heap " + s.heapMaxMb() + " MB (committed " + s.heapCommittedMb() + " MB, used "
                + s.heapUsedMb() + " MB); metaspace max " + mb(s.metaspaceMaxMb()) + "; code cache max "
                + mb(s.codeCacheMaxMb()) + "; direct buffers max " + mb(s.directMaxMb()) + "; GC " + s.gc()
                + "; container limit " + mb(s.containerLimitMb()) + "; process resident " + mb(s.processRssMb()) + ".";
    }

    private static String mb(Long v) {
        return v == null ? "unknown or unlimited" : v + " MB";
    }

    /** The figures as they are now. */
    public Snapshot now() {
        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        MemoryUsage nonHeap = ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage();
        long metaspaceUsed = 0;
        Long metaspaceMax = null;
        long codeUsed = 0;
        long codeMax = 0;
        boolean codeMaxKnown = true;
        for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
            String name = pool.getName();
            MemoryUsage u = pool.getUsage();
            if (u == null) continue;
            if (name.equals("Metaspace")) {
                metaspaceUsed = u.getUsed();
                metaspaceMax = u.getMax() < 0 ? null : u.getMax() / MB;
            } else if (name.startsWith("CodeHeap") || name.equals("Code Cache")) {
                codeUsed += u.getUsed();
                if (u.getMax() < 0) codeMaxKnown = false;
                else codeMax += u.getMax();
            }
        }
        long directUsed = 0;
        for (BufferPoolMXBean pool : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class)) {
            if (pool.getName().equals("direct")) directUsed = pool.getMemoryUsed();
        }
        String gc = ManagementFactory.getGarbageCollectorMXBeans().stream()
                .map(GarbageCollectorMXBean::getName).collect(Collectors.joining(", "));
        var threads = ManagementFactory.getThreadMXBean();
        return new Snapshot(heap.getUsed() / MB, heap.getCommitted() / MB, heap.getMax() / MB,
                metaspaceUsed / MB, metaspaceMax, codeUsed / MB, codeMaxKnown && codeMax > 0 ? codeMax / MB : null,
                directUsed / MB, directMax(), nonHeap.getUsed() / MB, threads.getThreadCount(),
                threads.getPeakThreadCount(), processRssMb(), containerLimitMb(), containerUsedMb(), gc,
                ManagementFactory.getRuntimeMXBean().getUptime() / 1000);
    }

    /** -XX:MaxDirectMemorySize, when set; null for the JVM's default (the max heap). */
    private static Long directMax() {
        try {
            var hotspot = ManagementFactory.getPlatformMXBean(com.sun.management.HotSpotDiagnosticMXBean.class);
            long bytes = Long.parseLong(hotspot.getVMOption("MaxDirectMemorySize").getValue());
            return bytes <= 0 ? null : bytes / MB;
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** VmRSS from /proc/self/status: what the process holds in RAM. */
    Long processRssMb() {
        try {
            for (String line : Files.readAllLines(procStatus)) {
                if (line.startsWith("VmRSS:")) {
                    String[] parts = line.substring(6).trim().split("\\s+");
                    return Long.parseLong(parts[0]) / 1024; // kB
                }
            }
        } catch (IOException | RuntimeException e) {
            // Not on Linux, or not readable: unknown.
        }
        return null;
    }

    /** The container's memory limit: cgroup v2 memory.max, or v1 memory.limit_in_bytes. */
    Long containerLimitMb() {
        Long v2 = readBytes(cgroupRoot.resolve("memory.max"));
        if (v2 != null) return v2 / MB;
        Long v1 = readBytes(cgroupRoot.resolve("memory").resolve("memory.limit_in_bytes"));
        // v1 reports "no limit" as a huge number.
        return v1 == null || v1 >= Long.MAX_VALUE / 2 ? null : v1 / MB;
    }

    /** What the container uses now, as its limit counts it. */
    Long containerUsedMb() {
        Long v2 = readBytes(cgroupRoot.resolve("memory.current"));
        if (v2 != null) return v2 / MB;
        Long v1 = readBytes(cgroupRoot.resolve("memory").resolve("memory.usage_in_bytes"));
        return v1 == null ? null : v1 / MB;
    }

    private static Long readBytes(Path file) {
        try {
            List<String> lines = Files.readAllLines(file);
            if (lines.isEmpty()) return null;
            String v = lines.get(0).trim();
            if (v.isEmpty() || v.equals("max")) return null;
            return Long.parseLong(v);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }
}
