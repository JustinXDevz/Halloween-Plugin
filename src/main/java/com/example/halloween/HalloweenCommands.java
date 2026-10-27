package com.example.halloween;

import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;

public final class HalloweenCommands implements CommandExecutor, TabCompleter {

    private final HalloweenPlugin plugin;
    private final ShopGUI shop;

    public HalloweenCommands(HalloweenPlugin plugin, ShopGUI shop) {
        this.plugin = plugin;
        this.shop = shop;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        switch (command.getName().toLowerCase()) {
            case "candy" -> candy(sender, args);
            case "halloweenshop" -> {
                if (sender instanceof Player p) shop.open(p);
                else plugin.send(sender, "Players only.");
            }
            default -> halloween(sender, args);
        }
        return true;
    }

    private void candy(CommandSender sender, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("top")) {
            List<Map.Entry<String, Integer>> top = plugin.candy().top(10);
            plugin.send(sender, "<gold>Top Candy Collectors</gold>");
            if (top.isEmpty()) {
                plugin.send(sender, "Nobody has collected candy yet!");
                return;
            }
            int rank = 1;
            for (Map.Entry<String, Integer> e : top) {
                plugin.send(sender, "<yellow>#" + rank++ + " <white>" + e.getKey()
                        + " <dark_gray>- <gold>" + e.getValue() + " candy");
            }
            return;
        }
        if (sender instanceof Player p) {
            plugin.send(p, "Balance: <gold>" + plugin.candy().balance(p.getUniqueId())
                    + " candy</gold> <dark_gray>(earned " + plugin.candy().earned(p.getUniqueId()) + " total)");
        } else {
            plugin.send(sender, "Use /candy top");
        }
    }

    private void halloween(CommandSender sender, String[] args) {
        if (args.length == 0) {
            plugin.send(sender, "Event: " + (plugin.isEventActive() ? "<green>ACTIVE" : "<red>inactive"));
            plugin.send(sender, "Runs <white>" + plugin.startDate() + "</white> to <white>" + plugin.endDate() + "</white>");
            plugin.send(sender, "Try <gold>/halloweenshop</gold> and <gold>/candy top</gold>");
            return;
        }

        if (!sender.hasPermission("halloween.admin")) {
            plugin.send(sender, "<red>No permission.");
            return;
        }

        switch (args[0].toLowerCase()) {
            case "npc" -> {
                if (!(sender instanceof Player p)) { plugin.send(sender, "Players only."); return; }
                if (args.length > 1 && args[1].equalsIgnoreCase("remove")) {
                    if (plugin.trickOrTreat().removeNearest(p.getLocation(), 5)) plugin.send(p, "Removed the nearest NPC.");
                    else plugin.send(p, "<red>No Trick-or-Treat NPC within 5 blocks.");
                    return;
                }
                plugin.trickOrTreat().spawnNpc(p.getLocation(), false);
                plugin.send(p, "Permanent NPC placed. Remove it with <gold>/halloween npc remove</gold>.");
            }
            case "pumpkin" -> {
                if (!(sender instanceof Player p)) { plugin.send(sender, "Players only."); return; }
                if (args.length > 1 && args[1].equalsIgnoreCase("random")) {
                    if (!plugin.hunt().spawnNear(p)) plugin.send(p, "<red>No safe spot found nearby. Try again.");
                    return;
                }
                Block target = p.getTargetBlockExact(6);
                if (target == null) { plugin.send(p, "<red>Look at a block within 6 blocks."); return; }
                BlockFace face = p.getTargetBlockFace(6);
                Block spot = target.getRelative(face != null ? face : BlockFace.UP);
                if (plugin.hunt().placeAt(spot)) plugin.send(p, "Pumpkin placed.");
                else plugin.send(p, "<red>That spot isn't empty.");
            }
            case "zone" -> zone(sender, args);
            case "give" -> {
                if (args.length < 3) { plugin.send(sender, "Usage: /halloween give <player> <amount>"); return; }
                Player target = Bukkit.getPlayerExact(args[1]);
                if (target == null) { plugin.send(sender, "<red>Player not found."); return; }
                try {
                    int amount = Integer.parseInt(args[2]);
                    plugin.candy().give(target, amount);
                    plugin.send(sender, "Gave <gold>" + amount + " candy</gold> to " + target.getName() + ".");
                } catch (NumberFormatException ex) {
                    plugin.send(sender, "<red>Amount must be a number.");
                }
            }
            case "reload" -> {
                plugin.reloadConfig();
                plugin.hunt().start();
                plugin.trickOrTreat().start();
                plugin.zones().start();
                plugin.send(sender, "Config reloaded.");
            }
            default -> plugin.send(sender, "Usage: /halloween [npc|pumpkin|zone|give|reload]");
        }
    }

    private void zone(CommandSender sender, String[] args) {
        String sub = args.length > 1 ? args[1].toLowerCase() : "";
        switch (sub) {
            case "create" -> {
                if (!(sender instanceof Player p)) { plugin.send(sender, "Players only."); return; }
                if (args.length < 3) { plugin.send(p, "Usage: /halloween zone create <name> [radius]"); return; }
                String name = args[2].toLowerCase();
                if (!name.matches("[a-z0-9_]{1,24}")) {
                    plugin.send(p, "<red>Name: letters, numbers and _ only (max 24).");
                    return;
                }
                int radius = plugin.getConfig().getInt("haunted-zones.default-radius", 30);
                if (args.length > 3) {
                    try {
                        radius = Integer.parseInt(args[3]);
                    } catch (NumberFormatException ex) {
                        plugin.send(p, "<red>Radius must be a number.");
                        return;
                    }
                }
                radius = Math.max(5, Math.min(200, radius));
                if (plugin.zones().add(name, p.getLocation(), radius)) {
                    plugin.send(p, "Haunted zone <gold>" + name + "</gold> created here (radius " + radius + ")."
                            + (plugin.zones().status(name).equals("clean") ? "" : " <red>It is already spreading!"));
                } else {
                    plugin.send(p, "<red>A zone with that name already exists.");
                }
            }
            case "remove" -> {
                if (args.length < 3) { plugin.send(sender, "Usage: /halloween zone remove <name>"); return; }
                if (plugin.zones().remove(args[2].toLowerCase())) {
                    plugin.send(sender, "Zone removed (any infection is being restored).");
                } else {
                    plugin.send(sender, "<red>No zone with that name.");
                }
            }
            case "list" -> {
                if (plugin.zones().all().isEmpty()) { plugin.send(sender, "No haunted zones yet."); return; }
                for (HauntedZones.Zone z : plugin.zones().all()) {
                    plugin.send(sender, "<gold>" + z.name() + "</gold> <gray>- " + z.world() + " at "
                            + z.x() + ", " + z.z() + " (radius " + z.baseRadius() + ") <dark_gray>["
                            + plugin.zones().status(z.name()) + "]");
                }
            }
            case "infect" -> {
                if (args.length < 3) { plugin.send(sender, "Usage: /halloween zone infect <name>"); return; }
                String name = args[2].toLowerCase();
                if (plugin.zones().get(name) == null) { plugin.send(sender, "<red>No zone with that name."); return; }
                if (!plugin.isEventActive()) {
                    plugin.send(sender, "<red>The event isn't active. Set event.always-active: true in config.yml to test.");
                    return;
                }
                if (plugin.zones().infect(name)) plugin.send(sender, "Infection started in <gold>" + name + "</gold>.");
                else plugin.send(sender, "<red>That zone is already infected or still healing.");
            }
            case "cleanse" -> {
                if (args.length < 3) { plugin.send(sender, "Usage: /halloween zone cleanse <name>"); return; }
                if (plugin.zones().cleanse(args[2].toLowerCase())) {
                    plugin.send(sender, "Cleansed. The land is being restored.");
                } else {
                    plugin.send(sender, "<red>That zone isn't infected.");
                }
            }
            case "heart" -> {
                if (args.length < 3) { plugin.send(sender, "Usage: /halloween zone heart <name>"); return; }
                int[] h = plugin.zones().heartPos(args[2].toLowerCase());
                if (h == null) plugin.send(sender, "<red>No active Cursed Heart for that zone.");
                else plugin.send(sender, "Cursed Heart at <white>" + h[0] + ", " + h[1] + ", " + h[2] + "</white>.");
            }
            default -> plugin.send(sender, "Usage: /halloween zone <create|remove|list|infect|cleanse|heart>");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String name = command.getName().toLowerCase();
        if (args.length == 1) {
            if (name.equals("candy")) return filter(List.of("top"), args[0]);
            if (name.equals("halloween") && sender.hasPermission("halloween.admin")) {
                return filter(List.of("npc", "pumpkin", "zone", "give", "reload"), args[0]);
            }
        }
        if (args.length == 2 && name.equals("halloween") && sender.hasPermission("halloween.admin")) {
            if (args[0].equalsIgnoreCase("npc")) return filter(List.of("remove"), args[1]);
            if (args[0].equalsIgnoreCase("pumpkin")) return filter(List.of("random"), args[1]);
            if (args[0].equalsIgnoreCase("zone")) {
                return filter(List.of("create", "remove", "list", "infect", "cleanse", "heart"), args[1]);
            }
        }
        if (args.length == 3 && name.equals("halloween") && sender.hasPermission("halloween.admin")
                && args[0].equalsIgnoreCase("zone")
                && List.of("remove", "infect", "cleanse", "heart").contains(args[1].toLowerCase())) {
            return filter(plugin.zones().names(), args[2]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        return options.stream().filter(s -> s.startsWith(prefix.toLowerCase())).toList();
    }
}
