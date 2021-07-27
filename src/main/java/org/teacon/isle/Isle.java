package org.teacon.isle;

import com.google.gson.JsonObject;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import mcp.MethodsReturnNonnullByDefault;
import net.minecraft.util.JSONUtils;
import net.minecraft.util.RegistryKey;
import net.minecraft.util.registry.Registry;
import net.minecraft.util.registry.RegistryLookupCodec;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.provider.BiomeProvider;
import net.minecraft.world.biome.provider.OverworldBiomeProvider;
import net.minecraft.world.gen.ChunkGenerator;
import net.minecraft.world.gen.DimensionSettings;
import net.minecraft.world.gen.IExtendedNoiseRandom;
import net.minecraft.world.gen.LazyAreaLayerContext;
import net.minecraft.world.gen.NoiseChunkGenerator;
import net.minecraft.world.gen.area.IArea;
import net.minecraft.world.gen.area.IAreaFactory;
import net.minecraft.world.gen.layer.AddBambooForestLayer;
import net.minecraft.world.gen.layer.AddIslandLayer;
import net.minecraft.world.gen.layer.AddMushroomIslandLayer;
import net.minecraft.world.gen.layer.AddSnowLayer;
import net.minecraft.world.gen.layer.BiomeLayer;
import net.minecraft.world.gen.layer.DeepOceanLayer;
import net.minecraft.world.gen.layer.EdgeBiomeLayer;
import net.minecraft.world.gen.layer.EdgeLayer;
import net.minecraft.world.gen.layer.HillsLayer;
import net.minecraft.world.gen.layer.IslandLayer;
import net.minecraft.world.gen.layer.Layer;
import net.minecraft.world.gen.layer.LayerUtil;
import net.minecraft.world.gen.layer.MixOceansLayer;
import net.minecraft.world.gen.layer.MixRiverLayer;
import net.minecraft.world.gen.layer.OceanLayer;
import net.minecraft.world.gen.layer.RareBiomeLayer;
import net.minecraft.world.gen.layer.RemoveTooMuchOceanLayer;
import net.minecraft.world.gen.layer.RiverLayer;
import net.minecraft.world.gen.layer.ShoreLayer;
import net.minecraft.world.gen.layer.SmoothLayer;
import net.minecraft.world.gen.layer.StartRiverLayer;
import net.minecraft.world.gen.layer.ZoomLayer;
import net.minecraft.world.gen.layer.traits.IAreaTransformer1;
import net.minecraft.world.gen.layer.traits.IDimOffset0Transformer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.common.world.ForgeWorldType;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ExtensionPoint;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.ObfuscationReflectionHelper;
import net.minecraftforge.fml.network.FMLNetworkConstants;
import org.apache.commons.lang3.tuple.Pair;

import javax.annotation.ParametersAreNonnullByDefault;
import java.util.List;
import java.util.Objects;
import java.util.function.LongFunction;

@Mod("isle")
@Mod.EventBusSubscriber(modid = "isle", bus = Mod.EventBusSubscriber.Bus.MOD)
public final class Isle {
    private static final float ISLE_SCALE_SQ = (float) Math.PI / 2F;

    public Isle() {
        ModLoadingContext.get().registerExtensionPoint(ExtensionPoint.DISPLAYTEST, () -> Pair.of(
                () -> FMLNetworkConstants.IGNORESERVERONLY, (serverVer, isDedicated) -> true));
    }

    @SubscribeEvent
    public static void levelType(RegistryEvent.Register<ForgeWorldType> event) {
        // The corresponding forge registry is not provided so we can only do this.
        Registry.register(Registry.BIOME_PROVIDER_CODEC, "isle:isle", IsleBiomeProvider.CODEC);
        event.getRegistry().register(new ForgeWorldType(Isle::createChunkGen).setRegistryName("isle:isle"));
    }

    private static ChunkGenerator createChunkGen(Registry<Biome> biomeReg, Registry<DimensionSettings> dimSettingsReg, long seed, String settingsString) {
        JsonObject settings = settingsString.isEmpty() ? new JsonObject() : JSONUtils.fromJson(settingsString);
        int tolerance = JSONUtils.getInt(settings, "tolerance", 16), borderRange = JSONUtils.getInt(settings, "border", 512);
        return new NoiseChunkGenerator(new IsleBiomeProvider(biomeReg, tolerance, borderRange, seed), seed, () -> dimSettingsReg.getOrThrow(DimensionSettings.OVERWORLD));
    }

    private static <T extends IArea> IAreaFactory<T> createAreaFactory(int tolerance, int borderRange, LongFunction<? extends IExtendedNoiseRandom<T>> noiseGenerator) {
        float biomeHalfTolerance = tolerance / 8F, biomeRadiusSq = borderRange * borderRange / 64F;

        // copied from net.minecraft.world.gen.layer.LayerUtil#build (func_237216_a_)
        IAreaFactory<T> main, ocean, river, biome, hill, result;

        main = IslandLayer.INSTANCE.apply(noiseGenerator.apply(1));
        main = ZoomLayer.FUZZY.apply(noiseGenerator.apply(2000), main);
        main = AddIslandLayer.INSTANCE.apply(noiseGenerator.apply(1), main);
        main = ZoomLayer.NORMAL.apply(noiseGenerator.apply(2001), main);
        main = AddIslandLayer.INSTANCE.apply(noiseGenerator.apply(2), main);
        main = AddIslandLayer.INSTANCE.apply(noiseGenerator.apply(50), main);
        main = AddIslandLayer.INSTANCE.apply(noiseGenerator.apply(70), main);
        main = RemoveTooMuchOceanLayer.INSTANCE.apply(noiseGenerator.apply(2), main);

        ocean = OceanLayer.INSTANCE.apply(noiseGenerator.apply(2));
        ocean = LayerUtil.repeat(2001, ZoomLayer.NORMAL, ocean, 6, noiseGenerator);

        main = AddSnowLayer.INSTANCE.apply(noiseGenerator.apply(2), main);
        main = AddIslandLayer.INSTANCE.apply(noiseGenerator.apply(3), main);
        main = EdgeLayer.CoolWarm.INSTANCE.apply(noiseGenerator.apply(2), main);
        main = EdgeLayer.HeatIce.INSTANCE.apply(noiseGenerator.apply(2), main);
        main = EdgeLayer.Special.INSTANCE.apply(noiseGenerator.apply(3), main);
        main = ZoomLayer.NORMAL.apply(noiseGenerator.apply(2002), main);
        main = ZoomLayer.NORMAL.apply(noiseGenerator.apply(2003), main);
        main = AddIslandLayer.INSTANCE.apply(noiseGenerator.apply(4), main);
        main = AddMushroomIslandLayer.INSTANCE.apply(noiseGenerator.apply(5), main);
        main = DeepOceanLayer.INSTANCE.apply(noiseGenerator.apply(4), main);
        main = LayerUtil.repeat(1000, ZoomLayer.NORMAL, main, 0, noiseGenerator);

        river = LayerUtil.repeat(1000, ZoomLayer.NORMAL, main, 0, noiseGenerator);
        river = StartRiverLayer.INSTANCE.apply(noiseGenerator.apply(100), river);

        biome = new BiomeLayer(false).apply(noiseGenerator.apply(200), main);
        biome = AddBambooForestLayer.INSTANCE.apply(noiseGenerator.apply(1001), biome);
        biome = LayerUtil.repeat(1000, ZoomLayer.NORMAL, biome, 2, noiseGenerator);
        biome = EdgeBiomeLayer.INSTANCE.apply(noiseGenerator.apply(1000), biome);

        // set to island
        // ======== START ========
        biome = new IsleFillLandOceanLayer(biomeRadiusSq, biomeHalfTolerance).apply(noiseGenerator.apply(500), biome);
        // ========= END =========

        hill = LayerUtil.repeat(1000, ZoomLayer.NORMAL, river, 2, noiseGenerator);
        biome = HillsLayer.INSTANCE.apply(noiseGenerator.apply(1000), biome, hill);

        river = LayerUtil.repeat(1000, ZoomLayer.NORMAL, river, 2, noiseGenerator);
        river = LayerUtil.repeat(1000, ZoomLayer.NORMAL, river, 4, noiseGenerator);
        river = RiverLayer.INSTANCE.apply(noiseGenerator.apply(1), river);
        river = SmoothLayer.INSTANCE.apply(noiseGenerator.apply(1000), river);

        biome = RareBiomeLayer.INSTANCE.apply(noiseGenerator.apply(1001), biome);

        // zoom only three times (vanilla: four times)
        biome = ZoomLayer.NORMAL.apply(noiseGenerator.apply(1000), biome);
        biome = AddIslandLayer.INSTANCE.apply(noiseGenerator.apply(3), biome);
        biome = ZoomLayer.NORMAL.apply(noiseGenerator.apply(1001), biome);
        biome = ShoreLayer.INSTANCE.apply(noiseGenerator.apply(1000), biome);
        biome = ZoomLayer.NORMAL.apply(noiseGenerator.apply(1002), biome);
        /* biome = ZoomLayer.NORMAL.apply(noiseGenerator.apply(1003), biome); */

        biome = SmoothLayer.INSTANCE.apply(noiseGenerator.apply(1000), biome);
        biome = MixRiverLayer.INSTANCE.apply(noiseGenerator.apply(100), biome, river);

        result = MixOceansLayer.INSTANCE.apply(noiseGenerator.apply(100), biome, ocean);

        // filter oceans
        // ======== START ========
        result = new IsleFilterOceanLayer(biomeRadiusSq).apply(noiseGenerator.apply(500), result);
        // ========= END =========

        return result;
    }

    private static float affectedRangeSq(float biomeCoordinate, int zoomFactor, float tolerance) {
        float zoomScale = 1 << zoomFactor, zoomed = 0.5F + biomeCoordinate * zoomScale;
        float affected = Math.max(0, Math.abs(zoomed) + 0.5F * zoomScale + tolerance);
        return affected * affected;
    }

    private static boolean isSimpleOcean(int i) {
        return i == 0 || i == 24 || i == 45 || i == 46 || i == 48 || i == 49;
    }

    private static int filterOcean(int i) {
        if (i == 10) return 46; // frozen => cold, drop spikes
        if (i == 50) return 49; // deep frozen => deep cold, drop spikes
        if (i == 44) return 45; // warm => luke warm, drop corals
        if (i == 47) return 48; // deep warm => deep luke warm, drop corals
        return i;
    }

    @MethodsReturnNonnullByDefault
    @ParametersAreNonnullByDefault
    private static final class IsleFillLandOceanLayer implements IAreaTransformer1, IDimOffset0Transformer {
        private final float biomeRadiusSq;
        private final float biomeHalfTolerance;

        private IsleFillLandOceanLayer(float biomeRadiusSq, float biomeHalfTolerance) {
            this.biomeRadiusSq = biomeRadiusSq;
            this.biomeHalfTolerance = biomeHalfTolerance;
        }

        @Override
        public int apply(IExtendedNoiseRandom<?> noiseGenerator, IArea area, int x, int z) {
            float innerSq = (affectedRangeSq(x, 3, -this.biomeHalfTolerance) + affectedRangeSq(z, 3, -this.biomeHalfTolerance)) * ISLE_SCALE_SQ;
            float outerSq = (affectedRangeSq(x, 3, this.biomeHalfTolerance) + affectedRangeSq(z, 3, this.biomeHalfTolerance)) * ISLE_SCALE_SQ;
            boolean holdsInner = true, holdsOuter = true;
            while (true) {
                float r1 = (innerSq + outerSq) / 2, r2 = noiseGenerator.random(2) == 0 ? innerSq : outerSq;
                if (!holdsInner) {
                    int currentBiome = area.getValue(this.getOffsetX(x), this.getOffsetZ(z));
                    return isSimpleOcean(filterOcean(currentBiome)) ? currentBiome : 0;
                }
                if (!holdsOuter) {
                    int currentBiome = area.getValue(this.getOffsetX(x), this.getOffsetZ(z));
                    return isSimpleOcean(filterOcean(currentBiome)) ? 1 : currentBiome;
                }
                innerSq = Math.min(r1, r2);
                outerSq = Math.max(r1, r2);
                holdsInner = innerSq < this.biomeRadiusSq;
                holdsOuter = outerSq > this.biomeRadiusSq;
            }
        }
    }

    @MethodsReturnNonnullByDefault
    @ParametersAreNonnullByDefault
    private static final class IsleFilterOceanLayer implements IAreaTransformer1, IDimOffset0Transformer {
        private final float biomeRadiusSq;

        private IsleFilterOceanLayer(float biomeRadiusSq) {
            this.biomeRadiusSq = biomeRadiusSq;
        }

        @Override
        public int apply(IExtendedNoiseRandom<?> noiseGenerator, IArea area, int x, int z) {
            int currentBiome = area.getValue(this.getOffsetX(x), this.getOffsetZ(z));
            float affectedRangeSq = Math.max(affectedRangeSq(x, 0, 0), affectedRangeSq(z, 0, 0));
            return affectedRangeSq < this.biomeRadiusSq ? currentBiome : this.oceanFiltered(currentBiome);
        }

        private int oceanFiltered(int biome) {
            int filtered = filterOcean(biome);
            return isSimpleOcean(filtered) ? filtered : 24;
        }
    }

    @MethodsReturnNonnullByDefault
    @ParametersAreNonnullByDefault
    private static final class IsleBiomeProvider extends BiomeProvider {
        private static final List<RegistryKey<Biome>> BIOMES = Objects.requireNonNull(
                ObfuscationReflectionHelper.getPrivateValue(OverworldBiomeProvider.class, null, "field_226847_e_"));

        private static final Codec<IsleBiomeProvider> CODEC = RecordCodecBuilder.create(i -> i.group(
                        RegistryLookupCodec.getLookUpCodec(Registry.BIOME_KEY).forGetter(p -> p.reg),
                        Codec.INT.fieldOf("tolerance").stable().forGetter(p -> p.isleTolerance),
                        Codec.INT.fieldOf("border").stable().forGetter(p -> p.borderRange),
                        Codec.LONG.fieldOf("seed").stable().forGetter(p -> p.seed))
                .apply(i, i.stable(IsleBiomeProvider::new)));

        private final Registry<Biome> reg;
        private final int isleTolerance;
        private final int borderRange;
        private final Layer layer;
        private final long seed;

        private IsleBiomeProvider(Registry<Biome> reg, int tolerance, int borderRange, long seed) {
            super(BIOMES.stream().map(k -> () -> reg.getOrThrow(k)));
            this.reg = reg;
            this.seed = seed;
            this.isleTolerance = tolerance;
            this.borderRange = borderRange;
            this.layer = new Layer(createAreaFactory(tolerance, borderRange, salt -> new LazyAreaLayerContext(25, seed, salt)));
        }

        @Override
        protected Codec<? extends BiomeProvider> getBiomeProviderCodec() {
            return CODEC;
        }

        @Override
        @OnlyIn(Dist.CLIENT)
        public BiomeProvider getBiomeProvider(long seed) {
            return new IsleBiomeProvider(this.reg, this.isleTolerance, this.borderRange, seed);
        }

        @Override
        public Biome getNoiseBiome(int x, int y, int z) {
            return this.layer.func_242936_a(this.reg, x, z);
        }
    }
}
