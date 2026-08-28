package net.milkbowl.vault.economy;

import org.bukkit.OfflinePlayer;

import java.util.concurrent.CompletableFuture;

/**
 * Interface for digital token wallets, crypto balances, and token mining in VaultX.
 */
public interface VaultCryptoAPI {

    record CryptoWallet(String walletAddress, String cryptoName, double balance) {}

    /**
     * Retrieves or generates a digital crypto wallet for a player.
     *
     * @param player Target player
     * @param cryptoName Token/Crypto currency name (e.g. "bitcoin", "vaultcoin")
     * @return Future containing CryptoWallet
     */
    CompletableFuture<CryptoWallet> getWalletAsync(OfflinePlayer player, String cryptoName);

    /**
     * Mines digital tokens for a player.
     *
     * @param player Target player
     * @param cryptoName Token name
     * @param amount Mined token amount
     * @return Future containing EconomyResponse
     */
    CompletableFuture<EconomyResponse> mineTokensAsync(OfflinePlayer player, String cryptoName, double amount);

    /**
     * Transfers crypto tokens between wallet addresses.
     *
     * @param fromAddress Sender wallet address
     * @param toAddress Recipient wallet address
     * @param cryptoName Token name
     * @param amount Amount to transfer
     * @return Future containing true if successful
     */
    CompletableFuture<Boolean> transferCryptoAsync(String fromAddress, String toAddress, String cryptoName, double amount);
}
