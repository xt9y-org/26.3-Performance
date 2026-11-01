package dev.xt9y.perf.client;

import imgui.ImGui;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GraphicsCard;

/** Read-only hardware sampling, only active while the profiler is visible. */
public final class SystemTelemetry {
    private static final int HISTORY = 120;
    private static final float[] SYSTEM_HISTORY = new float[HISTORY];
    private static final float[] PROCESS_HISTORY = new float[HISTORY];
    private static final float[] THREAD_HISTORY = new float[HISTORY];
    private static final float[] PARALLEL_HISTORY = new float[HISTORY];
    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();
    private static final com.sun.management.OperatingSystemMXBean OS =
            ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean bean
                    ? bean : null;

    private static CentralProcessor processor;
    private static long[] systemTicks;
    private static long[][] coreTicks;
    private static float[] coreLoads = new float[0];
    private static final Map<Long, Long> previousThreads = new HashMap<>();
    private static final String[] busiest = new String[5];
    private static String gpu = "GPU identification unavailable";
    private static String hardwareError = "";
    private static boolean initialized;
    private static int samples;
    private static long previousWall;
    private static long previousProcess = -1;
    private static float systemPercent;
    private static float processPercent;
    private static float effectiveCores;
    private static int threadCount;

    private record BusyThread(long id, long nanos) {}

    private SystemTelemetry() {}

    private static void initialize() {
        if (initialized) return;
        initialized = true;
        try {
            SystemInfo info = new SystemInfo();
            processor = info.getHardware().getProcessor();
            systemTicks = processor.getSystemCpuLoadTicks();
            coreTicks = processor.getProcessorCpuLoadTicks();
            List<GraphicsCard> cards = info.getHardware().getGraphicsCards();
            if (!cards.isEmpty()) gpu = cards.get(0).getName() + " (detected, not necessarily active)";
        } catch (Throwable error) {
            processor = null;
            hardwareError = error.getClass().getSimpleName();
        }
        try {
            if (THREADS.isThreadCpuTimeSupported() && !THREADS.isThreadCpuTimeEnabled())
                THREADS.setThreadCpuTimeEnabled(true);
        } catch (SecurityException | UnsupportedOperationException ignored) {
            // Thread counts still work without CPU accounting.
        }
    }

    public static void sample() {
        initialize();
        long now = System.nanoTime();
        long elapsed = previousWall == 0 ? 0 : now - previousWall;
        previousWall = now;

        if (processor != null) {
            try {
                if (elapsed > 0) {
                    systemPercent = (float) (100.0 * processor.getSystemCpuLoadBetweenTicks(systemTicks));
                    double[] loads = processor.getProcessorCpuLoadBetweenTicks(coreTicks);
                    if (coreLoads.length != loads.length) coreLoads = new float[loads.length];
                    for (int i = 0; i < loads.length; i++)
                        coreLoads[i] = (float) (100.0 * loads[i]);
                }
                systemTicks = processor.getSystemCpuLoadTicks();
                coreTicks = processor.getProcessorCpuLoadTicks();
            } catch (RuntimeException ignored) {
                // CPU topology can change between consecutive samples.
                systemTicks = processor.getSystemCpuLoadTicks();
                coreTicks = processor.getProcessorCpuLoadTicks();
            }
        }

        if (OS != null) {
            long cpu = OS.getProcessCpuTime();
            if (elapsed > 0 && previousProcess >= 0 && cpu >= previousProcess) {
                effectiveCores = (float) ((double) (cpu - previousProcess) / elapsed);
                processPercent = 100.0f * effectiveCores
                        / Math.max(1, Runtime.getRuntime().availableProcessors());
            }
            previousProcess = cpu;
        }

        threadCount = THREADS.getThreadCount();
        sampleThreads(elapsed);
        append(SYSTEM_HISTORY, systemPercent);
        append(PROCESS_HISTORY, processPercent);
        append(THREAD_HISTORY, threadCount);
        append(PARALLEL_HISTORY, effectiveCores);
        samples = Math.min(samples + 1, HISTORY);
    }

    private static void sampleThreads(long elapsed) {
        if (!THREADS.isThreadCpuTimeSupported() || !THREADS.isThreadCpuTimeEnabled()) return;
        Map<Long, Long> current = new HashMap<>();
        List<BusyThread> loads = new ArrayList<>();
        for (long id : THREADS.getAllThreadIds()) {
            long total = THREADS.getThreadCpuTime(id);
            if (total < 0) continue;
            current.put(id, total);
            Long old = previousThreads.get(id);
            if (old != null && total >= old && elapsed > 0)
                loads.add(new BusyThread(id, total - old));
        }
        previousThreads.clear();
        previousThreads.putAll(current);
        loads.sort(Comparator.comparingLong(BusyThread::nanos).reversed());
        for (int i = 0; i < busiest.length; i++) {
            busiest[i] = null;
            if (i >= loads.size()) continue;
            BusyThread thread = loads.get(i);
            ThreadInfo info = THREADS.getThreadInfo(thread.id());
            if (info == null) continue;
            busiest[i] = String.format(Locale.ROOT, "%s   %.1f%% of a core",
                    info.getThreadName(), thread.nanos() * 100.0 / elapsed);
        }
    }

    private static void append(float[] history, float value) {
        System.arraycopy(history, 1, history, 0, HISTORY - 1);
        history[HISTORY - 1] = Float.isFinite(value) ? value : 0;
    }

    public static void draw() {
        ImGui.separator();
        ImGui.text("CPU / parallel execution");
        ImGui.text(String.format(Locale.ROOT,
                "System %.1f%%     Java process %.1f%%     Effective %.2f cores / %d available",
                systemPercent, processPercent, effectiveCores,
                Runtime.getRuntime().availableProcessors()));
        ImGui.plotLines("System CPU %", SYSTEM_HISTORY, HISTORY, 0, null, 0, 100, 490, 60);
        ImGui.plotLines("Minecraft process %", PROCESS_HISTORY, HISTORY, 0, null, 0, 100, 490, 60);
        if (coreLoads.length > 0)
            ImGui.plotHistogram("Logical CPU cores %", coreLoads, coreLoads.length, 0,
                    null, 0, 100, 490, 90);
        else if (!hardwareError.isEmpty())
            ImGui.textDisabled("Per-core hardware counters unavailable: " + hardwareError);

        ImGui.plotLines("Parallel CPU cores used", PARALLEL_HISTORY, HISTORY, 0, null,
                0, Math.max(1, Runtime.getRuntime().availableProcessors()), 490, 60);
        ImGui.separator();
        ImGui.text("JVM threads " + threadCount + "    Peak " + THREADS.getPeakThreadCount());
        ImGui.plotLines("Thread count", THREAD_HISTORY, HISTORY, 0, null,
                0, Math.max(32, THREADS.getPeakThreadCount()), 490, 54);
        for (String thread : busiest)
            if (thread != null) ImGui.textDisabled(thread);

        ImGui.separator();
        ImGui.textWrapped("GPU: " + gpu);
        ImGui.textDisabled("GPU utilization / GPU time: unavailable without graphics-backend counters.");
        ImGui.textDisabled("Renderer: Minecraft-managed (NRI replacement not active)");
        ImGui.textDisabled("CPU plots sample every 500 ms; per-core bars show whole-system activity.");
    }
}
