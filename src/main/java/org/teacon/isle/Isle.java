package org.teacon.isle;

import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.client.Minecraft;
import net.minecraft.core.QuartPos;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.data.BuiltinRegistries;
import net.minecraft.resources.RegistryLookupCodec;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.OverworldBiomeBuilder;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSampler;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.Noises;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.world.ForgeWorldPreset;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.IExtensionPoint;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;

import javax.annotation.ParametersAreNonnullByDefault;
import java.text.DecimalFormat;
import java.util.function.Supplier;

@Mod("isle")
@Mod.EventBusSubscriber(modid = "isle", bus = Mod.EventBusSubscriber.Bus.MOD)
public final class Isle {
    private static final double ISLE_SCALE_SQ = Math.PI / 2.0;

    public Isle() {
        ModLoadingContext.get().registerExtensionPoint(IExtensionPoint.DisplayTest.class,
                () -> new IExtensionPoint.DisplayTest(() -> "ANY", (remote, isServer) -> true));
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> MinecraftForge.EVENT_BUS.addListener(IsleNoiseSampler::addDebugInfo));
    }

    @SubscribeEvent
    public static void levelType(RegistryEvent.Register<ForgeWorldPreset> event) {
        // The corresponding forge registries are not provided so we can only do this.
        Registry.register(Registry.CHUNK_GENERATOR, "isle:isle_noise", IsleNoiseChunkGenerator.CODEC);
        Registry.register(BuiltinRegistries.NOISE, IsleNoiseSampler.NOISE_KEY, IsleNoiseSampler.NOISE);
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
        private static final OverworldBiomeBuilder OVERWORLD_BIOME_BUILDER;

        private static final Climate.Parameter[] TEMPERATURES;
        private static final Climate.Parameter NORMAL_TEMPERATURE;

        private static final Climate.Parameter COAST_CONTINENTALNESS;
        private static final Climate.Parameter OCEAN_CONTINENTALNESS;
        private static final Climate.Parameter FAR_INLAND_CONTINENTALNESS;
        private static final Climate.Parameter DEEP_OCEAN_CONTINENTALNESS;

        private static final NormalNoise.NoiseParameters NOISE;
        private static final ResourceKey<NormalNoise.NoiseParameters> NOISE_KEY;

        static {
            OVERWORLD_BIOME_BUILDER = new OverworldBiomeBuilder();

            TEMPERATURES = OVERWORLD_BIOME_BUILDER.temperatures;
            NORMAL_TEMPERATURE = Climate.Parameter.span(TEMPERATURES[1], TEMPERATURES[TEMPERATURES.length - 2]);

            COAST_CONTINENTALNESS = OVERWORLD_BIOME_BUILDER.coastContinentalness;
            OCEAN_CONTINENTALNESS = OVERWORLD_BIOME_BUILDER.oceanContinentalness;
            FAR_INLAND_CONTINENTALNESS = OVERWORLD_BIOME_BUILDER.farInlandContinentalness;
            DEEP_OCEAN_CONTINENTALNESS = OVERWORLD_BIOME_BUILDER.deepOceanContinentalness;

            NOISE = new NormalNoise.NoiseParameters(-5, 1.0, 2.0, 1.0, 1.0, 1.0, 0.0);
            NOISE_KEY = ResourceKey.create(Registry.NOISE_REGISTRY, new ResourceLocation("isle:isle_noise"));
        }

        private final NormalNoise noise;
        private final float biomeRadiusSq;
        private final float biomeHalfTolerance;

        private final ThreadLocal<Cached> cachedThreadLocal = new ThreadLocal<>();

        public IsleNoiseSampler(NoiseSettings settings, boolean isNoiseCavesEnabled,
                                long seed, Registry<NormalNoise.NoiseParameters> registry,
                                WorldgenRandom.Algorithm algorithm, float biomeHalfTolerance, float biomeRadiusSq) {
            super(settings, isNoiseCavesEnabled, seed, registry, algorithm);
            var positionRandomFactory = algorithm.newInstance(seed).forkPositional();
            this.noise = Noises.instantiate(registry, positionRandomFactory, NOISE_KEY);
            this.biomeHalfTolerance = biomeHalfTolerance;
            this.biomeRadiusSq = biomeRadiusSq;
        }

        private static double trigMap(double input, double center, double ref, double bound) {
            // trigMap(0.5, ...) => center;
            // trigMap(0.0, ...), trigMap(1.0, ...) => ref;
            // trigMap(-Inf, ...), trigMap(+Inf, ...) => bound;
            var f = Math.acos(1 - 2 * Mth.inverseLerp(ref, center, bound));
            return Mth.lerp((1 - Math.cos(Mth.clamp((1 - 2 * input) * f, -Math.PI, Math.PI))) / 2, center, bound);
        }

        @Override
        public FlatNoiseData noiseData(int biomeX, int biomeZ, Blender blender) {
            var noiseX = (biomeX + this.getOffset(biomeX, 0, biomeZ));
            var noiseZ = (biomeZ + this.getOffset(biomeZ, biomeX, 0));

            var continentalness = this.getContinentalness(noiseX * 2.0, 0.0, noiseZ * 2.0);
            var weirdness = this.getWeirdness(noiseX * 2.0, 0.0, noiseZ * 2.0);
            var erosion = this.getErosion(noiseX * 2.0, 0.0, noiseZ * 2.0);

            var factors = this.getCachedFactors(biomeX, biomeZ);
            var factor = (factors.factorCircle + factors.continentalOffset + 0.5) / 2.0;

            var landMin = (COAST_CONTINENTALNESS.min() + COAST_CONTINENTALNESS.max()) / 2.0;
            var oceanMax = (OCEAN_CONTINENTALNESS.min() + OCEAN_CONTINENTALNESS.max()) / 2.0;

            var refMinMax = Mth.lerp(factor, oceanMax, landMin);

            var landMin2 = (COAST_CONTINENTALNESS.min() + 3.0 * COAST_CONTINENTALNESS.max()) / 4.0;
            var oceanMax2 = (3.0 * OCEAN_CONTINENTALNESS.min() + OCEAN_CONTINENTALNESS.max()) / 4.0;

            var clampedMin = Mth.clamp(refMinMax, DEEP_OCEAN_CONTINENTALNESS.min(), landMin2);
            var clampedMax = Mth.clamp(refMinMax, oceanMax2, FAR_INLAND_CONTINENTALNESS.max());

            var newWeirdness = (float) Mth.clampedMap(weirdness, -1.0, 1.0,
                    trigMap(factor, 0.0, -0.25, -1.0), trigMap(factor, 0.0, 0.25, 1.0));
            var newErosion = (float) Mth.clampedMap(erosion, -1.0, 1.0,
                    trigMap(factor, 0.8, 0.6, 0.0), 1.0);
            var newContinentalness = Climate.unquantizeCoord(Math.round(
                    Mth.clampedMap(Climate.quantizeCoord((float) continentalness),
                            OCEAN_CONTINENTALNESS.min(), FAR_INLAND_CONTINENTALNESS.max(), clampedMin, clampedMax)));

            var blockX = QuartPos.toBlock(biomeX);
            var blockZ = QuartPos.toBlock(biomeZ);
            var info = this.terrainInfo(blockX, blockZ,
                    newContinentalness, newWeirdness, (float) erosion, blender);

            return new FlatNoiseData(noiseX, noiseZ, newContinentalness, newWeirdness, newErosion, info);
        }

        @Override
        public Climate.TargetPoint target(int biomeX, int biomeY, int biomeZ, FlatNoiseData data) {
            var noiseX = data.shiftedX();
            var noiseY = biomeY + this.getOffset(biomeY, biomeZ, biomeX);
            var noiseZ = data.shiftedZ();

            var depth = this.computeBaseDensity(QuartPos.toBlock(biomeY), data.terrainInfo());
            var temperature = this.getTemperature(noiseX * 2.0, noiseY, noiseZ * 2.0);

            var factors = this.getCachedFactors(biomeX, biomeZ);
            var factor = (factors.factorCircle + factors.continentalOffset + 0.5) / 2.0;

            var newTemperature = (float) Mth.clampedMap(temperature, -1.0, 1.0,
                    trigMap(factor, 0.0, -0.075, -1.0), trigMap(factor, 0.0, 0.075, 1.0));
            if (factors.factorSquare <= 0) {
                newTemperature = Climate.unquantizeCoord(Mth.clamp(
                        Climate.quantizeCoord(newTemperature), NORMAL_TEMPERATURE.min(), NORMAL_TEMPERATURE.max()));
            }

            var humidity = this.getHumidity(noiseX * 2.0, noiseY, noiseZ * 2.0);
            var continentalness = data.continentalness();
            var weirdness = data.weirdness();
            var erosion = data.erosion();

            return Climate.target(newTemperature, (float) humidity,
                    (float) continentalness, (float) erosion, (float) depth, (float) weirdness);
        }

        @OnlyIn(Dist.CLIENT)
        private static void addDebugInfo(RenderGameOverlayEvent.Text event) {
            var mc = Minecraft.getInstance();
            var cameraEntity = mc.getCameraEntity();
            var server = mc.getSingleplayerServer();
            if (mc.options.renderDebug && server != null && cameraEntity != null) {
                var world = server.getLevel(cameraEntity.level.dimension());
                if (world != null) {
                    var sampler = world.getChunkSource().getGenerator().climateSampler();
                    if (sampler instanceof IsleNoiseSampler current) {
                        var biomeX = QuartPos.fromBlock(cameraEntity.getBlockX());
                        var biomeZ = QuartPos.fromBlock(cameraEntity.getBlockZ());
                        var factors = current.getCachedFactors(biomeX, biomeZ);

                        var f = new DecimalFormat("0.000");
                        var left = event.getLeft();

                        left.add("");
                        left.add("TeaCon Isle" +
                                " FC: " + f.format(factors.factorCircle) +
                                " FSQ: " + f.format(factors.factorSquare) +
                                " CO: " + f.format(factors.continentalOffset) +
                                " CF: " + f.format((factors.factorCircle + factors.continentalOffset + 0.5) / 2.0));
                    }
                }
            }
        }

        private Cached getCachedFactors(int biomeX, int biomeZ) {
            var result = this.cachedThreadLocal.get();
            if (result == null || result.biomeX != biomeX || result.biomeZ != biomeZ) {
                result = Cached.from(biomeX, biomeZ, this);
                this.cachedThreadLocal.set(result);
            }
            return result;
        }

        private record Cached(int biomeX, int biomeZ,
                              double continentalOffset, double factorCircle, double factorSquare) {
            private static Cached from(int biomeX, int biomeZ, IsleNoiseSampler parent) {
                var halfTol = parent.biomeHalfTolerance;
                var radiusSq = parent.biomeRadiusSq;

                var innerSq = (affectedRangeSq(biomeX, -halfTol) + affectedRangeSq(biomeZ, -halfTol)) * ISLE_SCALE_SQ;
                var outerSq = (affectedRangeSq(biomeX, halfTol) + affectedRangeSq(biomeZ, halfTol)) * ISLE_SCALE_SQ;
                var maxSq = Math.max(affectedRangeSq(biomeX, 0), affectedRangeSq(biomeZ, 0));
                var offset = parent.noise.getValue(biomeX, biomeZ, 0.0);

                return new Cached(biomeX, biomeZ,
                        offset, Mth.inverseLerp(radiusSq, innerSq, outerSq), 1 - maxSq / radiusSq);
            }
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

        public IsleNoiseChunkGenerator(Registry<NormalNoise.NoiseParameters> reg,
                                       BiomeSource source, long seed, IsleConfig config) {
            super(reg, source, seed, config.noiseSettings);
            this.config = config;
            var settings = config.noiseSettings.get();
            this.sampler = new IsleNoiseSampler(settings.noiseSettings(),
                    settings.isNoiseCavesEnabled(), seed, reg, settings.getRandomSource(),
                    Math.abs(config.tolerance / 8F), config.borderRange * config.borderRange / 64F);
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
