package net.playwright.extraction_dimensions;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.progress.ChunkProgressListener;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.storage.DerivedLevelData;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.LevelEvent;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class TemporaryDimensionHelper {

    private static final Object2IntMap<ResourceKey<Level>> TIMEOUTS = new Object2IntOpenHashMap<>();
    public static final int THIRTY_MINUTES = 30 * 60 * 20;

    private TemporaryDimensionHelper() {}

    /* -------------------------
       Command registration API
       ------------------------- */

    public static void registerCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        dispatcher.register(Commands.literal("tempdim")
                .requires(source -> source.hasPermission(2))
                .executes(ctx -> createAndWarp(ctx, THIRTY_MINUTES))
                .then(Commands.argument("minutes", IntegerArgumentType.integer(1, 720))
                        .executes(ctx -> {
                            int minutes = IntegerArgumentType.getInteger(ctx, "minutes");
                            return createAndWarp(ctx, minutes * 60 * 20);
                        }))
        );
    }

    private static int createAndWarp(CommandContext<CommandSourceStack> ctx, int lifetimeTicks)
            throws CommandSyntaxException {

        CommandSourceStack source = ctx.getSource();
        MinecraftServer server = source.getServer();
        ServerPlayer player = source.getPlayerOrException();

        ResourceLocation id = new ResourceLocation(DimensionMod.MODID, "temp/" + UUID.randomUUID());
        ResourceKey<Level> key = createTempDimension(server, id, lifetimeTicks);
        ServerLevel level = server.getLevel(key);

        if (level == null) {
            throw new SimpleCommandExceptionType(Component.literal("Failed to create temporary dimension")).create();
        }

        player.changeDimension(level);
        int seconds = lifetimeTicks / 20;
        source.sendSuccess(() -> Component.literal("Created " + key.location() + " (" + seconds + "s)"), true);
        return Command.SINGLE_SUCCESS;
    }

    /* -------------------------
       Runtime helpers
       ------------------------- */

    private static final ResourceLocation TEMPLATE_STEM_ID =
            new ResourceLocation(DimensionMod.MODID, "iron_world");


    public static ResourceKey<Level> createTempDimension(MinecraftServer server,
                                                         ResourceLocation name,
                                                         int lifetimeTicks) {

        ResourceKey<Level> levelKey = ResourceKey.create(Registries.DIMENSION, name);
        ResourceKey<LevelStem> stemKey = ResourceKey.create(Registries.LEVEL_STEM, TEMPLATE_STEM_ID);

        Holder<LevelStem> stemHolder = server.registryAccess()
                .registryOrThrow(Registries.LEVEL_STEM)
                .getHolder(stemKey)
                .orElseThrow(() -> new IllegalStateException("No LevelStem for " + TEMPLATE_STEM_ID));

        LevelStem stem = stemHolder.value();
        ServerLevel overworld = server.overworld();
        ServerLevelData levelData = new DerivedLevelData(server.getWorldData(), (ServerLevelData) overworld.getLevelData());

        long seed = server.getWorldData().worldGenOptions().seed();
        ChunkProgressListener progress = server.progressListenerFactory.create(11);

        ServerLevel newLevel = new ServerLevel(
                server,
                server.executor,
                server.storageSource,
                levelData,
                levelKey,
                stem,
                progress,
                false,
                seed,
                List.of(),
                false,
                null
        );

        server.levels.put(levelKey, newLevel);
        MinecraftForge.EVENT_BUS.post(new LevelEvent.Load(newLevel));

        TIMEOUTS.put(levelKey, lifetimeTicks);
        return levelKey;
    }

    public static void handleServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            tick(event.getServer());
        }
    }

    public static void tick(MinecraftServer server) {
        Iterator<Object2IntMap.Entry<ResourceKey<Level>>> it = TIMEOUTS.object2IntEntrySet().iterator();
        while (it.hasNext()) {
            Object2IntMap.Entry<ResourceKey<Level>> entry = it.next();
            int ticksLeft = entry.getIntValue() - 1;
            if (ticksLeft <= 0) {
                it.remove();
            } else {
                entry.setValue(ticksLeft);
            }
        }
    }


    private static void deleteRecursively(Path root) throws IOException {
        if (Files.notExists(root)) return;

        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                if (exc != null) throw exc;
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}