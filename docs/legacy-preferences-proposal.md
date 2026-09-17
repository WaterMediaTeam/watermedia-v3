# Legacy preference review

These items were found in the old MEMORIES.md. They are proposals for the owner to accept or discard, not active instructions. AGENTS.md remains authoritative. Code facts, rename history, build status and duplicated documentation were removed from memory rather than preserved here.

| Topic | Legacy preference to review |
| --- | --- |
| Structure | During the v3 beta, aggressively inline single-use methods. Prefer records for immutable carriers and sealed interfaces for small data hierarchies. Current AGENTS.md has more selective extraction criteria; those remain in force. |
| Integration | Keep WaterMedia independent of Minecraft/Mojang APIs; consumers supply host adapters and mixins. Do not restore Blaze3DEngine. |
| Graphics direction | Prioritize Vulkan development; treat OpenGL as secondary. Keep graphics backend decisions in the rendering layer. |
| Audio | Treat Java Sound as an explicit backend choice; do not automatically switch between OpenAL and Java Sound based on readiness. |
| Future APIs | Retain PNG/GIF chunk writing APIs for planned APNG writers. Do not add an external codec registry or separate BlockCompressor API. |
| Synchronization | Do not reintroduce a second ghost clock/player, speed nudges, generic Bridge types, or per-control Impl hooks without reevaluating the previous design decision. |
| Graphics APIs | Do not add a permanent GL texture-name pool or test-only graphics implementation. Keep the codec path free of AWT, Batik and JSVG. |
| UI naming | Preserve the retained Element/Group/Parent vocabulary and draw elements through Canvas. |
| Logging | Use class-name markers and shared WaterMedia.LOGGER; platform INFO summarizes resolution, DEBUG holds parsed details, WARN explains missing/unrecognized data. PlatformException messages should avoid repeating the automatically prefixed platform name. |
| Concurrency | The old blanket ban on Atomic types had exceptions for thread-name counters and synchronized-player revisions. Current AGENTS.md distinguishes truly atomic operations from compound state; keep that rule unless the owner requests otherwise. |
| Redistribution notices | Keep third-party notices scoped to redistributed dependencies and runtime-downloaded native programs, rather than host-provided libraries. Confirm legal requirements independently before adopting this as a blanket rule. |
| Draft licensing | Preserve the supported/unsupported/restricted environment split, apps-only embedding and distribution-channel choices only if the owner still wants them. The existing LICENSE-draft.md is the actual proposal; this review does not adopt or alter it. |

The old memory also claimed a records performance percentage and systematic shell-write corruption. Those historical claims were not independently established in this task and are not adopted as facts or instructions.
