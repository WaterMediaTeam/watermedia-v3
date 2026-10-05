# MODLOADERS
Compile-only stubs of the Forge, NeoForge and Fabric classes referenced by the WATERMeDIA and WATERMeDIA Binaries bootstraps.
The stubs are never packaged; each loader provides the real classes at runtime.

# COMPATIBILITY
- Forge: `@Mod` and the static `FMLLoader.getDist()`
- NeoForge: `@Mod` and the FML 10+ `FMLLoader.getCurrent()` instance; older FML is reached through reflection
- Fabric: `ModInitializer`, `EnvType` and `FabricLoader`
