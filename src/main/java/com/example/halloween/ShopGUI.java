package com.example.halloween;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Config-driven candy shop. */
public final class ShopGUI implements Listener {

    private final HalloweenPlugin plugin;

    private static final class ShopHolder implements InventoryHolder {
        private Inventory inventory;
        private final Map<Integer, String> slots = new HashMap<>();

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    public ShopGUI(HalloweenPlugin plugin) {
        this.plugin = plugin;
    }

    public void open(Player player) {
        if (!plugin.isEventActive()) {
            plugin.send(player, "The shop is closed until Halloween!");
            return;
        }
        ConfigurationSection items = plugin.getConfig().getConfigurationSection("shop.items");
        if (items == null || items.getKeys(false).isEmpty()) {
            plugin.send(player, "The shop is empty.");
            return;
        }

        ShopHolder holder = new ShopHolder();
        int count = items.getKeys(false).size();
        int size = Math.min(54, Math.max(9, ((count + 8) / 9) * 9));
        Inventory inv = Bukkit.createInventory(holder, size, HalloweenPlugin.mm("<dark_purple>Halloween Candy Shop"));
        holder.inventory = inv;

        int slot = 0;
        for (String key : items.getKeys(false)) {
            if (slot >= size) break;
            ConfigurationSection s = items.getConfigurationSection(key);
            if (s == null) continue;

            Material mat = Material.matchMaterial(s.getString("material", "PAPER"));
            if (mat == null) mat = Material.PAPER;
            int cost = s.getInt("cost", 1);

            ItemStack stack = new ItemStack(mat);
            ItemMeta meta = stack.getItemMeta();
            meta.displayName(HalloweenPlugin.mm(s.getString("name", key)));
            List<Component> lore = new ArrayList<>();
            for (String line : s.getStringList("lore")) lore.add(HalloweenPlugin.mm(line));
            lore.add(Component.empty());
            lore.add(HalloweenPlugin.mm("<yellow>Cost: <gold>" + cost + " candy"));
            lore.add(HalloweenPlugin.mm("<dark_gray>Click to buy"));
            meta.lore(lore);
            stack.setItemMeta(meta);

            inv.setItem(slot, stack);
            holder.slots.put(slot, key);
            slot++;
        }

        player.openInventory(inv);
        plugin.send(player, "You have <gold>" + plugin.candy().balance(player.getUniqueId()) + " candy</gold>.");
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof ShopHolder holder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() != e.getInventory()) return;

        String key = holder.slots.get(e.getSlot());
        if (key == null) return;

        ConfigurationSection s = plugin.getConfig().getConfigurationSection("shop.items." + key);
        if (s == null) return;

        int cost = s.getInt("cost", 1);
        if (!plugin.candy().take(p.getUniqueId(), cost)) {
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            plugin.send(p, "<red>Not enough candy!</red> You need <gold>" + cost + "</gold>.");
            return;
        }

        for (String cmd : s.getStringList("commands")) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("%player%", p.getName()));
        }
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
        plugin.send(p, "Purchased! You have <gold>" + plugin.candy().balance(p.getUniqueId()) + " candy</gold> left.");
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof ShopHolder) e.setCancelled(true);
    }
}
