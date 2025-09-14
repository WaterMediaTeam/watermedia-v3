package org.watermedia.bootstrap;

import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLLoader;
import org.watermedia.WaterMedia;

@Mod(WaterMedia.ID)
public class NeoBootstrap {
    private static final String NAME = "NeoForge";

    public NeoBootstrap() {
        try {
            WaterMedia.start(NAME, null, null, FMLLoader.getDist().isClient());
        } catch (Exception e) {
            throw new RuntimeException("Failed starting " + WaterMedia.NAME + " for " + NAME +": " + e.getMessage(), e);
        }
    }
}
