package com.controlswap;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.util.*;

@Mod(ControlSwapMod.MOD_ID)
public class ControlSwapMod {
    public static final String MOD_ID = "controlswap";

    private static final Map<String, TeamState> TEAMS = new LinkedHashMap<>();
    private static final Map<UUID, String> PLAYER_TEAM = new HashMap<>();

    public ControlSwapMod() {}

    @Mod.EventBusSubscriber(modid = MOD_ID)
    public static class Events {
        @SubscribeEvent
        public static void registerCommands(RegisterCommandsEvent event) {
            event.getDispatcher().register(
                Commands.literal("controlswap")
                    .requires(source -> source.hasPermission(2))
                    .then(Commands.literal("create")
                        .then(Commands.argument("team", StringArgumentType.word())
                            .executes(ctx -> create(ctx, StringArgumentType.getString(ctx, "team")))))
                    .then(Commands.literal("addplayer")
                        .then(Commands.argument("team", StringArgumentType.word())
                            .then(Commands.argument("player", StringArgumentType.word())
                                .executes(ctx -> addPlayer(ctx,
                                    StringArgumentType.getString(ctx, "team"),
                                    StringArgumentType.getString(ctx, "player"))))))
                    .then(Commands.literal("removeplayer")
                        .then(Commands.argument("player", StringArgumentType.word())
                            .executes(ctx -> removePlayer(ctx, StringArgumentType.getString(ctx, "player")))))
                    .then(Commands.literal("period")
                        .then(Commands.argument("team", StringArgumentType.word())
                            .then(Commands.argument("ticks", IntegerArgumentType.integer(20))
                                .executes(ctx -> setPeriod(ctx,
                                    StringArgumentType.getString(ctx, "team"),
                                    IntegerArgumentType.getInteger(ctx, "ticks"))))))
                    .then(Commands.literal("start")
                        .then(Commands.argument("team", StringArgumentType.word())
                            .executes(ctx -> start(ctx, StringArgumentType.getString(ctx, "team")))))
                    .then(Commands.literal("stop")
                        .then(Commands.argument("team", StringArgumentType.word())
                            .executes(ctx -> stop(ctx, StringArgumentType.getString(ctx, "team")))))
                    .then(Commands.literal("swap")
                        .then(Commands.argument("team", StringArgumentType.word())
                            .executes(ctx -> swap(ctx, StringArgumentType.getString(ctx, "team")))))
                    .then(Commands.literal("bench")
                        .then(Commands.argument("player", StringArgumentType.word())
                            .then(Commands.argument("value", BoolArgumentType.bool())
                                .executes(ctx -> bench(ctx,
                                    StringArgumentType.getString(ctx, "player"),
                                    BoolArgumentType.getBool(ctx, "value"))))))
                    .then(Commands.literal("list")
                        .executes(Events::list))
            );
        }

        @SubscribeEvent
        public static void serverTick(TickEvent.ServerTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;

            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server == null) return;

            for (TeamState team : TEAMS.values()) {
                if (!team.running || team.players.size() < 2) continue;

                team.ticksRemaining--;
                if (team.ticksRemaining <= 0) {
                    rotate(server, team);
                }
            }
        }
    }

    private static int create(CommandContext<CommandSourceStack> ctx, String name) {
        if (TEAMS.containsKey(name)) {
            msg(ctx, "Team already exists: " + name);
            return 0;
        }
        TEAMS.put(name, new TeamState(name));
        msg(ctx, "Created Control Swap team '" + name + "'.");
        return 1;
    }

    private static int addPlayer(CommandContext<CommandSourceStack> ctx, String teamName, String playerName) {
        TeamState team = TEAMS.get(teamName);
        if (team == null) {
            msg(ctx, "Team not found: " + teamName);
            return 0;
        }

        MinecraftServer server = ctx.getSource().getServer();
        ServerPlayer player = server.getPlayerList().getPlayerByName(playerName);
        if (player == null) {
            msg(ctx, "Player must be online: " + playerName);
            return 0;
        }

        removeFromAnyTeam(player.getUUID());

        team.players.add(player.getUUID());
        team.names.add(player.getGameProfile().getName());
        PLAYER_TEAM.put(player.getUUID(), teamName);

        msg(ctx, "Added " + playerName + " to " + teamName + ".");
        return 1;
    }

    private static int removePlayer(CommandContext<CommandSourceStack> ctx, String playerName) {
        MinecraftServer server = ctx.getSource().getServer();
        ServerPlayer player = server.getPlayerList().getPlayerByName(playerName);
        if (player == null) {
            msg(ctx, "Player must be online: " + playerName);
            return 0;
        }

        String teamName = PLAYER_TEAM.remove(player.getUUID());
        if (teamName == null) {
            msg(ctx, playerName + " is not in a Control Swap team.");
            return 0;
        }

        TeamState team = TEAMS.get(teamName);
        if (team != null) {
            int oldIndex = team.players.indexOf(player.getUUID());
            team.players.remove(player.getUUID());
            if (oldIndex >= 0) {
                team.names.remove(oldIndex);
                if (team.activeIndex >= team.players.size()) team.activeIndex = 0;
            }
        }

        player.setGameMode(GameType.SURVIVAL);
        msg(ctx, "Removed " + playerName + " from " + teamName + ".");
        return 1;
    }

    private static int setPeriod(CommandContext<CommandSourceStack> ctx, String teamName, int ticks) {
        TeamState team = TEAMS.get(teamName);
        if (team == null) {
            msg(ctx, "Team not found: " + teamName);
            return 0;
        }
        team.period = Math.max(20, ticks);
        team.ticksRemaining = team.period;
        msg(ctx, "Set " + teamName + " swap period to " + team.period + " ticks (" + (team.period / 20) + " seconds).");
        return 1;
    }

    private static int start(CommandContext<CommandSourceStack> ctx, String teamName) {
        TeamState team = TEAMS.get(teamName);
        if (team == null || team.players.size() < 2) {
            msg(ctx, "Team needs at least 2 online players.");
            return 0;
        }

        team.running = true;
        team.activeIndex = Math.floorMod(team.activeIndex, team.players.size());
        team.ticksRemaining = team.period;
        applyState(ctx.getSource().getServer(), team);
        msg(ctx, "Started " + teamName + ". Active player: " + team.getActiveName());
        return 1;
    }

    private static int stop(CommandContext<CommandSourceStack> ctx, String teamName) {
        TeamState team = TEAMS.get(teamName);
        if (team == null) {
            msg(ctx, "Team not found: " + teamName);
            return 0;
        }
        team.running = false;
        restoreSurvival(ctx.getSource().getServer(), team);
        msg(ctx, "Stopped " + teamName + ".");
        return 1;
    }

    private static int swap(CommandContext<CommandSourceStack> ctx, String teamName) {
        TeamState team = TEAMS.get(teamName);
        if (team == null || team.players.size() < 2) {
            msg(ctx, "Team needs at least 2 players.");
            return 0;
        }
        rotate(ctx.getSource().getServer(), team);
        return 1;
    }

    private static int bench(CommandContext<CommandSourceStack> ctx, String playerName, boolean value) {
        MinecraftServer server = ctx.getSource().getServer();
        ServerPlayer player = server.getPlayerList().getPlayerByName(playerName);
        if (player == null) {
            msg(ctx, "Player must be online: " + playerName);
            return 0;
        }
        player.setGameMode(value ? GameType.SPECTATOR : GameType.SURVIVAL);
        msg(ctx, playerName + (value ? " is now benched." : " is no longer benched."));
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        if (TEAMS.isEmpty()) {
            msg(ctx, "No Control Swap teams exist.");
            return 1;
        }

        for (TeamState team : TEAMS.values()) {
            msg(ctx, team.name + ": " + team.names + (team.running
                ? " | active=" + team.getActiveName() + " | " + Math.max(0, team.ticksRemaining / 20) + "s"
                : " | stopped"));
        }
        return 1;
    }

    private static void rotate(MinecraftServer server, TeamState team) {
        if (team.players.size() < 2) return;

        ServerPlayer oldActive = server.getPlayerList().getPlayer(team.players.get(team.activeIndex));
        int nextIndex = (team.activeIndex + 1) % team.players.size();
        ServerPlayer newActive = server.getPlayerList().getPlayer(team.players.get(nextIndex));

        if (newActive == null) return;

        if (oldActive != null) {
            copyPlayerData(oldActive, newActive);
        }

        team.activeIndex = nextIndex;
        team.ticksRemaining = team.period;
        applyState(server, team);

        broadcast(server, "Control Swap: " + team.getActiveName() + " is now in control!");
    }

    private static void applyState(MinecraftServer server, TeamState team) {
        for (int i = 0; i < team.players.size(); i++) {
            ServerPlayer p = server.getPlayerList().getPlayer(team.players.get(i));
            if (p == null) continue;

            if (i == team.activeIndex) {
                p.setGameMode(GameType.SURVIVAL);
            } else {
                p.setGameMode(GameType.SPECTATOR);
                p.teleportTo(server.overworld(), p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot());
            }
        }
    }

    private static void restoreSurvival(MinecraftServer server, TeamState team) {
        for (UUID uuid : team.players) {
            ServerPlayer p = server.getPlayerList().getPlayer(uuid);
            if (p != null) p.setGameMode(GameType.SURVIVAL);
        }
    }

    private static void copyPlayerData(ServerPlayer from, ServerPlayer to) {
        to.getInventory().clearContent();
        for (int i = 0; i < from.getInventory().getContainerSize(); i++) {
            to.getInventory().setItem(i, from.getInventory().getItem(i).copy());
        }

        to.setHealth(from.getHealth());
        to.getFoodData().setFoodLevel(from.getFoodData().getFoodLevel());
        to.getFoodData().setSaturation(from.getFoodData().getSaturationLevel());
        to.setExperienceLevels(from.experienceLevel);
        to.setExperiencePoints(from.totalExperience);

        Vec3 pos = from.position();
        to.teleportTo(from.serverLevel(), pos.x, pos.y, pos.z, from.getYRot(), from.getXRot());
    }

    private static void removeFromAnyTeam(UUID uuid) {
        String old = PLAYER_TEAM.remove(uuid);
        if (old == null) return;

        TeamState team = TEAMS.get(old);
        if (team != null) {
            int idx = team.players.indexOf(uuid);
            team.players.remove(uuid);
            if (idx >= 0) team.names.remove(idx);
            if (team.activeIndex >= team.players.size()) team.activeIndex = 0;
        }
    }

    private static void msg(CommandContext<CommandSourceStack> ctx, String text) {
        ctx.getSource().sendSuccess(() -> Component.literal(text), true);
    }

    private static void broadcast(MinecraftServer server, String text) {
        server.getPlayerList().broadcastSystemMessage(Component.literal(text), false);
    }

    private static class TeamState {
        final String name;
        final List<UUID> players = new ArrayList<>();
        final List<String> names = new ArrayList<>();
        int activeIndex = 0;
        int period = 600;
        int ticksRemaining = 600;
        boolean running = false;

        TeamState(String name) {
            this.name = name;
        }

        String getActiveName() {
            if (names.isEmpty()) return "none";
            return names.get(Math.floorMod(activeIndex, names.size()));
        }
    }
}
