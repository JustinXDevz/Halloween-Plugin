package com.example.halloween;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Stores candy balances in plugins/Halloween/candy.yml. */
public final class CandyManager {

    private final HalloweenPlugin plugin;
    private final File file;
    private final YamlConfiguration data;

    public CandyManager(HalloweenPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "candy.yml");
        this.data = YamlConfiguration.loadConfiguration(file);
    }

    public int balance(UUID id) {
        return data.getInt("players." + id + ".balance", 0);
    }

    /** Total candy ever earned (used for the leaderboard). */
    public int earned(UUID id) {
        return data.getInt("players." + id + ".earned", 0);
    }

    public void give(Player player, int amount) {
        UUID id = player.getUniqueId();
        String base = "players." + id;
        data.set(base + ".name", player.getName());
        data.set(base + ".balance", balance(id) + amount);
        data.set(base + ".earned", earned(id) + amount);
    }

    /** Returns false (and changes nothing) if the player can't afford it. */
    public boolean take(UUID id, int amount) {
        int bal = balance(id);
        if (bal < amount) return false;
        data.set("players." + id + ".balance", bal - amount);
        return true;
    }

    public List<Map.Entry<String, Integer>> top(int limit) {
        ConfigurationSection sec = data.getConfigurationSection("players");
        if (sec == null) return List.of();
        List<Map.Entry<String, Integer>> list = new ArrayList<>();
        for (String key : sec.getKeys(false)) {
            list.add(Map.entry(sec.getString(key + ".name", key), sec.getInt(key + ".earned", 0)));
        }
        list.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));
        return new ArrayList<>(list.subList(0, Math.min(limit, list.size())));
    }

    public void save() {
        try {
            data.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Could not save candy.yml: " + e.getMessage());
        }
    }
}
