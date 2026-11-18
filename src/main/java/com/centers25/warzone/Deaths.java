package com.centers25.warzone;

import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.WorldLoadEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

final class Deaths implements Listener {
    private static final class Respawn {
        final CompletableFuture<Location> location;
        boolean respawned;

        Respawn(CompletableFuture<Location> location) {
            this.location = location;
        }
    }

    private final Warzone plugin;
    private final int radius;
    private final int attempts;
    private final Map<UUID, Respawn> pending = new HashMap<>();

    Deaths(Warzone plugin, int radius, int attempts) {
        this.plugin = plugin;
        this.radius = radius;
        this.attempts = attempts;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getWorlds().forEach(this::configure);
    }

    private void configure(World world) {
        if (plugin.isWarzone(world)) world.setGameRule(GameRule.KEEP_INVENTORY, false);
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        configure(event.getWorld());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (!plugin.isWarzone(player.getWorld())) return;
        plugin.cancelSearch(player.getUniqueId());
        if (event.getKeepInventory() || !event.getItemsToKeep().isEmpty()) {
            event.getDrops().clear();
            for (var item : player.getInventory().getContents()) {
                if (item != null && !item.getType().isAir() && !item.containsEnchantment(Enchantment.VANISHING_CURSE))
                    event.getDrops().add(item.clone());
            }
        }
        event.getItemsToKeep().clear();
        event.setKeepInventory(false);
        event.setKeepLevel(false);
        event.setNewExp(0);
        event.setNewLevel(0);
        event.setNewTotalExp(0);
        event.setShouldDropExperience(true);
        event.setDroppedExp((int) Math.min(100L, 7L * player.getLevel()));
        var timer = Bukkit.getScoreboardManager().getMainScoreboard().getObjective("ket.pvp_timer");
        if (timer != null) {
            var score = timer.getScore(player.getName());
            score.setScore(Math.max(100, score.getScore()));
        }
        prepare(player);
    }

    private Respawn prepare(Player player) {
        clear(player.getUniqueId());
        Location spawn = plugin.warzoneSpawn();
        Respawn request = new Respawn(spawn == null
                ? CompletableFuture.completedFuture(null) : plugin.findSafe(spawn, radius, attempts));
        pending.put(player.getUniqueId(), request);
        request.location.whenComplete((target, error) -> {
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (pending.get(player.getUniqueId()) == request && request.respawned) finish(player, target, error);
            });
        });
        return request;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent event) {
        if (event.getRespawnReason() != PlayerRespawnEvent.RespawnReason.DEATH) return;
        Player player = event.getPlayer();
        Respawn request = pending.get(player.getUniqueId());
        if (request == null && !plugin.isWarzone(player.getWorld())) return;
        if ((event.isBedSpawn() || event.isAnchorSpawn()) && plugin.isWarzone(event.getRespawnLocation().getWorld())) {
            clear(player.getUniqueId());
            return;
        }
        Respawn current = request == null ? prepare(player) : request;
        Location target = current.location.getNow(null);
        if (target == null) target = plugin.warzoneSpawn();
        if (target != null) event.setRespawnLocation(target);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (pending.get(player.getUniqueId()) != current) return;
            current.respawned = true;
            if (current.location.isDone()) finish(player, current.location.getNow(null), null);
        });
    }

    private void finish(Player player, Location target, Throwable error) {
        clear(player.getUniqueId());
        if (!player.isOnline() || player.isDead() || !plugin.isWarzone(player.getWorld())
                || plugin.expired(player.getUniqueId())) return;
        if (target == null || error != null) {
            Msg.error(player, "Couldn't find a safe respawn spot. Staying at warzone spawn.");
        } else if ((player.getWorld().equals(target.getWorld()) && player.getLocation().distanceSquared(target) < 0.01)
                || player.teleport(target, PlayerTeleportEvent.TeleportCause.PLUGIN)) {
            player.setFallDistance(0);
            Msg.info(player, "Respawned at a new warzone location.");
        } else {
            Msg.error(player, "Respawn teleport was blocked. Staying at warzone spawn.");
        }
    }

    private void clear(UUID id) {
        Respawn old = pending.remove(id);
        if (old != null) old.location.cancel(false);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        clear(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        if (!plugin.isWarzone(event.getPlayer().getWorld())) clear(event.getPlayer().getUniqueId());
    }
}
