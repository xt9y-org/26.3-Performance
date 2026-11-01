package dev.xt9y.perf.client;

import dev.xt9y.perf.NativeBridge;
import dev.xt9y.perf.PerformanceState;
import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.flag.ImGuiCond;
import imgui.type.ImBoolean;

import java.util.Locale;

/** Opt-in profiling graphs; monitoring is disabled while the window is closed. */
public final class DebugMenu {
    private static final ImBoolean OPEN = new ImBoolean(false);
    private static final float[] EMPTY = new float[15];
    private static final float[] FRAME_HISTORY = new float[120];
    private static final float[] RENDER_HISTORY = new float[120];
    private static final float[] TICK_HISTORY = new float[120];
    private static long nextRefreshNs;
    private static String frameLine = "FPS  measuring...";
    private static String tickLine = "TPS  measuring...";
    private static String chunkLine = "Chunks  measuring...";
    private static String memoryLine = "Heap  measuring...";

    private DebugMenu() {}

    public static void toggle() {
        boolean next = !OPEN.get();
        if (next) NativeBridge.initialize();
        OPEN.set(next);
        PerformanceState.setActive(next && NativeBridge.ready());
        nextRefreshNs = 0;
    }

    public static boolean visible() { return OPEN.get(); }

    private static void push(float[] history, float value) {
        System.arraycopy(history, 1, history, 0, history.length - 1);
        history[history.length - 1] = Float.isFinite(value) ? value : 0;
    }

    private static void refresh() {
        float[] values = NativeBridge.ready() ? NativeBridge.metrics() : EMPTY;
        if (values == null || values.length < 15) values = EMPTY;
        frameLine = String.format(Locale.ROOT, "FPS  %.0f     Frame  %.1f ms     P95  %.1f ms",
                values[0], values[1], values[13]);
        tickLine = values[5] > 0
                ? String.format(Locale.ROOT, "TPS  %.1f     MSPT  %.1f ms     P95  %.1f ms",
                        values[5], values[4], values[14])
                : "TPS / MSPT  no integrated server samples";
        chunkLine = "Chunks  " + (long) values[8] + " generated   "
                + (long) values[6] + " loaded";
        Runtime runtime = Runtime.getRuntime();
        long used = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
        long max = runtime.maxMemory() / (1024 * 1024);
        memoryLine = "Heap  " + used + " / " + max + " MiB";
        push(FRAME_HISTORY, values[1]);
        push(RENDER_HISTORY, values[2]);
        push(TICK_HISTORY, values[4]);
        SystemTelemetry.sample();
    }

    public static void draw(ImGuiIO io) {
        if (!OPEN.get()) return;
        long now = System.nanoTime();
        if (now >= nextRefreshNs) {
            refresh();
            nextRefreshNs = now + 500_000_000L;
        }
        ImGui.setNextWindowSize(620, 760, ImGuiCond.FirstUseEver);
        if (ImGui.begin("26.3 Performance", OPEN)) {
            if (NativeBridge.ready()) {
                ImGui.text(frameLine);
                ImGui.text(tickLine);
                ImGui.text(chunkLine);
                ImGui.text(memoryLine);
                ImGui.plotLines("Frame interval (ms)", FRAME_HISTORY, FRAME_HISTORY.length,
                        0, null, 0, 50, 490, 60);
                ImGui.plotLines("CPU render callback (ms)", RENDER_HISTORY, RENDER_HISTORY.length,
                        0, null, 0, 50, 490, 60);
                ImGui.plotLines("Server tick (ms)", TICK_HISTORY, TICK_HISTORY.length,
                        0, null, 0, 50, 490, 60);
            } else {
                ImGui.textWrapped("Rust profiler unavailable: " + NativeBridge.error());
            }
            SystemTelemetry.draw();
        }
        ImGui.end();
        if (!OPEN.get()) PerformanceState.setActive(false);
    }
}
