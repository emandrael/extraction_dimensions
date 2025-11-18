package net.playwright.extraction_dimensions;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.progress.ChunkProgressListener;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.storage.DerivedLevelData;
import net.minecraft.world.level.storage.ServerLevelData;

import java.nio.file.Path;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public final class TemporaryDimensionManager {

    private static final Map<ResourceKey<Level>, Integer> TIMEOUTS = new Object2IntOpenHashMap<>();
    public static final int THIRTY_MINUTES = 30 * 60 * 20; // 36 000 ticks

    private TemporaryDimensionManager() {}

    public static ResourceKey<Level> createTempDimension(MinecraftServer server,
                                                         ResourceLocation name,
                                                         int lifetimeTicks) {

        ResourceKey<Level> levelKey      = ResourceKey.create(Registries.DIMENSION, name);
        ResourceKey<LevelStem> stemKey   = ResourceKey.create(Registries.LEVEL_STEM, name);

        // Grab the LevelStem that is defined in your JSON.
        Holder<LevelStem> stemHolder = server.registryAccess()
                .registryOrThrow(Registries.LEVEL_STEM)
                .getHolder(stemKey)
                .orElseThrow(() -> new IllegalStateException("No LevelStem for " + name));

        LevelStem stem = stemHolder.value();
        Holder<DimensionType> dimTypeHolder = stem.type();

        // Derived level data so the dimension shares gamerules with the overworld
        ServerLevel overworld = server.overworld();
        ServerLevelData levelData = new DerivedLevelData(server.getWorldData(), (ServerLevelData) overworld.getLevelData());

        // Reuse the same world seed / random sequences
        long seed = server.getWorldData().worldGenOptions().seed();

        // A chunk progress listener (doesn't show anything in SP)
        ChunkProgressListener progress = server.progressListenerFactory.create(11);

        ServerLevel newLevel = new ServerLevel(
                server,
                server.executor,
                server.storageSource,
                levelData,
                levelKey,
                stem,                  // contains the chunk generator
                progress,
                false,                 // isDebug
                seed,
                List.of(),
                false,                 // will spawn monsters (use true if you want)
                null
        );

        // Register it in the server’s dimension map
        server.levels.put(levelKey, newLevel);
        server.levelKeys.add(levelKey);

        // Forge hook so other mods know a world was loaded
        ForgeHooks.fireWorldLoadEvent(newLevel);

        TIMEOUTS.put(levelKey, lifetimeTicks);
        return levelKey;
    }

    public static void tick(MinecraftServer server) {
        Iterator<Map.Entry<ResourceKey<Level>, Integer>> it = TIMEOUTS.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<ResourceKey<Level>, Integer> entry = it.next();
            int ticksLeft = entry.getValue() - 1;
            if (ticksLeft <= 0) {
                deleteDimension(server, entry.getKey());
                it.remove();
            } else {
                entry.setValue(ticksLeft);
            }
        }
    }

    public static void deleteDimension(MinecraftServer server, ResourceKey<Level> key) {
        ServerLevel level = server.getLevel(key);
        if (level == null) return;

        // 1. Move players out
        ServerLevel overworld = server.overworld();
        for (ServerPlayer player : level.players()) {
            player.changeDimension(overworld);
        }

        // 2. Flush everything to disk & close
        level.save(null, true, false);
        level.getChunkSource().close();
        ForgeHooks.onWorldUnload(level);

        // 3. Remove from server maps
        server.levels.remove(key);
        server.levelKeys.remove(key);

        // 4. Delete the actual folder (level gets saved under DIMENSION/<namespace>/<path>)
        Path dimensionPath = server.getWorldPath(LevelResource.DIMENSIONS).resolve(key.location().getNamespace())
                .resolve(key.location().getPath());
        try {
            FileUtil.deleteRecursively(dimensionPath);
        } catch (IOException e) {
            DimensionMod.LOGGER.warn("Failed to delete temporary dimension folder {}", dimensionPath, e);
        }
    }
}