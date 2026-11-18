package com.centers25.warzone;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

final class Tickets implements Listener {
    private static final String ITEM = "minecraft:compass[minecraft:lodestone_tracker={target:{pos:[I;-63,67,-45],"
            + "dimension:\"minecraft:overworld\"}},minecraft:custom_name={text:\"Friend Ticket\",color:\"gold\",italic:false},"
            + "minecraft:lore=[{text:\"Redeem at spawn\",color:\"gray\",italic:false}],minecraft:custom_data={friend_ticket:1b}]";

    private final Warzone plugin;
    private final Path file;
    private final Map<UUID, Integer> pending = new HashMap<>();
    private final Map<UUID, Integer> debt = new HashMap<>();
    private final ItemStack ticket = Bukkit.getItemFactory().createItemStack(ITEM);
    private final int base;
    private final TreeMap<Integer, Double> rates;
    private final long min;
    private final long max;

    Tickets(Warzone plugin, int base, TreeMap<Integer, Double> rates, long min, long max) throws Exception {
        this.plugin = plugin;
        this.base = base;
        this.rates = rates;
        this.min = min;
        this.max = max;
        file = plugin.getDataFolder().toPath().resolve("tickets.yml");
        if (Files.exists(file)) {
            YamlConfiguration data = new YamlConfiguration();
            data.load(file.toFile());
            load(data, "pending", pending);
            load(data, "debt", debt);
        }
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Bukkit.getScheduler().runTaskTimer(plugin, () -> Bukkit.getOnlinePlayers().forEach(p -> deliver(p, true)), 1200L, 1200L);
    }

    private static void load(YamlConfiguration data, String path, Map<UUID, Integer> into) {
        var section = data.getConfigurationSection(path);
        if (section != null) for (String id : section.getKeys(false)) {
            if (section.getInt(id) > 0) into.put(UUID.fromString(id), section.getInt(id));
        }
    }

    int forfeit(Map<Integer, Long> time) {
        long played = time.values().stream().mapToLong(Long::longValue).sum();
        return (int) Math.max(1, Math.round(amount(base, played, max) * 0.2));
    }

    int debt(UUID id) {
        return debt.getOrDefault(id, 0);
    }

    void addDebt(Player player, int amount) {
        debt.merge(player.getUniqueId(), amount, Integer::sum);
        save();
        collect(player);
    }

    private void collect(Player player) {
        int owed = debt(player.getUniqueId());
        if (owed == 0) return;
        var inventory = player.getInventory();
        int taken = 0;
        for (int slot = 0; slot < inventory.getSize() && taken < owed; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || !ticket.isSimilar(item)) continue;
            int take = Math.min(owed - taken, item.getAmount());
            item.setAmount(item.getAmount() - take);
            inventory.setItem(slot, item.getAmount() > 0 ? item : null);
            taken += take;
        }
        if (taken > 0) pay(player, taken, "taken from your inventory");
    }

    private void pay(Player player, int paid, String how) {
        int left = debt(player.getUniqueId()) - paid;
        if (left > 0) debt.put(player.getUniqueId(), left);
        else debt.remove(player.getUniqueId());
        save();
        Msg.error(player, "<hl>" + paid + " Friend Tickets</hl> were " + how + " to pay your warzone debt. "
                + (left > 0 ? "<muted>" + left + " still owed." : "<good>Debt paid off!"));
    }

    static int amount(double base, long millis, long max) {
        return (int) Math.min(Integer.MAX_VALUE, Math.round(base * Math.pow(2, Math.min(millis, max) / 3_600_000.0 - 0.5)));
    }

    int bucket(int players) {
        return Math.min(Math.max(players, 1), rates.lastKey());
    }

    private double rate(int players) {
        var entry = rates.floorEntry(players);
        return entry == null ? 0 : entry.getValue();
    }

    double rate(Map<Integer, Long> time) {
        long total = time.values().stream().mapToLong(Long::longValue).sum();
        if (total == 0) return 0;
        return time.entrySet().stream().mapToDouble(e -> e.getValue() * rate(e.getKey())).sum() / total;
    }

    static int penalty(int deaths) {
        return deaths >= 5 ? 100 : deaths * 10;
    }

    void award(Player player, long duration, Map<Integer, Long> time, int deaths) {
        String played = Time.format(duration);
        if (duration < min) {
            Msg.info(player, "<muted>No Friend Tickets this time: stays need to be at least <hl>" + Time.format(min)
                    + "</hl>. Yours was <hl>" + played + "</hl>.");
            return;
        }
        double rate = rate(time);
        int amount = amount(base * rate * (100 - penalty(deaths)) / 100.0, duration, max);
        if (amount < 1) {
            Msg.info(player, "<muted>No Friend Tickets this time" + (deaths > 0 ? " (" + deaths + " deaths)." : ": not enough players were in the warzone."));
            return;
        }
        pending.merge(player.getUniqueId(), amount, Integer::sum);
        save();
        String capped = duration > max ? " <muted>(counted as the " + Time.format(max) + " maximum)</muted>" : "";
        if (rate < 1) capped += " <muted>(" + Math.round(rate * 100) + "% rate from players online)</muted>";
        if (deaths > 0) capped += " <muted>(-" + penalty(deaths) + "% for " + deaths + (deaths == 1 ? " death" : " deaths") + ")</muted>";
        Msg.success(player, "You earned <gold><b><amount> Friend Tickets</b></gold> for <hl>" + played
                + "</hl> of warzone playtime!" + capped, Placeholder.unparsed("amount", String.valueOf(amount)));
        player.playSound(player, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.2f);
        deliver(player, false);
    }

    private void deliver(Player player, boolean later) {
        if (debt.containsKey(player.getUniqueId())) collect(player);
        int amount = pending.getOrDefault(player.getUniqueId(), 0);
        if (amount == 0) return;
        int owed = debt(player.getUniqueId());
        if (owed > 0) {
            int paid = Math.min(owed, amount);
            amount -= paid;
            if (amount > 0) pending.put(player.getUniqueId(), amount);
            else pending.remove(player.getUniqueId());
            pay(player, paid, "kept from your rewards");
            if (amount == 0) return;
        }
        int left = amount;
        while (left > 0) {
            ItemStack stack = ticket.clone();
            int size = Math.min(stack.getMaxStackSize(), left);
            stack.setAmount(size);
            int rejected = player.getInventory().addItem(stack).values().stream().mapToInt(ItemStack::getAmount).sum();
            left -= size - rejected;
            if (rejected > 0) break;
        }
        if (left == amount) return;
        if (left > 0) pending.put(player.getUniqueId(), left);
        else pending.remove(player.getUniqueId());
        save();
        if (later) Msg.success(player, "You received <gold><b>" + (amount - left) + " Friend Tickets</b></gold>.");
        if (left > 0) Msg.info(player, "<hl>" + left + " Friend Tickets</hl> are waiting. Free up inventory space to receive them.");
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && debt.containsKey(player.getUniqueId())
                && ticket.isSimilar(event.getItem().getItemStack())) Bukkit.getScheduler().runTask(plugin, () -> collect(player));
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        deliver(event.getPlayer(), true);
    }

    private void save() {
        YamlConfiguration data = new YamlConfiguration();
        pending.forEach((id, amount) -> data.set("pending." + id, amount));
        debt.forEach((id, amount) -> data.set("debt." + id, amount));
        try {
            Store.write(file, data.saveToString());
        } catch (IOException e) {
            plugin.log().error("Could not save tickets.yml", e);
        }
    }
}
