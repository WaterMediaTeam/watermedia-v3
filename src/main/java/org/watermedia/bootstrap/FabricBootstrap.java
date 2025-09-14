package org.watermedia.bootstrap;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.watermedia.WaterMedia;

public class FabricBootstrap implements ModInitializer {
    private static final String NAME = "Fabric";

    @Override
    public void onInitialize() {
        try {
            WaterMedia.start(NAME, null, FabricLoader.getInstance().getGameDir(), FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT);
        } catch (Exception e) {
            throw new RuntimeException("Failed starting " + WaterMedia.NAME + " for " + NAME + ": " + e.getMessage(), e);
        }
    }
}
