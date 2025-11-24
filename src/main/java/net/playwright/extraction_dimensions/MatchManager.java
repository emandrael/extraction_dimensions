package net.playwright.extraction_dimensions;

import commoble.infiniverse.api.InfiniverseAPI;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Mod.EventBusSubscriber(modid = DimensionMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class MatchManager {

    private static final Map<ResourceKey<Level>, Match> activeMatches = new ConcurrentHashMap<>();
    private static final RandomSource random = RandomSource.create();

    public static void startMatch(MinecraftServer server, List<ServerPlayer> players) {
        // Create a unique dimension for this match
        String matchId = "match_" + System.currentTimeMillis();
        ResourceKey<Level> dimensionKey = ResourceKey.create(Registries.DIMENSION,
                new ResourceLocation(DimensionMod.MODID, matchId));

        // Use Infiniverse to create the dimension
        InfiniverseAPI.get().getOrCreateLevel(server, dimensionKey, () -> TemporaryDimensionHelper.createLevel(server));

        // Wait for a tick to ensure dimension is ready (Infiniverse usually handles
        // this immediately, but safe to queue)
        // For simplicity, we'll assume it's ready or handle it in the next tick if
        // needed.
        // Actually, getOrCreateLevel returns the ServerLevel if it exists, or creates
        // it.

        ServerLevel level = server.getLevel(dimensionKey);
        if (level == null) {
            DimensionMod.LOGGER.error("Failed to create dimension for match " + matchId);
            return;
        }

        Match match = new Match(dimensionKey, server.getTickCount());
        activeMatches.put(dimensionKey, match);

        // Add players to match but don't teleport yet
        for (ServerPlayer player : players) {
            match.addPlayer(player.getUUID());
            player.sendSystemMessage(Component.literal("Match found! Teleporting in 15 seconds..."));
        }
    }

    private static void teleportPlayerToMatch(ServerPlayer player, ServerLevel level) {
        // Random spread
        int x = random.nextInt(1000) - 500;
        int z = random.nextInt(1000) - 500;

        // Ensure chunk is loaded to get correct height
        level.getChunk(x >> 4, z >> 4);

        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        if (y <= level.getMinBuildHeight()) {
            y = level.getSeaLevel() + 1; // Fallback
        }

        player.teleportTo(level, x, y + 1, z, 0, 0);
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END)
            return;

        MinecraftServer server = event.getServer(); // This might be null in some contexts, but usually fine in
                                                    // ServerTick
        if (server == null)
            return;

        Iterator<Map.Entry<ResourceKey<Level>, Match>> iterator = activeMatches.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<ResourceKey<Level>, Match> entry = iterator.next();
            Match match = entry.getValue();
            ServerLevel level = server.getLevel(match.dimension);

            if (level == null) {
                iterator.remove();
                continue;
            }

            match.tick(server, level);

            if (match.isEnded()) {
                // Cleanup dimension?
                // InfiniverseAPI.get().markDimensionForUnregistration(server, match.dimension);
                // We might want to keep it for a bit or delete it.
                iterator.remove();
            }
        }
    }

    public static void startExtraction(ServerPlayer player) {
        ResourceKey<Level> dim = player.level().dimension();
        Match match = activeMatches.get(dim);
        if (match != null) {
            match.startExtraction(player);
        }
    }

    public static class Match {
        private final ResourceKey<Level> dimension;
        private long startTick; // When the actual gameplay starts
        private final long createdTick; // When the match object was created
        private final Set<UUID> players = new HashSet<>();
        private final Map<UUID, Long> extractingPlayers = new HashMap<>(); // UUID -> Start Tick
        private boolean ended = false;
        private MatchState state = MatchState.WARMUP;

        private static final int WARMUP_TIME_TICKS = 15 * 20; // 15 seconds
        private static final int MATCH_DURATION_TICKS = 30 * 60 * 20; // 30 minutes
        private static final int EXTRACTION_TIME_TICKS = 15 * 20; // 15 seconds

        public enum MatchState {
            WARMUP,
            PLAYING,
            ENDED
        }

        public Match(ResourceKey<Level> dimension, long createdTick) {
            this.dimension = dimension;
            this.createdTick = createdTick;
        }

        public void addPlayer(UUID uuid) {
            players.add(uuid);
        }

        public void tick(MinecraftServer server, ServerLevel level) {
            long currentTick = server.getTickCount();

            if (state == MatchState.WARMUP) {
                long elapsed = currentTick - createdTick;
                if (elapsed >= WARMUP_TIME_TICKS) {
                    startGameplay(server, level);
                } else {
                    // Optional: Countdown every second
                    if (elapsed % 20 == 0) {
                        int secondsLeft = 15 - (int) (elapsed / 20);
                        if (secondsLeft <= 5 && secondsLeft > 0) {
                            broadcast(server, Component.literal("Teleporting in " + secondsLeft + "..."));
                        }
                    }
                }
                return;
            }

            if (state == MatchState.PLAYING) {
                long elapsed = currentTick - startTick;

                // Check match timer
                if (elapsed >= MATCH_DURATION_TICKS && !ended) {
                    endMatch(server, level);
                }

                // Check extractions
                Iterator<Map.Entry<UUID, Long>> extIt = extractingPlayers.entrySet().iterator();
                while (extIt.hasNext()) {
                    Map.Entry<UUID, Long> entry = extIt.next();
                    UUID playerId = entry.getKey();
                    long extStart = entry.getValue();

                    if (currentTick - extStart >= EXTRACTION_TIME_TICKS) {
                        // Success
                        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                        if (player != null) {
                            extractPlayer(player, server);
                        }
                        extIt.remove();
                    } else {
                        // Notify player of time remaining
                        if ((currentTick - extStart) % 20 == 0) {
                            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
                            if (player != null) {
                                int secondsLeft = 15 - (int) ((currentTick - extStart) / 20);
                                player.displayClientMessage(Component.literal("Extracting in " + secondsLeft + "..."),
                                        true);
                            }
                        }
                    }
                }
            }
        }

        private void startGameplay(MinecraftServer server, ServerLevel level) {
            state = MatchState.PLAYING;
            startTick = server.getTickCount();

            for (UUID uuid : players) {
                ServerPlayer player = server.getPlayerList().getPlayer(uuid);
                if (player != null) {
                    teleportPlayerToMatch(player, level);
                }
            }
            broadcast(server,
                    Component.literal("Match started! You have 30 minutes to extract. Find the Extraction Block!"));
        }

        public void startExtraction(ServerPlayer player) {
            if (state != MatchState.PLAYING)
                return;
            if (extractingPlayers.containsKey(player.getUUID()))
                return;
            extractingPlayers.put(player.getUUID(), (long) player.getServer().getTickCount());
            player.sendSystemMessage(Component.literal("Extraction started! Stay alive for 15 seconds."));
        }

        private void extractPlayer(ServerPlayer player, MinecraftServer server) {
            player.sendSystemMessage(Component.literal("Extraction Successful!"));
            // Teleport to overworld spawn
            ServerLevel overworld = server.getLevel(Level.OVERWORLD);
            if (overworld != null) {
                BlockPos spawn = overworld.getSharedSpawnPos();
                player.teleportTo(overworld, spawn.getX(), spawn.getY(), spawn.getZ(), 0, 0);
            }
            players.remove(player.getUUID());
        }

        private void endMatch(MinecraftServer server, ServerLevel level) {
            ended = true;
            state = MatchState.ENDED;
            broadcast(server, Component.literal("Match Ended! All remaining players are lost."));

            for (UUID uuid : players) {
                ServerPlayer player = server.getPlayerList().getPlayer(uuid);
                if (player != null && player.level().dimension().equals(dimension)) {
                    player.kill();
                }
            }
            
        }

        public boolean isEnded() {
            return ended;
        }

        private void broadcast(MinecraftServer server, Component message) {
            for (UUID uuid : players) {
                ServerPlayer player = server.getPlayerList().getPlayer(uuid);
                if (player != null) {
                    player.sendSystemMessage(message);
                }
            }
        }
    }

    private static void broadcast(ServerLevel level, Component message) {
        for (ServerPlayer player : level.players()) {
            player.sendSystemMessage(message);
        }
    }
}
