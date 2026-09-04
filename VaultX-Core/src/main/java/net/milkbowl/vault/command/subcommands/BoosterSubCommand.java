package net.milkbowl.vault.command.subcommands;

import net.milkbowl.vault.Vault;
import net.milkbowl.vault.command.SubCommand;
import net.milkbowl.vault.command.VaultXCommand;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;

import java.util.Collections;
import java.util.List;

public class BoosterSubCommand implements SubCommand {

    private final VaultXCommand parent;

    public BoosterSubCommand(VaultXCommand parent) {
        this.parent = parent;
    }

    @Override
    public String getName() {
        return "booster";
    }

    @Override
    public List<String> getAliases() {
        return Collections.singletonList("boosters");
    }

    @Override
    public String getPermission() {
        return "vault.admin.booster";
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        if (!sender.hasPermission("vault.admin.booster")) {
            sender.sendMessage("§cYou do not have permission!");
            return true;
        }
        if (args.length < 4) {
            sender.sendMessage("§cUsage: /vaultx booster <currency> <multiplier> <duration_ms|1h|30m>");
            return true;
        }
        String currency = args[1];
        double mult;
        try {
            mult = parent.parsePositiveDouble(args[2]);
        } catch (Exception e) {
            sender.sendMessage("§cInvalid multiplier! Must be a positive number.");
            return true;
        }
        long durationMs = 3600000L;
        String durStr = args[3].toLowerCase();
        try {
            if (durStr.endsWith("m")) durationMs = Long.parseLong(durStr.replace("m", "")) * 60000L;
            else if (durStr.endsWith("h")) durationMs = Long.parseLong(durStr.replace("h", "")) * 3600000L;
            else if (durStr.endsWith("s")) durationMs = Long.parseLong(durStr.replace("s", "")) * 1000L;
            else durationMs = Long.parseLong(durStr);
            if (durationMs <= 0) throw new NumberFormatException("Non-positive duration");
        } catch (Exception e) {
            sender.sendMessage("§cInvalid duration format! Use e.g. 1h, 30m, 60s, or milliseconds.");
            return true;
        }

        Vault.getBoosterAPI().registerGlobalBooster(currency, mult, durationMs);
        Bukkit.broadcastMessage("§a§l🚀 EVENT BOOSTER §f" + mult + "x multiplier activated for §e" + currency.toUpperCase() + "§f!");
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String label, String[] args) {
        return Collections.emptyList();
    }
}
