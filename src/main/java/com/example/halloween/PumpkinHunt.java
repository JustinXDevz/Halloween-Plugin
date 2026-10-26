package com.example.halloween;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Cursed pumpkins: spawn safely at random, or be placed by an admin. Click to collect candy. */
public final class PumpkinHunt implements Listener {

    private final HalloweenPlugin plugin;
    private final Random random = new Random();
    /** Active pumpkin block locations -> their despawn task. */
    private final Map<Location, BukkitTask> active = new HashMap<>();

    private BukkitTask spawnTask;
    private BukkitTask particleTask;

    public PumpkinHunt(HalloweenPlugin plugin) {
        this.plugin = plugin;
    }

    // ---------- Lifecycle ----------

    public void start() {
        stop();
        long interval = Math.max(5, plugin.getConfig().getInt("pumpkin-hunt.spawn-interval-seconds", 60)) * 20L;
        spawnTask = Bukkit.getScheduler().runTaskTimer(plugin, this::trySpawn, interval, interval);
        particleTask = Bukkit.getScheduler().runTaskTimer(plugin, this::particles, 20L, 10L);
    }

    public void stop() {
        if (spawnTask != null) spawnTask.cancel();
        if (particleTask != null) particleTask.cancel();
        spawnTask = null;
        particleTask = null;
    }

    /** Removes every active pumpkin (used on shutdown). */
    public void clearAll() {
        stop();
        for (Map.Entry<Location, BukkitTask> e : new ArrayList<>(active.entrySet())) {
            e.getValue().cancel();
            e.getKey().getBlock().setType(Material.AIR);
        }
        active.clear();
    }

    // ---------- Spawning ----------

    private void trySpawn() {
        if (!plugin.isEventActive()) return;
        if (active.size() >= plugin.getConfig().getInt("pumpkin-hunt.max-active", 8)) return;

        List<Player> candidates = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getGameMode() != GameMode.SPECTATOR) candidates.add(p);
        }
        if (candidates.isEmpty()) return;
        spawnNear(candidates.get(random.nextInt(candidates.size())));
    }

    /** Random SAFE spot near the player. Never replaces or breaks existing blocks. */
    public boolean spawnNear(Player target) {
        FileConfiguration c = plugin.getConfig();
        int min = c.getInt("pumpkin-hunt.min-distance", 15);
        int max = Math.max(min + 1, c.getInt("pumpkin-hunt.max-distance", 50));

        Block spot = SpawnSpots.findNear(plugin, target, min, max, random);
        if (spot == null) return false;

        place(spot);
        plugin.send(target, "A <gold>cursed pumpkin</gold> has appeared within <white>" + max
                + "</white> blocks of you... find it before it vanishes!");
        return true;
    }

    /** Manual placement (admin). Only works on an empty air block, so nothing gets overwritten. */
    public boolean placeAt(Block spot) {
        if (!spot.getType().isAir()) return false;
        place(spot);
        return true;
    }

    private void place(Block spot) {
        spot.setType(Material.CARVED_PUMPKIN);
        Location key = spot.getLocation();
        long lifetime = plugin.getConfig().getInt("pumpkin-hunt.lifetime-seconds", 300) * 20L;

        BukkitTask despawn = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (active.remove(key) != null) {
                key.getBlock().setType(Material.AIR);
            }
        }, lifetime);
        active.put(key, despawn);
    }

    private void particles() {
        for (Location loc : active.keySet()) {
            World w = loc.getWorld();
            if (w == null) continue;
            w.spawnParticle(Particle.SOUL_FIRE_FLAME, loc.clone().add(0.5, 1.2, 0.5), 2, 0.2, 0.2, 0.2, 0.01);
        }
    }

    // ---------- Collecting ----------

    private void collect(Player player, Block block) {
        Location key = block.getLocation();
        BukkitTask task = active.remove(key);
        if (task == null) return;
        task.cancel();
        block.setType(Material.AIR);

        FileConfiguration c = plugin.getConfig();
        int min = c.getInt("pumpkin-hunt.candy-min", 3);
        int max = Math.max(min, c.getInt("pumpkin-hunt.candy-max", 8));
        int amount = min + random.nextInt(max - min + 1);
        plugin.candy().give(player, amount);

        Location fx = key.clone().add(0.5, 0.5, 0.5);
        block.getWorld().spawnParticle(Particle.SOUL, fx, 25, 0.3, 0.3, 0.3, 0.05);
        player.playSound(fx, Sound.ENTITY_VEX_AMBIENT, 1f, 0.6f);
        plugin.send(player, "You banished a cursed pumpkin and found <gold>" + amount + " candy</gold>!");
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND) return;
        Block b = e.getClickedBlock();
        if (b == null || !active.containsKey(b.getLocation())) return;
        e.setCancelled(true);
        collect(e.getPlayer(), b);
    }

    @EventHandler
    public void onBreak(BlockBreakEvent e) {
        if (!active.containsKey(e.getBlock().getLocation())) return;
        e.setCancelled(true);
        collect(e.getPlayer(), e.getBlock());
    }

    // Keep the event pumpkins from being moved or blown up (no stray blocks left behind)

    @EventHandler
    public void onExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(b -> active.containsKey(b.getLocation()));
    }

    @EventHandler
    public void onPistonExtend(BlockPistonExtendEvent e) {
        for (Block b : e.getBlocks()) {
            if (active.containsKey(b.getLocation())) { e.setCancelled(true); return; }
        }
    }

    @EventHandler
    public void onPistonRetract(BlockPistonRetractEvent e) {
        for (Block b : e.getBlocks()) {
            if (active.containsKey(b.getLocation())) { e.setCancelled(true); return; }
        }
    }
}
