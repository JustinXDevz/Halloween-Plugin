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
                plugin.send(sender, "Config reloaded.");
            }
            default -> plugin.send(sender, "Usage: /halloween [npc|pumpkin|give|reload]");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String name = command.getName().toLowerCase();
        if (args.length == 1) {
            if (name.equals("candy")) return filter(List.of("top"), args[0]);
            if (name.equals("halloween") && sender.hasPermission("halloween.admin")) {
                return filter(List.of("npc", "pumpkin", "give", "reload"), args[0]);
            }
        }
        if (args.length == 2 && name.equals("halloween") && sender.hasPermission("halloween.admin")) {
            if (args[0].equalsIgnoreCase("npc")) return filter(List.of("remove"), args[1]);
            if (args[0].equalsIgnoreCase("pumpkin")) return filter(List.of("random"), args[1]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        return options.stream().filter(s -> s.startsWith(prefix.toLowerCase())).toList();
    }
        }
