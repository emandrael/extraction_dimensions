package net.playwright.extraction_dimensions;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;

@Mod.EventBusSubscriber(modid = DimensionMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ExtractionCommands {

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();

        dispatcher.register(Commands.literal("extraction")
                .then(Commands.literal("start")
                        .executes(ExtractionCommands::startMatch)));
    }

    private static int startMatch(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        List<ServerPlayer> players = source.getServer().getPlayerList().getPlayers();

        if (players.isEmpty()) {
            source.sendFailure(Component.literal("No players online to start match."));
            return 0;
        }

        source.sendSuccess(() -> Component.literal("Starting match with " + players.size() + " players..."), true);
        MatchManager.startMatch(source.getServer(), players);
        return 1;
    }
}
