package net.milkbowl.vault.economy;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

/**
 * Black Market & Money Laundering API.
 */
public interface VaultBlackMarketAPI {

    class LaunderingResult {
        private final boolean success;
        private final boolean seized;
        private final double dirtyLaundered;
        private final double cleanReceived;
        private final double feePaid;

        public LaunderingResult(boolean success, boolean seized, double dirtyLaundered, double cleanReceived, double feePaid) {
            this.success = success;
            this.seized = seized;
            this.dirtyLaundered = dirtyLaundered;
            this.cleanReceived = cleanReceived;
            this.feePaid = feePaid;
        }

        public boolean isSuccess() {
            return success;
        }

        public boolean isSeized() {
            return seized;
        }

        public double getDirtyLaundered() {
            return dirtyLaundered;
        }

        public double getCleanReceived() {
            return cleanReceived;
        }

        public double getFeePaid() {
            return feePaid;
        }
    }

    double getDirtyBalance(OfflinePlayer player);

    void setDirtyBalance(OfflinePlayer player, double amount);

    void addDirtyMoney(OfflinePlayer player, double amount);

    LaunderingResult launder(Player player, double dirtyAmount);
}
