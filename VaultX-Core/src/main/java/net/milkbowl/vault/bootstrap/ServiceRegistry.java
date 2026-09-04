package net.milkbowl.vault.bootstrap;

import net.milkbowl.vault.discord.DiscordManager;
import net.milkbowl.vault.economy.BankBackupManager;
import net.milkbowl.vault.economy.BlackMarketManager;
import net.milkbowl.vault.economy.CentralBankManager;
import net.milkbowl.vault.economy.CurrencyExchangeAPI;
import net.milkbowl.vault.economy.DynamicPricingManager;
import net.milkbowl.vault.economy.ExchangeRateManager;
import net.milkbowl.vault.economy.LoanManager;
import net.milkbowl.vault.economy.MailboxManager;
import net.milkbowl.vault.economy.OptimizedEconomy;
import net.milkbowl.vault.economy.PaydayManager;
import net.milkbowl.vault.economy.StockExchangeManager;
import net.milkbowl.vault.economy.SubscriptionManager;
import net.milkbowl.vault.integration.TownyLandsFactionsHook;
import net.milkbowl.vault.redis.EscrowManager;
import net.milkbowl.vault.redis.LocalFailoverManager;
import net.milkbowl.vault.security.TransactionFirewall;
import net.milkbowl.vault.util.VaultXGUI;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Service registry holding references to all Vault component managers and APIs.
 */
public class ServiceRegistry {

    private TransactionFirewall firewall;
    private LocalFailoverManager failoverManager;
    private EscrowManager escrowManager;
    private ExchangeRateManager exchangeRateManager;
    private CentralBankManager centralBankManager;
    private MailboxManager mailboxManager;
    private VaultXGUI vaultXGUI;
    private StockExchangeManager stockExchangeManager;
    private LoanManager loanManager;
    private PaydayManager paydayManager;
    private SubscriptionManager subscriptionManager;
    private DynamicPricingManager dynamicPricingManager;
    private DiscordManager discordManager;
    private TownyLandsFactionsHook townyLandsFactionsHook;
    private BlackMarketManager blackMarketManager;
    private BankBackupManager bankBackupManager;

    private final List<OptimizedEconomy> wrappedEconomies = Collections.synchronizedList(new ArrayList<>());

    public TransactionFirewall getFirewall() {
        return firewall;
    }

    public void setFirewall(TransactionFirewall firewall) {
        this.firewall = firewall;
    }

    public LocalFailoverManager getFailoverManager() {
        return failoverManager;
    }

    public void setFailoverManager(LocalFailoverManager failoverManager) {
        this.failoverManager = failoverManager;
    }

    public EscrowManager getEscrowManager() {
        return escrowManager;
    }

    public void setEscrowManager(EscrowManager escrowManager) {
        this.escrowManager = escrowManager;
    }

    public CurrencyExchangeAPI getCurrencyExchangeAPI() {
        return exchangeRateManager;
    }

    public ExchangeRateManager getExchangeRateManager() {
        return exchangeRateManager;
    }

    public void setExchangeRateManager(ExchangeRateManager exchangeRateManager) {
        this.exchangeRateManager = exchangeRateManager;
    }

    public CentralBankManager getCentralBankManager() {
        return centralBankManager;
    }

    public void setCentralBankManager(CentralBankManager centralBankManager) {
        this.centralBankManager = centralBankManager;
    }

    public MailboxManager getMailboxManager() {
        return mailboxManager;
    }

    public void setMailboxManager(MailboxManager mailboxManager) {
        this.mailboxManager = mailboxManager;
    }

    public VaultXGUI getVaultXGUI() {
        return vaultXGUI;
    }

    public void setVaultXGUI(VaultXGUI vaultXGUI) {
        this.vaultXGUI = vaultXGUI;
    }

    public StockExchangeManager getStockExchangeManager() {
        return stockExchangeManager;
    }

    public void setStockExchangeManager(StockExchangeManager stockExchangeManager) {
        this.stockExchangeManager = stockExchangeManager;
    }

    public LoanManager getLoanManager() {
        return loanManager;
    }

    public void setLoanManager(LoanManager loanManager) {
        this.loanManager = loanManager;
    }

    public PaydayManager getPaydayManager() {
        return paydayManager;
    }

    public void setPaydayManager(PaydayManager paydayManager) {
        this.paydayManager = paydayManager;
    }

    public SubscriptionManager getSubscriptionManager() {
        return subscriptionManager;
    }

    public void setSubscriptionManager(SubscriptionManager subscriptionManager) {
        this.subscriptionManager = subscriptionManager;
    }

    public DynamicPricingManager getDynamicPricingManager() {
        return dynamicPricingManager;
    }

    public void setDynamicPricingManager(DynamicPricingManager dynamicPricingManager) {
        this.dynamicPricingManager = dynamicPricingManager;
    }

    public DiscordManager getDiscordManager() {
        return discordManager;
    }

    public void setDiscordManager(DiscordManager discordManager) {
        this.discordManager = discordManager;
    }

    public TownyLandsFactionsHook getTownyLandsFactionsHook() {
        return townyLandsFactionsHook;
    }

    public void setTownyLandsFactionsHook(TownyLandsFactionsHook townyLandsFactionsHook) {
        this.townyLandsFactionsHook = townyLandsFactionsHook;
    }

    public BlackMarketManager getBlackMarketManager() {
        return blackMarketManager;
    }

    public void setBlackMarketManager(BlackMarketManager blackMarketManager) {
        this.blackMarketManager = blackMarketManager;
    }

    public BankBackupManager getBankBackupManager() {
        return bankBackupManager;
    }

    public void setBankBackupManager(BankBackupManager bankBackupManager) {
        this.bankBackupManager = bankBackupManager;
    }

    public List<OptimizedEconomy> getWrappedEconomies() {
        return wrappedEconomies;
    }

    public void shutdownAll(org.bukkit.plugin.Plugin plugin) {
        for (OptimizedEconomy econ : wrappedEconomies) {
            try {
                econ.shutdown();
            } catch (Exception e) {
                plugin.getLogger().warning("[Vault] Exception shutting down OptimizedEconomy: " + e.getMessage());
            }
        }
        wrappedEconomies.clear();

        if (vaultXGUI != null) {
            try { vaultXGUI.close(); } catch (Exception e) { plugin.getLogger().warning("[Vault] Exception closing VaultXGUI: " + e.getMessage()); }
        }

        net.milkbowl.vault.util.UUIDCache.cleanup();
        plugin.getServer().getServicesManager().unregisterAll(plugin);
        net.milkbowl.vault.util.FoliaScheduler.cancelTasks(plugin);

        if (net.milkbowl.vault.redis.VaultRedisManager.getInstance() != null) {
            net.milkbowl.vault.redis.VaultRedisManager.getInstance().close();
        }
        if (net.milkbowl.vault.redis.VaultPostgresManager.getInstance() != null) {
            net.milkbowl.vault.redis.VaultPostgresManager.getInstance().close();
        }
        if (escrowManager != null) escrowManager.close();
        if (exchangeRateManager != null) exchangeRateManager.close();
        if (subscriptionManager != null) subscriptionManager.close();
        if (dynamicPricingManager != null) dynamicPricingManager.close();
        if (discordManager != null) discordManager.close();
        if (centralBankManager != null) centralBankManager.close();
        if (paydayManager != null) paydayManager.close();
        if (blackMarketManager != null) blackMarketManager.close();
        if (stockExchangeManager != null) stockExchangeManager.close();
        if (loanManager != null) loanManager.close();
        if (bankBackupManager != null) bankBackupManager.close();
        if (failoverManager != null) failoverManager.close();
        if (firewall != null) {
            try { firewall.close(); } catch (Exception e) { plugin.getLogger().warning("[Vault] Exception closing TransactionFirewall: " + e.getMessage()); }
        }

        net.milkbowl.vault.listener.PhysicalEconomyListener.cleanup();

        firewall = null;
        failoverManager = null;
        escrowManager = null;
        exchangeRateManager = null;
        centralBankManager = null;
        mailboxManager = null;
        vaultXGUI = null;
        stockExchangeManager = null;
        loanManager = null;
        paydayManager = null;
        subscriptionManager = null;
        dynamicPricingManager = null;
        discordManager = null;
        townyLandsFactionsHook = null;
        blackMarketManager = null;
        bankBackupManager = null;
    }
}
