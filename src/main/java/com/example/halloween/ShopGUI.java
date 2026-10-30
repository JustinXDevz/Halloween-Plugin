package com.example.halloween;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Config-driven candy shop. Always-available items come from shop.items (5 rows of 9);
 * shop.rotating.items is a pool of which a few are picked each day (same for everyone).
 *
 * An item with "amount" is handed over directly and needs free inventory space - if your
 * inventory is full the purchase is refused and you are NOT charged. Items with "commands"
 * (potion-style effects) run those commands as console.
 */
public final class ShopGUI implements Listener {

    private final HalloweenPlugin plugin;

    private static final class ShopHolder implements InventoryHolder {
        private Inventory inventory;
        private int balanceSlot;
        /** slot -> config path of the item. */
        private final Map<Integer, String> slots = new HashMap<>();

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    public ShopGUI(HalloweenPlugin plugin) {
        this.plugin = plugin;
    }

    /** True if the whole stack would fit in the player's inventory (nothing is changed). */
    static boolean hasRoom(Player p, ItemStack stack) {
        ItemStack[] contents = p.getInventory().getStorageContents();
        ItemStack[] copy = new ItemStack[contents.length];
        for (int i = 0; i < contents.length; i++) {
            copy[i] = contents[i] == null ? null : contents[i].clone();
        }
        Inventory tmp = Bukkit.createInventory(null, 36);
        tmp.setStorageContents(copy);
        return tmp.addItem(stack.clone()).isEmpty();
    }

    public void open(Player player) {
        if (!plugin.isEventActive()) {
            plugin.send(player, "The shop is closed until Halloween!");
            return;
        }
        FileConfiguration c = plugin.getConfig();

        List<String> regular = new ArrayList<>();
        ConfigurationSection items = c.getConfigurationSection("shop.items");
        if (items != null) {
            for (String key : items.getKeys(false)) regular.add("shop.items." + key);
        }
        List<String> specials = todaysSpecials(c);
        if (regular.isEmpty() && specials.isEmpty()) {
            plugin.send(player, "The shop is empty.");
            return;
        }

        // Regular items first, today's specials start on a fresh row, last slot = balance
        int specialStart = regular.isEmpty() ? 0 : ((regular.size() + 8) / 9) * 9;
        int total = specialStart + specials.size();
        int size = Math.min(54, Math.max(9, ((total + 1 + 8) / 9) * 9));
        int last = size - 1;

        ShopHolder holder = new ShopHolder();
        Inventory inv = Bukkit.createInventory(holder, size, HalloweenPlugin.mm("<dark_purple>Halloween Candy Shop"));
        holder.inventory = inv;
        holder.balanceSlot = last;

        for (int i = 0; i < regular.size(); i++) place(holder, inv, i, regular.get(i), false, last);
        for (int j = 0; j < specials.size(); j++) place(holder, inv, specialStart + j, specials.get(j), true, last);
        inv.setItem(last, balanceIcon(player));

        player.openInventory(inv);
    }

    /** Picks today's specials; the same date always gives the same picks. */
    private List<String> todaysSpecials(FileConfiguration c) {
        ConfigurationSection pool = c.getConfigurationSection("shop.rotating.items");
        if (pool == null) return List.of();
        List<String> keys = new ArrayList<>(pool.getKeys(false));
        Collections.shuffle(keys, new Random(LocalDate.now().toEpochDay()));
        int count = Math.max(0, c.getInt("shop.rotating.count", 6));
        List<String> out = new ArrayList<>();
        for (int i = 0; i < Math.min(count, keys.size()); i++) {
            out.add("shop.rotating.items." + keys.get(i));
        }
        return out;
    }

    private void place(ShopHolder holder, Inventory inv, int slot, String path, boolean special, int last) {
        if (slot >= last) return;
        ConfigurationSection s = plugin.getConfig().getConfigurationSection(path);
        if (s == null) return;
        inv.setItem(slot, icon(s, path.substring(path.lastIndexOf('.') + 1), special));
        holder.slots.put(slot, path);
    }

    private ItemStack icon(ConfigurationSection s, String fallbackName, boolean special) {
        Material mat = Material.matchMaterial(s.getString("material", "PAPER"));
        if (mat == null) mat = Material.PAPER;
        int cost = s.getInt("cost", 1);

        ItemStack stack = new ItemStack(mat);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(HalloweenPlugin.mm(s.getString("name", fallbackName)));
        List<Component> lore = new ArrayList<>();
        for (String line : s.getStringList("lore")) lore.add(HalloweenPlugin.mm(line));
        lore.add(Component.empty());
        if (special) lore.add(HalloweenPlugin.mm("<light_purple>Today's special"));
        lore.add(HalloweenPlugin.mm("<yellow>Cost: <gold>" + cost + " candy"));
        if (s.getInt("amount", 0) > 0) lore.add(HalloweenPlugin.mm("<dark_gray>Needs free inventory space"));
        lore.add(HalloweenPlugin.mm("<dark_gray>Click to buy"));
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack balanceIcon(Player p) {
        ItemStack stack = new ItemStack(Material.GOLD_NUGGET);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(HalloweenPlugin.mm("<gold>Your candy: <white>" + plugin.candy().balance(p.getUniqueId())));
        stack.setItemMeta(meta);
        return stack;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof ShopHolder holder)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() != e.getInventory()) return;

        String path = holder.slots.get(e.getSlot());
        if (path == null) return;

        ConfigurationSection s = plugin.getConfig().getConfigurationSection(path);
        if (s == null) return;

        int cost = s.getInt("cost", 1);
        if (plugin.candy().balance(p.getUniqueId()) < cost) {
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            plugin.send(p, "<red>Not enough candy!</red> You need <gold>" + cost + "</gold>.");
            return;
        }

        // Items that are handed over must fit - otherwise nothing happens and nothing is charged
        ItemStack give = null;
        int amount = s.getInt("amount", 0);
        if (amount > 0) {
            Material m = Material.matchMaterial(s.getString("material", ""));
            if (m != null && m.isItem()) give = new ItemStack(m, amount);
        }
        if (give != null && !hasRoom(p, give)) {
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            plugin.send(p, "<red>Your inventory is full!</red> Free some space first - you were not charged.");
            return;
        }

        if (!plugin.candy().take(p.getUniqueId(), cost)) return;

        if (give != null) {
            for (ItemStack rest : p.getInventory().addItem(give).values()) {
                p.getWorld().dropItemNaturally(p.getLocation(), rest);   // should never happen (checked above)
            }
        }
        for (String cmd : s.getStringList("commands")) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("%player%", p.getName()));
        }
        holder.inventory.setItem(holder.balanceSlot, balanceIcon(p));
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
        plugin.send(p, "Purchased! You have <gold>" + plugin.candy().balance(p.getUniqueId()) + " candy</gold> left.");
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof ShopHolder) e.setCancelled(true);
    }
}
