package com.example.halloween;

import org.bukkit.HeightMap;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;

import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * Finds spots for random spawns that never overwrite anything:
 * natural ground only, open sky (so not inside buildings or caves),
 * and a free 3x3x2 area. Only the empty air block is ever used.
 */
public final class SpawnSpots {

    private SpawnSpots() {}

    public static Block findNear(HalloweenPlugin plugin, Player target, int min, int max, Random random) {
        World world = target.getWorld();
        if (!worldAllowed(plugin, world)) return null;

        Set<Material> ground = groundMaterials(plugin);
        int span = Math.max(1, max - min);

        for (int attempt = 0; attempt < 20; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double dist = min + random.nextDouble() * span;
            int x = target.getLocation().getBlockX() + (int) Math.round(Math.cos(angle) * dist);
            int z = target.getLocation().getBlockZ() + (int) Math.round(Math.sin(angle) * dist);

            if (!world.isChunkLoaded(x >> 4, z >> 4)) continue;

            Block g = world.getHighestBlockAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
            if (!ground.contains(g.getType())) continue;

            Block spot = g.getRelative(BlockFace.UP);
            if (isSafe(spot)) return spot;
        }
        return null;
    }

    private static boolean isSafe(Block spot) {
        if (!spot.getType().isAir()) return false;
        if (!spot.getWorld().getWorldBorder().isInside(spot.getLocation())) return false;
        if (spot.getLightFromSky() < 14) return false; // must be under open sky

        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = 0; dy <= 1; dy++) {
                    Block b = spot.getRelative(dx, dy, dz);
                    if (b.isLiquid() || !b.isPassable()) return false;
                }
            }
        }
        return true;
    }

    private static boolean worldAllowed(HalloweenPlugin plugin, World world) {
        if (world.getEnvironment() != World.Environment.NORMAL) return false;
        List<String> allowed = plugin.getConfig().getStringList("spawning.worlds");
        return allowed.isEmpty() || allowed.contains(world.getName());
    }

    private static Set<Material> groundMaterials(HalloweenPlugin plugin) {
        Set<Material> set = EnumSet.noneOf(Material.class);
        for (String name : plugin.getConfig().getStringList("spawning.safe-ground")) {
            Material m = Material.matchMaterial(name);
            if (m != null) set.add(m);
        }
        return set;
    }
}
