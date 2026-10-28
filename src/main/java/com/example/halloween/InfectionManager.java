package com.example.halloween;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.TileState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Infection: an infected haunted zone keeps growing and corrupts the land
 * (natural ground turns cursed, everything else in the top layers is destroyed)
 * until its Cursed Creaking Heart is destroyed. Pale trees then grow over the
 * dead land. Every changed block is saved to disk, and when the zone is cleansed
 * everything is restored (and the zone is deleted).
 *
 * The heart also acts like a hive: the closer you get, the tougher the zone's
 * monsters are (see HauntedZones), with a heartbeat, darkness and slowness near it.
 *
 * Chests, signs, beds and other tile-entity blocks are never touched, so no
 * items can be lost.
 */
public final class InfectionManager implements Listener {

    private static final int FILE_VERSION = 1;

    /** One block of a growing pale tree. */
    private record Plant(int x, int y, int z, BlockData data) {}

    /** Everything we know about one zone's infection. */
    private static final class State {
        final String zone;
        final String world;
        final int cx;
        final int cz;
        int radius;
        boolean spreading = true;   // false = restoring
        boolean hasHeart;
        int hx;
        int hy;
        int hz;

        // Saved to disk
        final List<String> palette = new ArrayList<>();
        final Map<String, Integer> paletteIndex = new HashMap<>();
        final Map<Long, Integer> changes = new HashMap<>();   // block key -> palette index of the ORIGINAL block
        final Set<Long> done = new HashSet<>();               // columns already infected

        // Runtime only
        final ArrayDeque<Long> pending = new ArrayDeque<>();        // columns waiting to be infected
        final ArrayDeque<Long> restoreQueue = new ArrayDeque<>();   // block keys waiting to be restored
        final Map<Long, List<Long>> deferred = new HashMap<>();     // chunk key -> block keys (chunk unloaded)
        final ArrayDeque<Plant> growQueue = new ArrayDeque<>();     // pale tree blocks waiting to grow
        final Set<Long> treeColumns = new HashSet<>();              // where trees were planted
        boolean dirty;

        State(String zone, String world, int cx, int cz) {
            this.zone = zone;
            this.world = world;
            this.cx = cx;
            this.cz = cz;
        }
    }

    private final HalloweenPlugin plugin;
    private final HauntedZones zones;
    private final File dataFile;
    private final File dir;
    private final Material heartMat;
    private final Material paleLog;
    private final Material paleLeaves;
    private final Material paleCarpet;
    private final Material paleHanging;
    private final Random random = new Random();
    private final Map<String, State> states = new LinkedHashMap<>();
    private int effectTick = 0;

    private BukkitTask workTask;
    private BukkitTask spreadTask;
    private BukkitTask effectTask;
    private BukkitTask saveTask;

    public InfectionManager(HalloweenPlugin plugin, HauntedZones zones) {
        this.plugin = plugin;
        this.zones = zones;
        this.dataFile = new File(plugin.getDataFolder(), "infection.yml");
        this.dir = new File(plugin.getDataFolder(), "infection");
        this.heartMat = resolveHeart();
        // Pale oak (1.21.4+); older servers fall back to dark oak for the logs and leaves
        this.paleLog = firstMaterial("PALE_OAK_LOG", "DARK_OAK_LOG");
        this.paleLeaves = firstMaterial("PALE_OAK_LEAVES", "DARK_OAK_LEAVES");
        this.paleCarpet = firstMaterial("PALE_MOSS_CARPET");
        this.paleHanging = firstMaterial("PALE_HANGING_MOSS");
        dir.mkdirs();
        load();
    }

    private FileConfiguration cfg() {
        return plugin.getConfig();
    }

    private static Material firstMaterial(String... names) {
        for (String n : names) {
            Material m = Material.matchMaterial(n);
            if (m != null) return m;
        }
        return null;
    }

    private Material resolveHeart() {
        Material m = Material.matchMaterial("CREAKING_HEART");
        if (m == null) {
            plugin.getLogger().warning("This server has no Creaking Heart block (needs 1.21.4+). "
                    + "Using Crying Obsidian as the Cursed Heart instead.");
            m = Material.CRYING_OBSIDIAN;
        }
        return m;
    }

    // ---------- Lifecycle ----------

    public void start() {
        stop();
        workTask = Bukkit.getScheduler().runTaskTimer(plugin, this::work, 20L, 1L);
        long spreadTicks = Math.max(1, cfg().getInt("infection.spread-interval-seconds", 10)) * 20L;
        spreadTask = Bukkit.getScheduler().runTaskTimer(plugin, this::spread, spreadTicks, spreadTicks);
        effectTask = Bukkit.getScheduler().runTaskTimer(plugin, this::effects, 40L, 10L);
        saveTask = Bukkit.getScheduler().runTaskTimer(plugin, this::saveIfDirty, 600L, 600L);
        Bukkit.getScheduler().runTask(plugin, this::catchUp);
    }

    public void stop() {
        if (workTask != null) workTask.cancel();
        if (spreadTask != null) spreadTask.cancel();
        if (effectTask != null) effectTask.cancel();
        if (saveTask != null) saveTask.cancel();
        workTask = null;
        spreadTask = null;
        effectTask = null;
        saveTask = null;
    }

    public void shutdown() {
        stop();
        save();
    }

    /** After a restart: queue the columns of chunks that are already loaded. */
    private void catchUp() {
        for (State st : states.values()) {
            if (!st.spreading) continue;
            World w = Bukkit.getWorld(st.world);
            if (w == null) continue;
            for (org.bukkit.Chunk c : w.getLoadedChunks()) {
                enqueueChunk(st, c.getX(), c.getZ());
            }
        }
    }

    // ---------- Public API ----------

    /**
     * Starts spreading from the zone. If underFeet is true the Cursed Heart replaces the block
     * directly under {@code at} (it is restored when the heart is destroyed); otherwise it is
     * placed on top of the ground at the zone center.
     * Returns false if the zone is already infected / healing.
     */
    public boolean infect(HauntedZones.Zone z, Location at, boolean underFeet) {
        if (states.containsKey(z.name())) return false;
        World w = Bukkit.getWorld(z.world());
        if (w == null) return false;

        State st = new State(z.name(), z.world(), z.x(), z.z());
        st.radius = Math.max(1, cfg().getInt("infection.start-radius", 6));

        boolean placed = underFeet && at != null && placeHeartUnder(st, at);
        if (!placed) placeHeart(st, z, w);

        states.put(st.zone, st);
        z.setRadius(Math.max(z.baseRadius(), st.radius));
        enqueueRing(st, w, -1, st.radius);
        st.dirty = true;
        save();

        broadcast("<dark_purple>A <light_purple>Cursed Creaking Heart</light_purple> has awakened near <white>"
                + z.x() + ", " + z.z() + "</white> and the haunted zone <gold>" + z.name()
                + "</gold> <red>is spreading</red>! Destroy the heart to stop it. "
                + "The closer you get, the more its monsters defend it.");
        return true;
    }

    /** Stops the infection and starts restoring every changed block. */
    public boolean cleanse(String zone, Player by) {
        State st = states.get(zone);
        if (st == null || !st.spreading) return false;
        beginRestore(st, by);
        return true;
    }

    public String status(String zone) {
        State st = states.get(zone);
        if (st == null) return "clean";
        if (st.spreading) return "infected r=" + st.radius;
        return "healing (" + st.changes.size() + " blocks left)";
    }

    public List<String> stateNames() {
        return new ArrayList<>(states.keySet());
    }

    /** {x, y, z} of the Cursed Heart, or null. */
    public int[] heartPos(String zone) {
        State st = states.get(zone);
        if (st == null || !st.spreading || !st.hasHeart) return null;
        return new int[] {st.hx, st.hy, st.hz};
    }

    /**
     * Danger level at a location: 0 = far from the heart, higher = closer (up to 5 by default).
     * Thresholds come from haunted-zones.hive.level-distances.
     */
    public int levelAt(String zone, Location loc) {
        State st = states.get(zone);
        if (st == null || !st.spreading || !st.hasHeart) return 0;
        if (loc.getWorld() == null || !loc.getWorld().getName().equals(st.world)) return 0;

        double dx = loc.getX() - (st.hx + 0.5);
        double dy = loc.getY() - (st.hy + 0.5);
        double dz = loc.getZ() - (st.hz + 0.5);
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

        List<Integer> distances = cfg().getIntegerList("haunted-zones.hive.level-distances");
        if (distances.isEmpty()) distances = List.of(80, 52, 32, 18, 8);
        int level = 0;
        for (int d : distances) {
            if (dist <= d) level++;
        }
        return level;
    }

    /** Action-bar hint for players standing in an infected zone. */
    public void hint(Player p, String zone) {
        State st = states.get(zone);
        if (st == null) return;
        if (!st.spreading) {
            p.sendActionBar(HalloweenPlugin.mm("<green>The land is healing..."));
            return;
        }
        if (!st.hasHeart) return;
        Location l = p.getLocation();
        double dx = st.hx + 0.5 - l.getX();
        double dz = st.hz + 0.5 - l.getZ();
        int dist = (int) Math.round(Math.sqrt(dx * dx + dz * dz));
        p.sendActionBar(HalloweenPlugin.mm("<dark_purple>Infection <red>" + st.radius + "m <dark_gray>| "
                + "<light_purple>Heart <white>" + dist + "m " + compass(dx, dz)
                + " <dark_gray>| <red>Danger " + levelAt(zone, l)));
    }

    private static String compass(double dx, double dz) {
        double bearing = Math.toDegrees(Math.atan2(dx, -dz)); // 0 = north (-z), 90 = east (+x)
        if (bearing < 0) bearing += 360;
        String[] names = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        return names[(int) Math.round(bearing / 45.0) % 8];
    }

    private void broadcast(String text) {
        Bukkit.broadcast(HalloweenPlugin.mm(cfg().getString("prefix", "") + text));
    }

    // ---------- The heart ----------

    /** Heart replaces the block under the player; the original block is saved and comes back later. */
    private boolean placeHeartUnder(State st, Location at) {
        Block under = at.getBlock().getRelative(BlockFace.DOWN);
        for (int i = 0; i < 8 && under.getType().isAir(); i++) {
            under = under.getRelative(BlockFace.DOWN);
        }
        if (under.getType().isAir() || isProtected(under.getType())) return false;
        if (under.getState(false) instanceof TileState) return false;   // never overwrite chests, signs...

        record(st, under);
        under.setType(heartMat, false);
        st.hasHeart = true;
        st.hx = under.getX();
        st.hy = under.getY();
        st.hz = under.getZ();
        return true;
    }

    /** Heart on top of the ground: zone center first, then random spots nearby. */
    private void placeHeart(State st, HauntedZones.Zone z, World w) {
        if (tryHeart(st, w, z.x(), z.z())) return;
        for (int i = 0; i < 60; i++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double dist = z.baseRadius() * (0.1 + random.nextDouble() * 0.6);
            int x = z.x() + (int) Math.round(Math.cos(angle) * dist);
            int zz = z.z() + (int) Math.round(Math.sin(angle) * dist);
            if (tryHeart(st, w, x, zz)) return;
        }
        plugin.getLogger().warning("Could not place a Cursed Heart for zone " + z.name()
                + ". Use /halloween zone cleanse " + z.name() + " to stop it.");
    }

    /** Only ever uses an empty air block on top of solid ground. */
    private boolean tryHeart(State st, World w, int x, int z) {
        Block top = w.getHighestBlockAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        Block above = top.getRelative(BlockFace.UP);
        if (top.isLiquid() || !top.getType().isSolid() || !above.getType().isAir()) return false;
        above.setType(heartMat, false);
        st.hasHeart = true;
        st.hx = above.getX();
        st.hy = above.getY();
        st.hz = above.getZ();
        return true;
    }

    private void removeHeart(State st, World w) {
        if (!st.hasHeart) return;
        Block b = w.getBlockAt(st.hx, st.hy, st.hz);
        if (b.getType() == heartMat) b.setType(Material.AIR, false);
        st.hasHeart = false;
    }

    private boolean heartGone(State st, World w) {
        if (!st.hasHeart) return false;
        if (!w.isChunkLoaded(st.hx >> 4, st.hz >> 4)) return false;
        return w.getBlockAt(st.hx, st.hy, st.hz).getType() != heartMat;
    }

    private State heartState(Block b) {
        for (State st : states.values()) {
            if (st.hasHeart && b.getX() == st.hx && b.getY() == st.hy && b.getZ() == st.hz
                    && b.getWorld().getName().equals(st.world)) {
                return st;
            }
        }
        return null;
    }

    // ---------- Spreading ----------

    /** Runs every few seconds: grows each infection and checks that its heart still exists. */
    private void spread() {
        boolean active = plugin.isEventActive();
        int step = Math.max(1, cfg().getInt("infection.spread-blocks", 4));
        int max = cfg().getInt("infection.max-radius", 0);   // 0 = never stops

        for (State st : new ArrayList<>(states.values())) {
            if (!st.spreading) continue;
            World w = Bukkit.getWorld(st.world);
            if (w == null) continue;

            if (!active || heartGone(st, w)) {   // event over, or heart removed some other way
                beginRestore(st, null);
                continue;
            }
            if (max > 0 && st.radius >= max) continue;

            int old = st.radius;
            st.radius = old + step;
            if (max > 0) st.radius = Math.min(max, st.radius);
            st.dirty = true;
            HauntedZones.Zone z = zones.get(st.zone);
            if (z != null) z.setRadius(Math.max(z.baseRadius(), st.radius));
            enqueueRing(st, w, old, st.radius);
        }
    }

    /** Queues every column with oldR < distance <= newR from the zone center (ring only, so it stays fast). */
    private void enqueueRing(State st, World w, int oldR, int newR) {
        long outer = (long) newR * newR;
        long inner = oldR < 0 ? -1 : (long) oldR * oldR;
        for (int dx = -newR; dx <= newR; dx++) {
            long dx2 = (long) dx * dx;
            int outerZ = isqrt(outer - dx2);
            int innerZ = (oldR >= 0 && dx2 <= inner) ? isqrt(inner - dx2) : -1;
            for (int dz = innerZ + 1; dz <= outerZ; dz++) {
                addColumn(st, w, st.cx + dx, st.cz + dz);
                if (dz != 0) addColumn(st, w, st.cx + dx, st.cz - dz);
            }
        }
    }

    private static int isqrt(long n) {
        if (n < 0) return -1;
        long r = (long) Math.sqrt((double) n);
        while (r * r > n) r--;
        while ((r + 1) * (r + 1) <= n) r++;
        return (int) r;
    }

    private void addColumn(State st, World w, int x, int z) {
        if (!w.isChunkLoaded(x >> 4, z >> 4)) return;   // handled by ChunkLoadEvent later
        long ck = colKey(x, z);
        if (!st.done.contains(ck)) st.pending.add(ck);
    }

    /** Queues the not-yet-infected columns of a chunk that are inside the infection. */
    private void enqueueChunk(State st, int chunkX, int chunkZ) {
        long r2 = (long) st.radius * st.radius;
        int minX = chunkX << 4;
        int minZ = chunkZ << 4;
        for (int x = minX; x < minX + 16; x++) {
            for (int z = minZ; z < minZ + 16; z++) {
                long dx = x - st.cx;
                long dz = z - st.cz;
                if (dx * dx + dz * dz > r2) continue;
                long ck = colKey(x, z);
                if (!st.done.contains(ck)) st.pending.add(ck);
            }
        }
    }

    /** Runs every tick with a small budget so the server never lags. */
    private void work() {
        int columns = Math.max(1, cfg().getInt("infection.columns-per-tick", 30));
        int blocks = Math.max(1, cfg().getInt("infection.restore-blocks-per-tick", 300));
        int grow = Math.max(1, cfg().getInt("infection.trees.blocks-per-tick", 40));

        for (State st : new ArrayList<>(states.values())) {
            World w = Bukkit.getWorld(st.world);
            if (w == null) continue;
            if (st.spreading) {
                for (int i = 0; i < columns && !st.pending.isEmpty(); i++) {
                    processColumn(st, w, st.pending.poll());
                }
                for (int i = 0; i < grow && !st.growQueue.isEmpty(); i++) {
                    placePlant(st, w, st.growQueue.poll());
                }
            } else {
                restoreSome(st, w, blocks);
            }
        }
    }

    private void processColumn(State st, World w, long ck) {
        int x = keyX(ck);
        int z = keyZ(ck);
        if (!w.isChunkLoaded(x >> 4, z >> 4)) return;   // will be queued again when the chunk loads
        if (!st.done.add(ck)) return;                    // already infected
        st.dirty = true;

        int y = w.getHighestBlockYAt(x, z, HeightMap.WORLD_SURFACE);
        if (w.getBlockAt(x, y, z).isLiquid()) return;    // lakes and oceans stay clean

        // Walk down through tree canopies: leaves are destroyed, pale trees are left alone
        int limit = Math.max(w.getMinHeight(), y - 48);
        while (y > limit) {
            Block b = w.getBlockAt(x, y, z);
            Material m = b.getType();
            if (m.isAir() || isPale(m)) {
                y--;
                continue;
            }
            if (Tag.LEAVES.isTagged(m)) {
                corrupt(st, b);
                y--;
                continue;
            }
            break;
        }
        if (w.getBlockAt(x, y, z).isLiquid()) return;

        int top = y;
        int depth = Math.max(1, cfg().getInt("infection.depth", 6));
        int bottom = Math.max(w.getMinHeight(), top - depth + 1);
        for (int yy = top; yy >= bottom; yy--) {
            corrupt(st, w.getBlockAt(x, yy, z));
        }

        maybePlantTree(st, w, x, z, top);
    }

    private void corrupt(State st, Block b) {
        Material m = b.getType();
        if (m.isAir() || b.isLiquid() || isProtected(m)) return;
        if (st.hasHeart && b.getX() == st.hx && b.getY() == st.hy && b.getZ() == st.hz) return;

        Material target = cursedFor(m);          // null = destroy
        if (target == m) return;                 // already cursed
        if (target == null && b.getState(false) instanceof TileState) return;  // chests, signs, beds...

        record(st, b);
        b.setBlockData((target == null ? Material.AIR : target).createBlockData(), false);
    }

    private void record(State st, Block b) {
        String data = b.getBlockData().getAsString();
        Integer idx = st.paletteIndex.get(data);
        if (idx == null) {
            idx = st.palette.size();
            st.palette.add(data);
            st.paletteIndex.put(data, idx);
        }
        st.changes.putIfAbsent(blockKey(b.getX(), b.getY(), b.getZ()), idx);
        st.dirty = true;
    }

    /** Pale trees and moss belong to the infection and are never destroyed by it. */
    private boolean isPale(Material m) {
        return m == paleLog || m == paleLeaves || m == paleCarpet || m == paleHanging
                || m.name().startsWith("PALE_");
    }

    /** Cursed version of natural ground, or null if the block should simply be destroyed. */
    private Material cursedFor(Material m) {
        if (isPale(m)) return m;
        if (m == Material.SOUL_SOIL || m == Material.SOUL_SAND || m == Material.BLACKSTONE) return m;
        if (Tag.DIRT.isTagged(m) || m == Material.FARMLAND || m == Material.DIRT_PATH) return Material.SOUL_SOIL;
        if (Tag.SAND.isTagged(m)) return Material.SOUL_SAND;
        if (Tag.BASE_STONE_OVERWORLD.isTagged(m) || m == Material.GRAVEL || m == Material.CLAY
                || m == Material.SANDSTONE || m == Material.RED_SANDSTONE || m == Material.SNOW_BLOCK
                || m == Material.TERRACOTTA || m.name().endsWith("_ORE")) {
            return Material.BLACKSTONE;
        }
        return null;
    }

    private boolean isProtected(Material m) {
        if (m == heartMat) return true;
        return switch (m) {
            case BEDROCK, BARRIER, LIGHT, STRUCTURE_BLOCK, STRUCTURE_VOID, JIGSAW,
                 COMMAND_BLOCK, CHAIN_COMMAND_BLOCK, REPEATING_COMMAND_BLOCK,
                 END_PORTAL, END_PORTAL_FRAME, END_GATEWAY, NETHER_PORTAL, MOVING_PISTON,
                 SUSPICIOUS_SAND, SUSPICIOUS_GRAVEL -> true;
            default -> false;
        };
    }

    // ---------- Pale trees ----------

    /** Sometimes grows a pale tree on freshly corrupted ground. Trees are only ever placed into empty air. */
    private void maybePlantTree(State st, World w, int x, int z, int top) {
        if (!cfg().getBoolean("infection.trees.enabled", true) || paleLog == null || paleLeaves == null) return;
        if (random.nextDouble() >= cfg().getDouble("infection.trees.chance", 0.02)) return;
        if (st.hasHeart && Math.abs(x - st.hx) <= 3 && Math.abs(z - st.hz) <= 3) return;

        // The ground must be cursed ground (so trees only grow inside the infection)
        Block ground = w.getBlockAt(x, top, z);
        for (int i = 0; i < 7 && !ground.getType().isSolid(); i++) {
            ground = ground.getRelative(BlockFace.DOWN);
        }
        Material gm = ground.getType();
        if (gm != Material.SOUL_SOIL && gm != Material.SOUL_SAND && gm != Material.BLACKSTONE) return;

        // Keep trees apart
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                if (st.treeColumns.contains(colKey(x + dx, z + dz))) return;
            }
        }

        int minH = Math.max(3, cfg().getInt("infection.trees.min-height", 4));
        int maxH = Math.max(minH, cfg().getInt("infection.trees.max-height", 7));
        int h = minH + random.nextInt(maxH - minH + 1);
        int baseY = ground.getY() + 1;
        if (baseY + h + 3 >= w.getMaxHeight()) return;
        for (int i = 0; i < h + 2; i++) {
            if (!w.getBlockAt(x, baseY + i, z).getType().isAir()) return;   // trunk space must be free
        }

        st.treeColumns.add(colKey(x, z));
        planTree(st, x, baseY, z, h);
    }

    /** Builds the list of blocks of one tree, bottom to top, so it visibly grows. */
    private void planTree(State st, int x, int baseY, int z, int h) {
        Set<Long> used = new HashSet<>();
        BlockData log = paleLog.createBlockData();
        BlockData leaf = paleLeaves.createBlockData();
        if (leaf instanceof Leaves lv) lv.setPersistent(true);   // never decay

        // Moss carpet around the base
        if (paleCarpet != null) {
            BlockData carpet = paleCarpet.createBlockData();
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    if ((dx != 0 || dz != 0) && dx * dx + dz * dz <= 5 && random.nextDouble() < 0.45) {
                        addPlant(st, used, x + dx, baseY, z + dz, carpet);
                    }
                }
            }
        }

        // Trunk
        for (int i = 0; i < h; i++) {
            addPlant(st, used, x, baseY + i, z, log);
        }

        // Canopy: four layers, widest in the middle
        int[] layers = {h - 2, h - 1, h, h + 1};
        int[] radii = {2, 3, 2, 1};
        for (int li = 0; li < layers.length; li++) {
            int y = baseY + layers[li];
            int r = radii[li];
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (dx * dx + dz * dz > r * r + 1) continue;
                    if (dx == 0 && dz == 0 && layers[li] < h) continue;                         // trunk
                    if (Math.abs(dx) == r && Math.abs(dz) == r && random.nextBoolean()) continue; // ragged corners
                    addPlant(st, used, x + dx, y, z + dz, leaf);
                }
            }
        }

        // Hanging moss under the canopy
        if (paleHanging != null) {
            BlockData moss = paleHanging.createBlockData();
            for (int dx = -3; dx <= 3; dx++) {
                for (int dz = -3; dz <= 3; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    boolean leafAbove = used.contains(blockKey(x + dx, baseY + h - 2, z + dz));
                    boolean freeBelow = !used.contains(blockKey(x + dx, baseY + h - 3, z + dz));
                    if (leafAbove && freeBelow && random.nextDouble() < 0.3) {
                        addPlant(st, used, x + dx, baseY + h - 3, z + dz, moss);
                    }
                }
            }
        }
    }

    private void addPlant(State st, Set<Long> used, int x, int y, int z, BlockData data) {
        if (used.add(blockKey(x, y, z))) st.growQueue.add(new Plant(x, y, z, data));
    }

    private void placePlant(State st, World w, Plant p) {
        if (!w.isChunkLoaded(p.x() >> 4, p.z() >> 4)) return;
        Block b = w.getBlockAt(p.x(), p.y(), p.z());
        if (!b.getType().isAir()) return;   // only empty air - never replaces anything
        record(st, b);                      // so it is removed again when the zone is cleansed
        b.setBlockData(p.data(), false);
    }

    // ---------- Restoring ----------

    private void beginRestore(State st, Player by) {
        st.spreading = false;
        st.pending.clear();
        st.growQueue.clear();
        st.treeColumns.clear();
        st.done.clear();
        st.deferred.clear();
        st.restoreQueue.clear();
        st.restoreQueue.addAll(st.changes.keySet());
        st.dirty = true;

        World w = Bukkit.getWorld(st.world);
        if (w != null) removeHeart(st, w);

        // The zone itself is deleted; the land keeps healing in the background
        if (cfg().getBoolean("infection.delete-zone-on-cleanse", true)) {
            zones.dropZone(st.zone);
        } else {
            HauntedZones.Zone z = zones.get(st.zone);
            if (z != null) z.setRadius(z.baseRadius());
        }

        if (by != null) {
            int reward = Math.max(0, cfg().getInt("infection.cleanse-reward", 50));
            if (reward > 0) plugin.candy().give(by, reward);
            broadcast("<green>" + by.getName() + "</green> destroyed the <light_purple>Cursed Heart</light_purple> of <gold>"
                    + st.zone + "</gold>! The haunted zone is gone and the land is healing... <gray>(+" + reward + " candy)");
        } else {
            broadcast("The haunted zone <gold>" + st.zone + "</gold> has been cleansed. The land is healing...");
        }
        save();
    }

    private void restoreSome(State st, World w, int budget) {
        boolean touched = false;
        for (int i = 0; i < budget && !st.restoreQueue.isEmpty(); i++) {
            long key = st.restoreQueue.poll();
            int x = keyX(key);
            int y = keyY(key);
            int z = keyZ(key);
            int cx = x >> 4;
            int cz = z >> 4;

            if (!w.isChunkLoaded(cx, cz)) {
                st.deferred.computeIfAbsent(chunkKey(cx, cz), k -> new ArrayList<>()).add(key);
                continue;
            }
            Integer idx = st.changes.remove(key);
            if (idx == null) continue;
            st.dirty = true;
            if (idx < 0 || idx >= st.palette.size()) continue;
            try {
                w.getBlockAt(x, y, z).setBlockData(Bukkit.createBlockData(st.palette.get(idx)), false);
                touched = true;
            } catch (IllegalArgumentException ignored) {
                // block type no longer exists on this server - skip it
            }
        }
        if (touched) freePlayers(w);
        if (st.restoreQueue.isEmpty() && st.deferred.isEmpty()) finishRestore(st);
    }

    /** If a restored block ended up around a player's head, lift them to the surface. */
    private void freePlayers(World w) {
        for (Player p : w.getPlayers()) {
            Block eye = p.getEyeLocation().getBlock();
            if (!eye.getType().isOccluding()) continue;
            Location l = p.getLocation();
            int y = w.getHighestBlockYAt(l.getBlockX(), l.getBlockZ()) + 1;
            p.teleport(new Location(w, l.getBlockX() + 0.5, y, l.getBlockZ() + 0.5, l.getYaw(), l.getPitch()));
        }
    }

    private void finishRestore(State st) {
        states.remove(st.zone);
        File f = new File(dir, st.zone + ".bin");
        if (f.exists() && !f.delete()) {
            plugin.getLogger().warning("Could not delete " + f.getName());
        }
        save();
        plugin.getLogger().info("Haunted zone " + st.zone + " has been fully restored.");
    }

    // ---------- Visuals and hive atmosphere ----------

    private void effects() {
        effectTick++;
        boolean darkness = cfg().getBoolean("haunted-zones.hive.darkness", true);
        int slowAt = cfg().getInt("haunted-zones.hive.slowness-level", 4);
        int weakAt = cfg().getInt("haunted-zones.hive.weakness-level", 5);
        double range = Math.max(8, cfg().getDouble("haunted-zones.hive.heartbeat-range", 48));

        for (State st : states.values()) {
            if (!st.spreading || !st.hasHeart) continue;
            World w = Bukkit.getWorld(st.world);
            if (w == null || !w.isChunkLoaded(st.hx >> 4, st.hz >> 4)) continue;

            Location c = new Location(w, st.hx + 0.5, st.hy + 0.5, st.hz + 0.5);
            w.spawnParticle(Particle.SOUL_FIRE_FLAME, c, 6, 0.4, 0.4, 0.4, 0.02);
            w.spawnParticle(Particle.SCULK_SOUL, c, 4, 0.6, 0.6, 0.6, 0.02);
            for (int i = 2; i <= 14; i += 3) {
                w.spawnParticle(Particle.END_ROD, c.getX(), c.getY() + i, c.getZ(), 1, 0.05, 0.2, 0.05, 0.0);
            }

            for (Player p : w.getPlayers()) {
                double dist = p.getLocation().distance(c);
                if (dist > range) continue;

                // The nearer you are, the faster the heart beats
                int every = 1 + (int) (dist / 12);
                if (effectTick % every == 0) {
                    p.playSound(p.getLocation(), Sound.ENTITY_WARDEN_HEARTBEAT, 1f, 0.9f);
                }

                GameMode gm = p.getGameMode();
                if (gm != GameMode.SURVIVAL && gm != GameMode.ADVENTURE) continue;
                int level = levelAt(st.zone, p.getLocation());
                // The hive's aura makes it harder to get close
                if (darkness && level >= 3) {
                    p.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 60, 0, false, false));
                }
                if (slowAt > 0 && level >= slowAt) {
                    p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 0, false, false));
                }
                if (weakAt > 0 && level >= weakAt) {
                    p.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 60, 0, false, false));
                }
            }
        }
    }

    // ---------- Events ----------

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        State st = heartState(e.getBlock());
        if (st == null || !st.spreading) return;
        e.setDropItems(false);
        e.setExpToDrop(0);
        Player breaker = e.getPlayer();
        String zone = st.zone;
        // Run after the break finishes so the block is already gone
        Bukkit.getScheduler().runTask(plugin, () -> cleanse(zone, breaker));
    }

    @EventHandler(ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        e.blockList().removeIf(b -> heartState(b) != null);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        e.blockList().removeIf(b -> heartState(b) != null);
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityChange(EntityChangeBlockEvent e) {
        if (heartState(e.getBlock()) != null) e.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        for (Block b : e.getBlocks()) {
            if (heartState(b) != null) { e.setCancelled(true); return; }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        for (Block b : e.getBlocks()) {
            if (heartState(b) != null) { e.setCancelled(true); return; }
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent e) {
        String worldName = e.getWorld().getName();
        int cx = e.getChunk().getX();
        int cz = e.getChunk().getZ();
        for (State st : states.values()) {
            if (!st.world.equals(worldName)) continue;
            if (st.spreading) {
                enqueueChunk(st, cx, cz);
            } else {
                List<Long> keys = st.deferred.remove(chunkKey(cx, cz));
                if (keys != null) st.restoreQueue.addAll(keys);
            }
        }
    }

    // ---------- Saving / loading ----------

    private void saveIfDirty() {
        for (State st : states.values()) {
            if (st.dirty) {
                save();
                return;
            }
        }
    }

    public void save() {
        YamlConfiguration yml = new YamlConfiguration();
        for (State st : states.values()) {
            String base = "states." + st.zone;
            yml.set(base + ".world", st.world);
            yml.set(base + ".cx", st.cx);
            yml.set(base + ".cz", st.cz);
            yml.set(base + ".radius", st.radius);
            yml.set(base + ".spreading", st.spreading);
            yml.set(base + ".has-heart", st.hasHeart);
            yml.set(base + ".hx", st.hx);
            yml.set(base + ".hy", st.hy);
            yml.set(base + ".hz", st.hz);
            if (st.dirty) {
                writeData(st);
                st.dirty = false;
            }
        }
        try {
            yml.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save infection.yml: " + e.getMessage());
        }
    }

    private void load() {
        if (!dataFile.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection sec = yml.getConfigurationSection("states");
        if (sec == null) return;

        for (String name : sec.getKeys(false)) {
            ConfigurationSection s = sec.getConfigurationSection(name);
            if (s == null) continue;
            State st = new State(name, s.getString("world", "world"), s.getInt("cx"), s.getInt("cz"));
            st.radius = s.getInt("radius", 0);
            st.spreading = s.getBoolean("spreading", true);
            st.hasHeart = s.getBoolean("has-heart", false);
            st.hx = s.getInt("hx");
            st.hy = s.getInt("hy");
            st.hz = s.getInt("hz");
            readData(st);
            states.put(name, st);

            if (st.spreading) {
                HauntedZones.Zone z = zones.get(name);
                if (z != null) z.setRadius(Math.max(z.baseRadius(), st.radius));
            } else {
                st.restoreQueue.addAll(st.changes.keySet());
            }
        }
    }

    private void writeData(State st) {
        File target = new File(dir, st.zone + ".bin");
        File tmp = new File(dir, st.zone + ".bin.tmp");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)))) {
            out.writeInt(FILE_VERSION);
            out.writeInt(st.palette.size());
            for (String s : st.palette) out.writeUTF(s);
            out.writeInt(st.changes.size());
            for (Map.Entry<Long, Integer> en : st.changes.entrySet()) {
                out.writeLong(en.getKey());
                out.writeInt(en.getValue());
            }
            out.writeInt(st.done.size());
            for (long k : st.done) out.writeLong(k);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save infection data for " + st.zone + ": " + e.getMessage());
            return;
        }
        try {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not finish saving infection data for " + st.zone + ": " + e.getMessage());
        }
    }

    private void readData(State st) {
        File f = new File(dir, st.zone + ".bin");
        if (!f.exists()) return;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f)))) {
            if (in.readInt() != FILE_VERSION) return;
            int pal = in.readInt();
            for (int i = 0; i < pal; i++) {
                String s = in.readUTF();
                st.paletteIndex.put(s, st.palette.size());
                st.palette.add(s);
            }
            int n = in.readInt();
            for (int i = 0; i < n; i++) {
                long key = in.readLong();
                int idx = in.readInt();
                st.changes.put(key, idx);
            }
            int d = in.readInt();
            for (int i = 0; i < d; i++) st.done.add(in.readLong());
        } catch (IOException e) {
            plugin.getLogger().severe("Could not read infection data for " + st.zone + ": " + e.getMessage());
        }
    }

    // ---------- Key packing ----------

    private static long blockKey(int x, int y, int z) {
        return ((long) x << 38) | (((long) z & 0x3FFFFFFL) << 12) | ((y + 2048) & 0xFFFL);
    }

    private static long colKey(int x, int z) {
        return blockKey(x, -2048, z);
    }

    private static int keyX(long k) {
        return (int) (k >> 38);
    }

    private static int keyZ(long k) {
        return (int) ((k << 26) >> 38);
    }

    private static int keyY(long k) {
        return (int) (k & 0xFFFL) - 2048;
    }

    private static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }
            }
