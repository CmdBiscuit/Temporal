package com.temporal;

import com.temporal.client.TemporalClient;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;

@Mod(Temporal.MOD_ID)
public final class Temporal {
    public static final String MOD_ID = "temporal";

    // FMLJavaModLoadingContext.get(): passing it to the constructor instead only works from Forge 47.3.10.
    @SuppressWarnings("removal")
    public Temporal() {
        // Client-side only: on a dedicated server Temporal loads and does nothing.
        if (FMLEnvironment.dist == Dist.CLIENT) {
            TemporalClient.init(FMLJavaModLoadingContext.get().getModEventBus());
        }
    }

    /** {@code temporal:path}, through the constructor: Forge backported 1.21's factory methods only in 47.3. */
    @SuppressWarnings("removal")
    public static ResourceLocation id(String path) {
        return new ResourceLocation(MOD_ID, path);
    }
}
