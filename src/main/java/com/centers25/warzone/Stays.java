package com.centers25.warzone;

import io.papermc.paper.command.brigadier.BasicCommand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN;

final class Stays implements Listener {
    private static final int COUNTDOWN = 5;
    private static final long DEATH_TIME = 10 * 60_000L;
    private static final String[] COLORS = {"#FF5C3D", "#FF9F43", "#FFD93D", "#B8F04A", "#5CF28A"};

    record Home(String world, double x, double y, double z, float yaw, float pitch) {
        static Home of(Location at) {
            return new Home(at.getWorld().getName(), at.getX(), at.getY(), at.getZ(), at.getYaw(), at.getPitch());
        }

        Location location() {
            var loaded = Bukkit.getWorld(world);
            return loaded == null ? null : new Location(loaded, x, y, z, yaw, pitch);
        }
    }

    static final class Stay {
        final Playtime playtime;
        final long duration;
        int deaths;
        final Map<Integer, Reward> time = new HashMap<>();
        final Home home;

        Stay(long remaining, long duration, int deaths, Map<Integer, Long> time, Home home) {
            this.playtime = new Playtime(remaining);
            this.duration = duration;
            this.deaths = deaths;
            time.forEach((players, ms) -> this.time.put(players, new Reward(ms)));
            this.home = home;
        }

        void pause(long now) {
            playtime.update(now, false);
            time.values().forEach(clock -> clock.update(now, false));
        }

        Map<Integer, Long> totals(long now) {
            Map<Integer, Long> totals = new HashMap<>();
            time.forEach((players, clock) -> totals.put(players, clock.total(now)));
            return totals;
        }
    }

    private final Warzone plugin;
    private final Path file;
    private final Map<UUID, Stay> stays = new HashMap<>();
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Set<UUID> returning = new HashSet<>();
    private final Set<UUID> leaving = new HashSet<>();
    private final Map<UUID, Long> quits = new HashMap<>();

    Stays(Warzone plugin) throws Exception {
        this.plugin = plugin;
        file = plugin.getDataFolder().toPath().resolve("stays.yml");
        if (!Files.exists(file)) return;
        YamlConfiguration data = new YamlConfiguration();
        data.load(file.toFile());
        ConfigurationSection players = data.getConfigurationSection("players");
        if (players == null) return;
        long now = System.currentTimeMillis();
        for (String key : players.getKeys(false)) {
            UUID id = UUID.fromString(key);
            ConfigurationSection p = players.getConfigurationSection(key);
            if (p.getLong("cooldown-until") > now) cooldowns.put(id, p.getLong("cooldown-until"));
            if (!p.contains("remaining-ms") && !p.contains("until")) continue;
            String world = p.getString("return.world");
            if (world == null) throw new IOException("Missing return world for " + id);
            long remaining = p.contains("remaining-ms") ? p.getLong("remaining-ms") : Math.max(0, p.getLong("until") - now);
            Map<Integer, Long> time = new HashMap<>();
            ConfigurationSection saved = p.getConfigurationSection("time-ms");
            if (saved != null) {
                for (String count : saved.getKeys(false)) time.put(Integer.parseInt(count), saved.getLong(count));
            } else {
                long reward = p.getLong("reward-ms");
                time.put(2, reward);
                time.put(1, Math.max(0, p.getLong("played-ms", reward) - reward));
            }
            long duration = p.getLong("duration-ms", remaining + time.values().stream().mapToLong(Long::longValue).sum());
            stays.put(id, new Stay(remaining, duration, p.getInt("deaths"), time, new Home(world, p.getDouble("return.x"),
                    p.getDouble("return.y"), p.getDouble("return.z"), (float) p.getDouble("return.yaw"),
                    (float) p.getDouble("return.pitch"))));
        }
    }

    void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) check(player);
        }, 20L, 20L);
        Bukkit.getOnlinePlayers().forEach(this::account);
        Bukkit.getScheduler().runTaskTimer(plugin, this::save, 600L, 600L);
        plugin.registerCommand("leavewarzone", "Leave the warzone early. Clears your inventory.", (BasicCommand) (source, args) -> {
            if (source.getSender() instanceof Player player) leave(player);
            else Msg.error(source.getSender(), "Only players can use this.");
        });
    }

    private boolean trapped(Player player) {
        if (plugin.combat().remaining(player) > 0) Msg.error(player, "You can't leave while in combat.");
        else if (plugin.combat().danger(player)) Msg.error(player, "You can't leave while danger logged.");
        else return false;
        return true;
    }

    private void leave(Player player) {
        UUID id = player.getUniqueId();
        Stay stay = stays.get(id);
        if (stay == null || !plugin.isWarzone(player.getWorld())) {
            Msg.error(player, "You're not in the warzone.");
            return;
        }
        if (returning.contains(id) || remaining(id) == 0) {
            Msg.info(player, "<muted>You're already being returned to safety.");
            return;
        }
        if (player.isDead() || trapped(player)) return;
        Location home = stay.home.location();
        if (home == null || plugin.isWarzone(home.getWorld())) home = plugin.safeSpawn();
        if (home == null) {
            Msg.error(player, "The safe world is unavailable right now.");
            return;
        }
        account(player);
        Long warned = quits.remove(id);
        if (warned != null && System.nanoTime() - warned < 30_000_000_000L) {
            countdown(player, stay, home, true);
            return;
        }
        quits.put(id, System.nanoTime());
        int debt = plugin.tickets().forfeit(stay.totals(System.nanoTime()));
        Component confirm = Msg.parse("<bad><b>[ ✖ LEAVE THE WARZONE ]</b>")
                .clickEvent(ClickEvent.runCommand("/leavewarzone"))
                .hoverEvent(HoverEvent.showText(Msg.parse("<hl>Click to confirm</hl>\n<muted>Runs /leavewarzone")));
        player.sendMessage(Msg.parse(String.join("\n",
                Msg.RULE,
                " <" + Msg.BRAND + "><b>⚠ LEAVE EARLY ⚠</b></gradient>",
                "",
                " <#FF6B6B>Your whole inventory will be cleared!</#FF6B6B>",
                " <text>You get <hl>no Friend Tickets</hl> and owe a debt of <hl>" + debt + " Friend Tickets</hl>.",
                " <muted>Debt is taken from tickets you get later.",
                "",
                " <confirm> <dim>or run</dim> <hl>/leavewarzone</hl> <dim>again within 30s",
                Msg.RULE), Placeholder.component("confirm", confirm)));
        player.playSound(player, Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.6f);
    }

    private void check(Player player) {
        account(player);
        Stay stay = stays.get(player.getUniqueId());
        if (stay == null) return;
        if (remaining(player.getUniqueId()) == 0) returnHome(player, stay);
        else if (!plugin.isWarzone(player.getWorld())) toWarzone(player);
    }

    private void account(Player player) {
        Stay stay = stays.get(player.getUniqueId());
        if (stay == null) return;
        long now = System.nanoTime();
        boolean eligible = player.isOnline() && !player.isDead() && plugin.isWarzone(player.getWorld());
        stay.playtime.update(now, eligible);
        boolean counting = eligible && stay.playtime.remaining(now) > 0;
        int players = plugin.tickets().bucket(plugin.population());
        stay.time.computeIfAbsent(players, p -> new Reward(0));
        stay.time.forEach((p, clock) -> clock.update(now, counting && p == players));
    }

    void shutdown() {
        long now = System.nanoTime();
        stays.values().forEach(stay -> stay.pause(now));
        save();
    }

    boolean active(UUID id) {
        return stays.containsKey(id);
    }

    long remaining(UUID id) {
        Stay stay = stays.get(id);
        return stay == null ? 0 : stay.playtime.remaining(System.nanoTime());
    }

    long adjust(UUID id, long delta) {
        Stay stay = stays.get(id);
        if (stay == null) return -1;
        stay.playtime.adjust(System.nanoTime(), delta);
        save();
        return remaining(id);
    }

    long cooldown(UUID id) {
        return Math.max(0, cooldowns.getOrDefault(id, 0L) - System.currentTimeMillis());
    }

    boolean setCooldown(UUID id, long duration) {
        Long previous = cooldowns.get(id);
        if (duration == 0) cooldowns.remove(id);
        else cooldowns.put(id, Math.addExact(System.currentTimeMillis(), duration));
        if (save()) {
            plugin.cancelSearch(id);
            return true;
        }
        restore(id, previous);
        return false;
    }

    private void restore(UUID id, Long cooldown) {
        if (cooldown == null) cooldowns.remove(id);
        else cooldowns.put(id, cooldown);
    }

    boolean enter(Player player, Location destination, Location home, long duration, boolean charge) {
        UUID id = player.getUniqueId();
        if (active(id)) return false;
        Long previous = cooldowns.get(id);
        stays.put(id, new Stay(duration, duration, 0, Map.of(), Home.of(home)));
        if (charge) cooldowns.put(id, System.currentTimeMillis() + plugin.cooldown());
        boolean saved = save();
        if (!saved) Msg.error(player, "Couldn't save your stay. Please try again.");
        boolean moved = false;
        try {
            moved = saved && player.teleport(destination, PLUGIN);
        } finally {
            if (!moved) {
                stays.remove(id);
                restore(id, previous);
                if (saved) save();
            }
        }
        if (!moved) return false;
        account(player);
        player.setFallDistance(0);
        Msg.info(player, "You've entered the warzone for <hl>" + Time.format(duration)
                + "</hl> of playtime. <muted>You'll be returned automatically.");
        return true;
    }

    void sendSafe(Player player) {
        plugin.cancelSearch(player.getUniqueId());
        Stay stay = stays.get(player.getUniqueId());
        Location home = stay == null ? null : stay.home.location();
        if (home == null || plugin.isWarzone(home.getWorld())) home = plugin.safeSpawn();
        if (home == null || plugin.isWarzone(home.getWorld())) {
            Msg.error(player, "The safe world is unavailable right now.");
            return;
        }
        countdown(player, stay, home, false);
    }

    private void returnHome(Player player, Stay stay) {
        Location home = stay.home.location();
        if (home != null && !plugin.isWarzone(home.getWorld())) countdown(player, stay, home, false);
    }

    private void countdown(Player player, Stay stay, Location home, boolean quit) {
        UUID id = player.getUniqueId();
        if (player.isDead() || !returning.add(id)) return;
        Msg.info(player, "Returning you to safety in <hl>" + COUNTDOWN + "s</hl>…");
        player.playSound(player, Sound.BLOCK_PORTAL_TRIGGER, 0.25f, 1.4f);
        int[] tick = {0};
        BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (!player.isOnline() || player.isDead() || stays.get(id) != stay) {
                task[0].cancel();
                returning.remove(id);
                if (player.isOnline()) player.clearTitle();
                return;
            }
            int t = tick[0]++;
            if (t >= COUNTDOWN * 20) {
                task[0].cancel();
                teleportHome(player, stay, home, quit);
                return;
            }
            if (t % 20 == 0) tick(player, COUNTDOWN - t / 20);
            if (t % 2 == 0) swirl(player, (double) t / (COUNTDOWN * 20));
        }, 0L, 1L);
    }

    private static void tick(Player player, int seconds) {
        int filled = COUNTDOWN - seconds + 1;
        String bar = "<good>" + "■ ".repeat(filled) + "</good><dim>" + "■ ".repeat(COUNTDOWN - filled) + "</dim>";
        player.showTitle(Title.title(Msg.parse("<" + COLORS[filled - 1] + "><b>" + seconds + "</b>"),
                Msg.parse("<#B8F5CB>Returning to safety  " + bar.trim()),
                Title.Times.times(seconds == COUNTDOWN ? Duration.ofMillis(150) : Duration.ZERO,
                        Duration.ofMillis(1100), Duration.ofMillis(100))));
        player.playSound(player, Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 0.7f + 0.2f * (COUNTDOWN - seconds));
        player.playSound(player, Sound.BLOCK_NOTE_BLOCK_BASEDRUM, 0.5f, 0.8f);
    }

    private static void swirl(Player player, double progress) {
        Location base = player.getLocation();
        double radius = 1.1 - progress * 0.6;
        for (int i = 0; i < 3; i++) {
            double angle = progress * Math.PI * 12 + i * (Math.PI * 2 / 3);
            player.getWorld().spawnParticle(Particle.REVERSE_PORTAL, base.clone().add(Math.cos(angle) * radius,
                    progress * 2, Math.sin(angle) * radius), 2, 0.02, 0.02, 0.02, 0);
        }
    }

    private void teleportHome(Player player, Stay stay, Location home, boolean quit) {
        UUID id = player.getUniqueId();
        Location from = player.getLocation().clone().add(0, 1, 0);
        home.getWorld().getChunkAtAsync(home.getBlockX() >> 4, home.getBlockZ() >> 4, true).whenComplete((chunk, error) -> {
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    if (error != null || !player.isOnline() || player.isDead() || stays.get(id) != stay) return;
                    if (quit && trapped(player)) return;
                    leaving.add(id);
                    long now = System.nanoTime();
                    boolean completed = stay != null && stay.playtime.remaining(now) == 0;
                    Map<Integer, Long> time = stay == null ? Map.of() : stay.totals(now);
                    if (!player.teleport(home, PLUGIN)) return;
                    from.getWorld().spawnParticle(Particle.REVERSE_PORTAL, from, 60, 0.3, 0.8, 0.3, 0.05);
                    from.getWorld().playSound(from, Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f);
                    player.playSound(player, Sound.ENTITY_ENDERMAN_TELEPORT, 0.8f, 1.2f);
                    stays.remove(id);
                    save();
                    player.setFallDistance(0);
                    Msg.success(player, "You've been returned to safety.");
                    Msg.title(player, "<gradient:#5CF28A:#3FC1FF><b>✔ SAFE</b></gradient>", "<#B8F5CB>You've left the warzone", 2000);
                    player.playSound(player, Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.4f);
                    if (quit) {
                        player.getInventory().clear();
                        player.setItemOnCursor(null);
                        int debt = plugin.tickets().forfeit(time);
                        Msg.error(player, "You left early: inventory cleared, no Friend Tickets, and a debt of <hl>"
                                + debt + " Friend Tickets</hl>.");
                        plugin.tickets().addDebt(player, debt);
                    } else if (completed) {
                        plugin.tickets().award(player, stay.duration, time, stay.deaths);
                    }
                } finally {
                    leaving.remove(id);
                    returning.remove(id);
                }
            });
        });
    }

    private void toWarzone(Player player) {
        Location spawn = plugin.warzoneSpawn();
        if (spawn != null && !player.isDead()) player.teleportAsync(spawn, PLUGIN);
    }

    private void blockExit(PlayerTeleportEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        if (!active(id) || leaving.contains(id) || (event.getTo() != null && plugin.isWarzone(event.getTo().getWorld()))) return;
        event.setCancelled(true);
        Msg.error(event.getPlayer(), "You can't leave the warzone yet. <hl>" + Time.format(remaining(id)) + "</hl> remaining.");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        blockExit(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent event) {
        blockExit(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent event) {
        boolean inWarzone = plugin.isWarzone(event.getRespawnLocation().getWorld());
        Location spawn = null;
        if (active(event.getPlayer().getUniqueId())) {
            if (!inWarzone) spawn = plugin.warzoneSpawn();
        } else if (inWarzone && !plugin.isWarzone(event.getPlayer().getWorld())) {
            spawn = plugin.safeSpawn();
        }
        if (spawn != null) event.setRespawnLocation(spawn);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void afterRespawn(PlayerRespawnEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> account(event.getPlayer()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        Stay stay = stays.get(player.getUniqueId());
        if (stay == null) return;
        long now = System.nanoTime();
        stay.pause(now);
        if (plugin.isWarzone(player.getWorld())) {
            stay.deaths++;
            stay.playtime.adjust(now, DEATH_TIME);
            int penalty = Tickets.penalty(stay.deaths);
            Msg.error(player, "<hl>+" + Time.format(DEATH_TIME) + "</hl> warzone time and "
                    + (penalty == 100 ? "<hl>no Friend Tickets</hl> this stay" : "<hl>-" + penalty + "%</hl> Friend Tickets")
                    + " for dying <muted>(" + stay.deaths + (stay.deaths == 1 ? " death" : " deaths")
                    + " this stay, " + Time.format(stay.playtime.remaining(now)) + " left)");
        }
        save();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        pause(event.getPlayer());
    }

    private void pause(Player player) {
        Stay stay = stays.get(player.getUniqueId());
        if (stay == null) return;
        stay.pause(System.nanoTime());
        save();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        account(event.getPlayer());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTask(plugin, () -> check(event.getPlayer()));
    }

    boolean save() {
        YamlConfiguration data = new YamlConfiguration();
        data.set("format-version", 2);
        long now = System.nanoTime();
        cooldowns.values().removeIf(until -> until <= System.currentTimeMillis());
        cooldowns.forEach((id, until) -> data.set("players." + id + ".cooldown-until", until));
        stays.forEach((id, stay) -> {
            String path = "players." + id + ".";
            Home home = stay.home;
            data.set(path + "remaining-ms", stay.playtime.remaining(now));
            data.set(path + "duration-ms", stay.duration);
            data.set(path + "deaths", stay.deaths);
            stay.totals(now).forEach((players, ms) -> data.set(path + "time-ms." + players, ms));
            data.set(path + "return.world", home.world());
            data.set(path + "return.x", home.x());
            data.set(path + "return.y", home.y());
            data.set(path + "return.z", home.z());
            data.set(path + "return.yaw", home.yaw());
            data.set(path + "return.pitch", home.pitch());
        });
        try {
            Store.write(file, data.saveToString());
            return true;
        } catch (IOException e) {
            plugin.log().error("Could not save stays.yml", e);
            return false;
        }
    }
}
