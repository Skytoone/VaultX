package net.milkbowl.vault.command.subcommands;

import net.milkbowl.vault.Vault;
import net.milkbowl.vault.command.SubCommand;
import net.milkbowl.vault.command.VaultXCommand;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;

public class PaydaySubCommand implements SubCommand {

    private final VaultXCommand parent;

    public PaydaySubCommand(VaultXCommand parent) {
        this.parent = parent;
    }

    @Override
    public String getName() {
        return "payday";
    }

    @Override
    public List<String> getAliases() {
        return Collections.singletonList("salary");
    }

    @Override
    public String getPermission() {
        return "vault.command.payday";
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(parent.getMsg("general.only-players", "§cOnly players can execute this command."));
            return true;
        }

        if (!parent.getPlugin().getConfig().getBoolean("paydays.enabled", true)) {
            player.sendMessage(parent.getMsg("payday.info-disabled", "§cThe salary system is disabled."));
            return true;
        }

        net.milkbowl.vault.economy.PaydayManager pm = Vault.getPaydayManager();
        if (pm == null) {
            player.sendMessage(parent.getMsg("payday.info-disabled", "§cThe salary system is disabled."));
            return true;
        }

        net.milkbowl.vault.economy.PaydayManager.PaydayBreakdown breakdown = pm.getPlayerBreakdown(player);

        long remainingMs = breakdown.nextPaydayTime - System.currentTimeMillis();
        long diffSeconds = Math.max(0, remainingMs / 1000 % 60);
        long diffMinutes = Math.max(0, remainingMs / (60 * 1000) % 60);
        long diffHours = Math.max(0, remainingMs / (60 * 60 * 1000));

        String timeStr = String.format("%02dh %02dm %02ds", diffHours, diffMinutes, diffSeconds);
        if (diffHours == 0) {
            timeStr = String.format("%02dm %02ds", diffMinutes, diffSeconds);
        }

        Economy econ = parent.getEconomy();
        String grossStr = econ != null ? econ.format(breakdown.grossAmount) : String.format("%.2f", breakdown.grossAmount);
        String taxStr = econ != null ? econ.format(breakdown.taxAmount) : String.format("%.2f", breakdown.taxAmount);
        String garnishStr = econ != null ? econ.format(breakdown.garnishedAmount) : String.format("%.2f", breakdown.garnishedAmount);
        String netStr = econ != null ? econ.format(breakdown.netAmount) : String.format("%.2f", breakdown.netAmount);
        String remainingDebtStr = econ != null ? econ.format(breakdown.debt) : String.format("%.2f", breakdown.debt);

        player.sendMessage(parent.getMsg("payday.info-header", "§b§l=== Your Salary & Payday Details ==="));
        player.sendMessage(parent.getMsg("payday.info-next", "  §fNext payday in: §e%time%").replace("%time%", timeStr));
        player.sendMessage(parent.getMsg("payday.info-group", "  §fSalary Group: §e%group%").replace("%group%", breakdown.group.toUpperCase()));
        player.sendMessage(parent.getMsg("payday.info-gross", "  §fGross Salary: §e%gross%").replace("%gross%", grossStr));
        if (breakdown.taxAmount > 0.0) {
            player.sendMessage(parent.getMsg("payday.info-tax", "  §fIncome Tax: §c-%amount% §7(%percent%%)")
                    .replace("%amount%", taxStr)
                    .replace("%percent%", String.format("%.1f", breakdown.taxPercent)));
        }
        if (breakdown.garnishedAmount > 0.0) {
            player.sendMessage(parent.getMsg("payday.info-garnishment", "  §fDebt Garnishment: §c-%amount% §7(Remaining: %remaining%)")
                    .replace("%amount%", garnishStr)
                    .replace("%remaining%", remainingDebtStr));
        }
        player.sendMessage(parent.getMsg("payday.info-net", "  §fEstimated Net Payout: §a%amount%").replace("%amount%", netStr));
        player.sendMessage(parent.getMsg("payday.info-footer", "§b§l======================================"));
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String label, String[] args) {
        return Collections.emptyList();
    }
}
