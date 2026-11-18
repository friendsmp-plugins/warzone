package com.centers25.warzone;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.Arrays;
import java.util.List;

final class Voucher implements Listener {
    private final Warzone plugin;
    private final NamespacedKey key;

    Voucher(Warzone plugin) {
        this.plugin = plugin;
        // Keeps vouchers issued under the old FriendSMPWarzone name valid.
        this.key = new NamespacedKey("friendsmpwarzone", "cooldown_voucher");
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    ItemStack create(int amount) {
        ItemStack item = new ItemStack(Material.PAPER, amount);
        item.editMeta(meta -> {
            meta.displayName(Msg.parse("<!i><" + Msg.BRAND + "><b>Warzone Cooldown Voucher</b></gradient>"));
            meta.lore(List.of(
                    Msg.parse("<!i><muted>Right-click to reset your"),
                    Msg.parse("<!i><muted>warzone cooldown instantly."),
                    Msg.parse(""),
                    Msg.parse("<!i><hl>⚔ Single use")));
            meta.setEnchantmentGlintOverride(true);
            meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
        });
        return item;
    }

    private boolean is(ItemStack item) {
        return item != null && item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(key);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onUse(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) return;
        if (event.getHand() == null || !is(event.getItem())) return;
        event.setCancelled(true);
        Player player = event.getPlayer();
        Stays stays = plugin.stays();
        if (stays.cooldown(player.getUniqueId()) == 0) {
            Msg.error(player, "You don't have a warzone cooldown to reset.");
            return;
        }
        if (!stays.setCooldown(player.getUniqueId(), 0)) {
            Msg.error(player, "Couldn't reset your cooldown. Please try again.");
            return;
        }
        ItemStack held = player.getInventory().getItem(event.getHand());
        held.setAmount(held.getAmount() - 1);
        player.getInventory().setItem(event.getHand(), held.getAmount() > 0 ? held : null);
        Msg.success(player, "Your warzone cooldown has been reset! You can use <hl>/warzone</hl> again.");
        player.playSound(player, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.4f);
    }

    @EventHandler
    public void onCraft(PrepareItemCraftEvent event) {
        if (Arrays.stream(event.getInventory().getMatrix()).anyMatch(this::is)) event.getInventory().setResult(null);
    }
}
