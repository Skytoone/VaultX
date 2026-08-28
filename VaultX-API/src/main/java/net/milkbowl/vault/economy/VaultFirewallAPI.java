package net.milkbowl.vault.economy;

import org.bukkit.OfflinePlayer;

/**
 * Security & Anti-Exploit Firewall API.
 */
public interface VaultFirewallAPI {

    boolean isFrozen(OfflinePlayer player);

    void freezePlayer(OfflinePlayer player, String reason);

    void unfreezePlayer(OfflinePlayer player);

    boolean checkTransaction(OfflinePlayer player, double amount, String type, double currentBalance);
}
