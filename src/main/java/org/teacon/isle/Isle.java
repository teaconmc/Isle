package org.teacon.isle;

import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.Pair;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.core.QuartPos;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.RegistryLookupCodec;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.OverworldBiomeBuilder;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraftforge.common.world.ForgeWorldPreset;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.IExtensionPoint;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.ParametersAreNonnullByDefault;
import java.util.function.Supplier;

@Mod("isle")
@Mod.EventBusSubscriber(modid = "isle", bus = Mod.EventBusSubscriber.Bus.MOD)
public final class Isle {
    private static final double ISLE_SCALE_SQ = Math.PI / 2.0;

    public Isle() {
        ModLoadingContext.get().registerExtensionPoint(IExtensionPoint.DisplayTest.class,
                () -> new IExtensionPoint.DisplayTest(() -> "ANY", (remote, isServer) -> true));
    }

    @SubscribeEvent
    public static void levelType(RegistryEvent.Register<ForgeWorldPreset> event) {
        // The corresponding forge registry is not provided so we can only do this.
        Registry.register(Registry.CHUNK_GENERATOR, "isle:isle_noise", IsleNoiseChunkGenerator.CODEC);
        event.getRegistry().register(new ForgeWorldPreset(Isle::createChunkGen).setRegistryName("isle:isle"));
    }

    private static ChunkGenerator createChunkGen(RegistryAccess access, long seed, String settingsString) {
        var gen = access.registryOrThrow(Registry.NOISE_GENERATOR_SETTINGS_REGISTRY);
        var noise = access.registryOrThrow(Registry.NOISE_REGISTRY);
        var biome = access.registryOrThrow(Registry.BIOME_REGISTRY);

        var settings = settingsString.isEmpty() ? new JsonObject() : GsonHelper.parse(settingsString);
        var biomeSource = MultiNoiseBiomeSource.Preset.OVERWORLD.biomeSource(biome);
        var config = new IsleConfig(
                GsonHelper.getAsInt(settings, "border", 512),
                GsonHelper.getAsInt(settings, "tolerance", 16),
                () -> gen.getOrThrow(NoiseGeneratorSettings.OVERWORLD));

        return new IsleNoiseChunkGenerator(noise, biomeSource, seed, config);
    }

    private static double affectedRangeSq(double biomeCoordinate, double tolerance) {
        var affected = Math.max(0, Math.abs(0.5 + biomeCoordinate) + tolerance);
        return affected * affected;
    }

    @MethodsReturnNonnullByDefault
    @ParametersAreNonnullByDefault
    private static final class IsleNoiseSampler extends NoiseSampler {
        private static final OverworldBiomeBuilder OVERWORLD_BIOME_BUILDER = new OverworldBiomeBuilder();

        private static final Climate.Parameter[] ORIGINAL_TEMPERATURES = OVERWORLD_BIOME_BUILDER.temperatures;
        private static final Climate.Parameter ORIGINAL_COAST_CONTINENTALNESS = OVERWORLD_BIOME_BUILDER.coastContinentalness;
        private static final Climate.Parameter ORIGINAL_OCEAN_CONTINENTALNESS = OVERWORLD_BIOME_BUILDER.oceanContinentalness;
        private static final Climate.Parameter ORIGINAL_FAR_INLAND_CONTINENTALNESS = OVERWORLD_BIOME_BUILDER.farInlandContinentalness;
        private static final Climate.Parameter ORIGINAL_DEEP_OCEAN_CONTINENTALNESS = OVERWORLD_BIOME_BUILDER.deepOceanContinentalness;

        private static final Climate.Parameter LAND_CONTINENTALNESS = Climate.Parameter.span(ORIGINAL_COAST_CONTINENTALNESS, ORIGINAL_FAR_INLAND_CONTINENTALNESS);
        private static final Climate.Parameter OCEAN_CONTINENTALNESS = Climate.Parameter.span(ORIGINAL_DEEP_OCEAN_CONTINENTALNESS, ORIGINAL_OCEAN_CONTINENTALNESS);
        private static final Climate.Parameter NORMAL_TEMPERATURE = Climate.Parameter.span(ORIGINAL_TEMPERATURES[1], ORIGINAL_TEMPERATURES[ORIGINAL_TEMPERATURES.length - 2]);

        private final float biomeRadiusSq;
        private final float biomeHalfTolerance;

        public IsleNoiseSampler(NoiseSettings settings, boolean isNoiseCavesEnabled, long seed, Registry<NormalNoise.NoiseParameters> registry, WorldgenRandom.Algorithm algorithm, float biomeHalfTolerance, float biomeRadiusSq) {
            super(settings, isNoiseCavesEnabled, seed, registry, algorithm);
            this.biomeHalfTolerance = biomeHalfTolerance;
            this.biomeRadiusSq = biomeRadiusSq;
        }

        private long clampByReflection(long value, long start, long end) {
            var step = end - start;
            var offset = Math.floorMod(value - start, 2 * step);
            return offset >= step ? end + step - offset : start + offset;
        }

        private long clampSmoothly(long value, long start, long end) {
            var middle = (start + end) / 2.0;
            var factorExp = Math.exp(Mth.inverseLerp(value, middle, end));
            return Math.round(Mth.lerp((factorExp - 1) / (factorExp + 1), middle, end));
        }

        private long clamp(long value, long start, long end) {
            var factor = Mth.inverseLerp((double) value, OCEAN_CONTINENTALNESS.min(), LAND_CONTINENTALNESS.max());
            return Math.round(Mth.clampedLerp(start, end, factor));
        }

        @Override
        public FlatNoiseData noiseData(int biomeX, int biomeZ, Blender blender) {
            var noiseX = (biomeX + this.getOffset(biomeX, 0, biomeZ)) * 2.0;
            var noiseZ = (biomeZ + this.getOffset(biomeZ, biomeX, 0)) * 2.0;

            var oldContinentalness = (float) this.getContinentalness(noiseX * 4.0D, 0.0D, noiseZ * 4.0D);
            var weirdness = (float) this.getWeirdness(noiseX, 0.0D, noiseZ);
            var erosion = (float) this.getErosion(noiseX, 0.0D, noiseZ);

            var outerSq = (affectedRangeSq(biomeX, this.biomeHalfTolerance) + affectedRangeSq(biomeZ, this.biomeHalfTolerance)) * ISLE_SCALE_SQ;
            var innerSq = (affectedRangeSq(biomeX, -this.biomeHalfTolerance) + affectedRangeSq(biomeZ, -this.biomeHalfTolerance)) * ISLE_SCALE_SQ;

            var firstBaseline = (OCEAN_CONTINENTALNESS.max() + LAND_CONTINENTALNESS.min()) / 2.0;
            var secondBaseline = Mth.clampedMap(this.biomeRadiusSq, innerSq, outerSq, OCEAN_CONTINENTALNESS.min(), LAND_CONTINENTALNESS.max());
            var offset = Mth.clampedMap(Climate.quantizeCoord(oldContinentalness), OCEAN_CONTINENTALNESS.min(), LAND_CONTINENTALNESS.max(), firstBaseline, secondBaseline);

            var continentalness = Climate.unquantizeCoord(Math.round(offset));
            var terrainInfo = this.terrainInfo(QuartPos.toBlock(biomeX), QuartPos.toBlock(biomeZ), continentalness, weirdness, erosion, blender);

            return new FlatNoiseData(noiseX, noiseZ, continentalness, weirdness, erosion, terrainInfo);
        }

        @Override
        protected double getTemperature(double biomeX, double biomeY, double biomeZ) {
            var original = Climate.quantizeCoord((float) super.getTemperature(biomeX, biomeY, biomeZ));
            if (Math.max(affectedRangeSq(biomeX, 0), affectedRangeSq(biomeZ, 0)) >= biomeRadiusSq) {
                return Climate.unquantizeCoord(Mth.clamp(original, NORMAL_TEMPERATURE.min(), NORMAL_TEMPERATURE.max()));
            }
            return Climate.unquantizeCoord(original);
        }
    }

    @MethodsReturnNonnullByDefault
    @ParametersAreNonnullByDefault
    private static final class IsleNoiseChunkGenerator extends NoiseBasedChunkGenerator {
        public static final Codec<IsleNoiseChunkGenerator> CODEC = RecordCodecBuilder.create(i -> i.group(
                        RegistryLookupCodec.create(Registry.NOISE_REGISTRY).forGetter(g -> g.noises),
                        BiomeSource.CODEC.fieldOf("biome_source").forGetter(g -> g.biomeSource),
                        Codec.LONG.fieldOf("seed").stable().forGetter(g -> g.seed),
                        IsleConfig.CODEC.fieldOf("settings").forGetter(g -> g.config))
                .apply(i, i.stable(IsleNoiseChunkGenerator::new)));

        private final IsleConfig config;

        public IsleNoiseChunkGenerator(Registry<NormalNoise.NoiseParameters> reg, BiomeSource source, long seed, IsleConfig config) {
            super(reg, source, seed, config.noiseSettings);
            this.config = config;
            var settings = config.noiseSettings.get();
            this.sampler = new IsleNoiseSampler(settings.noiseSettings(),
                    settings.isNoiseCavesEnabled(), seed, reg, settings.getRandomSource(),
                    config.tolerance / 8F, config.borderRange * config.borderRange / 64F);
        }

        @Override
        protected Codec<? extends ChunkGenerator> codec() {
            return CODEC;
        }
    }

    @MethodsReturnNonnullByDefault
    @ParametersAreNonnullByDefault
    private record IsleConfig(int borderRange, int tolerance, Supplier<NoiseGeneratorSettings> noiseSettings) {
        public static final MapCodec<IsleConfig> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                        Codec.INT.fieldOf("border").stable().forGetter(IsleConfig::borderRange),
                        Codec.INT.fieldOf("tolerance").stable().forGetter(IsleConfig::tolerance),
                        NoiseGeneratorSettings.CODEC.fieldOf("noise_settings").forGetter(IsleConfig::noiseSettings))
                .apply(i, i.stable(IsleConfig::new)));
    }
}
