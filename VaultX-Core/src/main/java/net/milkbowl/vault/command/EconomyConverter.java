package net.milkbowl.vault.command;

import java.util.Collection;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.RegisteredServiceProvider;

import net.milkbowl.vault.Vault;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import net.milkbowl.vault.economy.MultiCurrencyEconomy;
import net.milkbowl.vault.util.FoliaScheduler;

public class EconomyConverter {

    public static void convertCommand(Vault plugin, CommandSender sender, String[] args) {
        Collection<RegisteredServiceProvider<Economy>> econs = plugin.getServer().getServicesManager()
                .getRegistrations(Economy.class);
        if (econs == null || econs.size() < 2) {
            sender.sendMessage(plugin.getMessage("admin-core.convert.no-economies", "§cYou must have at least 2 economies loaded to convert."));
            return;
        } else if (args.length != 2) {
            sender.sendMessage(plugin.getMessage("admin-core.convert.usage", "§cUsage: /vault-convert [economy1] [economy2]"));
            return;
        }

        Economy econ1 = null;
        Economy econ2 = null;
        StringBuilder economies = new StringBuilder();
        for (RegisteredServiceProvider<Economy> econ : econs) {
            String econName = econ.getProvider().getName().replace(" ", "");
            if (econName.equalsIgnoreCase(args[0])) {
                econ1 = econ.getProvider();
            } else if (econName.equalsIgnoreCase(args[1])) {
                econ2 = econ.getProvider();
            }
            if (economies.length() > 0) {
                economies.append(", ");
            }
            economies.append(econName);
        }

        if (econ1 == null || econ2 == null) {
            sender.sendMessage(plugin.getMessage("admin-core.convert.not-found", "§cEconomy not found! Loaded economies: %economies%")
                    .replace("%economies%", economies.toString()));
            return;
        }

        sender.sendMessage(plugin.getMessage("admin-core.convert.starting", "§eStarting conversion process... (Running in safe non-blocking batches)"));

        final Economy finalEcon1 = econ1;
        final Economy finalEcon2 = econ2;

        FoliaScheduler.runAsync(plugin, () -> {
            final OfflinePlayer[] players = Bukkit.getOfflinePlayers();
            final int total = players != null ? players.length : 0;
            final int batchSize = 50;
            final java.util.concurrent.atomic.AtomicInteger processedCount = new java.util.concurrent.atomic.AtomicInteger(0);

            class BatchProcessor implements Runnable {
                int index = 0;

                @Override
                public void run() {
                    int end = Math.min(index + batchSize, total);
                    for (int i = index; i < end; i++) {
                        OfflinePlayer op = players[i];
                        if (op != null) {
                            try {
                                if (finalEcon1.hasAccount(op)) {
                                    if (!finalEcon2.hasAccount(op)) {
                                        finalEcon2.createPlayerAccount(op);
                                    }

                                    boolean overallSuccess = true;
                                    if (finalEcon1 instanceof MultiCurrencyEconomy mc1 && finalEcon2 instanceof MultiCurrencyEconomy mc2) {
                                        java.util.List<String> currencies = mc1.getSupportedCurrencies();
                                        if (currencies != null && !currencies.isEmpty()) {
                                            for (String cur : currencies) {
                                                double diff = mc1.getCurrencyBalance(op, cur) - mc2.getCurrencyBalance(op, cur);
                                                if (diff > 0) {
                                                    EconomyResponse res = mc2.depositCurrencyPlayer(op, cur, diff);
                                                    if (!res.transactionSuccess()) overallSuccess = false;
                                                } else if (diff < 0) {
                                                    EconomyResponse res = mc2.withdrawCurrencyPlayer(op, cur, -diff);
                                                    if (!res.transactionSuccess()) overallSuccess = false;
                                                }
                                            }
                                        } else {
                                            double diff = finalEcon1.getBalance(op) - finalEcon2.getBalance(op);
                                            if (diff > 0) {
                                                EconomyResponse res = finalEcon2.depositPlayer(op, diff);
                                                if (!res.transactionSuccess()) overallSuccess = false;
                                            } else if (diff < 0) {
                                                EconomyResponse res = finalEcon2.withdrawPlayer(op, -diff);
                                                if (!res.transactionSuccess()) overallSuccess = false;
                                            }
                                        }
                                    } else {
                                        double diff = finalEcon1.getBalance(op) - finalEcon2.getBalance(op);
                                        if (diff > 0) {
                                            EconomyResponse res = finalEcon2.depositPlayer(op, diff);
                                            if (!res.transactionSuccess()) overallSuccess = false;
                                        } else if (diff < 0) {
                                            EconomyResponse res = finalEcon2.withdrawPlayer(op, -diff);
                                            if (!res.transactionSuccess()) overallSuccess = false;
                                        }
                                    }

                                    if (overallSuccess) {
                                        processedCount.incrementAndGet();
                                    }
                                }
                            } catch (Exception e) {
                                plugin.getLogger().warning("[Vault Convert] Error converting player " + (op.getName() != null ? op.getName() : op.getUniqueId()) + ": " + e.getMessage());
                            }
                        }
                    }
                    index = end;
                    if (index < total) {
                        FoliaScheduler.runLater(plugin, this, 1L);
                    } else {
                        sender.sendMessage(plugin.getMessage("admin-core.convert.success", "§a§l✔ §aConversion complete! Processed %processed% players.")
                                .replace("%processed%", String.valueOf(processedCount.get())));
                    }
                }
            }

            FoliaScheduler.runSync(plugin, new BatchProcessor());
        });
    }
}
