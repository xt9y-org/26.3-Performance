package dev.xt9y.perf.client;

import com.mojang.blaze3d.systems.TimerQuery;

/** GPU timestamps for GameRenderer, independent of the active OpenGL/Vulkan backend. */
public final class GpuFrameProfiler {
    private static TimerQuery query;
    private static boolean pending;
    private static boolean started;
    private static boolean supported = true;
    private static boolean hasSample;
    private static float gpuMs;
    private static String error = "";

    private GpuFrameProfiler() {}

    /** Called on the render thread before GameRenderer issues draw commands. */
    public static void beforeRender() {
        if (!DebugMenu.visible()) {
            if (query != null && !started) {
                try {
                    if (query.getStatus() == TimerQuery.Status.NOT_RECORDING) {
                        query.close();
                        query = null;
                        pending = false;
                        hasSample = false;
                    }
                } catch (RuntimeException ignored) {
                    // The graphics device may have been lost while shutting down.
                }
            }
            return;
        }
        if (!supported) return;
        try {
            if (query == null) query = new TimerQuery();
            if (query.getStatus() != TimerQuery.Status.NOT_RECORDING) return;
            if (pending) {
                long nanos = query.get();
                if (nanos > 0) {
                    gpuMs = nanos / 1_000_000.0f;
                    hasSample = true;
                }
                pending = false;
            }
            query.beginProfile();
            started = true;
        } catch (RuntimeException | LinkageError failure) {
            supported = false;
            error = failure.getClass().getSimpleName();
        }
    }

    /** Called before the ImGui overlay, after the game's render commands. */
    public static void afterRender() {
        if (!started || query == null) return;
        started = false;
        try {
            query.endProfile();
            pending = true;
        } catch (RuntimeException | LinkageError failure) {
            supported = false;
            error = failure.getClass().getSimpleName();
        }
    }

    public static boolean hasSample() { return hasSample; }
    public static float gpuMilliseconds() { return gpuMs; }
    public static String error() { return error; }
}
