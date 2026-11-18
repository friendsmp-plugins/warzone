package com.centers25.warzone;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

final class Afk implements Listener {
    private static final long[] WARNINGS = {60, 30, 10};
    private static final long SECOND = 1_000_000_000L;

    private final Warzone plugin;
    private final long idle;
    private final Map<UUID, Long> active = new HashMap<>();
    private final Map<UUID, Integer> warned = new HashMap<>();

    Afk(Warzone plugin, int minutes) {
        this.plugin = plugin;
        this.idle = minutes * 60 * SECOND;
        if (idle == 0) return;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    private void touch(Player player) {
        active.put(player.getUniqueId(), System.nanoTime());
        if (warned.remove(player.getUniqueId()) != null) Msg.success(player, "You moved. You won't be killed for being AFK.");
    }

    private void tick() {
        long now = System.nanoTime();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.isDead() || !plugin.isWarzone(player.getWorld())
                    || (player.getGameMode() != GameMode.SURVIVAL && player.getGameMode() != GameMode.ADVENTURE)) continue;
            UUID id = player.getUniqueId();
            long left = idle - (now - active.computeIfAbsent(id, k -> now));
            int stage = warned.getOrDefault(id, 0);
            if (stage < WARNINGS.length && left <= WARNINGS[stage] * SECOND) {
                warned.put(id, stage + 1);
                long seconds = Math.max(1, (left + SECOND - 1) / SECOND);
                Msg.title(player, "<#FFD93D><b>⚠ AFK</b>", "<#FFE9A3>Killed in <hl>" + seconds + "s</hl> if you don't move", 2500);
                Msg.error(player, "You have stood still too long! You will be killed by the system in <hl>" + seconds
                        + "s</hl> if you don't move.");
                player.playSound(player, Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 0.5f + 0.25f * stage);
            } else if (left <= 0) {
                warned.remove(id);
                active.put(id, now);
                player.setHealth(0);
                Msg.error(player, "You were killed by the system for standing still too long.");
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event.hasChangedOrientation()) touch(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInteract(PlayerInteractEvent event) {
        touch(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        touch(event.getPlayer());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        touch(event.getPlayer());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        touch(event.getPlayer());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        touch(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        active.remove(event.getPlayer().getUniqueId());
        warned.remove(event.getPlayer().getUniqueId());
    }
}
