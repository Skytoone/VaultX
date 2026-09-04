package net.milkbowl.vault.persistence.repository;

import net.milkbowl.vault.economy.VaultAuctionAPI;
import net.milkbowl.vault.economy.VaultCreditAPI;
import net.milkbowl.vault.economy.VaultStakingAPI;
import net.milkbowl.vault.persistence.DatabaseConnectionManager;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public class StakingAuctionRepository {

    private final CryptoRepository cryptoRepository;
    private final AuctionRepository auctionRepository;
    private final StakingRepository stakingRepository;
    private final CreditRepository creditRepository;

    public StakingAuctionRepository(DatabaseConnectionManager dbManager) {
        this.cryptoRepository = new CryptoRepository(dbManager);
        this.auctionRepository = new AuctionRepository(dbManager);
        this.stakingRepository = new StakingRepository(dbManager);
        this.creditRepository = new CreditRepository(dbManager);
    }

    public void saveCryptoWallet(UUID uuid, String cryptoName, double amount) {
        cryptoRepository.saveCryptoWallet(uuid, cryptoName, amount);
    }

    public void deleteCryptoWallet(UUID uuid, String cryptoName) {
        cryptoRepository.deleteCryptoWallet(uuid, cryptoName);
    }

    public Map<UUID, Map<String, Double>> loadAllCryptoWallets() {
        return cryptoRepository.loadAllCryptoWallets();
    }

    public Map<String, Double> loadCryptoWalletForPlayer(UUID uuid) {
        return cryptoRepository.loadCryptoWalletForPlayer(uuid);
    }

    public void saveAuction(VaultAuctionAPI.AuctionListing listing) {
        auctionRepository.saveAuction(listing);
    }

    public void deleteAuction(String auctionId) {
        auctionRepository.deleteAuction(auctionId);
    }

    public Map<String, VaultAuctionAPI.AuctionListing> loadAllAuctions() {
        return auctionRepository.loadAllAuctions();
    }

    public void savePendingAuctionItem(UUID uuid, ItemStack item) {
        auctionRepository.savePendingAuctionItem(uuid, item);
    }

    public Map<UUID, List<ItemStack>> loadAllPendingAuctionItems() {
        return auctionRepository.loadAllPendingAuctionItems();
    }

    public void deletePendingAuctionItems(UUID uuid) {
        auctionRepository.deletePendingAuctionItems(uuid);
    }

    public void saveStake(VaultStakingAPI.StakeDeposit deposit) {
        stakingRepository.saveStake(deposit);
    }

    public Map<String, VaultStakingAPI.StakeDeposit> loadAllStakes() {
        return stakingRepository.loadAllStakes();
    }

    public void saveCreditAccount(VaultCreditAPI.CreditAccount account) {
        creditRepository.saveCreditAccount(account);
    }

    public Map<UUID, Map<String, VaultCreditAPI.CreditAccount>> loadAllCreditAccounts() {
        return creditRepository.loadAllCreditAccounts();
    }

    public Map<String, VaultCreditAPI.CreditAccount> loadCreditAccountsForPlayer(UUID uuid) {
        return creditRepository.loadCreditAccountsForPlayer(uuid);
    }
}
