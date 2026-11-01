package com.example.halloween;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Creeper;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Hanging;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Tameable;
import org.bukkit.entity.Villager;
import org.bukkit.entity.WanderingTrader;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.GameMode;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * The Halloween finale: a boss marches on a city and the players have to defend it.
 *
 * - The boss walks toward the city centre and calls waves of weak monsters and creepers.
 *   Creepers run into the city and force themselves to explode, blowing holes in it.
 * - The boss has its own health pool and transforms (3 forms) as it gets hurt: Pumpkin King ->
 *   Headless Horror -> Hollow Lord. Later forms get ranged bolts and ground slams.
 * - The city has integrity. Creeper blasts, monsters that reach the centre and the boss itself wear
 *   it down. If it hits 0 the city falls. If the boss dies first, the defenders win and get candy.
 * - Everything the creepers blow up is saved and rebuilt after the event; containers, signs, item
 *   frames, villagers and pets are never harmed, and nothing drops (no lost or duplicated items).
 */
public final class BossEvent implements Listener {

    public record City(String name, String world, double x, double y, double z, int radius) {}

    private final HalloweenPlugin plugin;
    private final File citiesFile;
    private final Random random = new Random();
    private final NamespacedKey minionKey;
    private final NamespacedKey bossKey;
    private final Map<String, City> cities = new LinkedHashMap<>();
    private String lastAutoDate = "";
    private boolean shuttingDown;

    // ----- the running event -----
    private boolean running;
    private City city;
    private int phase;                 // 0 = preparing, 1..3 = boss forms
    private int prepLeft;
    private int prepTotal;
    private long seconds;              // seconds since the event began
    private double bossHp;
    private double bossMaxHp;
    private double cityHp;
    private double cityMaxHp;
    private LivingEntity boss;
    private Location lastBossLoc;
    private boolean transforming;
    private boolean finishing;
    private long invulnUntil;
    private long lastWave;
    private long lastBolt;
    private long lastSlam;
    private final Set<UUID> minions = new HashSet<>();
    private final Map<UUID, Long> creeperBorn = new HashMap<>();
    private final Map<UUID, Location> creeperTarget = new HashMap<>();
    private final Map<UUID, Double> damageDealt = new HashMap<>();
    private final Set<UUID> viewers = new HashSet<>();
    private BossBar bossBar;
    private BossBar cityBar;

    private BukkitTask tickTask;
    private BukkitTask transformTask;
    private BukkitTask autoTask;
    private BukkitTask restoreTask;

    // ----- damage done to the city (rebuilt afterwards) -----
    private String destroyedWorld;
    private final Map<Long, String> destroyed = new LinkedHashMap<>();
    private final ArrayDeque<Long> restoreQueue = new ArrayDeque<>();

    public BossEvent(HalloweenPlugin plugin) {
        this.plugin = plugin;
        this.citiesFile = new File(plugin.getDataFolder(), "cities.yml");
        this.minionKey = new NamespacedKey(plugin, "boss_minion");
        this.bossKey = new NamespacedKey(plugin, "boss_body");
        loadCities();
    }

    // =====================================================================
    // Cities
    // =====================================================================

    private void loadCities() {
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(citiesFile);
        lastAutoDate = yml.getString("last-auto-date", "");
        ConfigurationSection sec = yml.getConfigurationSection("cities");
        if (sec == null) return;
        for (String name : sec.getKeys(false)) {
            ConfigurationSection s = sec.getConfigurationSection(name);
            if (s == null) continue;
            cities.put(name, new City(name, s.getString("world", "world"), s.getDouble("x"), s.getDouble("y"),
                    s.getDouble("z"), s.getInt("radius", 60)));
        }
    }

    private void saveCities() {
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("last-auto-date", lastAutoDate);
        for (City c : cities.values()) {
            String base = "cities." + c.name();
            yml.set(base + ".world", c.world());
            yml.set(base + ".x", c.x());
            yml.set(base + ".y", c.y());
            yml.set(base + ".z", c.z());
            yml.set(base + ".radius", c.radius());
        }
        try {
            yml.save(citiesFile);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save cities.yml: " + e.getMessage());
        }
    }

    /** Marks the spot you stand on as the heart of a city the boss will attack. */
    public void setCity(String name, Location loc, int radius) {
        String key = name.toLowerCase(Locale.ROOT);
        cities.put(key, new City(key, loc.getWorld().getName(), loc.getX(), loc.getY(), loc.getZ(), radius));
        saveCities();
    }

    public boolean removeCity(String name) {
        boolean removed = cities.remove(name.toLowerCase(Locale.ROOT)) != null;
        if (removed) saveCities();
        return removed;
    }

    public List<String> cityNames() {
        return new ArrayList<>(cities.keySet());
    }

    public List<String> describeCities() {
        List<String> out = new ArrayList<>();
        for (City c : cities.values()) {
            out.add(c.name() + " - " + c.world() + " at " + (int) c.x() + ", " + (int) c.y() + ", " + (int) c.z()
                    + " (radius " + c.radius() + ")");
        }
        return out;
    }

    // =====================================================================
    // Lifecycle
    // =====================================================================

    /** Starts watching the clock for the automatic finale. */
    public void enable() {
        if (autoTask != null) autoTask.cancel();
        autoTask = Bukkit.getScheduler().runTaskTimer(plugin, this::checkAutoStart, 600L, 600L);
    }

    /** Plugin shutting down: end the event and rebuild the city right now. */
    public void shutdown() {
        shuttingDown = true;   // no new tasks may be scheduled while the plugin is disabling
        if (autoTask != null) autoTask.cancel();
        autoTask = null;
        if (running) endEvent(false, false);
        if (restoreTask != null) restoreTask.cancel();
        restoreTask = null;
        restoreEverythingNow();
    }

    private void checkAutoStart() {
        var c = plugin.getConfig();
        if (running || !restoreQueue.isEmpty() || !c.getBoolean("boss.auto-start.enabled", true)) return;
        if (!plugin.isEventActive() || Bukkit.getOnlinePlayers().isEmpty()) return;

        String date = c.getString("boss.auto-start.date", "2026-10-31");
        if (!LocalDate.now().toString().equals(date) || lastAutoDate.equals(date)) return;
        LocalTime at;
        try {
            at = LocalTime.parse(c.getString("boss.auto-start.time", "20:00"));
        } catch (DateTimeParseException e) {
            return;
        }
        if (LocalTime.now().isBefore(at)) return;

        if (cities.isEmpty()) {
            plugin.getLogger().warning("Boss finale is due but no city is set. Use /halloween city set <name>.");
            lastAutoDate = date;
            saveCities();
            return;
        }
        lastAutoDate = date;
        saveCities();
        String err = start(c.getString("boss.auto-start.city", ""));
        if (err != null) plugin.getLogger().warning("Could not start the boss finale: " + err);
    }

    /** Starts the event. Returns an error message, or null if it started. */
    public String start(String cityName) {
        if (running) return "A boss event is already running.";
        if (!restoreQueue.isEmpty()) return "The city is still being rebuilt from the last event.";
        if (!plugin.isEventActive()) return "The Halloween event isn't active (set event.always-active: true to test).";

        City c;
        if (cityName == null || cityName.isBlank()) {
            if (cities.isEmpty()) return "No city yet. Create one with /halloween city set <name> [radius].";
            List<City> all = new ArrayList<>(cities.values());
            c = all.get(random.nextInt(all.size()));
        } else {
            c = cities.get(cityName.toLowerCase(Locale.ROOT));
            if (c == null) return "No city called '" + cityName + "'.";
        }
        World w = Bukkit.getWorld(c.world());
        if (w == null) return "That city's world isn't loaded.";

        var cfg = plugin.getConfig();
        running = true;
        finishing = false;
        transforming = false;
        city = c;
        phase = 0;
        seconds = 0;
        prepTotal = Math.max(5, cfg.getInt("boss.prep-seconds", 120));
        prepLeft = prepTotal;
        cityMaxHp = Math.max(10, cfg.getDouble("boss.city.health", 200));
        cityHp = cityMaxHp;
        bossHp = 0;
        bossMaxHp = 1;
        boss = null;
        lastBossLoc = null;
        minions.clear();
        creeperBorn.clear();
        creeperTarget.clear();
        damageDealt.clear();
        viewers.clear();

        bossBar = BossBar.bossBar(HalloweenPlugin.mm("<gold>The boss is coming..."), 1f,
                BossBar.Color.PURPLE, BossBar.Overlay.NOTCHED_10);
        cityBar = BossBar.bossBar(HalloweenPlugin.mm("<green>City integrity"), 1f,
                BossBar.Color.GREEN, BossBar.Overlay.PROGRESS);

        broadcast("<dark_red><bold>Something terrible is coming for <gold>" + c.name() + "</gold>!</bold> "
                + "<gray>Gather at <white>" + (int) c.x() + ", " + (int) c.z() + "</white> and defend the city. "
                + "It arrives in <white>" + prepTotal + "</white> seconds.");
        tickTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
        return null;
    }

    /** Admin stop. The city is rebuilt. */
    public boolean stop() {
        if (!running) return false;
        broadcast("<gray>The boss event was called off.");
        endEvent(false, false);
        return true;
    }

    public String status() {
        if (!running) {
            return restoreQueue.isEmpty() ? "No boss event is running."
                    : "Rebuilding the city (" + restoreQueue.size() + " blocks left).";
        }
        if (phase == 0) return "Preparing: the boss arrives at " + city.name() + " in " + prepLeft + "s.";
        return "Form " + phase + " | boss " + (int) bossHp + "/" + (int) bossMaxHp + " | city "
                + (int) cityHp + "/" + (int) cityMaxHp + " | minions " + minions.size();
    }

    // =====================================================================
    // The main loop (once per second)
    // =====================================================================

    private World world() {
        return city == null ? null : Bukkit.getWorld(city.world());
    }

    private Location center(World w) {
        return surface(w, city.x(), city.z());
    }

    private void tick() {
        if (!running) return;
        World w = world();
        if (w == null) {
            endEvent(false, false);
            return;
        }
        seconds++;
        updateBars(w);

        if (phase == 0) {
            prepLeft--;
            if (prepLeft == 60 || prepLeft == 30 || prepLeft == 10 || (prepLeft <= 5 && prepLeft > 0)) {
                broadcast("<gold>" + city.name() + "</gold> <gray>- the boss arrives in <white>" + prepLeft + "</white>...");
            }
            if (prepLeft <= 0) spawnBoss(w);
            return;
        }

        // The boss may have been lost (unloaded, killed some odd way): bring it back
        if (!transforming && (boss == null || !boss.isValid())) {
            if (lastBossLoc != null) boss = spawnForm(lastBossLoc, phase);
        }
        if (boss != null && boss.isValid()) lastBossLoc = boss.getLocation();

        if (!transforming) bossAbilities(w);
        minionAI(w);
        cityPressure(w);

        if (cityHp <= 0 && !finishing) defeat();
    }

    // =====================================================================
    // Bars
    // =====================================================================

    private static float clamp(double v) {
        return (float) Math.max(0, Math.min(1, v));
    }

    private String formName(int form) {
        List<String> names = plugin.getConfig().getStringList("boss.names");
        if (names.isEmpty()) names = List.of("<gold>The Pumpkin King", "<red>The Headless Horror", "<dark_purple>The Hollow Lord");
        return names.get(Math.max(0, Math.min(form - 1, names.size() - 1)));
    }

    private void updateBars(World w) {
        if (phase == 0) {
            bossBar.name(HalloweenPlugin.mm("<gold>The boss arrives in <white>" + prepLeft + "s"));
            bossBar.progress(clamp(prepLeft / (double) prepTotal));
        } else {
            bossBar.name(HalloweenPlugin.mm(formName(phase) + " <gray>- <white>" + (int) Math.max(0, bossHp)
                    + " <gray>/ <white>" + (int) bossMaxHp));
            bossBar.progress(clamp(bossHp / bossMaxHp));
            bossBar.color(phase == 1 ? BossBar.Color.PURPLE : phase == 2 ? BossBar.Color.RED : BossBar.Color.WHITE);
        }

        double frac = cityHp / cityMaxHp;
        cityBar.name(HalloweenPlugin.mm("<green>" + city.name() + " integrity <white>" + (int) Math.max(0, frac * 100) + "%"));
        cityBar.progress(clamp(frac));
        cityBar.color(frac > 0.5 ? BossBar.Color.GREEN : frac > 0.25 ? BossBar.Color.YELLOW : BossBar.Color.RED);

        // Everyone near the city sees the bars
        double range = city.radius() + 250;
        Set<UUID> now = new HashSet<>();
        for (Player p : w.getPlayers()) {
            double dx = p.getLocation().getX() - city.x();
            double dz = p.getLocation().getZ() - city.z();
            if (dx * dx + dz * dz > range * range) continue;
            now.add(p.getUniqueId());
            if (viewers.add(p.getUniqueId())) {
                p.showBossBar(bossBar);
                p.showBossBar(cityBar);
            }
        }
        for (UUID id : new ArrayList<>(viewers)) {
            if (now.contains(id)) continue;
            viewers.remove(id);
            Player p = Bukkit.getPlayer(id);
            if (p != null) {
                p.hideBossBar(bossBar);
                p.hideBossBar(cityBar);
            }
        }
    }

    private void hideBars() {
        for (UUID id : viewers) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            if (bossBar != null) p.hideBossBar(bossBar);
            if (cityBar != null) p.hideBossBar(cityBar);
        }
        viewers.clear();
    }

    // =====================================================================
    // Spawning the boss and its minions
    // =====================================================================

    private Location surface(World w, double x, double z) {
        int bx = (int) Math.floor(x);
        int bz = (int) Math.floor(z);
        int y = w.getHighestBlockYAt(bx, bz, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1;
        return new Location(w, bx + 0.5, y, bz + 0.5);
    }

    private Location ringSpot(World w, Location around, double min, double max) {
        double angle = random.nextDouble() * Math.PI * 2;
        double dist = min + random.nextDouble() * Math.max(0.1, max - min);
        return surface(w, around.getX() + Math.cos(angle) * dist, around.getZ() + Math.sin(angle) * dist);
    }

    private void spawnBoss(World w) {
        var cfg = plugin.getConfig();
        phase = 1;
        double dist = Math.max(40, cfg.getDouble("boss.spawn-distance", 100));
        double angle = random.nextDouble() * Math.PI * 2;
        Location at = surface(w, city.x() + Math.cos(angle) * dist, city.z() + Math.sin(angle) * dist);

        int nearby = 0;
        for (Player p : w.getPlayers()) {
            double dx = p.getLocation().getX() - city.x();
            double dz = p.getLocation().getZ() - city.z();
            double r = city.radius() + 60;
            if (dx * dx + dz * dz <= r * r) nearby++;
        }
        bossMaxHp = Math.max(100, cfg.getDouble("boss.health", 1500))
                + Math.max(0, cfg.getDouble("boss.health-per-player", 300)) * Math.max(0, nearby - 1);
        bossHp = bossMaxHp;

        boss = spawnForm(at, 1);
        lastBossLoc = at;
        lastWave = seconds - 12;
        lastBolt = seconds;
        lastSlam = seconds;

        w.strikeLightningEffect(at);
        w.playSound(at, Sound.ENTITY_WITHER_SPAWN, 4f, 0.6f);
        broadcast("<dark_red><bold>" + formName(1) + " has arrived!</bold> <gray>It marches on <gold>" + city.name()
                + "</gold> from the " + compass(at.getX() - city.x(), at.getZ() - city.z())
                + ". Don't let the creepers reach the city!");
        for (Player p : w.getPlayers()) {
            if (!viewers.contains(p.getUniqueId())) continue;
            p.showTitle(Title.title(HalloweenPlugin.mm(formName(1)), HalloweenPlugin.mm("<gray>Protect " + city.name() + "!")));
        }
    }

    private static String compass(double dx, double dz) {
        double bearing = Math.toDegrees(Math.atan2(dx, -dz));
        if (bearing < 0) bearing += 360;
        String[] names = {"north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west"};
        return names[(int) Math.round(bearing / 45.0) % 8];
    }

    /** Spawns the body for a boss form. The body only moves and fights; its real health is tracked here. */
    private LivingEntity spawnForm(Location loc, int form) {
        EntityType type = form == 1 ? EntityType.VINDICATOR : form == 2 ? EntityType.WITHER_SKELETON : EntityType.RAVAGER;
        Entity ent = loc.getWorld().spawnEntity(loc, type);
        LivingEntity e = (LivingEntity) ent;
        e.customName(HalloweenPlugin.mm(formName(form)));
        e.setCustomNameVisible(true);
        e.setRemoveWhenFarAway(false);
        e.setPersistent(false);
        e.setCanPickupItems(false);
        e.setGlowing(true);
        e.getPersistentDataContainer().set(bossKey, PersistentDataType.BYTE, (byte) 1);

        EntityEquipment eq = e.getEquipment();
        if (eq != null && form != 3) {
            eq.setHelmet(new ItemStack(Material.CARVED_PUMPKIN));
            eq.setDropChance(EquipmentSlot.HEAD, 0f);
            if (form == 2) {
                eq.setItemInMainHand(new ItemStack(Material.NETHERITE_SWORD));
                eq.setDropChance(EquipmentSlot.HAND, 0f);
            }
        }
        infinite(e, PotionEffectType.STRENGTH, form == 1 ? 1 : form == 2 ? 2 : 3);
        infinite(e, PotionEffectType.SPEED, form == 1 ? 0 : 1);
        if (form == 3) infinite(e, PotionEffectType.RESISTANCE, 1);
        return e;
    }

    private static void infinite(LivingEntity e, PotionEffectType type, int amp) {
        e.addPotionEffect(new PotionEffect(type, PotionEffect.INFINITE_DURATION, amp, false, false));
    }

    private static Attribute attribute(String name) {
        Attribute a = Registry.ATTRIBUTE.get(NamespacedKey.minecraft(name));
        if (a == null) a = Registry.ATTRIBUTE.get(NamespacedKey.minecraft("generic." + name));
        return a;
    }

    private static void setMaxHealth(LivingEntity e, double hp) {
        Attribute attr = attribute("max_health");
        if (attr == null) return;
        AttributeInstance inst = e.getAttribute(attr);
        if (inst == null) return;
        inst.setBaseValue(hp);
        e.setHealth(Math.min(hp, inst.getValue()));
    }

    private void mark(LivingEntity e) {
        e.getPersistentDataContainer().set(minionKey, PersistentDataType.BYTE, (byte) 1);
        e.setPersistent(false);
        e.setRemoveWhenFarAway(false);
        minions.add(e.getUniqueId());
    }

    private void spawnWeakling(Location loc) {
        List<String> names = plugin.getConfig().getStringList("boss.weakling-mobs");
        if (names.isEmpty()) names = List.of("ZOMBIE", "SKELETON", "HUSK", "SILVERFISH");
        EntityType type;
        try {
            type = EntityType.valueOf(names.get(random.nextInt(names.size())).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            type = EntityType.ZOMBIE;
        }
        Entity ent = loc.getWorld().spawnEntity(loc, type);
        if (!(ent instanceof LivingEntity m)) {
            ent.remove();
            return;
        }
        mark(m);
        setMaxHealth(m, Math.max(2, plugin.getConfig().getDouble("boss.weakling-health", 6)));
        if (m instanceof Zombie z && random.nextInt(3) == 0) z.setBaby(true);   // small and fast
        EntityEquipment eq = m.getEquipment();
        if (eq != null && type != EntityType.SILVERFISH) {
            eq.setHelmet(new ItemStack(Material.CARVED_PUMPKIN));
            eq.setDropChance(EquipmentSlot.HEAD, 0f);
        }
        loc.getWorld().spawnParticle(Particle.SOUL, loc.clone().add(0, 1, 0), 8, 0.3, 0.4, 0.3, 0.02);
    }

    private void spawnCreeper(Location loc, boolean powered, Location target) {
        Entity ent = loc.getWorld().spawnEntity(loc, EntityType.CREEPER);
        if (!(ent instanceof Creeper c)) {
            ent.remove();
            return;
        }
        mark(c);
        c.setPowered(powered);
        c.setExplosionRadius(Math.max(1, plugin.getConfig().getInt("boss.creeper-power", 3)));
        c.setMaxFuseTicks(20);
        infinite(c, PotionEffectType.SPEED, 1);
        creeperBorn.put(c.getUniqueId(), System.currentTimeMillis());
        creeperTarget.put(c.getUniqueId(), target);
        loc.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, loc.clone().add(0, 1, 0), 10, 0.3, 0.4, 0.3, 0.02);
    }

    private static int pick(List<Integer> list, int idx, List<Integer> def) {
        List<Integer> l = list.isEmpty() ? def : list;
        return l.get(Math.max(0, Math.min(idx, l.size() - 1)));
    }

    /** A wave of weaklings and creepers around the boss. */
    private void summonWave(World w, Location around, boolean big) {
        var c = plugin.getConfig();
        int room = Math.max(4, c.getInt("boss.max-minions", 24)) - minions.size();
        if (room <= 0) return;

        int idx = Math.max(0, Math.min(2, phase - 1));
        int weaklings = pick(c.getIntegerList("boss.wave-weaklings"), idx, List.of(4, 6, 8));
        int creepers = pick(c.getIntegerList("boss.wave-creepers"), idx, List.of(1, 2, 3));
        if (big) {
            weaklings += 2;
            creepers += 1;
        }
        boolean mayPower = phase >= c.getInt("boss.powered-creepers-phase", 3);
        double reach = Math.max(8, city.radius() * 0.6);

        w.playSound(around, Sound.ENTITY_EVOKER_PREPARE_SUMMON, 2f, 0.7f);
        for (int i = 0; i < creepers && room > 0; i++, room--) {
            Location target = ringSpot(w, center(w), 3, reach);   // each creeper has its own target to blow up
            spawnCreeper(ringSpot(w, around, 3, 7), mayPower && random.nextBoolean(), target);
        }
        for (int i = 0; i < weaklings && room > 0; i++, room--) {
            spawnWeakling(ringSpot(w, around, 3, 8));
        }
    }

    // =====================================================================
    // Boss abilities and minion behaviour
    // =====================================================================

    private Player nearestPlayer(World w, Location from, double range) {
        Player best = null;
        double bestD = range * range;
        for (Player p : w.getPlayers()) {
            if (p.getGameMode() != GameMode.SURVIVAL && p.getGameMode() != GameMode.ADVENTURE) continue;
            double d = p.getLocation().distanceSquared(from);
            if (d < bestD) {
                bestD = d;
                best = p;
            }
        }
        return best;
    }

    private void bossAbilities(World w) {
        if (boss == null || !boss.isValid()) return;
        var c = plugin.getConfig();
        Location here = boss.getLocation();

        // March on the city unless a player is close enough to fight
        if (boss instanceof Mob mob) {
            LivingEntity t = mob.getTarget();
            if (t == null || t.getLocation().distanceSquared(here) > 30 * 30) stepToward(mob, center(w), 1.0);
        }

        // Summon a wave
        int waveEvery = pick(c.getIntegerList("boss.wave-seconds"), phase - 1, List.of(22, 18, 14));
        if (seconds - lastWave >= waveEvery) {
            lastWave = seconds;
            summonWave(w, here, false);
        }

        Player target = nearestPlayer(w, here, 40);

        // Ranged soul bolt (form 2 and 3)
        int boltEvery = pick(c.getIntegerList("boss.bolt-seconds"), phase - 1, List.of(0, 9, 6));
        if (boltEvery > 0 && target != null && seconds - lastBolt >= boltEvery) {
            lastBolt = seconds;
            bolt(target);
        }

        // Ground slam (form 3)
        int slamEvery = c.getInt("boss.slam-seconds", 14);
        if (phase >= 3 && slamEvery > 0 && seconds - lastSlam >= slamEvery) {
            double radius = Math.max(4, c.getDouble("boss.slam-radius", 9));
            if (nearestPlayer(w, here, radius) != null) {
                lastSlam = seconds;
                slam(w, here, radius);
            }
        }
    }

    /** A bolt of soul fire that hits instantly - blocked by walls, never damages blocks. */
    private void bolt(Player target) {
        Location from = boss.getEyeLocation();
        Location to = target.getEyeLocation();
        Vector dir = to.toVector().subtract(from.toVector());
        double len = dir.length();
        if (len < 1) return;
        dir.normalize();

        World w = from.getWorld();
        RayTraceResult hit = w.rayTraceBlocks(from, dir, len, FluidCollisionMode.NEVER, true);
        double reach = hit == null ? len : from.distance(hit.getHitPosition().toLocation(w));
        for (double d = 0; d < reach; d += 0.6) {
            w.spawnParticle(Particle.SOUL_FIRE_FLAME, from.clone().add(dir.clone().multiply(d)), 1, 0, 0, 0, 0);
        }
        w.playSound(from, Sound.ENTITY_WARDEN_SONIC_BOOM, 1.5f, 1.2f);
        if (hit != null) return;   // a wall took it

        target.damage(plugin.getConfig().getDouble("boss.bolt-damage", 8), boss);
        target.setVelocity(dir.clone().multiply(0.7).setY(0.3));
    }

    /** A shockwave: damages and throws everyone close to the boss. */
    private void slam(World w, Location at, double radius) {
        double dmg = plugin.getConfig().getDouble("boss.slam-damage", 12);
        w.playSound(at, Sound.ENTITY_RAVAGER_ROAR, 3f, 0.6f);
        w.playSound(at, Sound.ENTITY_IRON_GOLEM_DAMAGE, 3f, 0.5f);
        w.strikeLightningEffect(at);
        for (int i = 0; i < 48; i++) {
            double a = 2 * Math.PI * i / 48;
            for (double r = 1.5; r <= radius; r += 1.5) {
                w.spawnParticle(Particle.CLOUD, at.getX() + Math.cos(a) * r, at.getY() + 0.3, at.getZ() + Math.sin(a) * r,
                        1, 0.05, 0.05, 0.05, 0.0);
            }
        }
        for (Player p : w.getNearbyPlayers(at, radius)) {
            if (p.getGameMode() != GameMode.SURVIVAL && p.getGameMode() != GameMode.ADVENTURE) continue;
            p.damage(dmg, boss);
            Vector v = p.getLocation().toVector().subtract(at.toVector());
            if (v.lengthSquared() > 0) p.setVelocity(v.normalize().multiply(1.1).setY(0.8));
        }
    }

    /** Walks a mob toward a far-away target in hops it can actually path-find. */
    private void stepToward(Mob m, Location target, double speed) {
        Location here = m.getLocation();
        if (here.getWorld() != target.getWorld()) return;
        double dx = target.getX() - here.getX();
        double dz = target.getZ() - here.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 2) return;
        double step = Math.min(dist, 22);
        Location hop = surface(here.getWorld(), here.getX() + dx / dist * step, here.getZ() + dz / dist * step);
        m.getPathfinder().moveTo(hop, speed);
    }

    private void minionAI(World w) {
        Location core = center(w);
        long now = System.currentTimeMillis();
        long lifetime = Math.max(10, plugin.getConfig().getInt("boss.creeper-lifetime-seconds", 45)) * 1000L;

        for (UUID id : new ArrayList<>(minions)) {
            Entity ent = Bukkit.getEntity(id);
            if (!(ent instanceof Mob m) || !m.isValid()) {
                minions.remove(id);
                creeperBorn.remove(id);
                creeperTarget.remove(id);
                continue;
            }
            if (m instanceof Creeper c) {
                Location target = creeperTarget.getOrDefault(id, core);
                long age = now - creeperBorn.getOrDefault(id, now);
                // Forced self-destruct: it blows up when it reaches its target, or when its time runs out
                if (c.getLocation().distanceSquared(target) <= 9 || age > lifetime) {
                    c.explode();
                    continue;
                }
                LivingEntity t = c.getTarget();
                if (t == null || t.getLocation().distanceSquared(c.getLocation()) > 36) stepToward(c, target, 1.35);
            } else if (m.getTarget() == null) {
                stepToward(m, core, 1.1);
            }
        }
    }

    /** Monsters that reach the city centre, and the boss itself, wear the city down. */
    private void cityPressure(World w) {
        var c = plugin.getConfig();
        Location core = center(w);
        double coreR = Math.max(3, c.getDouble("boss.city.core-radius", 8));
        double perMinion = c.getDouble("boss.city.weakling-damage-per-second", 0.5);
        double dmg = 0;

        for (UUID id : minions) {
            Entity ent = Bukkit.getEntity(id);
            if (ent == null || ent instanceof Creeper) continue;
            Location l = ent.getLocation();
            double dx = l.getX() - core.getX();
            double dz = l.getZ() - core.getZ();
            if (dx * dx + dz * dz <= coreR * coreR) dmg += perMinion;
        }
        if (boss != null && boss.isValid()) {
            Location l = boss.getLocation();
            double dx = l.getX() - core.getX();
            double dz = l.getZ() - core.getZ();
            double r = coreR + 6;
            if (dx * dx + dz * dz <= r * r) dmg += c.getDouble("boss.city.boss-damage-per-second", 3);
        }
        if (dmg > 0) {
            cityHp -= dmg;
            if (seconds % 5 == 0) {
                for (Player p : w.getPlayers()) {
                    if (viewers.contains(p.getUniqueId())) {
                        p.sendActionBar(HalloweenPlugin.mm("<red>The city is under attack! Clear the centre!"));
                    }
                }
            }
        }
    }

    // =====================================================================
    // Damage to the boss, phases and transformation
    // =====================================================================

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBossDamage(EntityDamageEvent e) {
        if (!running || boss == null || !e.getEntity().getUniqueId().equals(boss.getUniqueId())) return;
        e.setCancelled(true);   // the body never really takes damage - the boss has its own health pool
        if (transforming || phase == 0 || finishing || System.currentTimeMillis() < invulnUntil) return;

        Player attacker = null;
        if (e instanceof EntityDamageByEntityEvent by) {
            if (by.getDamager() instanceof Player p) {
                attacker = p;
            } else if (by.getDamager() instanceof Projectile proj && proj.getShooter() instanceof Player p) {
                attacker = p;
            }
        }
        if (attacker == null) return;   // only players can hurt the boss

        double dmg = e.getFinalDamage();
        if (dmg <= 0) return;
        bossHp -= dmg;
        damageDealt.merge(attacker.getUniqueId(), dmg, Double::sum);
        boss.playHurtAnimation(0f);
        boss.getWorld().playSound(boss.getLocation(), Sound.ENTITY_WITHER_HURT, 1f, 0.8f);

        checkPhase();
    }

    private void checkPhase() {
        if (finishing || transforming) return;
        if (bossHp <= 0) {
            finishing = true;
            Bukkit.getScheduler().runTask(plugin, this::victory);
            return;
        }
        List<Double> at = plugin.getConfig().getDoubleList("boss.transform-at");
        if (at.size() < 2) at = List.of(0.66, 0.33);
        double frac = bossHp / bossMaxHp;
        if (phase == 1 && frac <= at.get(0)) beginTransform(2);
        else if (phase == 2 && frac <= at.get(1)) beginTransform(3);
    }

    /** The boss freezes, the air crackles, and it bursts into its next form. */
    private void beginTransform(int newPhase) {
        if (transforming || boss == null) return;
        transforming = true;
        LivingEntity old = boss;
        old.setAI(false);
        broadcast("<dark_purple><bold>" + formName(phase) + " is transforming!</bold> <gray>Get back!");
        int total = Math.max(1, plugin.getConfig().getInt("boss.transform-seconds", 4)) * 4;

        transformTask = new BukkitRunnable() {
            int t = 0;

            @Override
            public void run() {
                if (!running || !old.isValid()) {
                    cancel();
                    transforming = false;
                    return;
                }
                t++;
                Location l = old.getLocation();
                World w = l.getWorld();
                w.spawnParticle(Particle.SOUL_FIRE_FLAME, l.clone().add(0, 1, 0), 25, 0.8, 1.2, 0.8, 0.05);
                w.spawnParticle(Particle.SCULK_SOUL, l.clone().add(0, 1, 0), 12, 0.8, 1.2, 0.8, 0.05);
                if (t % 4 == 0) {
                    w.playSound(l, Sound.ENTITY_WITHER_AMBIENT, 2f, 0.5f + t * 0.03f);
                    w.strikeLightningEffect(l.clone().add(random.nextInt(9) - 4, 0, random.nextInt(9) - 4));
                }
                for (Player p : w.getNearbyPlayers(l, 6)) {
                    Vector v = p.getLocation().toVector().subtract(l.toVector());
                    if (v.lengthSquared() > 0) p.setVelocity(v.normalize().multiply(0.5).setY(0.25));
                }
                if (t >= total) {
                    cancel();
                    finishTransform(old, newPhase, l);
                }
            }
        }.runTaskTimer(plugin, 0L, 5L);
    }

    private void finishTransform(LivingEntity old, int newPhase, Location loc) {
        old.remove();
        phase = newPhase;
        boss = spawnForm(loc, newPhase);
        transforming = false;
        invulnUntil = System.currentTimeMillis() + 1500;
        lastBolt = seconds;
        lastSlam = seconds;
        lastWave = seconds;

        World w = loc.getWorld();
        w.strikeLightningEffect(loc);
        w.playSound(loc, Sound.ENTITY_ENDER_DRAGON_GROWL, 4f, 0.6f);
        w.spawnParticle(Particle.SOUL_FIRE_FLAME, loc.clone().add(0, 1, 0), 120, 1.5, 1.5, 1.5, 0.15);
        broadcast("<dark_red><bold>" + formName(newPhase) + " rises!</bold> <gray>It is stronger than before.");
        for (Player p : w.getPlayers()) {
            if (viewers.contains(p.getUniqueId())) {
                p.showTitle(Title.title(HalloweenPlugin.mm(formName(newPhase)), HalloweenPlugin.mm("<gray>Form " + newPhase)));
            }
        }
        summonWave(w, loc, true);
    }

    // =====================================================================
    // Creeper blasts: city damage, saved and rebuilt later
    // =====================================================================

    @EventHandler(ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent e) {
        Entity ent = e.getEntity();
        if (!ent.getPersistentDataContainer().has(minionKey, PersistentDataType.BYTE)) return;

        minions.remove(ent.getUniqueId());
        creeperBorn.remove(ent.getUniqueId());
        creeperTarget.remove(ent.getUniqueId());
        e.setYield(0f);   // nothing drops, so nothing can be lost or duplicated

        Location loc = e.getLocation();
        if (running && city != null && loc.getWorld().getName().equals(city.world())) {
            double dx = loc.getX() - city.x();
            double dz = loc.getZ() - city.z();
            double r = city.radius() * 1.15;
            if (dx * dx + dz * dz <= r * r) {
                cityHp -= plugin.getConfig().getDouble("boss.city.creeper-damage", 8);
            }
        }

        destroyedWorld = loc.getWorld().getName();
        Iterator<Block> it = e.blockList().iterator();
        while (it.hasNext()) {
            Block b = it.next();
            if (b.getState(false) instanceof TileState) {   // chests, signs, beds, furnaces... are never destroyed
                it.remove();
                continue;
            }
            destroyed.putIfAbsent(blockKey(b.getX(), b.getY(), b.getZ()), b.getBlockData().getAsString());
        }
    }

    /** People, pets, frames, paintings and dropped items survive the blasts. */
    @EventHandler(ignoreCancelled = true)
    public void onProtect(EntityDamageEvent e) {
        if (!running && destroyed.isEmpty()) return;
        EntityDamageEvent.DamageCause cause = e.getCause();
        if (cause != EntityDamageEvent.DamageCause.ENTITY_EXPLOSION && cause != EntityDamageEvent.DamageCause.BLOCK_EXPLOSION) return;

        Entity v = e.getEntity();
        boolean protectPeople = plugin.getConfig().getBoolean("boss.protect-citizens", true);
        if (v instanceof ArmorStand || v instanceof Hanging || v instanceof Item
                || (protectPeople && (v instanceof Villager || v instanceof WanderingTrader
                        || (v instanceof Tameable t && t.isTamed())))) {
            e.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onHangingBreak(HangingBreakEvent e) {
        if ((running || !destroyed.isEmpty()) && e.getCause() == HangingBreakEvent.RemoveCause.EXPLOSION) {
            e.setCancelled(true);
        }
    }

    @EventHandler
    public void onDeath(EntityDeathEvent e) {
        LivingEntity ent = e.getEntity();
        if (!ent.getPersistentDataContainer().has(minionKey, PersistentDataType.BYTE)) return;
        minions.remove(ent.getUniqueId());
        creeperBorn.remove(ent.getUniqueId());
        creeperTarget.remove(ent.getUniqueId());
        e.getDrops().clear();
        e.setDroppedExp(0);

        Player killer = ent.getKiller();
        int candy = plugin.getConfig().getInt("boss.minion-candy", 1);
        if (killer != null && running && candy > 0) {
            plugin.candy().give(killer, candy);
        }
    }

    // =====================================================================
    // Ending the event
    // =====================================================================

    private void victory() {
        if (!running) return;
        var c = plugin.getConfig();
        World w = world();
        Location loc = boss != null && boss.isValid() ? boss.getLocation() : lastBossLoc;
        if (w != null && loc != null) {
            w.strikeLightningEffect(loc);
            w.playSound(loc, Sound.ENTITY_WITHER_DEATH, 4f, 0.8f);
            w.spawnParticle(Particle.SOUL_FIRE_FLAME, loc.clone().add(0, 1, 0), 200, 2, 2, 2, 0.2);
        }

        List<Map.Entry<UUID, Double>> ranking = new ArrayList<>(damageDealt.entrySet());
        ranking.sort(Comparator.comparingDouble((Map.Entry<UUID, Double> en) -> en.getValue()).reversed());

        int participation = Math.max(0, c.getInt("boss.rewards.participation-candy", 250));
        List<Integer> top = c.getIntegerList("boss.rewards.top-damage-candy");
        if (top.isEmpty()) top = List.of(600, 350, 200);
        List<String> commands = c.getStringList("boss.rewards.commands");

        Set<UUID> rewarded = new HashSet<>(damageDealt.keySet());
        if (w != null) {
            for (Player p : w.getPlayers()) {
                double dx = p.getLocation().getX() - city.x();
                double dz = p.getLocation().getZ() - city.z();
                double r = city.radius() + 80;
                if (dx * dx + dz * dz <= r * r) rewarded.add(p.getUniqueId());
            }
        }
        for (UUID id : rewarded) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) continue;
            plugin.candy().give(p, participation);
            plugin.send(p, "<green>The city is saved!</green> You earned <gold>" + participation + " candy</gold>.");
            for (String cmd : commands) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("%player%", p.getName()));
            }
        }

        StringBuilder board = new StringBuilder();
        for (int i = 0; i < Math.min(3, ranking.size()); i++) {
            Player p = Bukkit.getPlayer(ranking.get(i).getKey());
            String name = p != null ? p.getName() : "someone";
            int bonus = top.get(Math.min(i, top.size() - 1));
            if (p != null) {
                plugin.candy().give(p, bonus);
                plugin.send(p, "<gold>#" + (i + 1) + " damage dealer!</gold> Bonus: <gold>" + bonus + " candy</gold>.");
            }
            board.append("<yellow>#").append(i + 1).append(" <white>").append(name).append(" <gray>(")
                    .append((int) (double) ranking.get(i).getValue()).append(" dmg)  ");
        }
        broadcast("<green><bold>" + formName(3) + " is defeated!</bold> <gray>" + city.name() + " is saved! " + board);
        endEvent(true, true);
    }

    private void defeat() {
        finishing = true;
        World w = world();
        int consolation = Math.max(0, plugin.getConfig().getInt("boss.defeat-candy", 40));
        if (w != null) {
            for (Player p : w.getPlayers()) {
                if (!viewers.contains(p.getUniqueId())) continue;
                p.showTitle(Title.title(HalloweenPlugin.mm("<dark_red>" + city.name() + " has fallen"),
                        HalloweenPlugin.mm("<gray>The boss laughs...")));
                if (consolation > 0) plugin.candy().give(p, consolation);
            }
        }
        broadcast("<dark_red><bold>" + city.name() + " has fallen!</bold> <gray>The boss vanishes into the fog... "
                + "the city will be rebuilt. Be ready next time.");
        endEvent(false, true);
    }

    /** Cleans everything up and starts rebuilding what the creepers destroyed. */
    private void endEvent(boolean won, boolean announceRebuild) {
        running = false;
        finishing = true;
        if (tickTask != null) tickTask.cancel();
        if (transformTask != null) transformTask.cancel();
        tickTask = null;
        transformTask = null;
        transforming = false;

        hideBars();
        if (boss != null) boss.remove();
        for (UUID id : minions) {
            Entity ent = Bukkit.getEntity(id);
            if (ent != null) ent.remove();
        }
        boss = null;
        minions.clear();
        creeperBorn.clear();
        creeperTarget.clear();

        beginRestore(announceRebuild);
    }

    // =====================================================================
    // Rebuilding the city
    // =====================================================================

    private static long blockKey(int x, int y, int z) {
        return ((long) x << 38) | (((long) z & 0x3FFFFFFL) << 12) | ((y + 2048) & 0xFFFL);
    }

    private void beginRestore(boolean announce) {
        if (destroyed.isEmpty() || shuttingDown) return;
        restoreQueue.clear();
        restoreQueue.addAll(destroyed.keySet());
        if (announce) broadcast("<aqua>The city is being rebuilt...");
        if (restoreTask != null) restoreTask.cancel();
        restoreTask = Bukkit.getScheduler().runTaskTimer(plugin, this::restoreStep, 60L, 1L);
    }

    private void restoreStep() {
        World w = destroyedWorld == null ? null : Bukkit.getWorld(destroyedWorld);
        int budget = Math.max(10, plugin.getConfig().getInt("boss.restore-blocks-per-tick", 200));
        boolean touched = false;
        for (int i = 0; i < budget && !restoreQueue.isEmpty(); i++) {
            long key = restoreQueue.poll();
            String data = destroyed.remove(key);
            if (data == null || w == null) continue;
            int x = (int) (key >> 38);
            int z = (int) ((key << 26) >> 38);
            int y = (int) (key & 0xFFFL) - 2048;
            try {
                w.getBlockAt(x, y, z).setBlockData(Bukkit.createBlockData(data), false);
                touched = true;
            } catch (IllegalArgumentException ignored) {
                // block type no longer exists - skip
            }
        }
        if (touched && w != null) liftPlayers(w);
        if (restoreQueue.isEmpty()) {
            destroyed.clear();
            if (restoreTask != null) restoreTask.cancel();
            restoreTask = null;
            broadcast("<aqua>The city has been rebuilt.");
        }
    }

    private void liftPlayers(World w) {
        for (Player p : w.getPlayers()) {
            if (!p.getEyeLocation().getBlock().getType().isOccluding()) continue;
            Location l = p.getLocation();
            int y = w.getHighestBlockYAt(l.getBlockX(), l.getBlockZ()) + 1;
            p.teleport(new Location(w, l.getBlockX() + 0.5, y, l.getBlockZ() + 0.5, l.getYaw(), l.getPitch()));
        }
    }

    /** Used on shutdown: rebuild everything at once. */
    private void restoreEverythingNow() {
        World w = destroyedWorld == null ? null : Bukkit.getWorld(destroyedWorld);
        if (w != null) {
            for (Map.Entry<Long, String> en : destroyed.entrySet()) {
                long key = en.getKey();
                int x = (int) (key >> 38);
                int z = (int) ((key << 26) >> 38);
                int y = (int) (key & 0xFFFL) - 2048;
                try {
                    w.getBlockAt(x, y, z).setBlockData(Bukkit.createBlockData(en.getValue()), false);
                } catch (IllegalArgumentException ignored) {
                    // skip
                }
            }
        }
        destroyed.clear();
        restoreQueue.clear();
    }

    private void broadcast(String text) {
        Bukkit.broadcast(HalloweenPlugin.mm(plugin.getConfig().getString("prefix", "") + text));
    }
        }
