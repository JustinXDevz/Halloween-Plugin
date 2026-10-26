package com.example.halloween;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Trick-or-Treat villagers. Admins place permanent ones; the plugin also
 * spawns temporary ones at safe spots that vanish after a while.
 */
public final class TrickOrTreat implements Listener {

    private final HalloweenPlugin plugin;
    private final Random random = new Random();
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    /** Auto-spawned NPCs (temporary) -> their despawn task. Admin-placed ones are never tracked. */
    private final Map<UUID, BukkitTask> autoNpcs = new HashMap<>();
    private BukkitTask autoTask;

    public TrickOrTreat(HalloweenPlugin plugin) {
        this.plugin = plugin;
    }

    // ---------- Lifecycle ----------

    public void start() {
        stop();
        if (!plugin.getConfig().getBoolean("trick-or-treat.auto-spawn.enabled", true)) return;
        long interval = Math.max(10, plugin.getConfig().getInt("trick-or-treat.auto-spawn.interval-seconds", 300)) * 20L;
        autoTask = Bukkit.getScheduler().runTaskTimer(plugin, this::trySpawnAuto, interval, interval);
    }

    public void stop() {
        if (autoTask != null) autoTask.cancel();
        autoTask = null;
    }

    /** Removes all temporary NPCs (shutdown). Admin-placed ones stay. */
    public void clearAuto() {
        stop();
        for (Map.Entry<UUID, BukkitTask> e : new ArrayList<>(autoNpcs.entrySet())) {
            e.getValue().cancel();
            Entity ent = Bukkit.getEntity(e.getKey());
            if (ent != null) ent.remove();
        }
        autoNpcs.clear();
    }

    // ---------- Spawning ----------

    private void trySpawnAuto() {
        if (!plugin.isEventActive()) return;

        // Forget NPCs that already vanished (e.g. chunk unloaded)
        autoNpcs.entrySet().removeIf(en -> {
            Entity ent = Bukkit.getEntity(en.getKey());
            if (ent == null || !ent.isValid()) { en.getValue().cancel(); return true; }
            return false;
        });

        var c = plugin.getConfig();
        if (autoNpcs.size() >= c.getInt("trick-or-treat.auto-spawn.max-active", 3)) return;

        List<Player> players = new ArrayList<>(Bukkit.getOnlinePlayers());
        players.removeIf(p -> p.getGameMode() == org.bukkit.GameMode.SPECTATOR);
        if (players.isEmpty()) return;
        Player target = players.get(random.nextInt(players.size()));

        int min = c.getInt("trick-or-treat.auto-spawn.min-distance", 20);
        int max = Math.max(min + 1, c.getInt("trick-or-treat.auto-spawn.max-distance", 60));
        Block spot = SpawnSpots.findNear(plugin, target, min, max, random);
        if (spot == null) return;

        Villager npc = spawnNpc(spot.getLocation().add(0.5, 0, 0.5), true);
        long lifetime = c.getInt("trick-or-treat.auto-spawn.lifetime-seconds", 900) * 20L;
        UUID id = npc.getUniqueId();
        BukkitTask despawn = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            autoNpcs.remove(id);
            Entity ent = Bukkit.getEntity(id);
            if (ent != null) {
                ent.getWorld().spawnParticle(Particle.SOUL, ent.getLocation().add(0, 1, 0), 20, 0.3, 0.5, 0.3, 0.03);
                ent.remove();
            }
        }, lifetime);
        autoNpcs.put(id, despawn);

        plugin.send(target, "A <gold>Trick-or-Treater</gold> is wandering within <white>" + max
                + "</white> blocks of you. Knock on their door!");
    }

    /**
     * Spawns the NPC. {@code temporary} NPCs are not saved with the world,
     * so they can never be left behind after a restart.
     */
    public Villager spawnNpc(Location loc, boolean temporary) {
        return loc.getWorld().spawn(loc, Villager.class, v -> {
            v.customName(HalloweenPlugin.mm("<gold>Trick-or-Treat"));
            v.setCustomNameVisible(true);
            v.setAI(false);
            v.setInvulnerable(true);
            v.setSilent(true);
            v.setPersistent(!temporary);
            v.setRemoveWhenFarAway(temporary);
            v.setProfession(Villager.Profession.NITWIT);
            v.getPersistentDataContainer().set(plugin.npcKey(), PersistentDataType.BYTE, (byte) 1);
        });
    }

    /** Removes the closest Trick-or-Treat NPC within the radius. */
    public boolean removeNearest(Location loc, double radius) {
        Entity best = null;
        double bestDist = Double.MAX_VALUE;
        for (Entity ent : loc.getWorld().getNearbyEntities(loc, radius, radius, radius)) {
            if (!ent.getPersistentDataContainer().has(plugin.npcKey(), PersistentDataType.BYTE)) continue;
            double d = ent.getLocation().distanceSquared(loc);
            if (d < bestDist) { bestDist = d; best = ent; }
        }
        if (best == null) return false;
        BukkitTask t = autoNpcs.remove(best.getUniqueId());
        if (t != null) t.cancel();
        best.remove();
        return true;
    }

    // ---------- Interaction ----------

    @EventHandler
    public void onInteract(PlayerInteractEntityEvent e) {
        Entity npc = e.getRightClicked();
        if (!npc.getPersistentDataContainer().has(plugin.npcKey(), PersistentDataType.BYTE)) return;

        e.setCancelled(true); // never open the trade window
        if (e.getHand() != EquipmentSlot.HAND) return;

        Player p = e.getPlayer();
        if (!plugin.isEventActive()) {
            plugin.send(p, "The spirits are sleeping. Come back during Halloween!");
            return;
        }

        long now = System.currentTimeMillis();
        long cooldown = plugin.getConfig().getInt("trick-or-treat.cooldown-seconds", 60) * 1000L;
        Long last = cooldowns.get(p.getUniqueId());
        if (last != null && now - last < cooldown) {
            long left = (cooldown - (now - last) + 999) / 1000;
            plugin.send(p, "The door is closed... knock again in <white>" + left + "s</white>.");
            return;
        }
        cooldowns.put(p.getUniqueId(), now);

        double chance = plugin.getConfig().getDouble("trick-or-treat.trick-chance", 0.2);
        if (random.nextDouble() < chance) {
            trick(p);
        } else {
            treat(p);
        }
    }

    private void treat(Player p) {
        int min = plugin.getConfig().getInt("trick-or-treat.candy-min", 3);
        int max = Math.max(min, plugin.getConfig().getInt("trick-or-treat.candy-max", 8));
        int amount = min + random.nextInt(max - min + 1);
        plugin.candy().give(p, amount);
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
        plugin.send(p, "<green>TREAT!</green> You got <gold>" + amount + " candy</gold>.");
    }

    private void trick(Player p) {
        switch (random.nextInt(3)) {
            case 0 -> {
                p.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 100, 0));
                plugin.send(p, "<red>TRICK!</red> Everything went dark...");
            }
            case 1 -> {
                p.addPotionEffect(new PotionEffect(PotionEffectType.NAUSEA, 160, 0));
                plugin.send(p, "<red>TRICK!</red> That candy was cursed. You feel woozy...");
            }
            default -> {
                PlayerInventory inv = p.getInventory();
                List<ItemStack> hotbar = new ArrayList<>();
                for (int i = 0; i < 9; i++) hotbar.add(inv.getItem(i));
                Collections.shuffle(hotbar);
                for (int i = 0; i < 9; i++) inv.setItem(i, hotbar.get(i));
                plugin.send(p, "<red>TRICK!</red> Your hotbar got shuffled by a poltergeist!");
            }
        }
        p.playSound(p.getLocation(), Sound.ENTITY_WITCH_AMBIENT, 1f, 0.8f);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        cooldowns.remove(e.getPlayer().getUniqueId());
    }
}
