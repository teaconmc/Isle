package org.teacon.isle;

import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.data.BuiltinRegistries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.synth.NormalNoise.NoiseParameters;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.world.ForgeWorldPreset;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.IExtensionPoint;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;

import javax.annotation.ParametersAreNonnullByDefault;
import java.util.List;

import static net.minecraft.world.level.levelgen.DensityFunctions.*;
import static net.minecraft.world.level.levelgen.NoiseRouterData.*;

@Mod("isle")
@Mod.EventBusSubscriber(modid = "isle", bus = Mod.EventBusSubscriber.Bus.MOD)
public final class Isle {
    private static final ResourceKey<NoiseParameters> VEGETATION = createNoiseKey("vegetation");
    private static final ResourceKey<NoiseParameters> TEMPERATURE = createNoiseKey("temperature");
    private static final ResourceKey<NoiseParameters> CONTINENTALNESS = createNoiseKey("continentalness");

    public Isle() {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            MinecraftForge.EVENT_BUS.addListener(Debug::addInfo);
        }
        ModLoadingContext.get().registerExtensionPoint(IExtensionPoint.DisplayTest.class,
                () -> new IExtensionPoint.DisplayTest(() -> "ANY", (remote, isServer) -> true));
    }

    @SubscribeEvent
    public static void levelType(RegistryEvent.Register<ForgeWorldPreset> event) {
        // The corresponding forge registries are not provided so we can only do this.
        Registry.register(Registry.CHUNK_GENERATOR, "isle:isle_noise", IsleChunkGenerator.CODEC);
        Registry.register(Registry.DENSITY_FUNCTION_TYPES, "isle:smooth", Smooth.MAP_CODEC.codec());
        Registry.register(Registry.DENSITY_FUNCTION_TYPES, "isle:distance", Distance.MAP_CODEC.codec());
        Registry.register(BuiltinRegistries.NOISE, VEGETATION, new NoiseParameters(-7, 1, 1, 0, 0, 0, 0));
        Registry.register(BuiltinRegistries.NOISE, TEMPERATURE, new NoiseParameters(-9, 1.5, 0, 1, 0, 0, 0));
        Registry.register(BuiltinRegistries.NOISE, CONTINENTALNESS, new NoiseParameters(-5, 1, 2, 2, 2, 1, 1, 1, 1));
        event.getRegistry().register(new ForgeWorldPreset(Isle::createChunkGen).setRegistryName("isle:isle"));
    }

    private static ChunkGenerator createChunkGen(RegistryAccess access, long seed, String settingsString) {
        var noise = access.registryOrThrow(Registry.NOISE_REGISTRY);
        var biome = access.registryOrThrow(Registry.BIOME_REGISTRY);
        var structure = access.registryOrThrow(Registry.STRUCTURE_SET_REGISTRY);

        var settings = settingsString.isEmpty() ? new JsonObject() : GsonHelper.parse(settingsString);
        var biomeSource = MultiNoiseBiomeSource.Preset.OVERWORLD.biomeSource(biome);

        var border = GsonHelper.getAsInt(settings, "border", 512);
        var tolerance = GsonHelper.getAsInt(settings, "tolerance", 16);

        return new IsleChunkGenerator(structure, noise, biomeSource, seed, new IsleConfig(border, tolerance));
    }

    private static ResourceKey<NoiseParameters> createNoiseKey(String path) {
        return ResourceKey.create(Registry.NOISE_REGISTRY, new ResourceLocation("isle", path));
    }

    @MethodsReturnNonnullByDefault
    @ParametersAreNonnullByDefault
    private static final class Debug {
        private static void addInfo(RenderGameOverlayEvent.Text event) {
            var mc = Minecraft.getInstance();
            var camera = mc.getCameraEntity();
            var server = mc.getSingleplayerServer();
            if (mc.options.renderDebug && server != null && camera != null) {
                var level = server.getLevel(camera.level.dimension());
                if (level != null && level.getChunkSource().getGenerator() instanceof IsleChunkGenerator generator) {
                    var config = generator.config;
                    var normalizedX = Math.abs(camera.getX() * 2 / config.borderRange);
                    var normalizedZ = Math.abs(camera.getZ() * 2 / config.borderRange);
                    var normalizedOffset = (double) config.tolerance / config.borderRange;
                    var cmin = Distance.interpolate(normalizedX, normalizedZ, normalizedOffset, -0.15);
                    var cmax = Distance.interpolate(normalizedX, normalizedZ, -normalizedOffset, -0.15);
                    event.getLeft().addAll(List.of("", String.format("[Isle] CMIN: %.5f CMAX: %.5f", cmin, cmax)));
                }
            }
        }
    }

    @MethodsReturnNonnullByDefault
    @ParametersAreNonnullByDefault
    private record Smooth(DensityFunction argument) implements DensityFunction {
        private static final MapCodec<Smooth> MAP_CODEC = DensityFunction
                .HOLDER_HELPER_CODEC.fieldOf("argument").xmap(Smooth::new, Smooth::argument);

        @Override
        public double compute(FunctionContext context) {
            var x = context.blockX();
            var y = context.blockY();
            var z = context.blockZ();
            var d1 = this.argument.compute(new SinglePointContext(x - 1, y, z - 1));
            var d2 = this.argument.compute(new SinglePointContext(x - 1, y, z));
            var d3 = this.argument.compute(new SinglePointContext(x - 1, y, z + 1));
            var d4 = this.argument.compute(new SinglePointContext(x, y, z - 1));
            var d5 = this.argument.compute(new SinglePointContext(x, y, z));
            var d6 = this.argument.compute(new SinglePointContext(x, y, z + 1));
            var d7 = this.argument.compute(new SinglePointContext(x + 1, y, z - 1));
            var d8 = this.argument.compute(new SinglePointContext(x + 1, y, z));
            var d9 = this.argument.compute(new SinglePointContext(x + 1, y, z + 1));
            return (d1 + d2 + d3 + d4 + d5 + d6 + d7 + d8 + d9) / 9;
        }

        @Override
        public void fillArray(double[] array, ContextProvider provider) {
            provider.fillAllDirectly(array, this);
        }

        @Override
        public DensityFunction mapAll(Visitor visitor) {
            return visitor.apply(new Smooth(this.argument.mapAll(visitor)));
        }

        @Override
        public double minValue() {
            return this.argument.minValue();
        }

        @Override
        public double maxValue() {
            return this.argument.maxValue();
        }

        @Override
        public Codec<? extends DensityFunction> codec() {
            return MAP_CODEC.codec();
        }
    }

    @MethodsReturnNonnullByDefault
    @ParametersAreNonnullByDefault
    private record Distance(double borderSize, double offset, double halfPoint) implements DensityFunction {
        private static final MapCodec<Distance> MAP_CODEC = RecordCodecBuilder.mapCodec(b -> b.group(
                        Codec.DOUBLE.fieldOf("border_size").stable().forGetter(Distance::borderSize),
                        Codec.DOUBLE.fieldOf("distance_offset").stable().forGetter(Distance::offset),
                        Codec.DOUBLE.fieldOf("half_point").stable().forGetter(Distance::halfPoint))
                .apply(b, Distance::new));

        private static final double ISLE_SCALE_SQ = Math.PI / 2.0;

        private static double interpolate(double normalizedX, double normalizedZ,
                                          double normalizedOffset, double halfPoint) {
            var normalizedLength = Math.sqrt(Mth.square(normalizedX) + Mth.square(normalizedZ));
            var factorHalfSq = Mth.square(Math.max(0, normalizedLength + normalizedOffset)) * ISLE_SCALE_SQ - 1;
            if (factorHalfSq > 0) {
                var factorRange = 1 - Math.max(0, Math.max(normalizedX, normalizedZ) + normalizedOffset);
                if (factorRange > 0) {
                    return Mth.lerp(factorHalfSq / (factorHalfSq + Mth.square(factorRange)), halfPoint, -1);
                }
                return -1;
            }
            return Mth.lerp(-factorHalfSq, halfPoint, 1);
        }

        @Override
        public double compute(FunctionContext context) {
            var normalizedX = Math.abs(2 * context.blockX() + 1) / this.borderSize;
            var normalizedZ = Math.abs(2 * context.blockZ() + 1) / this.borderSize;
            return interpolate(normalizedX, normalizedZ, 2 * this.offset / this.borderSize, this.halfPoint);
        }

        @Override
        public void fillArray(double[] array, ContextProvider provider) {
            provider.fillAllDirectly(array, this);
        }

        @Override
        public DensityFunction mapAll(Visitor visitor) {
            return visitor.apply(this);
        }

        @Override
        public double minValue() {
            return -1;
        }

        @Override
        public double maxValue() {
            return 1;
        }

        @Override
        public Codec<? extends DensityFunction> codec() {
            return MAP_CODEC.codec();
        }
    }

    @MethodsReturnNonnullByDefault
    @ParametersAreNonnullByDefault
    private static final class IsleChunkGenerator extends NoiseBasedChunkGenerator {
        public static final Codec<IsleChunkGenerator> CODEC = RecordCodecBuilder.create(i -> commonCodec(i).and(
                        i.group(RegistryOps.retrieveRegistry(Registry.NOISE_REGISTRY).forGetter(g -> g.noises),
                                BiomeSource.CODEC.fieldOf("biome_source").forGetter(g -> g.biomeSource),
                                Codec.LONG.fieldOf("seed").stable().forGetter(g -> g.seed),
                                IsleConfig.CODEC.fieldOf("settings").forGetter(g -> g.config)))
                .apply(i, i.stable(IsleChunkGenerator::new)));
        private final IsleConfig config;

        @SuppressWarnings("deprecation")
        private static Holder.Direct<NoiseGeneratorSettings> initSettings(int borderRange, int tolerance) {
            var registry = BuiltinRegistries.NOISE_GENERATOR_SETTINGS;

            var originalSettings = registry.getOrThrow(NoiseGeneratorSettings.OVERWORLD);
            var originalRouter = originalSettings.noiseRouter();

            // step 0: initial ridges and continent density function
            var initialRidges = mul(
                    max(new Distance(borderRange, 0, 0), constant(0.05)), getFunction(RIDGES));
            var initialContinent = initContinent(
                    new Distance(borderRange, tolerance * 0.5, -0.15),
                    new Distance(borderRange, tolerance * -0.5, -0.15));

            // step 1: copied from NoiseRouterData.bootstrap
            var bootstrapDensityFunction2 = flatCache(initialContinent);
            var bootstrapDensityFunction3 = flatCache(getFunction(EROSION));
            var bootstrapDensityFunction4 = flatCache(initialRidges);
            var bootstrapDensityFunction5 = noise(BuiltinRegistries.NOISE.getHolderOrThrow(Noises.JAGGED), 1500, 0);
            var bootstrapDensityFunction6 = splineWithBlending(bootstrapDensityFunction2, bootstrapDensityFunction3, bootstrapDensityFunction4, TerrainShaperSpline.SplineType.OFFSET, -0.81, 2.5, blendOffset());
            var bootstrapDensityFunction7 = splineWithBlending(bootstrapDensityFunction2, bootstrapDensityFunction3, bootstrapDensityFunction4, TerrainShaperSpline.SplineType.FACTOR, 0.0, 8.0, BLENDING_FACTOR);
            var bootstrapDensityFunction8 = add(yClampedGradient(-64, 320, 1.5, -1.5), bootstrapDensityFunction6);
            var bootstrapSlopedCheese = slopedCheese(bootstrapDensityFunction2, bootstrapDensityFunction3, bootstrapDensityFunction4, bootstrapDensityFunction7, bootstrapDensityFunction8, bootstrapDensityFunction5);

            // step 2: copied from NoiseRouterData.overworldWithNewCaves
            var overworldWithNewCavesDensityFunction4 = getFunction(SHIFT_X);
            var overworldWithNewCavesDensityFunction5 = getFunction(SHIFT_Z);
            var overworldWithNewCavesDensityFunction6 = shiftedNoise2d(overworldWithNewCavesDensityFunction4, overworldWithNewCavesDensityFunction5, 0.25, BuiltinRegistries.NOISE.getHolderOrThrow(TEMPERATURE));
            var overworldWithNewCavesDensityFunction7 = shiftedNoise2d(overworldWithNewCavesDensityFunction4, overworldWithNewCavesDensityFunction5, 0.25, BuiltinRegistries.NOISE.getHolderOrThrow(VEGETATION));
            var overworldWithNewCavesDensityFunction10 = noiseGradientDensity(cache2d(bootstrapDensityFunction7), bootstrapDensityFunction8);
            var overworldWithNewCavesDensityFunction12 = min(bootstrapSlopedCheese, mul(constant(5.0), getFunction(ENTRANCES)));
            var overworldWithNewCavesDensityFunction13 = rangeChoice(bootstrapSlopedCheese, -1000000.0, 1.5625, overworldWithNewCavesDensityFunction12, underground(bootstrapSlopedCheese));
            var overworldWithNewCavesDensityFunction14 = min(postProcess(originalSettings.noiseSettings(), overworldWithNewCavesDensityFunction13), getFunction(NOODLE));

            // step 3: replace fields
            var newRouter = new NoiseRouterWithOnlyNoises(
                    originalRouter.barrierNoise(),
                    originalRouter.fluidLevelFloodednessNoise(),
                    originalRouter.fluidLevelSpreadNoise(),
                    originalRouter.lavaNoise(),
                    overworldWithNewCavesDensityFunction6,
                    overworldWithNewCavesDensityFunction7,
                    bootstrapDensityFunction2,
                    originalRouter.erosion(),
                    bootstrapDensityFunction8,
                    bootstrapDensityFunction4,
                    overworldWithNewCavesDensityFunction10,
                    overworldWithNewCavesDensityFunction14,
                    originalRouter.veinToggle(),
                    originalRouter.veinRidged(),
                    originalRouter.veinGap());
            var newSettings = new NoiseGeneratorSettings(
                    originalSettings.noiseSettings(),
                    originalSettings.defaultBlock(),
                    originalSettings.defaultFluid(),
                    newRouter,
                    originalSettings.surfaceRule(),
                    originalSettings.seaLevel(),
                    originalSettings.disableMobGeneration(),
                    originalSettings.aquifersEnabled(),
                    originalSettings.oreVeinsEnabled(),
                    originalSettings.useLegacyRandomSource());

            return new Holder.Direct<>(newSettings);
        }

        private static DensityFunction initContinent(Distance lower, Distance upper) {
            return flatCache(new Smooth(lerp(mul(constant(0.1), add(constant(5.0), shiftA(
                    BuiltinRegistries.NOISE.getHolderOrThrow(CONTINENTALNESS)))).clamp(0.0, 1.0), lower, upper)));
        }

        public IsleChunkGenerator(Registry<StructureSet> structReg,
                                  Registry<NoiseParameters> noiseParamReg,
                                  BiomeSource source, long seed, IsleConfig isleConfig) {
            super(structReg, noiseParamReg, source, seed, initSettings(isleConfig.borderRange, isleConfig.tolerance));
            this.config = isleConfig;
        }

        @Override
        protected Codec<? extends ChunkGenerator> codec() {
            return CODEC;
        }
    }

    @MethodsReturnNonnullByDefault
    @ParametersAreNonnullByDefault
    private record IsleConfig(int borderRange, int tolerance) {
        public static final MapCodec<IsleConfig> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                        Codec.INT.fieldOf("border").stable().forGetter(IsleConfig::borderRange),
                        Codec.INT.fieldOf("tolerance").stable().forGetter(IsleConfig::tolerance))
                .apply(i, i.stable(IsleConfig::new)));
    }
}
