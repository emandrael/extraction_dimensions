package net.playwright.extraction_dimensions;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DynamicOps;
import commoble.infiniverse.api.InfiniverseAPI;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.commands.CommandRuntimeException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.progress.ChunkProgressListener;
import net.minecraft.util.RandomSource;
import net.minecraft.util.profiling.InactiveProfiler;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.WorldGenSettings;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.storage.DerivedLevelData;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
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

import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class TemporaryDimensionHelper {

    public static final ResourceKey<Level> LEVEL_KEY = ResourceKey.create(Registries.DIMENSION, new ResourceLocation(DimensionMod.MODID, "iron_world_2"));


    void onRegisterCommands(RegisterCommandsEvent event)
    {
        event.getDispatcher().register(Commands.literal("temp_dimension")
                .then(Commands.literal("create_dimension")
                        .executes(this::createDimension))
                .then(Commands.literal("remove_dimension")
                        .executes(this::removeDimension)));
    }

    int createDimension(CommandContext<CommandSourceStack> context)
    {
        InfiniverseAPI.get().getOrCreateLevel(context.getSource().getServer(), LEVEL_KEY, () -> createLevel(context.getSource().getServer()));
        return 1;
    }

    int removeDimension(CommandContext<CommandSourceStack> context)
    {
        InfiniverseAPI.get().markDimensionForUnregistration(context.getSource().getServer(), LEVEL_KEY);

        return 1;
    }

    // returns a LevelStem whose generator is pre-seeded, but note:
// vanilla will still re-seed it with the world seed when the level is created.


    public static LevelStem stemWithRandomSeed(MinecraftServer server,
                                               ResourceLocation templateId,
                                               RandomSource rng) {

        // Look up the datapack stem we want to copy.
        HolderGetter<LevelStem> stems = server.registryAccess()
                .lookupOrThrow(Registries.LEVEL_STEM);
        LevelStem template = stems.getOrThrow(
                ResourceKey.create(Registries.LEVEL_STEM, templateId)).get();

        // Serialize the existing generator through its public codec.
        DynamicOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, server.registryAccess());
        Tag encoded = ChunkGenerator.CODEC.encodeStart(ops, template.generator())
                .getOrThrow(false, msg -> new IllegalStateException("Encode failed: " + msg));

        // Patch both generator seed and biome-source seed if they exist.
        long newSeed = rng.nextLong();
        CompoundTag genTag = (CompoundTag) encoded;

        genTag.putLong("seed", newSeed);

        DimensionMod.LOGGER.info( String.format("%s",genTag.toString()));

        CompoundTag biomeSource = genTag.getCompound("biome_source");

        if (biomeSource.contains("seed", Tag.TAG_LONG)) {
            biomeSource.putLong("seed", newSeed);
        }

        // Deserialize into a brand-new generator instance.
        ChunkGenerator freshGenerator = ChunkGenerator.CODEC.parse(ops, genTag)
                .getOrThrow(false, msg -> new IllegalStateException("Parse failed: " + msg));


        // Wrap everything back into a LevelStem.
        return new LevelStem(template.type(), freshGenerator);
    }

    static LevelStem createLevel(MinecraftServer server)
    {
        ResourceLocation templateId = new ResourceLocation(DimensionMod.MODID, "iron_world");
        return stemWithRandomSeed(server,templateId,RandomSource.create());
    }


}