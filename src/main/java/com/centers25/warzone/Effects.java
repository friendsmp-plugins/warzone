package com.centers25.warzone;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.scheduler.BukkitTask;

final class Effects implements Listener {
    private static final int TICKS = 30;
    private static final BlockData BLOOD = Material.REDSTONE_BLOCK.createBlockData();
    private static final Particle.DustOptions DROP = new Particle.DustOptions(Color.fromRGB(0x8A0303), 1.6f);
    private static final Particle.DustOptions POOL = new Particle.DustOptions(Color.fromRGB(0x5C0000), 1.3f);
    private static final Particle.DustTransition SOUL = new Particle.DustTransition(Color.fromRGB(0xFF2E2E), Color.fromRGB(0x1A0000), 1.1f);

    private final Warzone plugin;

    Effects(Warzone plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        if (!plugin.isWarzone(victim.getWorld())) return;
        Location at = victim.getLocation().clone();
        burst(at);
        spiral(at);
        Player killer = victim.getKiller();
        boolean slain = killer != null && !killer.equals(victim);
        Msg.title(victim, "<gradient:#FF2E2E:#7A0000><b>☠ ELIMINATED</b></gradient>",
                slain ? "<#FFA3A3>Slain by <white><killer>" : "<#FFA3A3>You died in the warzone", 2500,
                Placeholder.unparsed("killer", slain ? killer.getName() : ""));
        if (slain && plugin.isWarzone(killer.getWorld())) {
            Msg.actionBar(killer, "<#FF2E2E>⚔</#FF2E2E> <white><b>ELIMINATED</b></white> <dim>»</dim> <hl><victim>",
                    Placeholder.unparsed("victim", victim.getName()));
            killer.playSound(killer, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1f, 0.8f);
            killer.playSound(killer, Sound.BLOCK_NOTE_BLOCK_BELL, 0.6f, 1.8f);
        }
    }

    private static void burst(Location at) {
        World world = at.getWorld();
        Location chest = at.clone().add(0, 1, 0);
        world.spawnParticle(Particle.BLOCK, chest, 90, 0.35, 0.5, 0.35, 0.2, BLOOD);
        world.spawnParticle(Particle.DUST, chest, 60, 0.45, 0.6, 0.45, 0, DROP);
        world.spawnParticle(Particle.DAMAGE_INDICATOR, chest, 12, 0.3, 0.4, 0.3, 0.15);
        world.spawnParticle(Particle.FLASH, chest, 1, 0, 0, 0, 0, Color.fromRGB(0xFF3030));
        world.playSound(at, Sound.BLOCK_SLIME_BLOCK_BREAK, 1.2f, 0.5f);
        world.playSound(at, Sound.BLOCK_HONEY_BLOCK_BREAK, 1f, 0.6f);
        world.playSound(at, Sound.ENTITY_WARDEN_HEARTBEAT, 1f, 0.7f);
    }

    private void spiral(Location at) {
        World world = at.getWorld();
        int[] tick = {0};
        BukkitTask[] task = new BukkitTask[1];
        task[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            int t = tick[0]++;
            if (!world.isChunkLoaded(at.getBlockX() >> 4, at.getBlockZ() >> 4)) {
                task[0].cancel();
                return;
            }
            if (t <= 12) {
                double radius = 0.25 + t * 0.07;
                for (int i = 0; i < 14; i++) {
                    double angle = Math.PI * 2 * i / 14 + t * 0.3;
                    world.spawnParticle(Particle.DUST, at.clone().add(Math.cos(angle) * radius, 0.05, Math.sin(angle) * radius),
                            1, 0.02, 0, 0.02, 0, POOL);
                }
                world.spawnParticle(Particle.DUST, at.clone().add(0, 1.2, 0), 4, 0.25, 0.4, 0.25, 0, DROP);
            }
            double progress = (double) t / TICKS;
            double height = progress * 2.6;
            double radius = 0.7 * (1 - progress) + 0.12;
            for (int strand = 0; strand < 2; strand++) {
                double angle = t * 0.55 + strand * Math.PI;
                world.spawnParticle(Particle.DUST_COLOR_TRANSITION, at.clone().add(Math.cos(angle) * radius, height,
                        Math.sin(angle) * radius), 2, 0.03, 0.03, 0.03, 0, SOUL);
            }
            if (t % 4 == 0) world.spawnParticle(Particle.SOUL, at.clone().add(0, height, 0), 1, 0.1, 0.05, 0.1, 0.02);
            if (t < TICKS) return;
            Location top = at.clone().add(0, 2.7, 0);
            world.spawnParticle(Particle.SOUL, top, 14, 0.2, 0.2, 0.2, 0.06);
            world.spawnParticle(Particle.LARGE_SMOKE, top, 10, 0.15, 0.15, 0.15, 0.03);
            world.spawnParticle(Particle.DUST_COLOR_TRANSITION, top, 25, 0.35, 0.35, 0.35, 0, SOUL);
            world.playSound(top, Sound.PARTICLE_SOUL_ESCAPE, 2f, 0.8f);
            world.playSound(top, Sound.ENTITY_WITHER_SKELETON_HURT, 0.6f, 0.5f);
            task[0].cancel();
        }, 1L, 1L);
    }
}
