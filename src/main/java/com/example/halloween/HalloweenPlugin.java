package com.example.halloween;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

public final class HalloweenPlugin extends JavaPlugin {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    /** Bump this together with config-version in config.yml whenever the config gets new sections. */
    private static final int CONFIG_VERSION = 6;

    private CandyManager candy;
    private PumpkinHunt pumpkinHunt;
    private TrickOrTreat trickOrTreat;
    private HauntedZones zones;
    private ShopNpcs shopNpcs;
    private RareGear rareGear;
    private NamespacedKey npcKey;

    @Override
    public void onEnable() {
        ensureCurrentConfig();

        npcKey = new NamespacedKey(this, "trick_or_treat_npc");
        candy = new CandyManager(this);
        pumpkinHunt = new PumpkinHunt(this);
        trickOrTreat = new TrickOrTreat(this);
        zones = new HauntedZones(this);
        ShopGUI shop = new ShopGUI(this);
        rareGear = new RareGear(this);
        shopNpcs = new ShopNpcs(this, shop, rareGear);

        var pm = getServer().getPluginManager();
        pm.registerEvents(pumpkinHunt, this);
        pm.registerEvents(trickOrTreat, this);
        pm.registerEvents(zones, this);
        pm.registerEvents(shop, this);
        pm.registerEvents(shopNpcs, this);
        pm.registerEvents(rareGear, this);

        HalloweenCommands commands = new HalloweenCommands(this, shop);
        for (String name : new String[] {"halloween", "candy", "halloweenshop"}) {
            PluginCommand cmd = getCommand(name);
            if (cmd != null) {
                cmd.setExecutor(commands);
                cmd.setTabCompleter(commands);
            }
        }

        pumpkinHunt.start();
        trickOrTreat.start();
        zones.start();
        // Autosave every 5 minutes
        getServer().getScheduler().runTaskTimer(this, candy::save, 6000L, 6000L);
        getLogger().info("Halloween enabled. Event active: " + isEventActive());
    }

    @Override
    public void onDisable() {
        if (pumpkinHunt != null) pumpkinHunt.clearAll();
        if (trickOrTreat != null) trickOrTreat.clearAuto();
        if (zones != null) zones.shutdown();
        if (candy != null) candy.save();
    }

    // ---------- Config upgrade ----------

    /**
     * saveDefaultConfig() never overwrites an existing file, so an old config.yml would miss newer
     * sections (like the merchant pool). If the file on disk is older than this version, back it up,
     * write the new default, and carry over the prefix and event dates.
     */
    private void ensureCurrentConfig() {
        File file = new File(getDataFolder(), "config.yml");
        if (!file.exists()) {
            saveDefaultConfig();
            return;
        }
        YamlConfiguration old = YamlConfiguration.loadConfiguration(file);
        if (old.getInt("config-version", 0) >= CONFIG_VERSION) return;

        File backup = new File(getDataFolder(), "config-backup-" + (System.currentTimeMillis() / 1000) + ".yml");
        if (!file.renameTo(backup)) {
            getLogger().warning("config.yml is from an older version and could not be replaced automatically. "
                    + "Delete it and restart the server.");
            return;
        }
        saveDefaultConfig();
        reloadConfig();
        for (String key : new String[] {"prefix", "event.always-active", "event.start-date", "event.end-date"}) {
            if (old.isSet(key)) getConfig().set(key, old.get(key));
        }
        saveConfig();
        getLogger().warning("Your config.yml was from an older version, so it was replaced with the new one "
                + "(prefix and event dates were kept). The old file is saved as " + backup.getName());
    }

    // ---------- Event window ----------

    public boolean isEventActive() {
        if (getConfig().getBoolean("event.always-active", false)) return true;
        try {
            LocalDate start = LocalDate.parse(startDate());
            LocalDate end = LocalDate.parse(endDate());
            LocalDate today = LocalDate.now();
            return !today.isBefore(start) && !today.isAfter(end);
        } catch (DateTimeParseException e) {
            getLogger().warning("Invalid event dates in config.yml (use yyyy-MM-dd).");
            return false;
        }
    }

    public String startDate() { return getConfig().getString("event.start-date", "2026-10-20"); }
    public String endDate()   { return getConfig().getString("event.end-date", "2026-11-01"); }

    // ---------- Accessors ----------

    public CandyManager candy() { return candy; }
    public PumpkinHunt hunt() { return pumpkinHunt; }
    public TrickOrTreat trickOrTreat() { return trickOrTreat; }
    public HauntedZones zones() { return zones; }
    public ShopNpcs shopNpcs() { return shopNpcs; }
    public NamespacedKey npcKey() { return npcKey; }

    // ---------- Message helpers ----------

    /** Parses MiniMessage; italics off so item names/lore look clean. */
    public static Component mm(String text) {
        return MM.deserialize(text).decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    public void send(CommandSender to, String text) {
        to.sendMessage(mm(getConfig().getString("prefix", "") + text));
    }
}
