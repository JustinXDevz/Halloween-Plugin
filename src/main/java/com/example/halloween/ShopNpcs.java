package com.example.halloween;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.entity.WanderingTrader;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Two NPCs that only exist when an admin summons them:
 *  - the Candy Shop NPC (opens the candy shop)
 *  - the Rare Merchant (sells rare, over-enchanted gear for candy; every summon rolls different stock)
 *
 * Merchant gear carries real bonuses on top of its enchantments (see RareGear): extra damage,
 * extra damage reduction, and it never breaks. Armor never gets Blast Protection, because Blast
 * Protection weakens the knockback of explosions - including wind charges - and you could no
 * longer wind-charge yourself upwards.
 */
public final class ShopNpcs implements Listener {

    private record Offer(ItemStack item, int cost, int stock) {}

    private static final int SIZE = 36;
    private static final int BALANCE_SLOT = 31;
    private static final int[] SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};

    private static final class MerchantHolder implements InventoryHolder {
        private final UUID merchant;
        private Inventory inventory;
        private final Map<Integer, Integer> slotToOffer = new HashMap<>();

        MerchantHolder(UUID merchant) {
            this.merchant = merchant;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final HalloweenPlugin plugin;
    private final ShopGUI shop;
    private final RareGear gear;
    private final Random random = new Random();
    private final NamespacedKey shopKey;
    private final NamespacedKey merchantKey;
    private final NamespacedKey offersKey;

    public ShopNpcs(HalloweenPlugin plugin, ShopGUI shop, RareGear gear) {
        this.plugin = plugin;
        this.shop = shop;
        this.gear = gear;
        this.shopKey = new NamespacedKey(plugin, "candy_shop_npc");
        this.merchantKey = new NamespacedKey(plugin, "rare_merchant");
        this.offersKey = new NamespacedKey(plugin, "merchant_offers");
    }

    // ---------- Spawning (commands only) ----------

    public void spawnShopNpc(Location loc) {
        loc.getWorld().spawn(loc, Villager.class, v -> {
            v.customName(HalloweenPlugin.mm("<gold>Candy Shop"));
            v.setCustomNameVisible(true);
            v.setAI(false);
            v.setInvulnerable(true);
            v.setSilent(true);
            v.setPersistent(true);
            v.setRemoveWhenFarAway(false);
            v.setProfession(Villager.Profession.FARMER);
            v.getPersistentDataContainer().set(shopKey, PersistentDataType.BYTE, (byte) 1);
        });
    }

    /** Summons a Rare Merchant with freshly rolled stock. False if the pool is empty/invalid. */
    public boolean spawnMerchant(Location loc) {
        List<Offer> offers = rollOffers();
        if (offers.isEmpty()) return false;

        String name = plugin.getConfig().getString("merchant.name", "<dark_purple>Rare Merchant");
        loc.getWorld().spawn(loc, WanderingTrader.class, t -> {
            t.customName(HalloweenPlugin.mm(name));
            t.setCustomNameVisible(true);
            t.setAI(false);
            t.setInvulnerable(true);
            t.setSilent(true);
            t.setPersistent(true);
            t.setRemoveWhenFarAway(false);
            t.setDespawnDelay(Integer.MAX_VALUE);
            t.getPersistentDataContainer().set(merchantKey, PersistentDataType.BYTE, (byte) 1);
            writeOffers(t, offers);
        });
        return true;
    }

    /** Removes the closest shop NPC (or merchant) within the radius. */
    public boolean removeNearest(Location loc, double radius, boolean merchant) {
        NamespacedKey key = merchant ? merchantKey : shopKey;
        Entity best = null;
        double bestDist = Double.MAX_VALUE;
        for (Entity ent : loc.getWorld().getNearbyEntities(loc, radius, radius, radius)) {
            if (!ent.getPersistentDataContainer().has(key, PersistentDataType.BYTE)) continue;
            double d = ent.getLocation().distanceSquared(loc);
            if (d < bestDist) {
                bestDist = d;
                best = ent;
            }
        }
        if (best == null) return false;
        best.remove();
        return true;
    }

    // ---------- Rolling the merchant's stock ----------

    private List<Offer> rollOffers() {
        FileConfiguration c = plugin.getConfig();
        ConfigurationSection pool = c.getConfigurationSection("merchant.pool");
        if (pool == null) return List.of();

        List<Offer> templates = new ArrayList<>();
        for (String key : pool.getKeys(false)) {
            ConfigurationSection s = pool.getConfigurationSection(key);
            if (s == null) continue;
            ItemStack item = buildItem(s);
            if (item == null) {
                plugin.getLogger().warning("merchant.pool." + key + " has an unknown material - skipped.");
                continue;
            }
            templates.add(new Offer(item, Math.max(1, s.getInt("cost", 1000)), 1));
        }
        if (templates.isEmpty()) return List.of();

        Collections.shuffle(templates, random);
        int n = Math.min(Math.min(templates.size(), SLOTS.length), Math.max(1, c.getInt("merchant.offers-per-summon", 6)));
        int stock = Math.max(1, c.getInt("merchant.stock-per-offer", 1));
        double variation = Math.max(0.0, Math.min(0.9, c.getDouble("merchant.price-variation", 0.15)));

        List<Offer> result = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Offer t = templates.get(i);
            double factor = 1 + (random.nextDouble() * 2 - 1) * variation;
            int cost = (int) (Math.round(t.cost() * factor / 10.0) * 10);
            result.add(new Offer(t.item(), Math.max(10, cost), stock));
        }
        return result;
    }

    /**
     * Builds a merchant item: any enchantment level, plus real bonuses (damage, damage reduction)
     * and unbreakable, all listed in the lore.
     */
    private ItemStack buildItem(ConfigurationSection s) {
        Material mat = Material.matchMaterial(s.getString("material", ""));
        if (mat == null || !mat.isItem()) return null;

        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(HalloweenPlugin.mm(s.getString("name", mat.name())));

        List<Component> lore = new ArrayList<>();
        for (String line : s.getStringList("lore")) lore.add(HalloweenPlugin.mm(line));

        // Blast Protection would weaken wind charges (and every other explosion's knockback), so it is never added
        boolean allowBlast = plugin.getConfig().getBoolean("merchant.allow-blast-protection", false);
        for (String entry : s.getStringList("enchants")) {
            String[] parts = entry.split(":");
            String name = parts[0].trim().toLowerCase(Locale.ROOT);
            if (!allowBlast && name.equals("blast_protection")) continue;
            try {
                Enchantment ench = Registry.ENCHANTMENT.get(NamespacedKey.minecraft(name));
                if (ench == null) continue;
                int level = parts.length > 1 ? Integer.parseInt(parts[1].trim()) : 1;
                meta.addEnchant(ench, Math.max(1, Math.min(255, level)), true);
            } catch (IllegalArgumentException ignored) {
                plugin.getLogger().warning("Bad enchant entry '" + entry + "' in the merchant pool.");
            }
        }

        double damage = s.getDouble("bonus-damage", 0);
        double smash = s.getDouble("smash-bonus", 0);
        double reduction = s.getDouble("damage-reduction", 0);
        gear.tag(meta, damage, smash, reduction);
        if (damage > 0) lore.add(HalloweenPlugin.mm("<green>+" + fmt(damage) + " damage on every hit"));
        if (smash > 0) lore.add(HalloweenPlugin.mm("<green>+" + fmt(smash) + " damage per block fallen"));
        if (reduction > 0) lore.add(HalloweenPlugin.mm("<aqua>-" + fmt(reduction * 100) + "% damage taken"));
        if (s.getBoolean("unbreakable", false)) {
            meta.setUnbreakable(true);
            lore.add(HalloweenPlugin.mm("<gray>Unbreakable"));
        }

        if (!lore.isEmpty()) meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static String fmt(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.format(Locale.ROOT, "%.1f", d);
    }

    // ---------- Stock is stored on the merchant itself ----------

    private List<Offer> readOffers(Entity merchant) {
        List<Offer> list = new ArrayList<>();
        String yaml = merchant.getPersistentDataContainer().get(offersKey, PersistentDataType.STRING);
        if (yaml == null) return list;

        YamlConfiguration y = new YamlConfiguration();
        try {
            y.loadFromString(yaml);
        } catch (InvalidConfigurationException e) {
            plugin.getLogger().warning("Could not read a merchant's stock: " + e.getMessage());
            return list;
        }
        ConfigurationSection sec = y.getConfigurationSection("offers");
        if (sec == null) return list;
        for (String k : sec.getKeys(false)) {
            ConfigurationSection s = sec.getConfigurationSection(k);
            if (s == null) continue;
            ItemStack item = s.getItemStack("item");
            if (item == null) continue;
            list.add(new Offer(item, s.getInt("cost"), s.getInt("stock")));
        }
        return list;
    }

    private void writeOffers(Entity merchant, List<Offer> offers) {
        YamlConfiguration y = new YamlConfiguration();
        for (int i = 0; i < offers.size(); i++) {
            Offer o = offers.get(i);
            y.set("offers." + i + ".item", o.item());
            y.set("offers." + i + ".cost", o.cost());
            y.set("offers." + i + ".stock", o.stock());
        }
        merchant.getPersistentDataContainer().set(offersKey, PersistentDataType.STRING, y.saveToString());
    }

    // ---------- Interaction ----------

    @EventHandler
    public void onInteract(PlayerInteractEntityEvent e) {
        Entity npc = e.getRightClicked();
        var pdc = npc.getPersistentDataContainer();
        boolean candyNpc = pdc.has(shopKey, PersistentDataType.BYTE);
        boolean merchant = pdc.has(merchantKey, PersistentDataType.BYTE);
        if (!candyNpc && !merchant) return;

        e.setCancelled(true); // never open the vanilla trade window
        if (e.getHand() != EquipmentSlot.HAND) return;

        Player p = e.getPlayer();
        if (candyNpc) {
            shop.open(p);
        } else {
            openMerchant(p, npc);
        }
    }

    @EventHandler
    public void onDamage(EntityDamageEvent e) {
        var pdc = e.getEntity().getPersistentDataContainer();
        if (pdc.has(shopKey, PersistentDataType.BYTE) || pdc.has(merchantKey, PersistentDataType.BYTE)) {
            e.setCancelled(true);
        }
    }

    // ---------- Merchant GUI ----------

    private void openMerchant(Player p, Entity merchant) {
        if (!plugin.isEventActive()) {
            plugin.send(p, "The merchant has vanished into the fog... come back during Halloween!");
            return;
        }
        MerchantHolder h = new MerchantHolder(merchant.getUniqueId());
        String title = plugin.getConfig().getString("merchant.name", "<dark_purple>Rare Merchant");
        Inventory inv = Bukkit.createInventory(h, SIZE, HalloweenPlugin.mm(title));
        h.inventory = inv;
        render(h, p);
        p.openInventory(inv);
    }

    private void render(MerchantHolder h, Player viewer) {
        Inventory inv = h.inventory;
        ItemStack filler = named(Material.BLACK_STAINED_GLASS_PANE, " ");
        for (int i = 0; i < inv.getSize(); i++) inv.setItem(i, filler);
        h.slotToOffer.clear();

        Entity m = Bukkit.getEntity(h.merchant);
        List<Offer> offers = m == null ? List.of() : readOffers(m);
        for (int i = 0; i < offers.size() && i < SLOTS.length; i++) {
            Offer o = offers.get(i);
            inv.setItem(SLOTS[i], o.stock() > 0 ? display(o) : named(Material.RED_STAINED_GLASS_PANE, "<red>SOLD OUT"));
            h.slotToOffer.put(SLOTS[i], i);
        }
        inv.setItem(BALANCE_SLOT, named(Material.GOLD_NUGGET,
                "<gold>Your candy: <white>" + plugin.candy().balance(viewer.getUniqueId())));
    }

    private ItemStack display(Offer o) {
        ItemStack icon = o.item().clone();
        ItemMeta meta = icon.getItemMeta();
        List<Component> lore = meta.hasLore() ? new ArrayList<>(meta.lore()) : new ArrayList<>();
        lore.add(Component.empty());
        lore.add(HalloweenPlugin.mm("<yellow>Price: <gold>" + o.cost() + " candy"));
        lore.add(HalloweenPlugin.mm("<gray>In stock: <white>" + o.stock()));
        lore.add(HalloweenPlugin.mm("<dark_gray>Needs free inventory space"));
        lore.add(HalloweenPlugin.mm("<dark_gray>Click to buy"));
        meta.lore(lore);
        icon.setItemMeta(meta);
        return icon;
    }

    private static ItemStack named(Material mat, String name) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(HalloweenPlugin.mm(name));
        it.setItemMeta(meta);
        return it;
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getInventory().getHolder() instanceof MerchantHolder h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() != e.getInventory()) return;

        Integer index = h.slotToOffer.get(e.getSlot());
        if (index == null) return;
        buy(p, h, index);
    }

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getInventory().getHolder() instanceof MerchantHolder) e.setCancelled(true);
    }

    private void buy(Player p, MerchantHolder h, int index) {
        if (!plugin.isEventActive()) {
            p.closeInventory();
            return;
        }
        Entity m = Bukkit.getEntity(h.merchant);
        if (m == null) {
            p.closeInventory();
            return;
        }
        List<Offer> offers = readOffers(m);
        if (index < 0 || index >= offers.size()) return;
        Offer o = offers.get(index);

        if (o.stock() <= 0) {
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            plugin.send(p, "<red>Sold out!</red> Rare goods don't come twice.");
            return;
        }
        if (plugin.candy().balance(p.getUniqueId()) < o.cost()) {
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            plugin.send(p, "<red>Not enough candy!</red> You need <gold>" + o.cost() + "</gold>.");
            return;
        }

        // No room = no sale and no charge
        ItemStack give = o.item().clone();
        if (!ShopGUI.hasRoom(p, give)) {
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            plugin.send(p, "<red>Your inventory is full!</red> Free some space first - you were not charged.");
            return;
        }
        if (!plugin.candy().take(p.getUniqueId(), o.cost())) return;

        offers.set(index, new Offer(o.item(), o.cost(), o.stock() - 1));
        writeOffers(m, offers);

        for (ItemStack rest : p.getInventory().addItem(give).values()) {
            p.getWorld().dropItemNaturally(p.getLocation(), rest);   // should never happen (checked above)
        }
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 0.8f);
        plugin.send(p, "Purchased for <gold>" + o.cost() + " candy</gold>! You have <gold>"
                + plugin.candy().balance(p.getUniqueId()) + "</gold> left.");

        // Everyone looking at this merchant sees the updated stock
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.getOpenInventory().getTopInventory().getHolder() instanceof MerchantHolder mh
                    && mh.merchant.equals(h.merchant)) {
                render(mh, viewer);
            }
        }
    }
        }
