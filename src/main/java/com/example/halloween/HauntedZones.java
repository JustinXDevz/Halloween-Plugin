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
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;

/**
 * Haunted Zones: admin-defined circular areas with fog, ambient sounds,
 * extra mobs, a candy multiplier, and candy drops from haunted mobs.
 * A zone can also be infected (see InfectionManager): it then spreads until
 * its Cursed Creaking Heart is destroyed.
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

    private final HalloweenPlugin plugin;
    private final File file;
    private final Random random = new Random();
    private final NamespacedKey mobKey;
    private final InfectionManager infection;

    private final Map<String, Zone> zones = new LinkedHashMap<>();
    /** Player -> name of the zone they are currently in. */
    private final Map<UUID, String> inside = new HashMap<>();
    /** Haunted mob -> zone it belongs to. */
    private final Map<UUID, String> mobs = new HashMap<>();

    private BukkitTask tickTask;
    private BukkitTask mobTask;
    private int tickCount = 0;

    public HauntedZones(HalloweenPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "zones.yml");
        this.mobKey = new NamespacedKey(plugin, "haunted_mob");
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

    public boolean add(String name, Location center, int radius) {
        if (zones.containsKey(name)) return false;
        Zone zone = new Zone(name, center.getWorld().getName(), center.getBlockX(), center.getBlockZ(), radius);
        zones.put(name, zone);
        save();
        if (plugin.getConfig().getBoolean("infection.auto-start", true) && plugin.isEventActive()) {
            infection.infect(zone);
        }
        return true;
    }

    public boolean remove(String name) {
        if (zones.remove(name) == null) return false;
        for (Iterator<Map.Entry<UUID, String>> it = mobs.entrySet().iterator(); it.hasNext();) {
            Map.Entry<UUID, String> en = it.next();
            if (en.getValue().equals(name)) {
                Entity ent = Bukkit.getEntity(en.getKey());
                if (ent != null) ent.remove();
                it.remove();
            }
        }
        inside.values().removeIf(n -> n.equals(name));
        infection.cleanse(name, null);   // give the land back before the zone disappears
        save();
        return true;
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

    /** Starts the infection in a zone. False if the zone doesn't exist or is already infected/healing. */
    public boolean infect(String name) {
        Zone z = zones.get(name);
        return z != null && infection.infect(z);
    }

    /** Stops the infection and restores the land. False if the zone isn't infected. */
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
        infection.start();
    }

    public void stop() {
        if (tickTask != null) tickTask.cancel();
        if (mobTask != null) mobTask.cancel();
        tickTask = null;
        mobTask = null;
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

    // ---------- Extra mobs ----------

    private List<EntityType> mobTypes() {
        List<EntityType> types = new ArrayList<>();
        for (String name : plugin.getConfig().getStringList("haunted-zones.mobs")) {
            try {
                EntityType t = EntityType.valueOf(name.toUpperCase());
                if (t.isSpawnable() && t.isAlive()) types.add(t);
            } catch (IllegalArgumentException ignored) {
                plugin.getLogger().warning("Unknown mob in haunted-zones.mobs: " + name);
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

        int max = plugin.getConfig().getInt("haunted-zones.max-mobs-per-zone", 6);
        List<EntityType> types = mobTypes();
        if (types.isEmpty()) return;

        for (Zone z : zones.values()) {
            World w = Bukkit.getWorld(z.world());
            if (w == null) continue;

            List<Player> inZone = new ArrayList<>();
            for (Player p : w.getPlayers()) {
                if (p.getGameMode() != GameMode.SPECTATOR && z.contains(p.getLocation())) inZone.add(p);
            }
            if (inZone.isEmpty()) continue;

            long count = mobs.values().stream().filter(n -> n.equals(z.name())).count();
            if (count >= max) continue;

            Player target = inZone.get(random.nextInt(inZone.size()));
            Location spot = findMobSpot(z, target);
            if (spot == null) continue;

            spawnMob(z, spot, types.get(random.nextInt(types.size())));
        }
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

    private void spawnMob(Zone z, Location loc, EntityType type) {
        Entity ent = loc.getWorld().spawnEntity(loc, type);
        if (!(ent instanceof LivingEntity mob)) {
            ent.remove();
            return;
        }
        mob.getPersistentDataContainer().set(mobKey, PersistentDataType.BYTE, (byte) 1);
        mob.setPersistent(false);
        mob.setRemoveWhenFarAway(true);

        // A pumpkin on the head keeps zombies/skeletons from burning in daylight.
        EntityEquipment eq = mob.getEquipment();
        if (eq != null && (mob instanceof Zombie || mob instanceof AbstractSkeleton)) {
            eq.setHelmet(new ItemStack(Material.CARVED_PUMPKIN));
            eq.setDropChance(EquipmentSlot.HEAD, 0f);
        }

        mobs.put(mob.getUniqueId(), z.name());
        loc.getWorld().spawnParticle(Particle.SOUL, loc.clone().add(0, 1, 0), 15, 0.3, 0.5, 0.3, 0.03);
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

        int min = c.getInt("haunted-zones.kill-candy-min", 1);
        int max = Math.max(min, c.getInt("haunted-zones.kill-candy-max", 3));
        int amount = min + random.nextInt(max - min + 1);
        plugin.candy().give(killer, amount);
        killer.sendActionBar(HalloweenPlugin.mm("<gold>+" + amount + " candy <gray>(haunted kill)"));
    }
}
