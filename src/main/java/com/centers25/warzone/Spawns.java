package com.centers25.warzone;

import com.destroystokyo.paper.event.player.PlayerSetSpawnEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

final class Spawns implements Listener {
    private record Point(String world, double x, double y, double z, float yaw, boolean forced) {
        static Point of(Location at, boolean forced) {
            return new Point(at.getWorld().getName(), at.getX(), at.getY(), at.getZ(), at.getYaw(), forced);
        }

        Location location() {
            var loaded = Bukkit.getWorld(world);
            return loaded == null ? null : new Location(loaded, x, y, z, yaw, 0);
        }
    }

    private final Warzone plugin;
    private final Path file;
    private final Map<String, Point> points = new HashMap<>();
    private boolean swapping;

    Spawns(Warzone plugin) throws Exception {
        this.plugin = plugin;
        file = plugin.getDataFolder().toPath().resolve("spawns.yml");
        if (Files.exists(file)) {
            YamlConfiguration data = new YamlConfiguration();
            data.load(file.toFile());
            ConfigurationSection players = data.getConfigurationSection("players");
            if (players != null) for (String id : players.getKeys(false)) {
                for (String side : new String[] {"main", "warzone"}) {
                    ConfigurationSection p = players.getConfigurationSection(id + "." + side);
                    if (p == null) continue;
                    points.put(UUID.fromString(id) + "." + side, new Point(p.getString("world"), p.getDouble("x"),
                            p.getDouble("y"), p.getDouble("z"), (float) p.getDouble("yaw"), p.getBoolean("forced")));
                }
            }
        }
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getOnlinePlayers().forEach(this::swap);
    }

    private static String key(UUID id, boolean warzone) {
        return id + (warzone ? ".warzone" : ".main");
    }

    Location warzonePoint(UUID id) {
        Point point = points.get(key(id, true));
        return point == null ? null : point.location();
    }

    Location validWarzonePoint(Player player) {
        Point point = points.get(key(player.getUniqueId(), true));
        if (point == null) return null;
        try {
            set(player, point);
            Location valid = player.getRespawnLocation(true);
            return valid != null && plugin.isWarzone(valid.getWorld()) ? valid : null;
        } finally {
            apply(player);
        }
    }

    private void set(Player player, Point point) {
        swapping = true;
        try {
            player.setRespawnLocation(point == null ? null : point.location(), point != null && point.forced());
        } finally {
            swapping = false;
        }
    }

    private void apply(Player player) {
        set(player, points.get(key(player.getUniqueId(), plugin.isWarzone(player.getWorld()))));
    }

    private void swap(Player player) {
        Location current = player.getRespawnLocation(false);
        if (current != null && current.getWorld() != null) {
            String key = key(player.getUniqueId(), plugin.isWarzone(current.getWorld()));
            if (!points.containsKey(key)) {
                Material type = current.getBlock().getType();
                points.put(key, Point.of(current, !Tag.BEDS.isTagged(type) && type != Material.RESPAWN_ANCHOR));
                save();
            }
        }
        apply(player);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSetSpawn(PlayerSetSpawnEvent event) {
        if (swapping) return;
        Player player = event.getPlayer();
        Location at = event.getLocation();
        boolean warzone = plugin.isWarzone(at == null ? player.getWorld() : at.getWorld());
        if (at == null) points.remove(key(player.getUniqueId(), warzone));
        else points.put(key(player.getUniqueId(), warzone), Point.of(at, event.isForced()));
        save();
        if (warzone != plugin.isWarzone(player.getWorld())) Bukkit.getScheduler().runTask(plugin, () -> apply(player));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        if (plugin.isWarzone(event.getFrom()) != plugin.isWarzone(event.getPlayer().getWorld())) swap(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        swap(event.getPlayer());
    }

    private void save() {
        YamlConfiguration data = new YamlConfiguration();
        points.forEach((key, point) -> {
            String path = "players." + key + ".";
            data.set(path + "world", point.world());
            data.set(path + "x", point.x());
            data.set(path + "y", point.y());
            data.set(path + "z", point.z());
            data.set(path + "yaw", point.yaw());
            data.set(path + "forced", point.forced());
        });
        try {
            Store.write(file, data.saveToString());
        } catch (IOException e) {
            plugin.log().error("Could not save spawns.yml", e);
        }
    }
}
