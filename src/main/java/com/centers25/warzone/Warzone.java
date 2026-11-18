package com.centers25.warzone;

import com.centers25.core.backup.PluginBackups;
import com.centers25.core.logging.PluginLogger;
import com.centers25.core.logging.PluginLogs;
import com.destroystokyo.paper.event.player.PlayerElytraBoostEvent;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Firework;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

public final class Warzone extends JavaPlugin implements Listener, BasicCommand {
    private static final String ADMIN = "friendsmp.warzone.admin";
    private static final String LEGACY_FOLDER = "FriendSMPWarzone";

    private record Confirmation(long at, long duration) {}

    private record Search(UUID token, Location home, long duration, boolean charge, CommandSender admin) {}

    private final Map<UUID, Confirmation> confirmations = new HashMap<>();
    private final Map<UUID, Search> searches = new HashMap<>();
    private final Map<UUID, Long> fireworks = new HashMap<>();
    private Stays stays;
    private Stats stats;
    private Spawns spawns;
    private Combat combat;
    private Voucher voucher;
    private long cooldown;
    private Tickets tickets;
    private String main;
    private String warzone;
    private Set<String> worlds;
    private String fireworkMode;
    private long confirmNanos;
    private long fireworkNanos;
    private int radius;
    private int attempts;
    private PluginLogger log;

    @Override
    public void onEnable() {
        log = PluginLogs.get(this);
        migrateLegacyFolder();
        saveDefaultConfig();
        var config = getConfig();
        main = config.getString("worlds.main", "world");
        warzone = config.getString("worlds.warzone", "warzone");
        worlds = new HashSet<>(List.of(warzone, config.getString("worlds.nether", "warzone_nether"),
                config.getString("worlds.end", "warzone_the_end")));
        radius = config.getInt("rtp.radius", 10_000);
        attempts = config.getInt("rtp.max-attempts", 40);
        int respawnRadius = config.getInt("respawn.radius", 10_000);
        int confirmSeconds = config.getInt("confirmation-seconds", 30);
        int fireworkSeconds = config.getInt("fireworks.cooldown-seconds", 10);
        int combatSeconds = config.getInt("combat.seconds", 30);
        int dangerChunks = config.getInt("danger-log.chunks", 8);
        int afkMinutes = config.getInt("afk.minutes", 5);
        int invisMinutes = config.getInt("invisibility.cooldown-minutes", 12);
        int cooldownHours = config.getInt("cooldown-hours", 6);
        fireworkMode = config.getString("fireworks.mode", "allowed").toLowerCase(Locale.ROOT);
        int killer = config.getInt("kill-time.killer-minutes", 10);
        int window = config.getInt("kill-time.repeat-window-minutes", 60);
        int base = config.getInt("friend-tickets.base", 16);
        TreeMap<Integer, Double> rates = rates(config.getConfigurationSection("friend-tickets.rates"));
        int minMinutes = config.getInt("friend-tickets.min-minutes", 30);
        int maxMinutes = config.getInt("friend-tickets.max-minutes", 360);
        if (radius < 1 || radius > 29_000_000 || respawnRadius < 1 || respawnRadius > 29_000_000
                || attempts < 1 || attempts > 200 || confirmSeconds < 1 || fireworkSeconds < 0 || combatSeconds < 0 || cooldownHours < 0
                || dangerChunks < 0 || dangerChunks > 32 || afkMinutes < 0 || invisMinutes < 0
                || killer < 0 || window < 0 || base < 0 || rates == null || minMinutes < 0 || maxMinutes < minMinutes
                || !Set.of("disabled", "cooldown", "allowed").contains(fireworkMode)) {
            log().error("Invalid config.yml");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        confirmNanos = confirmSeconds * 1_000_000_000L;
        cooldown = cooldownHours * 3_600_000L;
        fireworkNanos = fireworkSeconds * 1_000_000_000L;
        try {
            tickets = new Tickets(this, base, rates, minMinutes * 60_000L, maxMinutes * 60_000L);
            stays = new Stays(this);
        } catch (Exception e) {
            log().error("Could not load stays.yml or tickets.yml", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        var plugins = getServer().getPluginManager();
        plugins.registerEvents(this, this);
        if (plugins.getPermission(ADMIN) == null) plugins.addPermission(new Permission(ADMIN, PermissionDefault.OP));
        registerCommand("warzone", "Enter the warzone, e.g. /warzone 5m.", this);
        registerCommand("sendtowarzone", "Send a player to the warzone.", admin(Time.SUGGESTIONS,
                "Usage: <hl>/sendtowarzone \\<player> \\<time></hl> <muted>(e.g. 5m)", this::sendToWarzone));
        registerCommand("sendtosafe", "Release a player to safety.", admin(null,
                "Usage: <hl>/sendtosafe \\<player></hl>", this::sendToSafe));
        registerCommand("setwarzonecooldown", "Set or clear a player's warzone cooldown.",
                admin(Stream.concat(Stream.of("0s"), Time.SUGGESTIONS.stream()).toList(),
                        "Usage: <hl>/setwarzonecooldown \\<player> \\<time></hl> <muted>(e.g. 5m, 5h or 0s to clear)", this::setCooldown));
        voucher = new Voucher(this);
        registerCommand("givewarzonevoucher", "Give a player warzone cooldown vouchers.", admin(List.of("1", "5", "10"),
                "Usage: <hl>/givewarzonevoucher \\<player> \\<amount></hl>", this::giveVoucher));
        stays.start();
        try {
            spawns = new Spawns(this);
        } catch (Exception e) {
            log().error("Could not load spawns.yml", e);
        }
        new Deaths(this, respawnRadius, attempts);
        new Effects(this);
        combat = new Combat(this, combatSeconds, dangerChunks);
        new Afk(this, afkMinutes);
        new Invis(this, invisMinutes);
        try {
            stats = new Stats(this, killer * 60_000L, window * 60_000L);
            stats.start();
        } catch (Exception e) {
            stats = null;
            log().error("Could not load combat.yml", e);
        }
        getServer().getScheduler().runTaskTimer(this, () -> {
            long now = System.nanoTime();
            confirmations.values().removeIf(c -> now - c.at() >= confirmNanos);
            fireworks.values().removeIf(at -> now - at >= fireworkNanos);
        }, 1200L, 1200L);
        PluginBackups.get(this).register(this);
        log.info("Warzone enabled.");
    }

    @Override
    public void onDisable() {
        if (stays != null) stays.shutdown();
        if (stats != null) stats.save();
        PluginBackups.get(this).unregister(this);
    }

    PluginLogger log() {
        return log;
    }

    private void migrateLegacyFolder() {
        java.io.File legacy = new java.io.File(getDataFolder().getParentFile(), LEGACY_FOLDER);
        if (!legacy.isDirectory() || getDataFolder().exists()) return;
        if (legacy.renameTo(getDataFolder())) {
            log.info("Moved existing data from plugins/" + LEGACY_FOLDER + " to plugins/" + getDataFolder().getName() + ".");
        } else {
            log.warn("Could not move plugins/" + LEGACY_FOLDER + " to plugins/" + getDataFolder().getName() + ".");
        }
    }

    boolean isWarzone(World world) {
        return world != null && worlds.contains(world.getName());
    }

    Location safeSpawn() {
        World world = Bukkit.getWorld(main);
        return world == null || isWarzone(world) ? null : world.getSpawnLocation();
    }

    Location warzoneSpawn() {
        World world = Bukkit.getWorld(warzone);
        return world == null ? null : world.getSpawnLocation();
    }

    private static TreeMap<Integer, Double> rates(ConfigurationSection section) {
        var rates = new TreeMap<Integer, Double>();
        if (section == null) return new TreeMap<>(Map.of(1, 0.2, 2, 0.75, 3, 1.0));
        try {
            for (String players : section.getKeys(false)) rates.put(Integer.parseInt(players), section.getDouble(players));
        } catch (NumberFormatException e) {
            return null;
        }
        boolean valid = !rates.isEmpty() && rates.firstKey() >= 1 && rates.values().stream().allMatch(r -> r >= 0 && r <= 1);
        return valid ? rates : null;
    }

    long cooldown() {
        return cooldown;
    }

    Stays stays() {
        return stays;
    }

    Combat combat() {
        return combat;
    }

    Tickets tickets() {
        return tickets;
    }

    int population() {
        return (int) Bukkit.getOnlinePlayers().stream().filter(p -> isWarzone(p.getWorld())).count();
    }

    boolean expired(UUID id) {
        return stays.active(id) && stays.remaining(id) == 0;
    }

    void cancelSearch(UUID id) {
        searches.remove(id);
        confirmations.remove(id);
    }

    private static List<String> matching(Collection<String> options, String prefix) {
        String start = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(start)).toList();
    }

    private static TagResolver name(Player player) {
        return Placeholder.unparsed("player", player.getName());
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        return args.length <= 1 ? matching(Time.SUGGESTIONS, args.length == 0 ? "" : args[0]) : List.of();
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        if (!(source.getSender() instanceof Player player)) {
            Msg.error(source.getSender(), "Only players can use this.");
            return;
        }
        UUID id = player.getUniqueId();
        if (stays.active(id)) {
            Msg.error(player, "You're already in the warzone. <hl>" + Time.format(stays.remaining(id)) + "</hl> remaining.");
            return;
        }
        if (!player.getWorld().getName().equals(main)) {
            Msg.error(player, "You can only use <hl>/warzone</hl> in <hl><world></hl>.", Placeholder.unparsed("world", main));
            return;
        }
        if (player.isDead()) {
            Msg.error(player, "Respawn first.");
            return;
        }
        if (args.length != 1) {
            Msg.info(player, "Usage: <hl>/warzone \\<time></hl> <muted>— e.g. <hl>30s</hl>, <hl>5m</hl> or <hl>5h</hl>");
            return;
        }
        long duration;
        try {
            duration = Time.parse(args[0]);
        } catch (IllegalArgumentException e) {
            Msg.error(player, e.getMessage());
            return;
        }
        if (stays.cooldown(id) > 0) {
            Msg.error(player, "You're on cooldown. Try again in <hl>" + Time.format(stays.cooldown(id)) + "</hl>.");
            return;
        }
        if (searches.containsKey(id)) {
            Msg.info(player, "<muted>Still searching for a safe landing spot…");
            return;
        }
        if (warzoneSpawn() == null) {
            Msg.error(player, "The warzone is unavailable right now.");
            return;
        }
        Confirmation warned = confirmations.remove(id);
        if (warned == null || warned.duration() != duration || System.nanoTime() - warned.at() > confirmNanos) {
            confirmations.put(id, new Confirmation(System.nanoTime(), duration));
            warn(player, duration, args[0].toLowerCase(Locale.ROOT));
            return;
        }
        search(player, duration, true, null);
    }

    private void warn(Player player, long duration, String time) {
        Component confirm = Msg.parse("<" + Msg.BRAND + "><b>[ ⚔ ENTER THE WARZONE ]</b></gradient>")
                .clickEvent(ClickEvent.runCommand("/warzone " + time))
                .hoverEvent(HoverEvent.showText(Msg.parse("<hl>Click to confirm</hl>\n<muted>Runs /warzone " + time)));
        player.sendMessage(Msg.parse(String.join("\n",
                Msg.RULE,
                " <" + Msg.BRAND + "><b>⚠ WARZONE WARNING ⚠</b></gradient>",
                "",
                " <#FF6B6B>You are entering the warzone!</#FF6B6B> <text>This is a place where griefing",
                " <text>is allowed, and you can lose your current items.",
                "",
                " <muted>Playtime <dim>»</dim> <hl>" + Time.format(duration) + "</hl>   <muted>Cooldown <dim>»</dim> <hl>" + Time.format(cooldown) + "</hl>"
                        + "   <muted>Early return <dim>»</dim> <bad>None",
                "",
                " <confirm> <dim>or run</dim> <hl>/warzone " + time + "</hl> <dim>again within "
                        + confirmNanos / 1_000_000_000L + "s",
                Msg.RULE), Placeholder.component("confirm", confirm)));
        player.playSound(player, Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
    }

    private BasicCommand admin(List<String> times, String usage, BiConsumer<CommandSender, String[]> run) {
        return new BasicCommand() {
            @Override
            public String permission() {
                return ADMIN;
            }

            @Override
            public void execute(CommandSourceStack source, String[] args) {
                CommandSender sender = source.getSender();
                if (!sender.hasPermission(ADMIN)) Msg.error(sender, "You don't have permission to do that.");
                else if (args.length != (times == null ? 1 : 2)) Msg.error(sender, usage);
                else run.accept(sender, args);
            }

            @Override
            public Collection<String> suggest(CommandSourceStack source, String[] args) {
                if (!source.getSender().hasPermission(ADMIN)) return List.of();
                if (args.length <= 1) return matching(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(),
                        args.length == 0 ? "" : args[0]);
                return args.length == 2 && times != null ? matching(times, args[1]) : List.of();
            }
        };
    }

    private Player target(CommandSender sender, String name) {
        Player player = Bukkit.getPlayerExact(name);
        if (player == null) {
            Msg.error(sender, "<hl><player></hl> is not online.", Placeholder.unparsed("player", name));
        } else if (player.isDead()) {
            Msg.error(sender, "<hl><player></hl> must respawn first.", name(player));
            return null;
        }
        return player;
    }

    private void sendToWarzone(CommandSender sender, String[] args) {
        Player player = target(sender, args[0]);
        if (player == null) return;
        try {
            search(player, Time.parse(args[1]), false, sender);
        } catch (IllegalArgumentException e) {
            Msg.error(sender, e.getMessage());
        }
    }

    private void sendToSafe(CommandSender sender, String[] args) {
        Player player = target(sender, args[0]);
        if (player == null) return;
        stays.sendSafe(player);
        Msg.info(sender, "Sending <hl><player></hl> to safety…", name(player));
    }

    private void giveVoucher(CommandSender sender, String[] args) {
        Player player = Bukkit.getPlayerExact(args[0]);
        if (player == null) {
            Msg.error(sender, "<hl><player></hl> is not online.", Placeholder.unparsed("player", args[0]));
            return;
        }
        int amount;
        try {
            amount = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            amount = 0;
        }
        if (amount < 1 || amount > 64 * 36) {
            Msg.error(sender, "Amount must be between <hl>1</hl> and <hl>2304</hl>.");
            return;
        }
        int left = amount;
        while (left > 0) {
            int size = Math.min(64, left);
            player.getInventory().addItem(voucher.create(size)).values()
                    .forEach(rest -> player.getWorld().dropItemNaturally(player.getLocation(), rest));
            left -= size;
        }
        Msg.success(sender, "Gave <hl><player></hl> <hl>" + amount + "</hl> cooldown voucher" + (amount == 1 ? "" : "s") + ".", name(player));
        if (!player.equals(sender)) Msg.success(player, "You received <hl>" + amount + "</hl> warzone cooldown voucher"
                + (amount == 1 ? "" : "s") + ". Right-click one to reset your cooldown.");
    }

    private void setCooldown(CommandSender sender, String[] args) {
        long duration;
        try {
            duration = Time.parseCooldown(args[1]);
        } catch (IllegalArgumentException e) {
            Msg.error(sender, e.getMessage());
            return;
        }
        OfflinePlayer target = Bukkit.getPlayerExact(args[0]);
        if (target == null) target = Bukkit.getOfflinePlayerIfCached(args[0]);
        if (target == null) {
            Msg.error(sender, "Unknown player <hl><player></hl>.", Placeholder.unparsed("player", args[0]));
            return;
        }
        if (!stays.setCooldown(target.getUniqueId(), duration)) {
            Msg.error(sender, "Couldn't save the cooldown. Please try again.");
            return;
        }
        var name = Placeholder.unparsed("player", target.getName() == null ? args[0] : target.getName());
        if (duration == 0) Msg.success(sender, "Cleared <hl><player></hl>'s warzone cooldown.", name);
        else Msg.success(sender, "Set <hl><player></hl>'s warzone cooldown to <hl>" + Time.format(duration) + "</hl>.", name);
    }

    private void search(Player player, long duration, boolean charge, CommandSender admin) {
        CommandSender feedback = admin == null ? player : admin;
        UUID id = player.getUniqueId();
        if (stays.active(id)) {
            Msg.error(feedback, "<hl><player></hl> is already in the warzone. Use <hl>/sendtosafe</hl> first.", name(player));
            return;
        }
        if (searches.containsKey(id)) {
            Msg.error(feedback, "Already searching for a safe spot.");
            return;
        }
        World world = Bukkit.getWorld(warzone);
        if (world == null || world.getEnvironment() != World.Environment.NORMAL) {
            Msg.error(feedback, "The warzone is unavailable right now.");
            return;
        }
        Location home = isWarzone(player.getWorld()) ? safeSpawn() : player.getLocation().clone();
        if (home == null) {
            Msg.error(feedback, "The safe world is unavailable right now.");
            return;
        }
        Search search = new Search(UUID.randomUUID(), home, duration, charge, admin);
        searches.put(id, search);
        Msg.info(feedback, "<muted>Searching for a safe landing spot…");
        if (admin == null) player.playSound(player, Sound.BLOCK_BEACON_ACTIVATE, 0.6f, 1.6f);
        destination(player, world, feedback).thenAccept(target -> {
            boolean valid = player.isOnline() && !player.isDead() && searches.get(id) == search
                    && (admin != null || player.getWorld().getName().equals(main));
            searches.remove(id, search);
            if (!valid) return;
            if (target == null) {
                Msg.error(feedback, "Couldn't find a safe spot. Please try again.");
                return;
            }
            target.setRotation(player.getYaw(), player.getPitch());
            if (!stays.enter(player, target, home, duration, charge)) Msg.error(feedback, "Teleport failed. Please try again.");
            else if (admin != null) Msg.success(feedback, "Sent <hl><player></hl> to the warzone for <hl>"
                    + Time.format(duration) + "</hl>.", name(player));
        });
    }

    private CompletableFuture<Location> destination(Player player, World world, CommandSender feedback) {
        Location point = spawns == null ? null : spawns.warzonePoint(player.getUniqueId());
        if (point == null) return findSafe(new Location(world, 0, 0, 0), radius, attempts);
        var result = new CompletableFuture<Location>();
        point.getWorld().getChunkAtAsync(point).whenComplete((chunk, error) -> {
            if (!isEnabled()) return;
            getServer().getScheduler().runTask(this, () -> {
                Location spawn = error == null && player.isOnline() ? spawns.validWarzonePoint(player) : null;
                if (spawn != null) {
                    Msg.info(feedback, "<muted>Using the warzone spawn point.");
                    result.complete(spawn);
                } else {
                    findSafe(new Location(world, 0, 0, 0), radius, attempts).thenAccept(result::complete);
                }
            });
        });
        return result;
    }

    CompletableFuture<Location> findSafe(Location center, int range, int tries) {
        var result = new CompletableFuture<Location>();
        find(center.clone(), range, tries, result);
        return result;
    }

    private void find(Location center, int range, int tries, CompletableFuture<Location> result) {
        if (result.isDone() || !isEnabled()) return;
        if (tries == 0) {
            result.complete(null);
            return;
        }
        World world = center.getWorld();
        double angle = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
        double distance = Math.sqrt(ThreadLocalRandom.current().nextDouble()) * range;
        int x = (int) Math.floor(center.getX() + Math.cos(angle) * distance);
        int z = (int) Math.floor(center.getZ() + Math.sin(angle) * distance);
        if (Math.hypot(x + 0.5 - center.getX(), z + 0.5 - center.getZ()) > range
                || !world.getWorldBorder().isInside(new Location(world, x + 0.5, 0, z + 0.5))) {
            getServer().getScheduler().runTask(this, () -> find(center, range, tries - 1, result));
            return;
        }
        world.getChunkAtAsync(x >> 4, z >> 4, true).whenComplete((chunk, error) -> {
            if (!isEnabled() || result.isDone()) return;
            getServer().getScheduler().runTask(this, () -> {
                if (result.isDone()) return;
                if (error != null) {
                    log().warn("Could not load RTP chunk: " + error.getMessage());
                    result.complete(null);
                    return;
                }
                Block ground = world.getHighestBlockAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
                Location target = new Location(world, x + 0.5, ground.getY() + 1, z + 0.5);
                if (safe(ground) && world.getWorldBorder().isInside(target)) result.complete(target);
                else find(center, range, tries - 1, result);
            });
        });
    }

    private static boolean safe(Block ground) {
        Material type = ground.getType();
        if (!type.isSolid() || Tag.LEAVES.isTagged(type) || dangerous(type)
                || ground.getY() + 2 >= ground.getWorld().getMaxHeight()) return false;
        for (int y = 1; y <= 2; y++) {
            Block space = ground.getRelative(0, y, 0);
            if (!space.isPassable() || space.isLiquid() || dangerous(space.getType())) return false;
        }
        for (int dx = -1; dx <= 1; dx++)
            for (int dz = -1; dz <= 1; dz++)
                for (int dy = 0; dy <= 2; dy++)
                    if (dangerous(ground.getRelative(dx, dy, dz).getType())) return false;
        return true;
    }

    private static boolean dangerous(Material type) {
        return switch (type) {
            case LAVA, FIRE, SOUL_FIRE, MAGMA_BLOCK, CACTUS, SWEET_BERRY_BUSH,
                 WITHER_ROSE, CAMPFIRE, SOUL_CAMPFIRE, POWDER_SNOW, POINTED_DRIPSTONE -> true;
            default -> false;
        };
    }

    private boolean blockFirework(Player player) {
        if (!isWarzone(player.getWorld())) return false;
        if (fireworkMode.equals("disabled")) {
            Msg.actionBar(player, "<bad>✖</bad> <#FFA3A3>Fireworks are disabled in the warzone");
            return true;
        }
        long fighting = combat.remaining(player);
        if (fighting > 0) {
            Msg.actionBar(player, "<bad>✖</bad> <#FFA3A3>Fireworks are disabled in combat <dim>»</dim> <hl>" + Time.format(fighting));
            return true;
        }
        if (combat.danger(player)) {
            Msg.actionBar(player, "<bad>✖</bad> <#FFA3A3>Fireworks are disabled while danger logged");
            return true;
        }
        Long last = fireworks.get(player.getUniqueId());
        long elapsed = last == null ? fireworkNanos : System.nanoTime() - last;
        if (!fireworkMode.equals("cooldown") || elapsed >= fireworkNanos) return false;
        Msg.actionBar(player, "<bad>✖</bad> <#FFA3A3>Fireworks on cooldown <dim>»</dim> <hl>"
                + (fireworkNanos - elapsed + 999_999_999L) / 1_000_000_000L + "s");
        return true;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBoost(PlayerElytraBoostEvent event) {
        if (blockFirework(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFirework(ProjectileLaunchEvent event) {
        if (!(event.getEntity() instanceof Firework firework) || !isWarzone(firework.getWorld())) return;
        if (firework.getShooter() instanceof Player player) {
            if (blockFirework(player)) event.setCancelled(true);
        } else if (fireworkMode.equals("disabled")) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFireworkLaunched(ProjectileLaunchEvent event) {
        if (event.getEntity() instanceof Firework firework && firework.getShooter() instanceof Player player
                && isWarzone(firework.getWorld()) && fireworkMode.equals("cooldown")) {
            fireworks.put(player.getUniqueId(), System.nanoTime());
        }
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        cancelSearch(player.getUniqueId());
        if (isWarzone(player.getWorld()) && !isWarzone(event.getFrom())) {
            Msg.title(player, "<" + Msg.BRAND + "><b>⚔ WARZONE ⚔</b></gradient>", "<#FFB4A2>You are now in the warzone!", 3000);
            player.playSound(player, Sound.ENTITY_ENDER_DRAGON_GROWL, 0.45f, 1.2f);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cancelSearch(event.getPlayer().getUniqueId());
    }
}
