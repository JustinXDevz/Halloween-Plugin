package com.example.halloween;

import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.AbstractSkeleton;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;

/**
 * Haunted Zones: circular areas with fog, ambient sounds, extra mobs, a candy multiplier
 * and candy drops from haunted mobs. Each zone holds a Cursed Creaking Heart (see
 * InfectionManager): the zone spreads until the heart is destroyed, then it is cleansed and deleted.
 * The closer a mob spawns to the heart, the tougher it is - the heart defends itself like a hive.
 * Everything is dormant outside the event dates.
 */
public final class HauntedZones implements Listener {

    public static final class Zone {
        private final String name;
        private final String world;
        private final int x;
        private final int z;
        private final int baseRadius;
        private int radius;

        Zone(String name, String world, int x, int z, int radius) {
            this.name = name;
            this.world = world;
            this.x = x;
            this.z = z;
            this.baseRadius = radius;
            this.radius = radius;
        }

        public String name() { return name; }
        public String world() { return world; }
        public int x() { return x; }
        public int z() { return z; }
        /** Radius the zone was created with. */
        public int baseRadius() { return baseRadius; }
        /** Current radius (grows while the zone is infected). */
        public int radius() { return radius; }

        void setRadius(int radius) { this.radius = radius; }

        public boolean contains(Location loc) {
            if (loc.getWorld() == null || !loc.getWorld().getName().equals(world)) return false;
            double dx = loc.getX() - x;
            double dz = loc.getZ() - z;
            return dx * dx + dz * dz <= (double) radius * radius;
        }
    }

    // Mob buffs per danger level (index = level, clamped to 4). -1 = no effect.
    private static final int[] SPEED = {-1, 0, 0, 1, 1};
    private static final int[] STRENGTH = {-1, -1, 0, 0, 1};
    private static final int[] RESISTANCE = {-1, -1, -1, 0, 1};
    private static final int[] ABSORPTION = {-1, -1, 0, 1, 2};
    private static final int[] REGEN = {-1, -1, -1, -1, 0};

    private final HalloweenPlugin plugin;
    private final File file;
    private final Random random = new Random();
    private final NamespacedKey mobKey;
    private final NamespacedKey levelKey;
    private final InfectionManager infection;

    private final Map<String, Zone> zones = new LinkedHashMap<>();
    /** Player -> name of the zone they are currently in. */
    private final Map<UUID, String> inside = new HashMap<>();
    /** Haunted mob -> zone it belongs to. */
    private final Map<UUID, String> mobs = new HashMap<>();

    private BukkitTask tickTask;
    private BukkitTask mobTask;
    private BukkitTask autoTask;
    private int tickCount = 0;

    public HauntedZones(HalloweenPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "zones.yml");
        this.mobKey = new NamespacedKey(plugin, "haunted_mob");
        this.levelKey = new NamespacedKey(plugin, "haunted_level");
        load();
        this.infection = new InfectionManager(plugin, this);
        plugin.getServer().getPluginManager().registerEvents(infection, plugin);
    }

    // ---------- Storage ----------

    private void load() {
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection sec = yml.getConfigurationSection("zones");
        if (sec == null) return;
        for (String name : sec.getKeys(false)) {
            ConfigurationSection z = sec.getConfigurationSection(name);
            if (z == null) continue;
            zones.put(name, new Zone(name, z.getString("world", "world"),
                    z.getInt("x"), z.getInt("z"), z.getInt("radius", 30)));
        }
    }

    private void save() {
        YamlConfiguration yml = new YamlConfiguration();
        for (Zone z : zones.values()) {
            String base = "zones." + z.name();
            yml.set(base + ".world", z.world());
            yml.set(base + ".x", z.x());
            yml.set(base + ".z", z.z());
            yml.set(base + ".radius", z.baseRadius());
        }
        try {
            yml.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save zones.yml: " + e.getMessage());
        }
    }

    // ---------- Zone API ----------

    /** Creates a zone centered on {@code center}; the Cursed Heart replaces the block under that spot. */
    public boolean add(String name, Location center, int radius) {
        return add(name, center, radius, true);
    }

    private boolean add(String name, Location center, int radius, boolean heartUnderCenter) {
        if (zones.containsKey(name)) return false;
        Zone zone = new Zone(name, center.getWorld().getName(), center.getBlockX(), center.getBlockZ(), radius);
        zones.put(name, zone);
        save();
        if (plugin.getConfig().getBoolean("infection.auto-start", true) && plugin.isEventActive()) {
            infection.infect(zone, center, heartUnderCenter);
        }
        return true;
    }

    /** Next free automatic name: haunt_1, haunt_2, ... */
    public String nextName() {
        int i = 1;
        while (zones.containsKey("haunt_" + i) || !infection.status("haunt_" + i).equals("clean")) i++;
        return "haunt_" + i;
    }

    /** Removes a zone. Any infection it had is cleansed and the land is restored. */
    public boolean remove(String name) {
        boolean existed = zones.containsKey(name);
        infection.cleanse(name, null);
        dropZone(name);
        return existed;
    }

    /** Deletes the zone (and its mobs) without touching the infection; used when a zone is cleansed. */
    void dropZone(String name) {
        if (zones.remove(name) == null) return;
        removeMobsOf(name);
        inside.values().removeIf(n -> n.equals(name));
        save();
    }

    public Zone get(String name) {
        return zones.get(name);
    }

    public Collection<Zone> all() {
        return zones.values();
    }

    public List<String> names() {
        return new ArrayList<>(zones.keySet());
    }

    /** Infections that are still restoring the land after their zone was deleted. */
    public List<String> orphanStatuses() {
        List<String> out = new ArrayList<>();
        for (String name : infection.stateNames()) {
            if (!zones.containsKey(name)) out.add(name + " [" + infection.status(name) + "]");
        }
        return out;
    }

    public Zone zoneAt(Location loc) {
        for (Zone z : zones.values()) {
            if (z.contains(loc)) return z;
        }
        return null;
    }

    /** Candy multiplier at a location (1 outside zones or outside the event). */
    public int multiplierAt(Location loc) {
        if (!plugin.isEventActive() || zoneAt(loc) == null) return 1;
        return Math.max(1, plugin.getConfig().getInt("haunted-zones.candy-multiplier", 2));
    }

    // ---------- Infection API ----------

    /** Starts the infection in an existing zone (heart on the ground at its center). */
    public boolean infect(String name) {
        Zone z = zones.get(name);
        return z != null && infection.infect(z, null, false);
    }

    /** Stops the infection, deletes the zone and restores the land. False if the zone isn't infected. */
    public boolean cleanse(String name) {
        return infection.cleanse(name, null);
    }

    public String status(String name) {
        return infection.status(name);
    }

    public int[] heartPos(String name) {
        return infection.heartPos(name);
    }

    // ---------- Lifecycle ----------

    public void start() {
        stop();
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 10L);
        long mobInterval = Math.max(3, plugin.getConfig().getInt("haunted-zones.mob-spawn-interval-seconds", 8)) * 20L;
        mobTask = Bukkit.getScheduler().runTaskTimer(plugin, this::spawnMobs, mobInterval, mobInterval);
        if (plugin.getConfig().getBoolean("haunted-zones.auto-spawn.enabled", false)) {
            long autoInterval = Math.max(1, plugin.getConfig().getInt("haunted-zones.auto-spawn.interval-minutes", 30)) * 1200L;
            autoTask = Bukkit.getScheduler().runTaskTimer(plugin, this::autoSpawn, autoInterval, autoInterval);
        }
        infection.start();
    }

    public void stop() {
        if (tickTask != null) tickTask.cancel();
        if (mobTask != null) mobTask.cancel();
        if (autoTask != null) autoTask.cancel();
        tickTask = null;
        mobTask = null;
        autoTask = null;
        infection.stop();
    }

    /** Removes all haunted mobs. */
    public void clearMobs() {
        stop();
        for (UUID id : new ArrayList<>(mobs.keySet())) {
            Entity ent = Bukkit.getEntity(id);
            if (ent != null) ent.remove();
        }
        mobs.clear();
    }

    /** Called when the plugin disables: removes mobs and saves the infection. */
    public void shutdown() {
        clearMobs();
        infection.shutdown();
    }

    // ---------- Optional automatic zones ----------

    /** Creates a new haunted zone near a random player (only when haunted-zones.auto-spawn.enabled). */
    private void autoSpawn() {
        var c = plugin.getConfig();
        if (!plugin.isEventActive()) return;
        if (zones.size() >= Math.max(1, c.getInt("haunted-zones.auto-spawn.max-zones", 1))) return;

        List<Player> players = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getGameMode() != GameMode.SPECTATOR) players.add(p);
        }
        if (players.isEmpty()) return;
        Player target = players.get(random.nextInt(players.size()));

        int min = c.getInt("haunted-zones.auto-spawn.min-distance", 60);
        int max = Math.max(min + 1, c.getInt("haunted-zones.auto-spawn.max-distance", 140));
        Block spot = SpawnSpots.findNear(plugin, target, min, max, random);
        if (spot == null) return;

        add(nextName(), spot.getLocation(), Math.max(5, c.getInt("haunted-zones.default-radius", 30)), false);
    }

    // ---------- Atmosphere (runs every 0.5s) ----------

    private void tick() {
        if (zones.isEmpty()) {
            inside.clear();
            return;
        }
        boolean active = plugin.isEventActive();
        tickCount++;

        for (Player p : Bukkit.getOnlinePlayers()) {
            Zone z = active ? zoneAt(p.getLocation()) : null;
            String current = z == null ? null : z.name();
            String previous = inside.get(p.getUniqueId());

            if (!Objects.equals(current, previous)) {
                if (current == null) {
                    inside.remove(p.getUniqueId());
                    p.sendActionBar(HalloweenPlugin.mm("<gray>You left the haunted zone."));
                } else {
                    inside.put(p.getUniqueId(), current);
                    enter(p, z);
                }
            }

            if (z != null) {
                fog(p);
                if (tickCount % 10 == 0 && random.nextDouble() < 0.3) {
                    p.playSound(p.getLocation(), Sound.AMBIENT_CAVE, 0.7f, 0.8f);
                }
                if (tickCount % 4 == 0) infection.hint(p, z.name());
            } else if (active && tickCount % 2 == 0) {
                ring(p);
            }
        }
    }

    private void enter(Player p, Zone z) {
        int mult = Math.max(1, plugin.getConfig().getInt("haunted-zones.candy-multiplier", 2));
        p.showTitle(Title.title(
                HalloweenPlugin.mm("<dark_purple>Haunted Zone"),
                HalloweenPlugin.mm("<gray>" + z.name() + " <dark_gray>- <gold>x" + mult + " candy")));
        p.playSound(p.getLocation(), Sound.ENTITY_VEX_AMBIENT, 1f, 0.5f);
    }

    private void fog(Player p) {
        Location l = p.getLocation();
        p.spawnParticle(Particle.CLOUD, l.getX(), l.getY() + 0.3, l.getZ(), 6, 6, 0.4, 6, 0.0);
        p.spawnParticle(Particle.ASH, l.getX(), l.getY() + 2, l.getZ(), 25, 8, 3, 8, 0.0);
    }

    /** Soul-fire ring on the zone border, shown to players who are near but outside. */
    private void ring(Player p) {
        Location pl = p.getLocation();
        World w = pl.getWorld();
        for (Zone z : zones.values()) {
            if (!z.world().equals(w.getName())) continue;
            double dx = pl.getX() - z.x();
            double dz = pl.getZ() - z.z();
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist < z.radius() || dist > z.radius() + 48) continue;

            int points = Math.max(24, Math.min(90, (int) (2 * Math.PI * z.radius() / 3)));
            for (int i = 0; i < points; i++) {
                double a = 2 * Math.PI * i / points;
                int x = (int) Math.round(z.x() + Math.cos(a) * z.radius());
                int zz = (int) Math.round(z.z() + Math.sin(a) * z.radius());

                double px = x - pl.getX();
                double pz = zz - pl.getZ();
                if (px * px + pz * pz > 48 * 48) continue;
                if (!w.isChunkLoaded(x >> 4, zz >> 4)) continue;

                int y = w.getHighestBlockYAt(x, zz, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1;
                p.spawnParticle(Particle.SOUL_FIRE_FLAME, x + 0.5, y + 0.5, zz + 0.5, 1, 0.1, 0.4, 0.1, 0.0);
            }
        }
    }

    // ---------- Extra mobs (the hive defends its heart) ----------

    /** Base mobs plus the extra mobs unlocked at each danger level up to {@code level}. */
    private List<EntityType> mobTypes(int level) {
        List<String> names = new ArrayList<>(plugin.getConfig().getStringList("haunted-zones.mobs"));
        for (int l = 2; l <= level; l++) {
            names.addAll(plugin.getConfig().getStringList("haunted-zones.hive.extra-mobs.level-" + l));
        }
        List<EntityType> types = new ArrayList<>();
        for (String name : names) {
            try {
                EntityType t = EntityType.valueOf(name.toUpperCase(Locale.ROOT));
                if (t.isSpawnable() && t.isAlive()) types.add(t);
            } catch (IllegalArgumentException ignored) {
                plugin.getLogger().warning("Unknown mob in haunted-zones config: " + name);
            }
        }
        return types;
    }

    private void spawnMobs() {
        if (!plugin.isEventActive() || zones.isEmpty()) return;

        mobs.entrySet().removeIf(en -> {
            Entity ent = Bukkit.getEntity(en.getKey());
            return ent == null || !ent.isValid();
        });

        var c = plugin.getConfig();
        int baseCap = c.getInt("haunted-zones.max-mobs-per-zone", 6);
        int extraPerLevel = Math.max(0, c.getInt("haunted-zones.hive.mobs-per-level", 1));
        int capPerLevel = Math.max(0, c.getInt("haunted-zones.hive.cap-per-level", 3));

        for (Zone z : new ArrayList<>(zones.values())) {
            World w = Bukkit.getWorld(z.world());
            if (w == null) continue;

            List<Player> inZone = new ArrayList<>();
            for (Player p : w.getPlayers()) {
                if (p.getGameMode() != GameMode.SPECTATOR && z.contains(p.getLocation())) inZone.add(p);
            }
            if (inZone.isEmpty()) continue;

            Player target = inZone.get(random.nextInt(inZone.size()));
            int playerLevel = infection.levelAt(z.name(), target.getLocation());
            int cap = baseCap + capPerLevel * playerLevel;
            int toSpawn = 1 + extraPerLevel * playerLevel;
            int count = countMobs(z.name());

            for (int i = 0; i < toSpawn && count < cap; i++) {
                Location spot = findMobSpot(z, target);
                if (spot == null) break;
                int level = infection.levelAt(z.name(), spot);
                List<EntityType> types = mobTypes(level);
                if (types.isEmpty()) break;
                spawnMob(z, spot, types.get(random.nextInt(types.size())), level);
                count++;
            }
        }
    }

    private int countMobs(String zoneName) {
        int n = 0;
        for (String zn : mobs.values()) {
            if (zn.equals(zoneName)) n++;
        }
        return n;
    }

    private Location findMobSpot(Zone z, Player near) {
        World w = near.getWorld();
        Location pl = near.getLocation();

        for (int i = 0; i < 15; i++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double dist = 10 + random.nextDouble() * 14;
            int x = pl.getBlockX() + (int) Math.round(Math.cos(angle) * dist);
            int zz = pl.getBlockZ() + (int) Math.round(Math.sin(angle) * dist);

            if (!z.contains(new Location(w, x + 0.5, pl.getY(), zz + 0.5))) continue;
            if (!w.isChunkLoaded(x >> 4, zz >> 4)) continue;

            Block ground = w.getHighestBlockAt(x, zz, HeightMap.MOTION_BLOCKING_NO_LEAVES);
            if (!ground.getType().isSolid()) continue;

            Block feet = ground.getRelative(BlockFace.UP);
            Block head = feet.getRelative(BlockFace.UP);
            if (!feet.isPassable() || feet.isLiquid() || !head.isPassable() || head.isLiquid()) continue;
            if (!w.getWorldBorder().isInside(feet.getLocation())) continue;

            return feet.getLocation().add(0.5, 0, 0.5);
        }
        return null;
    }

    private void spawnMob(Zone z, Location loc, EntityType type, int level) {
        Entity ent = loc.getWorld().spawnEntity(loc, type);
        if (!(ent instanceof LivingEntity mob)) {
            ent.remove();
            return;
        }
        mob.getPersistentDataContainer().set(mobKey, PersistentDataType.BYTE, (byte) 1);
        mob.getPersistentDataContainer().set(levelKey, PersistentDataType.INTEGER, level);
        mob.setPersistent(false);
        mob.setRemoveWhenFarAway(true);

        // A pumpkin on the head keeps zombies/skeletons from burning in daylight.
        EntityEquipment eq = mob.getEquipment();
        if (eq != null && (mob instanceof Zombie || mob instanceof AbstractSkeleton)) {
            eq.setHelmet(new ItemStack(Material.CARVED_PUMPKIN));
            eq.setDropChance(EquipmentSlot.HEAD, 0f);
        }
        applyLevel(mob, level);

        mobs.put(mob.getUniqueId(), z.name());
        loc.getWorld().spawnParticle(Particle.SOUL, loc.clone().add(0, 1, 0), 15, 0.3, 0.5, 0.3, 0.03);
    }

    /** The closer to the heart, the tougher the mob. */
    private void applyLevel(LivingEntity mob, int level) {
        if (level <= 0) return;
        int i = Math.min(level, 4);
        infinite(mob, PotionEffectType.SPEED, SPEED[i]);
        infinite(mob, PotionEffectType.STRENGTH, STRENGTH[i]);
        infinite(mob, PotionEffectType.RESISTANCE, RESISTANCE[i]);
        infinite(mob, PotionEffectType.ABSORPTION, ABSORPTION[i]);
        infinite(mob, PotionEffectType.REGENERATION, REGEN[i]);
        if (level >= 4) mob.setGlowing(true);   // the heart's guardians glow so you can see them coming
    }

    private static void infinite(LivingEntity mob, PotionEffectType type, int amplifier) {
        if (amplifier < 0) return;
        mob.addPotionEffect(new PotionEffect(type, PotionEffect.INFINITE_DURATION, amplifier, false, false));
    }

    private void removeMobsOf(String zoneName) {
        for (Map.Entry<UUID, String> en : new ArrayList<>(mobs.entrySet())) {
            if (!en.getValue().equals(zoneName)) continue;
            Entity ent = Bukkit.getEntity(en.getKey());
            if (ent != null) ent.remove();
            mobs.remove(en.getKey());
        }
    }

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        LivingEntity mob = e.getEntity();
        if (!mob.getPersistentDataContainer().has(mobKey, PersistentDataType.BYTE)) return;
        mobs.remove(mob.getUniqueId());

        Player killer = mob.getKiller();
        if (killer == null || !plugin.isEventActive()) return;

        var c = plugin.getConfig();
        if (random.nextDouble() >= c.getDouble("haunted-zones.kill-candy-chance", 0.6)) return;

        Integer stored = mob.getPersistentDataContainer().get(levelKey, PersistentDataType.INTEGER);
        int level = stored == null ? 0 : stored;

        int min = c.getInt("haunted-zones.kill-candy-min", 1);
        int max = Math.max(min, c.getInt("haunted-zones.kill-candy-max", 3));
        int amount = (min + random.nextInt(max - min + 1)) * (1 + level);   // tougher mob = more candy
        plugin.candy().give(killer, amount);
        killer.sendActionBar(HalloweenPlugin.mm("<gold>+" + amount + " candy <gray>(haunted kill)"));
    }
            }
