package dev.xt9y.perf;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PerfMod implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("xt9y263");

    @Override
    public void onInitialize() {
        if ("1".equals(System.getenv("XT9Y_TEST_BLOCKSTATES"))) {
            net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED
                    .register(server -> dev.xt9y.perf.testing.BlockstateOracle.verify());
        }
        if ("1".equals(System.getenv("XT9Y_TEST_CHUNK_RANDOM"))) {
            net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED
                    .register(server -> dev.xt9y.perf.testing.ChunkRandomOracle.verify(server.overworld()));
        }
        if ("1".equals(System.getenv("XT9Y_TEST_SHAPE_CACHE"))) {
            net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STARTED
                    .register(server -> dev.xt9y.perf.testing.ShapeCacheOracle.verify());
        }
        if ("1".equals(System.getenv("XT9Y_TEST_LIGHT_ORACLE")))
            dev.xt9y.perf.testing.LightingOracle.register();
        if ("1".equals(System.getenv("XT9Y_TPS_BENCH")))
            dev.xt9y.perf.testing.ServerTickBenchmark.register();
        if ("1".equals(System.getenv("XT9Y_TEST_WORLDGEN_SNAPSHOT"))) {
            net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING
                    .register(server -> dev.xt9y.perf.testing.WorldgenSnapshot.verify(server.overworld()));
        }
        if ("1".equals(System.getenv("XT9Y_TEST_PATH_ORACLE"))) {
            net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING
                    .register(server -> dev.xt9y.perf.testing.PathNavigationOracle.verify(server.overworld()));
        }
        // No native loading, file IO, or sampling until the debugger is opened.
        final long[] started = {0};
        ServerTickEvents.START_SERVER_TICK.register(server -> {
            if (PerformanceState.active()) {
                long now = System.nanoTime();
                started[0] = now;
                NativeBridge.serverStart(now);
            } else {
                started[0] = 0;
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            long start = started[0];
            if (PerformanceState.active() && start != 0) {
                NativeBridge.record(3, System.nanoTime() - start);
            }
        });
        ServerChunkEvents.CHUNK_LOAD.register((world, chunk, generated) -> {
            if (!PerformanceState.active()) return;
            NativeBridge.chunkEvent(0);
            if (generated) NativeBridge.chunkEvent(2);
        });
        ServerChunkEvents.CHUNK_UNLOAD.register((world, chunk) -> {
            if (PerformanceState.active()) NativeBridge.chunkEvent(1);
        });
    }
}
