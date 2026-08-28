package net.milkbowl.vault.economy;

import org.bukkit.OfflinePlayer;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Interface for tracking and rewarding player financial balance milestones and achievements in VaultX.
 */
public interface VaultMilestoneAPI {

    record Milestone(String milestoneId, String displayName, String currency, double requiredBalance) {}

    /**
     * Registers a financial milestone.
     *
     * @param milestone Milestone object
     */
    void registerMilestone(Milestone milestone);

    /**
     * Queries all milestones reached by a player.
     *
     * @param player Target player
     * @return Future containing list of reached Milestone IDs
     */
    CompletableFuture<List<String>> getPlayerMilestonesAsync(OfflinePlayer player);

    /**
     * Checks if a player has reached a specific financial milestone.
     *
     * @param player Target player
     * @param milestoneId Milestone identifier
     * @return Future containing true if reached
     */
    CompletableFuture<Boolean> hasReachedMilestoneAsync(OfflinePlayer player, String milestoneId);
}
