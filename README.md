# APSIS

A 2D orbital mechanics game. Build a rocket part by part, fly it on real
two-body physics, and work your way out through a solar system of planets,
moons and black holes.

Fuel and staging, time warp, docking, landing, parachutes, rovers and space
stations. Autopilot for ascent, docking, landing and booster recovery. A tech
tree to unlock, and a sandbox if you would rather skip it.

Orbits are solved analytically from the Keplerian elements rather than stepped,
so a trajectory stays exact at any time warp.

## Build

```sh
./build.sh
```

`aapt → javac → d8 → zipalign → apksigner`, driven by one shell script. No
Gradle, no NDK, no engine. The APK lands in `out/`.

## Requirements

Android 8.0 (API 26) or newer.

---

**DESIGNED BY MOHAMMAD SHADAB, CODED WITH CLAUDE**
