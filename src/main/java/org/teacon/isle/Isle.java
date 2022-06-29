package org.teacon.isle;

import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.data.BuiltinRegistries;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.*;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraftforge.common.world.ForgeWorldPreset;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.IExtensionPoint;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import org.jetbrains.annotations.NotNull;

import javax.annotation.ParametersAreNonnullByDefault;

import static net.minecraft.world.level.levelgen.DensityFunctions.*;
import static net.minecraft.world.level.levelgen.NoiseRouterData.*;

@Mod("isle")
@Mod.EventBusSubscriber(modid = "isle", bus = Mod.EventBusSubscriber.Bus.MOD)
public final class Isle {
    private static final double ISLE_SCALE = Math.sqrt(Math.PI / 2.0);

    public Isle() {
        ModLoadingContext.get().registerExtensionPoint(IExtensionPoint.DisplayTest.class,
                () -> new IExtensionPoint.DisplayTest(() -> "ANY", (remote, isServer) -> true));
    }

    @SubscribeEvent
    public static void levelType(RegistryEvent.Register<ForgeWorldPreset> event) {
        // The corresponding forge registries are not provided so we can only do this.
        Registry.register(Registry.CHUNK_GENERATOR, "isle:isle_noise", IsleNoiseChunkGenerator.CODEC);
        Registry.register(Registry.DENSITY_FUNCTION_TYPES, new ResourceLocation("isle:distance"), Distance.IMPL_CODEC);
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

        return new IsleNoiseChunkGenerator(structure, noise, biomeSource, seed, new IsleConfig(border, tolerance));
    }

    @MethodsReturnNonnullByDefault
    @ParametersAreNonnullByDefault
    private record Distance(DensityFunction factor) implements DensityFunction {
        private static final Codec<Distance> IMPL_CODEC = DensityFunction
                .HOLDER_HELPER_CODEC.fieldOf("argument").xmap(Distance::new, Distance::factor).codec();

        @Override
        public double compute(FunctionContext context) {
            var centerX = context.blockX() + 0.5;
            var centerZ = context.blockZ() + 0.5;
            return this.factor.compute(context) * Math.sqrt(centerX * centerX + centerZ * centerZ);
        }

        @Override
        public void fillArray(double[] array, ContextProvider provider) {
            provider.fillAllDirectly(array, this);
        }

        @Override
        public DensityFunction mapAll(Visitor visitor) {
            return visitor.apply(new Distance(this.factor.mapAll(visitor)));
        }

        @Override
        public double minValue() {
            return Math.min(0, this.factor.minValue()) * Integer.MAX_VALUE;
        }

        @Override
        public double maxValue() {
            return Math.max(0, this.factor.maxValue()) * Integer.MAX_VALUE;
        }

        @Override
        public Codec<? extends DensityFunction> codec() {
            return IMPL_CODEC;
        }
    }

    @MethodsReturnNonnullByDefault
    @ParametersAreNonnullByDefault
    private static final class IsleNoiseChunkGenerator extends NoiseBasedChunkGenerator {
        public static final Codec<IsleNoiseChunkGenerator> CODEC = RecordCodecBuilder.create(i -> commonCodec(i).and(
                        i.group(RegistryOps.retrieveRegistry(Registry.NOISE_REGISTRY).forGetter(g -> g.noises),
                                BiomeSource.CODEC.fieldOf("biome_source").forGetter(g -> g.biomeSource),
                                Codec.LONG.fieldOf("seed").stable().forGetter(g -> g.seed),
                                IsleConfig.CODEC.fieldOf("settings").forGetter(g -> g.config)))
                .apply(i, i.stable(IsleNoiseChunkGenerator::new)));

        private final IsleConfig config;

        @SuppressWarnings("deprecation")
        private static Holder.Direct<NoiseGeneratorSettings> initSettings(int borderRange, int tolerance) {
            var registry = BuiltinRegistries.NOISE_GENERATOR_SETTINGS;

            var originalSettings = registry.getOrThrow(NoiseGeneratorSettings.OVERWORLD);
            var originalRouter = originalSettings.noiseRouter();

            // step 0: initial continent density function
            var initialContinent = initContinent(borderRange, tolerance);

            // step 1: copied from NoiseRouterData.bootstrap
            var bootstrapDensityFunction2 = flatCache(initialContinent);
            var bootstrapDensityFunction3 = getFunction(EROSION);
            var bootstrapDensityFunction4 = getFunction(RIDGES);
            var bootstrapDensityFunction5 = noise(BuiltinRegistries.NOISE.getHolderOrThrow(Noises.JAGGED), 1500, 0);
            var bootstrapDensityFunction6 = splineWithBlending(bootstrapDensityFunction2, bootstrapDensityFunction3, bootstrapDensityFunction4, TerrainShaperSpline.SplineType.OFFSET, -0.81, 2.5, blendOffset());
            var bootstrapDensityFunction7 = splineWithBlending(bootstrapDensityFunction2, bootstrapDensityFunction3, bootstrapDensityFunction4, TerrainShaperSpline.SplineType.FACTOR, 0.0D, 8.0D, BLENDING_FACTOR);
            var bootstrapDensityFunction8 = add(yClampedGradient(-64, 320, 1.5, -1.5), bootstrapDensityFunction6);
            var bootstrapSlopedCheese = slopedCheese(bootstrapDensityFunction2, bootstrapDensityFunction3, bootstrapDensityFunction4, bootstrapDensityFunction7, bootstrapDensityFunction8, bootstrapDensityFunction5);

            // step 2: copied from NoiseRouterData.overworldWithNewCaves
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
                    originalRouter.temperature(),
                    originalRouter.vegetation(),
                    bootstrapDensityFunction2,
                    originalRouter.erosion(),
                    bootstrapDensityFunction8,
                    originalRouter.ridges(),
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

        @NotNull
        private static DensityFunction initContinent(int borderRange, int tolerance) {
            var slope = -0.16 / tolerance;
            var intercept = -0.15 - slope * (borderRange / 2 / ISLE_SCALE);
            var lower = add(new Distance(constant(slope)), constant(intercept - 0.08)).clamp(-1.0, 0.3);
            var upper = add(new Distance(constant(slope)), constant(intercept + 0.08)).clamp(-0.455, 1.0);
            return lerp(mul(add(shiftA(BuiltinRegistries.NOISE.getHolderOrThrow(
                    Noises.CONTINENTALNESS)), constant(1.2)), constant(1.0 / 2.2)).clamp(0.0, 1.0), lower, upper);
        }

        public IsleNoiseChunkGenerator(Registry<StructureSet> structReg,
                                       Registry<NormalNoise.NoiseParameters> noiseParamReg,
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
