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
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.AbstractSkeleton;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * Haunted Zones: circular areas with ash fog, ambient sounds, extra mobs, a candy multiplier
 * and candy drops from haunted mobs. Each zone holds a Cursed Creaking Heart (see
 * InfectionManager): the zone spreads until the heart is destroyed, then it is cleansed and deleted.
 * The heart defends itself like a hive: the closer a mob spawns to it, the tougher it is, sentries
 * guard it, and getting close sounds an alarm.
 * Zones can be created with a command, and optionally spawn automatically.
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

    // Mob buffs per danger level (index = level, clamped to 5). -1 = no effect.
    private static final int[] SPEED = {-1, 0, 0, 1, 1, 2};
    private static final int[] STRENGTH = {-1, -1, 0, 0, 1, 2};
    private static final int[] RESISTANCE = {-1, -1, -1, 0, 1, 2};
    private static final int[] ABSORPTION = {-1, -1, 0, 1, 2, 4};
    private static final int[] REGEN = {-1, -1, -1, -1, 0, 1};

    private final HalloweenPlugin plugin;
    private final File file;
    private final Random random = new Random();
    private final NamespacedKey mobKey;
    private final NamespacedKey levelKey;
    private final InfectionManager infection;
    private final BlockData ashDust;

    private final Map<String, Zone> zones = new LinkedHashMap<>();
    /** Player -> name of the zone they are currently in. */
    private final Map<UUID, String> inside = new HashMap<>();
    /** Haunted mob -> zone it belongs to. */
    private final Map<UUID, String> mobs = new HashMap<>();
    /** Mobs that guard the heart (they respawn). */
    private final Set<UUID> sentries = new HashSet<>();
    /** Player -> last time the hive alarm went off for them. */
    private final Map<UUID, Long> alarmCooldown = new HashMap<>();
    private final Set<String> warnedMobs = new HashSet<>();

    private BukkitTask tickTask;
    private BukkitTask mobTask;
    private BukkitTask autoTask;
    private int tickCount = 0;

    public HauntedZones(HalloweenPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "zones.yml");
        this.mobKey = new NamespacedKey(plugin, "haunted_mob");
        this.levelKey = new NamespacedKey(plugin, "haunted_level");
        this.ashDust = Material.LIGHT_GRAY_CONCRETE_POWDER.createBlockData();
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
        var c = plugin.getConfig();
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 10L);
        long mobInterval = Math.max(3, c.getInt("haunted-zones.mob-spawn-interval-seconds", 8)) * 20L;
        mobTask = Bukkit.getScheduler().runTaskTimer(plugin, this::spawnMobs, mobInterval, mobInterval);
        if (c.getBoolean("haunted-zones.auto-spawn.enabled", true)) {
            long first = Math.max(1, c.getInt("haunted-zones.auto-spawn.first-delay-minutes", 5)) * 1200L;
            long every = Math.max(1, c.getInt("haunted-zones.auto-spawn.interval-minutes", 30)) * 1200L;
            autoTask = Bukkit.getScheduler().runTaskTimer(plugin, this::autoSpawn, first, every);
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
        sentries.clear();
    }

    /** Called when the plugin disables: removes mobs and saves the infection. */
    public void shutdown() {
        clearMobs();
        infection.shutdown();
    }

    // ---------- Automatic zones ----------

    /** Creates a new haunted zone near a random player, away from world spawn. */
    private void autoSpawn() {
        var c = plugin.getConfig();
        if (!plugin.isEventActive()) return;
        if (zones.size() >= Math.max(1, c.getInt("haunted-zones.auto-spawn.max-zones", 1))) return;

        List<Player> players = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getGameMode() != GameMode.SPECTATOR) players.add(p);
        }
        if (players.isEmpty()) return;

        int min = c.getInt("haunted-zones.auto-spawn.min-distance", 60);
        int max = Math.max(min + 1, c.getInt("haunted-zones.auto-spawn.max-distance", 140));
        double avoid = Math.max(0, c.getDouble("haunted-zones.auto-spawn.avoid-spawn-radius", 250));

        for (int attempt = 0; attempt < 8; attempt++) {
            Player target = players.get(random.nextInt(players.size()));
            Block spot = SpawnSpots.findNear(plugin, target, min, max, random);
            if (spot == null) continue;

            Location sp = spot.getWorld().getSpawnLocation();
            double dx = spot.getX() - sp.getX();
            double dz = spot.getZ() - sp.getZ();
            if (Math.sqrt(dx * dx + dz * dz) < avoid) continue;   // keep spawn safe

            add(nextName(), spot.getLocation(), Math.max(5, c.getInt("haunted-zones.default-radius", 30)), false);
            return;
        }
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
                int level = infection.levelAt(z.name(), p.getLocation());
                boolean infected = infection.heartPos(z.name()) != null;
                fog(p, level, infected);
                if (tickCount % 10 == 0 && random.nextDouble() < 0.3) {
                    p.playSound(p.getLocation(), Sound.AMBIENT_CAVE, 0.7f, 0.8f);
                }
                if (tickCount % 4 == 0) infection.hint(p, z.name());
                checkAlarm(z, p, level);
            } else if (active && tickCount % 2 == 0) {
                ring(p);
            }
        }
    }

    private void enter(Player p, Zone z) {
        int mult = Math.max(1, plugin.getConfig().getInt("haunted-zones.candy-multiplier", 2));
        if (infection.heartPos(z.name()) != null) {
            p.showTitle(Title.title(
                    HalloweenPlugin.mm("<dark_red>Infected Zone"),
                    HalloweenPlugin.mm("<gray>Ash chokes the air... <gold>x" + mult + " candy")));
        } else {
            p.showTitle(Title.title(
                    HalloweenPlugin.mm("<dark_purple>Haunted Zone"),
                    HalloweenPlugin.mm("<gray>" + z.name() + " <dark_gray>- <gold>x" + mult + " candy")));
        }
        p.playSound(p.getLocation(), Sound.ENTITY_VEX_AMBIENT, 1f, 0.5f);
    }

    /** Ash fog: drifting ashes and falling dust, thicker the closer you are to the heart. */
    private void fog(Player p, int level, boolean infected) {
        Location l = p.getLocation();
        if (!infected) {
            p.spawnParticle(Particle.CLOUD, l.getX(), l.getY() + 0.3, l.getZ(), 6, 6, 0.4, 6, 0.0);
            p.spawnParticle(Particle.ASH, l.getX(), l.getY() + 2, l.getZ(), 25, 8, 3, 8, 0.0);
            return;
        }
        p.spawnParticle(Particle.CLOUD, l.getX(), l.getY() + 0.3, l.getZ(), 6 + level * 2, 6, 0.4, 6, 0.0);
        p.spawnParticle(Particle.ASH, l.getX(), l.getY() + 3, l.getZ(), 50 + level * 20, 10, 5, 10, 0.0);
        p.spawnParticle(Particle.WHITE_ASH, l.getX(), l.getY() + 4, l.getZ(), 30 + level * 15, 10, 6, 10, 0.0);
        p.spawnParticle(Particle.FALLING_DUST, l.getX(), l.getY() + 6, l.getZ(), 8 + level * 2, 8, 2, 8, 0.0, ashDust);
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

    // ---------- The hive alarm ----------

    /** When someone gets deep into the hive, its guardians swarm them. */
    private void checkAlarm(Zone z, Player p, int level) {
        int alarmLevel = plugin.getConfig().getInt("haunted-zones.hive.alarm-level", 4);
        if (alarmLevel <= 0 || level < alarmLevel) return;
        GameMode gm = p.getGameMode();
        if (gm != GameMode.SURVIVAL && gm != GameMode.ADVENTURE) return;

        long cooldown = Math.max(5, plugin.getConfig().getInt("haunted-zones.hive.alarm-cooldown-seconds", 30)) * 1000L;
        long now = System.currentTimeMillis();
        Long last = alarmCooldown.get(p.getUniqueId());
        if (last != null && now - last < cooldown) return;
        alarmCooldown.put(p.getUniqueId(), now);

        alarm(z, p);
    }

    private void alarm(Zone z, Player p) {
        p.showTitle(Title.title(
                HalloweenPlugin.mm("<dark_red>The hive awakens!"),
                HalloweenPlugin.mm("<gray>Its guardians swarm you")));
        p.playSound(p.getLocation(), Sound.ENTITY_RAVAGER_ROAR, 1f, 0.6f);

        int n = Math.max(0, plugin.getConfig().getInt("haunted-zones.hive.alarm-mobs", 6));
        List<EntityType> types = mobTypes(5);
        if (types.isEmpty()) return;
        World w = p.getWorld();
        Location pl = p.getLocation();
        for (int i = 0; i < n; i++) {
            Location spot = findSpot(w, pl.getX(), pl.getZ(), 6, 14, z);
            if (spot == null) continue;
            int level = Math.max(4, infection.levelAt(z.name(), spot));
            LivingEntity mob = spawnMob(z, spot, types.get(random.nextInt(types.size())), level, false);
            if (mob instanceof Mob m) m.setTarget(p);
        }
    }

    // ---------- Extra mobs (the hive defends its heart) ----------

    /** Base mobs plus the extra mobs unlocked at each danger level up to {@code level}. */
    private List<EntityType> mobTypes(int level) {
        List<String> names = new ArrayList<>(plugin.getConfig().getStringList("haunted-zones.mobs"));
        for (int l = 2; l <= level; l++) {
            names.addAll(plugin.getConfig().getStringList("haunted-zones.hive.extra-mobs.level-" + l));
        }
        return parseTypes(names);
    }

    private List<EntityType> parseTypes(List<String> names) {
        List<EntityType> types = new ArrayList<>();
        for (String name : names) {
            String key = name.toUpperCase(Locale.ROOT);
            try {
                EntityType t = EntityType.valueOf(key);
                if (t.isSpawnable() && t.isAlive()) types.add(t);
            } catch (IllegalArgumentException ignored) {
                // e.g. a mob this server version doesn't have - warn once, then skip it
                if (warnedMobs.add(key)) {
                    plugin.getLogger().warning("Unknown mob in haunted-zones config (skipped): " + name);
                }
            }
        }
        return types;
    }

    private void spawnMobs() {
        if (!plugin.isEventActive() || zones.isEmpty()) return;

        mobs.entrySet().removeIf(en -> {
            Entity ent = Bukkit.getEntity(en.getKey());
            boolean gone = ent == null || !ent.isValid();
            if (gone) sentries.remove(en.getKey());
            return gone;
        });

        var c = plugin.getConfig();
        int baseCap = c.getInt("haunted-zones.max-mobs-per-zone", 6);
        int extraPerLevel = Math.max(0, c.getInt("haunted-zones.hive.mobs-per-level", 2));
        int capPerLevel = Math.max(0, c.getInt("haunted-zones.hive.cap-per-level", 4));

        for (Zone z : new ArrayList<>(zones.values())) {
            World w = Bukkit.getWorld(z.world());
            if (w == null) continue;

            List<Player> inZone = new ArrayList<>();
            for (Player p : w.getPlayers()) {
                if (p.getGameMode() != GameMode.SPECTATOR && z.contains(p.getLocation())) inZone.add(p);
            }
            if (inZone.isEmpty()) continue;

            // Sentries keep guard around the heart whenever someone is nearby
            int[] heart = infection.heartPos(z.name());
            if (heart != null) topUpSentries(z, w, heart, inZone);

            // Regular zone mobs spawn around a random player - tougher near the heart
            Player target = inZone.get(random.nextInt(inZone.size()));
            Location pl = target.getLocation();
            int playerLevel = infection.levelAt(z.name(), pl);
            int cap = baseCap + capPerLevel * playerLevel;
            int toSpawn = 1 + extraPerLevel * playerLevel;
            int count = countMobs(z.name(), false);

            for (int i = 0; i < toSpawn && count < cap; i++) {
                Location spot = findSpot(w, pl.getX(), pl.getZ(), 10, 24, z);
                if (spot == null) break;
                int level = infection.levelAt(z.name(), spot);
                List<EntityType> types = mobTypes(level);
                if (types.isEmpty()) break;
                spawnMob(z, spot, types.get(random.nextInt(types.size())), level, false);
                count++;
            }
        }
    }

    /** Keeps a few elite guardians near the heart. */
    private void topUpSentries(Zone z, World w, int[] heart, List<Player> inZone) {
        var c = plugin.getConfig();
        int wanted = Math.max(0, c.getInt("haunted-zones.hive.sentries", 5));
        if (wanted == 0) return;

        double range = Math.max(10, c.getDouble("haunted-zones.hive.sentry-range", 56));
        boolean someoneNear = false;
        for (Player p : inZone) {
            double dx = p.getLocation().getX() - heart[0];
            double dz = p.getLocation().getZ() - heart[2];
            if (dx * dx + dz * dz <= range * range) {
                someoneNear = true;
                break;
            }
        }
        if (!someoneNear) return;

        int have = countMobs(z.name(), true);
        if (have >= wanted) return;
        List<EntityType> types = parseTypes(c.getStringList("haunted-zones.hive.sentry-mobs"));
        if (types.isEmpty()) return;

        for (int i = have; i < wanted; i++) {
            Location spot = findSpot(w, heart[0] + 0.5, heart[2] + 0.5, 4, 10, null);
            if (spot == null) break;
            spawnMob(z, spot, types.get(random.nextInt(types.size())), 5, true);
        }
    }

    private int countMobs(String zoneName, boolean sentryOnly) {
        int n = 0;
        for (Map.Entry<UUID, String> en : mobs.entrySet()) {
            if (!en.getValue().equals(zoneName)) continue;
            if (sentries.contains(en.getKey()) == sentryOnly) n++;
        }
        return n;
    }

    /** A free standing spot on the surface around a point; if limit is set it must be inside that zone. */
    private Location findSpot(World w, double cx, double cz, double minD, double maxD, Zone limit) {
        for (int i = 0; i < 15; i++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double dist = minD + random.nextDouble() * (maxD - minD);
            int x = (int) Math.floor(cx + Math.cos(angle) * dist);
            int zz = (int) Math.floor(cz + Math.sin(angle) * dist);

            if (limit != null && !limit.contains(new Location(w, x + 0.5, 0, zz + 0.5))) continue;
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

    private LivingEntity spawnMob(Zone z, Location loc, EntityType type, int level, boolean sentry) {
        Entity ent = loc.getWorld().spawnEntity(loc, type);
        if (!(ent instanceof LivingEntity mob)) {
            ent.remove();
            return null;
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
        if (sentry) sentries.add(mob.getUniqueId());
        loc.getWorld().spawnParticle(Particle.SOUL, loc.clone().add(0, 1, 0), 15, 0.3, 0.5, 0.3, 0.03);
        return mob;
    }

    /** The closer to the heart, the tougher the mob. */
    private void applyLevel(LivingEntity mob, int level) {
        if (level <= 0) return;
        int i = Math.min(level, 5);
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
            sentries.remove(en.getKey());
        }
    }

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        LivingEntity mob = e.getEntity();
        if (!mob.getPersistentDataContainer().has(mobKey, PersistentDataType.BYTE)) return;
        mobs.remove(mob.getUniqueId());
        sentries.remove(mob.getUniqueId());

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

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        inside.remove(e.getPlayer().getUniqueId());
        alarmCooldown.remove(e.getPlayer().getUniqueId());
    }
    }
