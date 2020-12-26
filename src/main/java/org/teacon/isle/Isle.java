package org.teacon.isle;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.util.registry.Registry;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.provider.OverworldBiomeProvider;
import net.minecraft.world.gen.ChunkGenerator;
import net.minecraft.world.gen.DimensionSettings;
import net.minecraft.world.gen.NoiseChunkGenerator;
import net.minecraft.world.gen.settings.DimensionStructuresSettings;
import net.minecraft.world.gen.settings.NoiseSettings;
import net.minecraft.world.gen.settings.ScalingSettings;
import net.minecraft.world.gen.settings.SlideSettings;
import net.minecraftforge.common.world.ForgeWorldType;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.ObfuscationReflectionHelper;

@Mod("isle")
@Mod.EventBusSubscriber(modid = "isle", bus = Mod.EventBusSubscriber.Bus.MOD)
public final class Isle {

    @SubscribeEvent
    public static void levelType(RegistryEvent.Register<ForgeWorldType> event) {
        event.getRegistry().register(new ForgeWorldType(Isle::createChunkGen).setRegistryName("isle:isle"));
    }

    private static ChunkGenerator createChunkGen(Registry<Biome> biomeReg, Registry<DimensionSettings> dimSettingsReg, long seed) {
        return new NoiseChunkGenerator(new OverworldBiomeProvider(seed, false, false, biomeReg), seed, Isle::createDimSetting);
    }

    private static DimensionSettings createDimSetting() {
        final DimensionStructuresSettings dimStructSettings = new DimensionStructuresSettings(true);
        final int height = 84;
        final ScalingSettings sampling = new ScalingSettings(32D, 4D, 80D, 160D);
        final SlideSettings topSlide = new SlideSettings(-30, 5, 1);
        final SlideSettings bottomSlide = new SlideSettings(-3000, 56, -46);
        final int horizontalSize = 4, verticalSize = 1, densityFactor = 0, densityOffset = 0;
        final boolean useSimplexSurfaceNoise = true, useRandomDensityOffset = true, useIslandNoise = true, isAmplified = false;
        final NoiseSettings noiseSettings = new NoiseSettings(height, sampling, topSlide, bottomSlide,
                horizontalSize, verticalSize, densityFactor, densityOffset,
                useSimplexSurfaceNoise, useRandomDensityOffset, useIslandNoise, isAmplified);
        final BlockState baseBlock = Blocks.STONE.getDefaultState();
        final BlockState baseFluid = Blocks.WATER.getDefaultState();
        final int bedrockStarts = -10, bedrockEnds = -10;
        final int seaLevel = 63;
        final boolean disableMobGen = true;
        try {
            return ObfuscationReflectionHelper.findConstructor(DimensionSettings.class, DimensionStructuresSettings.class,
                    NoiseSettings.class, BlockState.class, BlockState.class, int.class, int.class, int.class, boolean.class)
                    .newInstance(dimStructSettings, noiseSettings, baseBlock, baseFluid, bedrockStarts, bedrockEnds, seaLevel, disableMobGen);
        } catch (Exception e) {
            throw new RuntimeException("Failed to create our own dimension settings", e);
        }
    }
}
