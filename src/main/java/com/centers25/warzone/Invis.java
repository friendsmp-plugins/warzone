package com.centers25.warzone;

import com.destroystokyo.paper.event.player.PlayerLaunchProjectileEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class Invis implements Listener {
    private final Warzone plugin;
    private final long cooldown;
    private final Map<UUID, Long> until = new HashMap<>();

    Invis(Warzone plugin, int minutes) {
        this.plugin = plugin;
        this.cooldown = minutes * 60_000L;
        if (cooldown > 0) Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    private static boolean has(List<PotionEffect> effects) {
        return effects.stream().anyMatch(e -> e.getType().equals(PotionEffectType.INVISIBILITY));
    }

    private boolean counts(Player player, ItemStack item) {
        if (!plugin.isWarzone(player.getWorld()) || item == null || !(item.getItemMeta() instanceof PotionMeta meta)) return false;
        return has(meta.getCustomEffects()) || (meta.getBasePotionType() != null && has(meta.getBasePotionType().getPotionEffects()));
    }

    private boolean blocked(Player player, ItemStack item) {
        if (!counts(player, item)) return false;
        long left = until.getOrDefault(player.getUniqueId(), 0L) - System.currentTimeMillis();
        if (left <= 0) return false;
        Msg.error(player, "You can use another invisibility potion in <hl>" + Time.format(left) + "</hl>.");
        return true;
    }

    private void used(Player player, ItemStack item) {
        if (!counts(player, item)) return;
        until.put(player.getUniqueId(), System.currentTimeMillis() + cooldown);
        Msg.info(player, "<muted>Invisibility potions are on cooldown for <hl>" + Time.format(cooldown) + "</hl>.");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrink(PlayerItemConsumeEvent event) {
        if (blocked(event.getPlayer(), event.getItem())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void afterDrink(PlayerItemConsumeEvent event) {
        used(event.getPlayer(), event.getItem());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onThrow(PlayerLaunchProjectileEvent event) {
        if (blocked(event.getPlayer(), event.getItemStack())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void afterThrow(PlayerLaunchProjectileEvent event) {
        used(event.getPlayer(), event.getItemStack());
    }
}
