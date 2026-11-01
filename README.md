# 26.3 Performance

Lightweight **Minecraft Java 26.3 / Fabric only** performance telemetry.

## In-game profiler

**Right Shift** toggles one read-only ImGui performance window:

- FPS, frame intervals, CPU render callback time, TPS and MSPT (singleplayer only).
- Frame, render and tick histories with P95 summaries.
- System CPU utilization, per-logical-core usage (OSHI), Minecraft process CPU usage and effective CPU-core parallelism.
- JVM live/peak thread counts, thread-count history and the most CPU-intensive Java threads.
- Detected GPU name. GPU utilization and GPU timings are shown as **unavailable** until real graphics-backend telemetry exists; CPU render timing is **not** GPU time.
- Chunk load/generation event counts while the profiler is open; JVM heap usage.

The mod does not implement a replacement GPU renderer or choose an NRI backend yet. Minecraft's own graphics backend remains active. NRI integration would require replacing and validating terrain, entities, GUI, texture and resource submission, including GPU synchronization and swap-chain ownership.

## Performance engineering

The profiler is **inactive by default**. Its Rust library is loaded only when the window is first opened; closed-window rendering and tick hooks avoid timestamp sampling, heap allocations and JNI calls. The native hot path uses bounded 240-slot atomic ring buffers. Snapshot aggregation, hardware polling and text formatting happen only twice per second while open.

This reduces **the mod's own monitoring overhead and startup work**. It is not a claim that unmodified Minecraft terrain generation, lighting, entity ticking, renderer, or region IO is faster. Those systems require specific profiled hot spots, behavioral equivalence tests, and in-game before/after benchmarks before modifying them safely.

The old offline chunk regeneration tool and its pending-queue processing have been removed. If you used the previous version, you can delete `config/xt9y263-pending-regen.txt`. The new build does not process that file.

## Build

Requires Java 25, Gradle 9.7, Rust stable.

```sh
cargo test --manifest-path rust/Cargo.toml
gradle build
```

CI compiles Rust on Windows x64, Linux x64, Apple Silicon and Intel macOS and releases a **single universal Fabric JAR** bundling the corresponding native libraries.

[Releases](https://github.com/xt9y-org/26.3/releases) · [CI](https://github.com/xt9y-org/26.3/actions)

For high-impact whole-game performance improvements, use verified Minecraft 26.3-compatible rendering, memory, tick and chunk-optimization mods, and profile compatibility before integrating equivalent kernels into this mod.

## Integrated source optimization (lossless)

**Empty data-component map compaction:** when an item's patched component map becomes empty, Minecraft now reuses a shared immutable empty map instead of retaining a per-object mutable map. Its copy-on-write flag ensures later component edits work normally. This optimization is implemented directly in our own `ComponentMapCompactionMixin.java` source, adapted from FerriteCore under MIT. The upstream duplicate is disabled automatically if FerriteCore is separately installed.

No item behavior or quality settings are changed. A real Minecraft 26.3 dedicated-server boot is included in CI to catch invalid mixin hooks. This is one limited memory optimization, not a complete rewrite of the chunk, lighting, renderer, and entity engines.

### In-house entity-tick optimization

Exact vanilla sign and furnace block entity classes no longer register a ticker
when proven idle. Editing a sign and changing furnace fuel/input force a
re-evaluation so useful ticks are restored. Mod subclass block entities
are not gated. These are ported into our own Java/mixin source from the
MIT-licensed Ferrite Minecraft 26.3 implementation. When Ferrite is also
installed, the ticker mixin set is disabled automatically to avoid duplicate
redirections. No tick-frequency or quality reduction is introduced.

### In-house block-state neighbour lookup

Block-state neighbour tables are now backed by a shared indexed lookup
instead of a separate per-state neighbor array. The port retains vanilla's
property-value storage and is based on FerriteCore's MIT fast-map algorithm.
Only exact property changes use the shared transition table; no worldgen,
rendering quality or simulation values are intentionally changed.
The optimization is automatically skipped if FerriteCore is installed.

### In-house block-state face-array deduplication

Cached face-sturdiness arrays with identical boolean content now share storage,
avoiding one allocation per block state. The values are unchanged, with no effect
on collision, occlusion or visuals. This feature runs only when vanilla builds a
block-state cache; it adds no per-frame or per-tick work. Upstream FerriteCore
already has an equivalent implementation, so this mixin is automatically skipped
when FerriteCore is installed.

## Diagnostic A/B baseline mode

Set `XT9Y_PERF_BASELINE=1` before launching Minecraft to disable **only
xt9y's optimization mixins** while keeping the normal profiler and mod installed.
This option is meant for identical-workload benchmarks and CI oracles, not a
graphics-quality setting. The normal release enables the lossless optimizations.

CI now launches both the optimized and baseline modes and verifies that all
registered vanilla block-state property transitions remain identical. Note that
startup/oracle timings alone are not a meaningful FPS or TPS speedup benchmark.

### Lossless chunk random-tick allocation reduction

The server's chunk random-tick path now reuses one mutable block position when
calculating random tick coordinates, instead of allocating a temporary position
on every lookup. Position objects handed to block and fluid random-tick methods
are made immutable to prevent escaping a reused mutable object. The vanilla
random-number sequence is unchanged, verified by an exhaustive 32,768-call
oracle in the actual Minecraft 26.3 server. Adapted under LGPL-3.0 from
Lithium's 26.3 allocation optimization; full source and license are embedded.
The mixins are skipped automatically if Lithium is installed separately, and
`XT9Y_PERF_BASELINE=1` disables the optimization for comparisons.

### In-house worldgen / shape-occlusion cache

A bounded 256-entry **per-thread** voxel-shape cache avoids repeated, exact
full-block voxel comparisons used by geometry/worldgen and related block logic.
No input coordinates, RNG state, light levels, transparency or quality setting
are modified. The result is computed using Minecraft's original
`Shapes.joinIsNotEmpty(..., BooleanOp.NOT_SAME)` predicate on cache misses.
It is disabled when Lithium/C2ME is separately installed to avoid duplicate
mixins. CI tests cached results against vanilla in the real 26.3 server, in
both optimized and `XT9Y_PERF_BASELINE=1` modes.

This does **not** replace the terrain noise generator or full lighting engine.

## In-game lighting equivalence gate

When `XT9Y_TEST_LIGHT_ORACLE=1` is set before launching the
dedicated server, a test-only fixture inserts a glowstone light emitter and
an obsidian occlusion wall at Y=260, then removes the emitter. It waits for
lighting to settle and hashes 8,125 positions' block and sky light levels
in each of the lit/dark phases. CI launches the same world seed with
`XT9Y_PERF_BASELINE=1` and with xt9y optimizations, then requires the
two fields to match exactly. This provides real lighting-correctness coverage
for future lighting algorithm changes, but is not itself an optimized
replacement for Minecraft's lighting engine.

## Real Minecraft client FPS benchmark

Launch Minecraft with `XT9Y_FPS_BENCH=1` and enter the same world, at the
same camera position with the same graphics settings. A test-only recorder
collects real client **frame intervals** from GameRenderer, skipping the
first 120 in-world frames, then logging 1,800 frames' mean, P95 and P99
frame times, average FPS and approximate 1% low FPS. No Rust/JNI calls are
needed. Without this environment variable the recorder is dormant.

To compare A/B on the same machine, restart once with
`XT9Y_PERF_BASELINE=1 XT9Y_FPS_BENCH=1`, and once with
`XT9Y_FPS_BENCH=1`; keep view distance, VSync, resolution, shaders,
scene and player position unchanged. Disable VSync and FPS caps when
measuring GPU throughput. One session is not statistical proof of a
performance gain. A GPU graphical client pixel-equivalence test is
still needed before the experimental framebuffer-binding patch is
enabled by default.

### In-house text-renderer hot-path optimization

Text glyph rendering uses a per-GlyphRenderer identity cache to avoid
redundant `getVertexBuilder` calls for adjacent glyphs sharing a render
type. Ported as editable source from LGPL-3.0-or-later ImmediatelyFast.
No glyph appearance or quality settings are changed; baseline mode
(`XT9Y_PERF_BASELINE=1`) disables the redirect. If ImmediatelyFast
is separately installed its own identical mixin takes precedence.

This targets **text/immediate rendering**, not the entire terrain
renderer. A dedicated graphical-client smoke and A/B frame-time test
are still required to claim measured FPS gains.

### Apple GPU upload path

On macOS, a complete GPU-buffer replacement (offset 0, full buffer size)
uses `bufferData` rather than `bufferSubData`, which can avoid a slow
Apple OpenGL driver path. Partial uploads and non-Apple platforms retain
vanilla behavior. Adapted under LGPL from the 26.3 ImmediatelyFast source.

This is a targeted OpenGL optimization, **not** a Metal backend and not a
complete renderer rewrite. It remains in the development PR pending
graphical-client startup verification and frame-time measurements.

## Opt-in actual Minecraft server MSPT benchmark

The server hook `XT9Y_TPS_BENCH=1` records real tick durations into
a bounded array after a 60-tick warmup, then logs mean/P95/P99 MSPT and
heap use at shutdown. The benchmark hook does not register in normal gameplay.

A/B testing starts a real dedicated 26.3 server twice with seed 73452981
and the same 169 forced chunks. The first run uses
`XT9Y_PERF_BASELINE=1`; the second enables native optimizations.
`scripts/compare_tick_bench.py` rejects missing or insufficient samples.
One pair of server runs is **not** evidence of a sustained or statistically
significant speedup, and says nothing about FPS.

Graphical smoke in CI requires a real client tick and completed rendered
frame, and separately checks baseline mode. The title screen does not
necessarily invoke world-text glyph rendering; in-world glyph testing requires
a separate fixture (`XT9Y_REQUIRE_GLYPH_SMOKE=1`). This is a startup/hook
correctness check, **not** a visual pixel-parity test or real-GPU benchmark.


## Experimental generator setting cache; vanilla mob navigation

`XT9Y_NATIVE_HOTPATHS=1` now enables **only** the lazy sea-level
setting cache in `NoiseBasedChunkGenerator`. The custom
`PathNavigationRegionMixin` AI/pathfinding block/fluid lookup replacement
was **removed**, including from opt-in mode. Swimming, drowning,
navigation, water-surface avoidance, and entity behavior use Minecraft's
native vanilla code instead.

This is a gameplay-safety rollback following a report of aquatic mobs
accumulating at the water surface. The original CI test sampled block and
fluid state data but did not simulate actual mob movement or swimming.
The rollback is deliberately not presented as proof of the root cause.
The normal dev-25 release did not enable this experimental mixin unless
`XT9Y_NATIVE_HOTPATHS=1` was set. If the issue also occurs with
`XT9Y_PERF_BASELINE=1`, investigate the remaining optimization mixins
and other installed mods before assigning causality.

CI continues to compare real `PathNavigationRegion` vanilla block/fluid
lookups against world state, now sampling each Y level around sea level.
It also rejects any production JAR registering or containing the retired
pathfinding mixin. The vanilla oracle cannot prove arbitrary mob AI
behaviour, so manual water-mob testing remains mandatory.

To run the seeded world and navigation oracle in disposable worlds:

```sh
bash scripts/test_engine_parity.sh
```

`XT9Y_FPS_BENCH=1` and `XT9Y_TPS_BENCH=1` remain available for
actual gameplay performance measurements.

## Major stable v1.0.0 candidate

For the complete release checklist, actual local test command, exact versioning,
manual publish workflow and remaining unreplaced engine systems, see
[`RELEASE_1.0.0.md`](RELEASE_1.0.0.md). The major version is **not**
published from a PR: it must pass tests, merge into main, then be launched
manually in GitHub Actions with `version=1.0.0` and `confirm_publish=RELEASE`.
