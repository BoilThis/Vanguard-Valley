# Vanguard Valley

Destruction sandbox for Android. Kotlin, Jetpack Compose, OpenGL ES 2.0, and Jolt Physics on Android 11+ (`minSdk` 30).

## Build

Install Android SDK 36 and a JDK 17, then:

```bash
./gradlew :app:assembleDebug
./gradlew :app:testDebugUnitTest
```

Debug signing uses the SDK's default debug keystore. If `debug.keystore` is present in the repo root (AI Studio), that key is used instead. Release signing is applied only when `STORE_PASSWORD` is set and the upload keystore exists. `isMinifyEnabled` is on for release; Jolt JNI classes are kept.

The app id is `com.boilthis.vanguardvalley`. That replaces the old AI Studio id, so an existing install will not update in place.

## Layout

| Area | Where |
|---|---|
| Sim loop, weapons, missions | `engine/sim/SandboxSimulation.kt` |
| Structural graph, fracture, collapse | `engine/physics/StructuralWorld.kt` |
| Block and shard types | `engine/physics/StructuralTypes.kt` |
| Jolt bodies, or the custom integrator if the native library fails to load | `JoltPhysicsWorld.kt`, `StructuralWorld.stepShards` |
| Heightfield | `engine/terrain/ChunkedTerrain.kt` |
| GLES 2 renderer | `engine/render/ValleyRenderer.kt` |
| HUD | `ui/ValleyHudOverlay.kt` |

District geometry is still built in the `*Builder.kt` files. Mission contracts in `DemolitionOpsCatalog` must keep matching `targetTag` values on those blocks. `everyDemolitionOpsTargetIsPlacedInTheValley` fails if a tag disappears.

`StructuralWorld.allowJoltPhysics = false` before construction forces the custom integrator. `customIntegratorStepsAndFracturesWhenJoltIsDisabled` covers that path.

## Runtime notes

- The sim thread steps at 60 Hz. The Compose HUD publishes immediately when weapons, score, zone, or the banner change, and at 10 Hz for shard count and FPS.
- Dynamic meshes (shards, particles, tracers, aim rings) are triple-buffered. Draw code uses the float count on `RenderSnapshot`, not the array capacity.
- Sleeping chips are baked into the rubble mesh and dropped from the live shard pool so the 280 / 540 caps do not erase debris the player can still see. Large sleeping pieces stay simulated so they can still be stood on.

`public/assets/aistudio/` is reference captures. Gradle does not package it.
