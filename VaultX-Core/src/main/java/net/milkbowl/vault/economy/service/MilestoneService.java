package net.milkbowl.vault.economy.service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.Plugin;

import net.milkbowl.vault.economy.VaultMilestoneAPI;
import net.milkbowl.vault.economy.VaultMilestoneAPI.Milestone;
import net.milkbowl.vault.economy.events.VaultMilestoneReachedEvent;

/**
 * Service managing registered wealth milestones and player milestone progress tracking.
 */
public class MilestoneService implements VaultMilestoneAPI {

    private final Plugin plugin;
    private final ExecutorService asyncExecutor;
    private final Map<String, Milestone> registeredMilestones = new ConcurrentHashMap<>();

    public MilestoneService(Plugin plugin, ExecutorService asyncExecutor) {
        this.plugin = plugin;
        this.asyncExecutor = asyncExecutor;
    }

    public Map<String, Milestone> getRegisteredMilestones() {
        return registeredMilestones;
    }

    public void registerMilestone(Milestone milestone) {
        if (milestone != null && plugin.getConfig().getBoolean("milestones.enabled", true)) {
            registeredMilestones.put(milestone.milestoneId(), milestone);
        }
    }

    public CompletableFuture<List<String>> getPlayerMilestonesAsync(OfflinePlayer player) {
        return CompletableFuture.completedFuture(List.of());
    }

    public CompletableFuture<Boolean> hasReachedMilestoneAsync(OfflinePlayer player, String milestoneId, Function<OfflinePlayer, Double> balanceSupplier) {
        if (!plugin.getConfig().getBoolean("milestones.enabled", true)) {
            return CompletableFuture.completedFuture(false);
        }
        Milestone m = registeredMilestones.get(milestoneId);
        if (m == null)
            return CompletableFuture.completedFuture(false);
        return CompletableFuture.supplyAsync(() -> {
            boolean reached = balanceSupplier.apply(player) >= m.requiredBalance();
            if (reached) {
                boolean broadcast = plugin.getConfig().getBoolean("milestones.broadcast-achievements", true);
                if (broadcast && player.isOnline() && player.getPlayer() != null) {
                    String msg = net.milkbowl.vault.Vault
                            .getMessage("milestones.reached",
                                    "&a&l[Achievement] &fPlayer &e%player% &freached milestone &e%name%!")
                            .replace("%player%", player.getName() != null ? player.getName() : "Player")
                            .replace("%name%", m.milestoneId());
                    Bukkit.broadcastMessage(msg);
                }
                Bukkit.getPluginManager()
                        .callEvent(new VaultMilestoneReachedEvent(player, m.currency(), m.requiredBalance(), ""));
            }
            return reached;
        }, asyncExecutor);
    }

    @Override
    public CompletableFuture<Boolean> hasReachedMilestoneAsync(OfflinePlayer player, String milestoneId) {
        return hasReachedMilestoneAsync(player, milestoneId, p -> {
            var registry = net.milkbowl.vault.Vault.getServiceRegistry();
            var econ = (registry != null && !registry.getWrappedEconomies().isEmpty()) ? registry.getWrappedEconomies().get(0) : null;
            return econ != null ? econ.getBalance(p) : 0.0;
        });
    }

    public void clear() {
        registeredMilestones.clear();
    }
}
