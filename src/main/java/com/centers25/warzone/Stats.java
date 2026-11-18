package com.centers25.warzone;

import io.papermc.paper.command.brigadier.BasicCommand;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

final class Stats implements Listener {
    private static final class Entry {
        int kills;
        int deaths;
    }

    private final Warzone plugin;
    private final Path file;
    private final Map<UUID, Entry> entries = new HashMap<>();
    private final Map<String, Long> pairs = new HashMap<>();
    private final long killerTime;
    private final long window;
    private boolean dirty;

    Stats(Warzone plugin, long killerTime, long window) throws Exception {
        this.plugin = plugin;
        this.killerTime = killerTime;
        this.window = window;
        file = plugin.getDataFolder().toPath().resolve("combat.yml");
        if (!Files.exists(file)) return;
        YamlConfiguration data = new YamlConfiguration();
        data.load(file.toFile());
        ConfigurationSection players = data.getConfigurationSection("players");
        if (players != null) for (String key : players.getKeys(false)) {
            Entry entry = new Entry();
            entry.kills = Math.max(0, players.getInt(key + ".kills"));
            entry.deaths = Math.max(0, players.getInt(key + ".deaths"));
            entries.put(UUID.fromString(key), entry);
        }
        ConfigurationSection kills = data.getConfigurationSection("kill-time");
        long now = System.currentTimeMillis();
        if (kills != null) for (String path : kills.getKeys(true)) {
            if (kills.isConfigurationSection(path)) continue;
            String[] ids = path.split("\\.");
            String pair = UUID.fromString(ids[0]) + ":" + UUID.fromString(ids[1]);
            long at = kills.getLong(path);
            if (now - at < window) pairs.put(pair, at);
        }
    }

    void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::save, 600L, 600L);
        plugin.registerCommand("kills", "View your warzone kills.", show(true));
        plugin.registerCommand("deaths", "View your warzone deaths.", show(false));
    }

    private Entry entry(Player player) {
        return entries.computeIfAbsent(player.getUniqueId(), id -> new Entry());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        if (!plugin.isWarzone(victim.getWorld())) return;
        entry(victim).deaths++;
        Player killer = victim.getKiller();
        if (killer != null && !killer.equals(victim) && plugin.isWarzone(killer.getWorld())) {
            entry(killer).kills++;
            changeTime(killer, victim);
        }
        dirty = true;
    }

    private void changeTime(Player killer, Player victim) {
        Stays stays = plugin.stays();
        String pair = killer.getUniqueId() + ":" + victim.getUniqueId();
        long now = System.currentTimeMillis();
        Long last = pairs.get(pair);
        var victimName = Placeholder.unparsed("victim", victim.getName());
        if (!stays.active(killer.getUniqueId())) return;
        if (last != null && now - last < window) {
            Msg.info(killer, "<muted>No time change: you already killed <hl><victim></hl> recently. <dim>("
                    + Time.format(window - (now - last)) + ")</dim>", victimName);
            return;
        }
        pairs.put(pair, now);
        long killerLeft = stays.adjust(killer.getUniqueId(), -killerTime);
        if (killerLeft >= 0 && killerTime > 0) Msg.success(killer, "<hl>-" + Time.format(killerTime)
                + "</hl> warzone time for killing <hl><victim></hl>. <muted>" + Time.format(killerLeft) + " left.", victimName);
    }

    private BasicCommand show(boolean kills) {
        return (source, args) -> {
            if (!(source.getSender() instanceof Player player)) {
                Msg.error(source.getSender(), "Only players can use this.");
                return;
            }
            Entry entry = entries.getOrDefault(player.getUniqueId(), new Entry());
            int value = kills ? entry.kills : entry.deaths;
            Msg.info(player, "You have <hl>" + value + "</hl> warzone " + (kills ? "kill" : "death") + (value == 1 ? "" : "s") + ".");
        };
    }

    void save() {
        if (!dirty) return;
        YamlConfiguration data = new YamlConfiguration();
        entries.forEach((id, entry) -> {
            data.set("players." + id + ".kills", entry.kills);
            data.set("players." + id + ".deaths", entry.deaths);
        });
        long now = System.currentTimeMillis();
        pairs.values().removeIf(at -> now - at >= window);
        pairs.forEach((pair, at) -> data.set("kill-time." + pair.replace(':', '.'), at));
        try {
            Store.write(file, data.saveToString());
            dirty = false;
        } catch (IOException e) {
            plugin.log().error("Could not save combat.yml", e);
        }
    }
}
