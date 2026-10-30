package com.example.halloween;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

/**
 * Real, flat bonuses for Rare Merchant gear. Enchantment levels above vanilla's maximum barely change
 * anything (protection is capped, many enchants are not worth much past their limit), so the gear
 * also carries its own bonuses, stored on the item:
 *  - bonus damage: added to every hit (melee, arrows, bolts, tridents)
 *  - smash bonus (mace): extra damage per block fallen
 *  - damage reduction: extra percent of damage ignored while worn (or held, for shields)
 */
public final class RareGear implements Listener {

    private final HalloweenPlugin plugin;
    private final NamespacedKey damageKey;
    private final NamespacedKey smashKey;
    private final NamespacedKey reductionKey;
    private final NamespacedKey projectileKey;

    public RareGear(HalloweenPlugin plugin) {
        this.plugin = plugin;
        this.damageKey = new NamespacedKey(plugin, "rare_damage");
        this.smashKey = new NamespacedKey(plugin, "rare_smash");
        this.reductionKey = new NamespacedKey(plugin, "rare_reduction");
        this.projectileKey = new NamespacedKey(plugin, "rare_projectile");
    }

    /** Stores the bonuses on an item (any of them can be 0). */
    public void tag(ItemMeta meta, double damage, double smash, double reduction) {
        var pdc = meta.getPersistentDataContainer();
        if (damage > 0) pdc.set(damageKey, PersistentDataType.DOUBLE, damage);
        if (smash > 0) pdc.set(smashKey, PersistentDataType.DOUBLE, smash);
        if (reduction > 0) pdc.set(reductionKey, PersistentDataType.DOUBLE, reduction);
    }

    private double read(ItemStack item, NamespacedKey key) {
        if (item == null || !item.hasItemMeta()) return 0;
        Double v = item.getItemMeta().getPersistentDataContainer().get(key, PersistentDataType.DOUBLE);
        return v == null ? 0 : v;
    }

    private static boolean isBow(Material m) {
        return m == Material.BOW || m == Material.CROSSBOW;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent e) {
        double damage = e.getDamage();

        // Attacker bonuses
        if (e instanceof EntityDamageByEntityEvent by) {
            Entity damager = by.getDamager();
            if (damager instanceof Player p) {
                ItemStack hand = p.getInventory().getItemInMainHand();
                if (!isBow(hand.getType())) damage += read(hand, damageKey);   // punching with a bow gets no bonus
                double smash = read(hand, smashKey);
                if (smash > 0 && p.getFallDistance() > 1.5f) {
                    damage += smash * Math.min(60f, p.getFallDistance());
                }
            } else if (damager instanceof Projectile proj) {
                Double v = proj.getPersistentDataContainer().get(projectileKey, PersistentDataType.DOUBLE);
                if (v != null) damage += v;
            }
        }

        // Defender bonus
        if (e.getEntity() instanceof Player victim && reducible(e.getCause())) {
            double r = reduction(victim);
            if (r > 0) damage *= (1 - r);
        }

        if (damage != e.getDamage()) e.setDamage(damage);
    }

    private static boolean reducible(DamageCause cause) {
        return cause != DamageCause.VOID && cause != DamageCause.SUICIDE
                && cause != DamageCause.KILL && cause != DamageCause.WORLD_BORDER;
    }

    private double reduction(Player p) {
        PlayerInventory inv = p.getInventory();
        double r = read(inv.getHelmet(), reductionKey)
                + read(inv.getChestplate(), reductionKey)
                + read(inv.getLeggings(), reductionKey)
                + read(inv.getBoots(), reductionKey);
        ItemStack main = inv.getItemInMainHand();
        ItemStack off = inv.getItemInOffHand();
        if (main.getType() == Material.SHIELD) r += read(main, reductionKey);
        if (off.getType() == Material.SHIELD) r += read(off, reductionKey);
        double max = Math.max(0, Math.min(0.9, plugin.getConfig().getDouble("merchant.max-damage-reduction", 0.6)));
        return Math.min(max, r);
    }

    /** Arrows, bolts and tridents carry the bonus of the weapon they were shot from. */
    @EventHandler(ignoreCancelled = true)
    public void onLaunch(ProjectileLaunchEvent e) {
        if (!(e.getEntity().getShooter() instanceof Player p)) return;
        double bonus = ranged(p.getInventory().getItemInMainHand());
        if (bonus <= 0) bonus = ranged(p.getInventory().getItemInOffHand());
        if (bonus > 0) {
            e.getEntity().getPersistentDataContainer().set(projectileKey, PersistentDataType.DOUBLE, bonus);
        }
    }

    private double ranged(ItemStack item) {
        Material m = item.getType();
        if (m != Material.BOW && m != Material.CROSSBOW && m != Material.TRIDENT) return 0;
        return read(item, damageKey);
    }
}
