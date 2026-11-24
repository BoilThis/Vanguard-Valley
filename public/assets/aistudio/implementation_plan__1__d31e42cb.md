# Mega-City Deformation & Destruction Tech Spec Plan

This plan defines the structure and contents of a reusable engineering specification document that captures the hybrid 3D deformation and destruction technology built in Crater Lab—and specifies how to scale it to a multi-district, chunked mega-city game on the same Kotlin, Jetpack Compose, and OpenGL ES 2.0 stack.

## User Review & Critical Decisions

> [!IMPORTANT]
> Confirmed selections from Phase 1 will govern the deliverable produced on approval:
> - **Deliverable Format**: A standalone, copy-ready technical specification document (authored as a clean Markdown reference artifact/document without altering the running game code).
> - **Target Scale**: A larger chunked world supporting sprawling mega-city districts, tall multi-story buildings, and long-range destruction.
> - **Technology Stack**: Same Kotlin + Jetpack Compose + OpenGL ES 2.0 architecture, using version-stamped snapshot handoff and procedural texture atlasing.

---

## 1. Overview & Core Concept

- **What It Delivers**: A complete, self-contained technical blueprint describing both (a) the exact algorithms, vertex formats, shaders, and physics rules powering Crater Lab today, and (b) the spatial chunking, dirty-chunk meshing, shard sleeping/pooling, and OpenGL ES 2.0 VBO caching patterns required to scale the same engine from a single `56m × 32m` valley into a multi-district mega-city.
- **Target Audience**: Yourself or an AI coding agent bootstrapping a brand-new Kotlin/Compose/GLES2 project from scratch.
- **Key Value**: Lets you paste one self-contained specification into a new project to reproduce stable stacked buildings, real-time Voronoi structural shattering, soil cratering, and crisp procedural architecture without re-discovering edge cases (such as resting-frame stability, shard vertex explosion, or thread-safe GL uploads).

---

## 2. Deliverable Structure & Technical Depth

The tech spec document will be organized into four copy-ready engineering sections:

1. **System Pipeline & Concurrency Model**
   - Unidirectional simulation loop (`16ms` coroutine step → immutable render snapshot → `GLSurfaceView` continuous draw thread) so Jetpack Compose UI and OpenGL rendering never contend on mutable physics arrays.
   - Coordinate conventions, gravity/ballistics integration, sub-stepped projectile collision, and camera-relative 3D character traversal across deformed terrain and fallen rubble.

2. **Terrain Deformation & Structural Destruction Math**
   - **Deformable Heightmap Ground**: Analytic baseline surface sculpting, bilinear `groundSurfaceY` sampling, cosine crater bowl excavation with sinusoidal rim uplift, scorch-intensity tracking, and iterative angle-of-repose soil relaxation.
   - **3D Voronoi Polyhedra Buildings**: Closed convex polyhedron representation (`localFaces` + center + Euler orientation + triplanar `uvOrigin`), multi-tier structural assembly (pillars, slabs, cornices, glass curtain towers), 3D half-space plane clipping (`clipPolyhedronHalfSpace`) with sorted cap-polygon reconstruction, and automatic foundation-undermining collapse detection whenever a crater lowers the heightmap below a static block's footprint.
   - **Stability Rules & Invariants**: Proportional footprint insets and overlap thresholds so thin architectural trims, bridges, and cornices remain `0 shards` at rest until disturbed.

3. **OpenGL ES 2.0 Rendering & Procedural Material Atlas**
   - **13-Float Interleaved Lit Vertex Format** (`[x, y, z, nx, ny, nz, r, g, b, a, u, v, tileIndex]`) + 7-float debug/trail line buffer.
   - **Procedural 4×2 Material Atlas (`1024×512`)**: Single-draw-call rendering across 8 materials (concrete windows, brick masonry, timber planks, glass curtain, steel, asphalt, turf, sandstone) via shader UV wrapping (`fract(vUV)` with edge-bleed inset) and triplanar world-space UV projection so freshly fractured Voronoi faces immediately inherit continuous building textures.

4. **Scaling Blueprint: From Single Basin to Chunked Mega-City**
   - **World Chunk Grid (`Chunk(cx, cz)`)**: Partitioning heightmap tiles (`32×32` cells per chunk with 1-vertex shared seam stitching) and Voronoi structures into spatial chunks around the active camera/player radius.
   - **Dirty-Chunk Remeshing & Per-Chunk GL Buffers**: Replacing the single full-world mesh version counter with per-chunk `version` IDs and GPU buffers so an explosion only rebuilds the 1–4 touched chunks instead of the entire city.
   - **Shard Lifecycle, Sleeping & Budget Pools**: Settling resting shards back into static obstacles (or heightmap rubble decals), capped active dynamic shard pools, and distance-based LOD/culling for tall skyscrapers across distant districts.

---

## 3. Key Architectural Decisions & Trade-Offs

- **Decision 1: Copy-Ready Markdown Tech Spec vs. Live Code Refactor**
  - *Chosen Approach*: Produce a standalone, well-structured engineering specification document without modifying the existing Crater Lab game codebase.
  - *Why*: Keeps the verified, playable Crater Lab build (`versionCode = 10`) safe while giving you a clean prompt/blueprint for your next game repository.
  - *Alternatives Considered*: Refactoring Crater Lab directly into a chunked engine right now (higher risk, outside what was requested).

- **Decision 2: Chunked Heightmap + Spatial Hash Voronoi vs. Pure Voxels**
  - *Chosen Approach*: Retain the hybrid Heightmap + Convex Voronoi architecture, scaled via `32×32` spatial chunks and per-chunk VBO caches.
  - *Why*: Voxels lose sharp architectural lines and crisp brick/window silhouettes unless resolution is huge; hybrid Voronoi + Heightmap preserves crisp skyscrapers, realistic slanted rubble shards, and smooth terrain hills at high mobile framerates on OpenGL ES 2.0.
  - *Alternatives Considered*: Uniform 3D voxel grids or marching cubes everywhere (higher memory and triangle overhead for flat walls and roads).

---

## 4. Target System Architecture (Tech Spec Diagram)

```text
┌─────────────────────────────────────────────────────────────────────────┐
│                       GAMEPLAY & SIMULATION LAYER                       │
│  Fixed-Step Coroutine Loop ─▶ Ballistics / Player / District AI         │
│         │                                                               │
│         ▼                                                               │
│  ┌───────────────────────────────────────────────────────────────────┐  │
│  │               CHUNKED HYBRID DESTRUCTION ENGINE                   │  │
│  │                                                                   │  │
│  │  ┌─────────────────────────┐    ┌──────────────────────────────┐  │  │
│  │  │ Spatial Chunk Grid      │    │ Voronoi Building Registry    │  │  │
│  │  │ [Chunk (cx, cz)]        │◀──▶│ - Static Structural Polyhedra│  │  │
│  │  │ - 32x32 Heightmap       │    │ - Half-Space 3D Clipper      │  │  │
│  │  │ - Shared Border Verts   │    │ - Sleep/Pool Dynamic Shards  │  │  │
│  │  │ - Scorch & Soil Relax   │    │ - Crater Undermine Detector  │  │  │
│  │  └───────────┬─────────────┘    └──────────────┬───────────────┘  │  │
│  │              └────────────────┬────────────────┘                  │  │
│  │                               ▼                                   │  │
│  │                Dirty-Chunk Mesh Cache (Per-Chunk Version)         │  │
│  └───────────────────────────────┬───────────────────────────────────┘  │
└──────────────────────────────────┼──────────────────────────────────────┘
                                   │ Lock-Free Snapshot Handoff
                                   ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                    OPENGL ES 2.0 RENDERER + COMPOSE HUD                 │
│  - Per-Chunk Static FloatBuffers (Only re-uploaded when chunk dirty)    │
│  - Dynamic Batch Buffer (Player, Projectiles, Particles, Active Shards) │
│  - Single-Bind 1024x512 Procedural 8-Tile Material Atlas + Triplanar UV │
│  - Jetpack Compose Overlay (Joystick, Ordnance Dock, District Minimap)  │
└─────────────────────────────────────────────────────────────────────────┘
```

### Verification Plan Upon Approval
1. Write the complete, copy-ready technical specification document as a user-facing Markdown artifact (no changes to Android source or `app/build.gradle.kts`).
2. Cross-check every formula, constant, vertex attribute, and shader detail against the live Crater Lab source code so the spec is 100% accurate and immediately usable in a new project.
