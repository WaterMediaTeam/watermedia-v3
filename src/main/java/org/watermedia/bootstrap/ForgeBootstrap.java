package org.watermedia.bootstrap;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLLoader;
import org.watermedia.WaterMedia;

@Mod(WaterMedia.ID)
public class ForgeBootstrap {
    private static final String NAME = "Forge";

    public ForgeBootstrap() {
        try {
            WaterMedia.start(NAME, null, null, FMLLoader.getDist().isClient());
        } catch (Exception e) {
            throw new RuntimeException("Failed starting " + WaterMedia.NAME + " for " + NAME + ": " + e.getMessage(), e);
        }
    }
}
