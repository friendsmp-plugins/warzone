package com.centers25.warzone;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class Combat implements Listener {
    private static final long GRACE = 3000;

    private final Warzone plugin;
    private final long duration;
    private final double range;
    private final Map<UUID, Long> until = new HashMap<>();
    private final Map<UUID, UUID> attackers = new HashMap<>();
    private final Map<UUID, Map<UUID, Long>> seen = new HashMap<>();
    private final Set<UUID> danger = new HashSet<>();
    private final Map<UUID, String> notices = new HashMap<>();

    Combat(Warzone plugin, int seconds, int chunks) {
        this.plugin = plugin;
        this.duration = seconds * 1000L;
        this.range = chunks * 16.0;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
        if (range > 0) Bukkit.getScheduler().runTaskTimer(plugin, this::scan, 10L, 10L);
    }

    long remaining(Player player) {
        return Math.max(0, until.getOrDefault(player.getUniqueId(), 0L) - System.currentTimeMillis());
    }

    boolean danger(Player player) {
        return danger.contains(player.getUniqueId());
    }

    private void tick() {
        until.entrySet().removeIf(entry -> {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null) return true;
            long left = entry.getValue() - System.currentTimeMillis();
            if (left > 0) {
                Msg.actionBar(player, "<bad>⚔ In combat</bad> <dim>»</dim> <hl>" + Time.format(left));
                return false;
            }
            attackers.remove(entry.getKey());
            Msg.actionBar(player, "<good>✔ You are no longer in combat");
            return true;
        });
        for (UUID id : danger) {
            Player player = Bukkit.getPlayer(id);
            if (player != null && !until.containsKey(id))
                Msg.actionBar(player, "<bad>⚠ Danger logged</bad> <dim>»</dim> <muted>logging out will kill you");
        }
    }

    private boolean watched(Player player) {
        return player != null && !player.isDead() && plugin.isWarzone(player.getWorld())
                && (player.getGameMode() == GameMode.SURVIVAL || player.getGameMode() == GameMode.ADVENTURE);
    }

    private boolean visible(Player a, Player b) {
        if (!a.canSee(b) || !b.canSee(a)) return false;
        Location from = a.getEyeLocation();
        Vector line = b.getEyeLocation().toVector().subtract(from.toVector());
        double distance = line.length();
        if (distance > range) return false;
        return distance < 0.1 || a.getWorld().rayTraceBlocks(from, line.normalize(), distance, FluidCollisionMode.NEVER, true) == null;
    }

    private void scan() {
        long now = System.currentTimeMillis();
        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers().stream().filter(this::watched).toList());
        for (int i = 0; i < players.size(); i++) {
            Player a = players.get(i);
            for (int j = i + 1; j < players.size(); j++) {
                Player b = players.get(j);
                if (!a.getWorld().equals(b.getWorld()) || a.getLocation().distanceSquared(b.getLocation()) > range * range
                        || !visible(a, b)) continue;
                seen.computeIfAbsent(a.getUniqueId(), id -> new HashMap<>()).put(b.getUniqueId(), now);
                seen.computeIfAbsent(b.getUniqueId(), id -> new HashMap<>()).put(a.getUniqueId(), now);
            }
        }
        seen.entrySet().removeIf(entry -> {
            if (!watched(Bukkit.getPlayer(entry.getKey()))) return true;
            entry.getValue().entrySet().removeIf(v -> now - v.getValue() > GRACE || !watched(Bukkit.getPlayer(v.getKey())));
            return entry.getValue().isEmpty();
        });
        danger.removeIf(id -> {
            if (seen.containsKey(id)) return false;
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                Msg.success(player, "You are no longer danger logged. Nobody can see you.");
                Msg.actionBar(player, "<good>✔ No longer danger logged");
            }
            return true;
        });
        seen.forEach((id, viewers) -> {
            if (!danger.add(id)) return;
            Player player = Bukkit.getPlayer(id);
            Player viewer = Bukkit.getPlayer(viewers.keySet().iterator().next());
            Msg.error(player, "<b>⚠</b> You are now <hl>danger logged</hl>: <hl><viewer></hl> can see you."
                    + " <muted>Logging out now will kill you.", Placeholder.unparsed("viewer", viewer.getName()));
            player.playSound(player, Sound.BLOCK_NOTE_BLOCK_BIT, 0.8f, 0.6f);
        });
    }

    private void tag(Player player) {
        if (remaining(player) == 0) Msg.error(player, "You are in combat for <hl>" + Time.format(duration)
                + "</hl>. <muted>Logging out will kill you and fireworks are disabled.");
        until.put(player.getUniqueId(), System.currentTimeMillis() + duration);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (duration == 0 || !(event.getEntity() instanceof Player victim) || !plugin.isWarzone(victim.getWorld())) return;
        Player attacker = event.getDamager() instanceof Player player ? player
                : event.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter ? shooter : null;
        if (attacker == null || attacker.equals(victim)) return;
        tag(victim);
        tag(attacker);
        attackers.put(victim.getUniqueId(), attacker.getUniqueId());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        UUID id = event.getEntity().getUniqueId();
        until.remove(id);
        attackers.remove(id);
        seen.remove(id);
        danger.remove(id);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        boolean fighting = remaining(player) > 0;
        boolean risky = danger.contains(id);
        UUID attacker = attackers.remove(id);
        until.remove(id);
        seen.remove(id);
        danger.remove(id);
        if ((!fighting && !risky) || player.isDead() || !plugin.isWarzone(player.getWorld())) return;
        String reason = fighting ? "in combat" : "while danger logged";
        if (event.getReason() != PlayerQuitEvent.QuitReason.DISCONNECTED) {
            notices.put(id, "<muted>You lost connection " + reason + ", so you weren't killed.");
            return;
        }
        Player killer = fighting && attacker != null ? Bukkit.getPlayer(attacker) : null;
        if (killer != null && plugin.isWarzone(killer.getWorld())) player.setKiller(killer);
        player.setHealth(0);
        notices.put(id, "<bad>You died for logging out " + reason + ".");
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (!other.equals(player) && plugin.isWarzone(other.getWorld()))
                Msg.info(other, "<hl><player></hl> logged out " + reason + " and died.", Placeholder.unparsed("player", player.getName()));
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        String notice = notices.remove(event.getPlayer().getUniqueId());
        if (notice != null) Msg.info(event.getPlayer(), notice);
    }
}
