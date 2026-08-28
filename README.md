# ⚡ VaultX & VaultX-API

## 🌟 Core Features

- **⚡ Virtual Threading & O(1) Memory Caching**: Zero main-thread blocking, powered by Java 21 Virtual Threads and instant RAM cache.
- **🌐 Multi-Currency Support**: Infinite custom currencies (`dollars`, `gems`, `tokens`, `coins`) with dedicated symbols and exchange rates.
- **💳 Physical Bank Checks**: Issue, trade, and redeem physical check items in player inventories (`VaultCheckAPI`).
- **🏦 Bank Loans & Credit Scoring**: Dynamic credit score calculation (300-850), automated loans, and repayment schedules (`VaultLoanAPI`).
- **📈 Inflation & Wealth Tax Control**: Global inflation multipliers, transaction taxes, and progressive wealth tax regulation (`VaultInflationAPI`).
- **🏆 Milestones & Achievements**: Automatic balance milestone triggers and custom rewards (`VaultMilestoneAPI`).
- **🧧 Digital Tokens & Crypto Wallets**: Pseudo-decentralized wallets and token mining (`VaultCryptoAPI`).
- **🛡️ Memory Leak Proof**: Strict cache invalidation on player quit and complete thread pool shutdown on plugin disable.

---

## 📦 Developer Integration (Maven & Gradle)

You can easily add `VaultX-API` to your project using **JitPack**.

### Maven (`pom.xml`)

Add the JitPack repository and the `VaultX-API` dependency:

```xml
<repositories>
    <repository>
        <id>jitpack.io</id>
        <url>https://jitpack.io</url>
    </repository>
</repositories>

<dependencies>
    <!-- VaultX API Dependency -->
    <dependency>
        <groupId>com.github.Skytoone</groupId>
        <artifactId>VaultX-API</artifactId>
        <version>1.1.2</version> <!-- Replace with desired tag or release version -->
        <scope>provided</scope>
    </dependency>
</dependencies>
```

### Gradle (`build.gradle`)

```groovy
repositories {
    mavenCentral()
    maven { url 'https://jitpack.io' }
}

dependencies {
    compileOnly 'com.github.Skytoone:VaultX-API:1.1.2'
}
```

---

## 💻 API Usage Examples

### 1. Accessing Multi-Currency Economy

```java
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.MultiCurrencyEconomy;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.RegisteredServiceProvider;

public class EconomyExample {

    public void checkCustomBalance(Player player) {
        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (rsp == null) return;

        Economy econ = rsp.getProvider();

        // Check if the provider supports VaultX MultiCurrencyEconomy
        if (econ instanceof MultiCurrencyEconomy multiEcon) {
            double gems = multiEcon.getCurrencyBalance(player, "gems");
            player.sendMessage("You have " + gems + " gems!");

            // Deposit custom currency
            multiEcon.depositCurrencyPlayer(player, "gems", 50.0);
        } else {
            // Standard Vault fallback
            double balance = econ.getBalance(player);
            player.sendMessage("Your balance is $" + balance);
        }
    }
}
```

### 2. Listening to VaultX Events

```java
import net.milkbowl.vault.economy.events.VaultTransactionEvent;
import net.milkbowl.vault.economy.events.VaultInflationUpdateEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public class EconomyEventListener implements Listener {

    @EventHandler
    public void onTransaction(VaultTransactionEvent event) {
        Bukkit.getLogger().info("Transaction: " + event.getPlayerName() 
            + " | Currency: " + event.getCurrency()
            + " | Amount: " + event.getAmount() 
            + " | Target: " + event.getTargetName()
            + " | New Balance: " + event.getNewBalance()
            + " | Type: " + event.getType());
    }

    @EventHandler
    public void onBankTransaction(VaultBankTransactionEvent event) {
        Bukkit.getLogger().info("Bank Transaction: " + event.getBankName()
            + " | Amount: " + event.getAmount()
            + " | New Balance: " + event.getNewBankBalance()
            + " | Type: " + event.getType());
    }

    @EventHandler
    public void onInflationUpdate(VaultInflationUpdateEvent event) {
        Bukkit.getLogger().info("New inflation multiplier for " + event.getCurrency() + ": " + event.getNewMultiplier());
    }
}
```

### 3. Using 1-Line `VaultXHook` Helper

```java
import fr.skynex.vaultx.util.VaultXHook;
import org.bukkit.entity.Player;

public class QuickHookExample {

    public void rewardPlayer(Player player) {
        // Fast 1-line access to VaultX multi-currency
        VaultXHook.getMultiCurrencyEconomy().ifPresent(econ -> {
            econ.depositCurrencyPlayer(player, "tokens", 10.0);
        });
    }
}
```

### 4. Leaderboard & Top Balances API

```java
import fr.skynex.vaultx.util.VaultXHook;

public class LeaderboardExample {

    public void printTop10Gems() {
        VaultXHook.getLeaderboardAPI().ifPresent(api -> {
            api.getTopBalancesAsync("gems", 10).thenAccept(entries -> {
                for (var entry : entries) {
                    System.out.println("#" + entry.rank() + " " + entry.playerName() + ": " + entry.balance() + " gems");
                }
            });
        });
    }
}
```

### 5. Atomic Batch Transactions with Automatic Rollback

```java
import net.milkbowl.vault.economy.VaultBatchTransactionAPI.BatchOperation;
import net.milkbowl.vault.economy.VaultBatchTransactionAPI.OperationType;
import fr.skynex.vaultx.util.VaultXHook;
import java.util.List;

public class TradeSystem {

    public void executePlayerTrade(Player seller, Player buyer, double amount) {
        VaultXHook.getBatchAPI().ifPresent(api -> {
            List<BatchOperation> ops = List.of(
                new BatchOperation(buyer, "dollars", amount, OperationType.WITHDRAW),
                new BatchOperation(seller, "dollars", amount, OperationType.DEPOSIT)
            );
            api.executeAtomicBatchAsync(ops).thenAccept(result -> {
                if (result.success()) {
                    seller.sendMessage("Trade completed successfully!");
                } else {
                    buyer.sendMessage("Trade failed: " + result.errorMessage());
                }
            });
        });
    }
}
```

### 6. Cancelling Transactions (`VaultPreTransactionEvent`)

```java
import net.milkbowl.vault.economy.events.VaultPreTransactionEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

public class CombatLoggerProtection implements Listener {

    @EventHandler
    public void onPreTransaction(VaultPreTransactionEvent event) {
        if (isInCombat(event.getPlayer())) {
            event.setCancelled(true);
            event.setCancelReason("You cannot execute financial transactions while in combat!");
        }
    }
}
```

### 7. Anti-Duplication Thread-Safe Lock (`VaultLockAPI`)

```java
import fr.skynex.vaultx.util.VaultXHook;
import org.bukkit.entity.Player;

public class CasinoGame {

    public void spinRoulette(Player player, double betAmount) {
        VaultXHook.getLockAPI().ifPresent(lock -> {
            lock.executeWithLock(player, () -> {
                // Guaranteed 100% thread-safe against click spam duplication
                VaultXHook.getMultiCurrencyEconomy().ifPresent(econ -> {
                    if (econ.has(player, betAmount)) {
                        econ.withdrawPlayer(player, betAmount);
                        // Process roulette outcome safely...
                    }
                });
            });
        });
    }
}
```

### 8. Unit Testing Without Spigot Server (`VaultXTestKit`)

```java
import net.milkbowl.vault.economy.MultiCurrencyEconomy;
import fr.skynex.vaultx.util.VaultXTestKit;
import org.junit.jupiter.api.Test;

public class PluginUnitTest {

    @Test
    public void testShopPurchase() {
        // Mock economy with $1000 starting balance
        MultiCurrencyEconomy mockEcon = VaultXTestKit.createMockEconomy(1000.0);
        
        // Run tests directly in JUnit without launching Spigot!
        mockEcon.withdrawPlayer("PlayerName", 50.0);
    }
}
```

### 9. Real-Time Currency Exchange API (`CurrencyExchangeAPI`)

```java
import fr.skynex.vaultx.util.VaultXHook;

public class ForexSystem {

    public double convertGemsToDollars(double gemsAmount) {
        return VaultXHook.getExchangeAPI()
            .map(api -> api.convert("gems", "dollars", gemsAmount))
            .orElse(gemsAmount);
    }
}
```

### 10. Recurring Subscriptions & Taxes (`VaultSubscriptionAPI`)

```java
import fr.skynex.vaultx.util.VaultXHook;
import org.bukkit.entity.Player;

public class TownyTaxSystem {

    public void registerPlotTax(Player player, String plotId, double taxAmount) {
        VaultXHook.getSubscriptionAPI().ifPresent(api -> {
            // Deduct $50 every 7 days (604,800,000 ms)
            api.registerSubscriptionAsync(player, "tax_" + plotId, "dollars", taxAmount, 604800000L);
        });
    }
}
```

### 11. Offline Player Payouts (`VaultMailboxAPI`)

```java
import fr.skynex.vaultx.util.VaultXHook;
import java.util.UUID;

public class AuctionHousePayout {

    public void rewardOfflineSeller(UUID sellerUuid, double profit) {
        VaultXHook.getMailboxAPI().ifPresent(api -> {
            api.sendOfflinePaymentAsync(sellerUuid, "dollars", profit, "Auction House Sale #512");
        });
    }
}
```

### 12. Payout Boosters & Event Multipliers (`VaultBoosterAPI`)

```java
import fr.skynex.vaultx.util.VaultXHook;

public class EventManager {

    public void startDoubleGemsEvent() {
        VaultXHook.getBoosterAPI().ifPresent(api -> {
            // Activate 2.0x multiplier for gems for 2 hours (7,200,000 ms)
            api.registerGlobalBooster("gems", 2.0, 7200000L);
        });
    }
}
```

### 13. Economy Server-Wide Analytics (`VaultAnalyticsAPI`)

```java
import fr.skynex.vaultx.util.VaultXHook;

public class AdminStats {

    public void printServerMoneySupply() {
        VaultXHook.getAnalyticsAPI().ifPresent(api -> {
            api.getTotalSupplyAsync("dollars").thenAccept(total -> {
                System.out.println("Total dollars in circulation: $" + total);
            });
        });
    }
}
```

### 14. Custom Currency Registration (`VaultCurrencyRegistry`)

```java
import fr.skynex.vaultx.util.VaultXHook;

public class CustomAddon {

    public void registerCryptoCurrency() {
        VaultXHook.getCurrencyRegistry().ifPresent(registry -> {
            registry.registerCurrency("crypto", new MyCryptoProvider());
        });
    }
}
```

### 15. Transaction History Audit (`VaultAuditAPI`)

```java
import fr.skynex.vaultx.util.VaultXHook;
import org.bukkit.entity.Player;

public class SecurityAudit {

    public void checkPlayerLogs(Player player) {
        VaultXHook.getAuditAPI().ifPresent(api -> {
            api.getPlayerTransactionHistoryAsync(player, 20).thenAccept(logs -> {
                logs.forEach(log -> System.out.println(log.timestamp() + " | " + log.type() + " | " + log.amount()));
            });
        });
    }
}
```

### 16. Escrow Transactions API (`VaultEscrowAPI`)

```java
import fr.skynex.vaultx.util.VaultXHook;
import org.bukkit.entity.Player;

public class EscrowTradeExample {

    public void startTradeEscrow(Player seller, Player buyer, double amount) {
        VaultXHook.getEscrowAPI().ifPresent(api -> {
            api.startEscrow(buyer, seller, amount, "default", 300).thenAccept(result -> {
                if (result.success) {
                    buyer.sendMessage("Escrow created! ID: " + result.escrowId);
                }
            });
        });
    }
}
```

### 17. Security Firewall & Account Freeze API (`VaultFirewallAPI`)

```java
import fr.skynex.vaultx.util.VaultXHook;
import org.bukkit.entity.Player;

public class AntiCheatProtection {

    public void freezeSuspiciousAccount(Player player, String reason) {
        VaultXHook.getFirewallAPI().ifPresent(api -> {
            if (!api.isFrozen(player)) {
                api.freezePlayer(player, reason);
            }
        });
    }
}
```

### 18. Black Market & Dirty Money API (`VaultBlackMarketAPI`)

```java
import fr.skynex.vaultx.util.VaultXHook;
import org.bukkit.entity.Player;

public class IllegalQuests {

    public void rewardDirtyMoney(Player player, double dirtyAmount) {
        VaultXHook.getBlackMarketAPI().ifPresent(api -> {
            api.addDirtyMoney(player, dirtyAmount);
        });
    }
}
```

### 19. Real-Time Stock Market & Commodities API (`VaultStockAPI`)

```java
import fr.skynex.vaultx.util.VaultXHook;
import org.bukkit.entity.Player;

public class InvestmentBroker {

    public void buyGoldShares(Player player, double shares) {
        VaultXHook.getStockAPI().ifPresent(api -> {
            double currentPrice = api.getCommodityPrice("gold");
            api.buySharesAsync(player, "gold", shares).thenAccept(success -> {
                if (success) {
                    player.sendMessage("Successfully bought " + shares + " gold shares at $" + currentPrice + "/share!");
                }
            });
        });
    }
}
```

### 20. Physical Bank Checks & Vouchers API (`VaultCheckAPI`)

```java
import fr.skynex.vaultx.util.VaultXHook;
import org.bukkit.entity.Player;

public class BankerNPC {

    public void issueCheck(Player player, double amount) {
        VaultXHook.getCheckAPI().ifPresent(api -> {
            api.createCheckAsync(player, "dollars", amount).thenAccept(checkItem -> {
                player.getInventory().addItem(checkItem);
                player.sendMessage("Here is your physical bank check of $" + amount);
            });
        });
    }
}
```

### 21. Loans, Credit Scores & Repayment API (`VaultLoanAPI`)

```java
import fr.skynex.vaultx.util.VaultXHook;
import org.bukkit.entity.Player;

public class LoanManager {

    public void evaluateCredit(Player player) {
        VaultXHook.getLoanAPI().ifPresent(api -> {
            api.getCreditScoreAsync(player).thenAccept(score -> {
                player.sendMessage("Your Financial Credit Score is: " + score + "/850");
                if (score >= 700) {
                    api.takeLoanAsync(player, "dollars", 10000.0, 7, 5.0);
                }
            });
        });
    }
}
```

### 22. Economy Inflation & Tax Control API (`VaultInflationAPI`)

```java
import fr.skynex.vaultx.util.VaultXHook;

public class EconomyGovernor {

    public void adjustServerTaxes() {
        VaultXHook.getInflationAPI().ifPresent(api -> {
            api.setTransactionTaxRate("dollars", 2.5); // 2.5% transaction tax
            api.setInflationRate("dollars", 1.05);     // 5% inflation rate
        });
    }
}
```

### 23. Financial Milestones & Achievements API (`VaultMilestoneAPI`)

```java
import fr.skynex.vaultx.util.VaultXHook;
import net.milkbowl.vault.economy.VaultMilestoneAPI.Milestone;

public class MilestoneSystem {

    public void registerMilestones() {
        VaultXHook.getMilestoneAPI().ifPresent(api -> {
            api.registerMilestone(new Milestone("billionaire", "Server Billionaire", "dollars", 1000000000.0));
        });
    }
}
```

### 24. Digital Tokens & Crypto Wallets API (`VaultCryptoAPI`)

```java
import fr.skynex.vaultx.util.VaultXHook;
import org.bukkit.entity.Player;

public class CryptoMiner {

    public void rewardMining(Player player, double tokens) {
        VaultXHook.getCryptoAPI().ifPresent(api -> {
            api.mineTokensAsync(player, "vaultcoin", tokens).thenAccept(resp -> {
                player.sendMessage("Mined " + tokens + " VaultCoins!");
            });
        });
    }
}
```

---

## 🔌 PlaceholderAPI Placeholders

VaultX automatically registers official Placeholders when `PlaceholderAPI` is installed:

| Placeholder | Description | Example Output |
| :--- | :--- | :--- |
| `%vaultx_balance%` | Default currency balance | `1500.50` |
| `%vaultx_balance_<currency>%` | Custom currency balance | `50.00` |
| `%vaultx_balance_formatted_<currency>%` | Formatted balance with symbol | `1,500.50 💎` |
| `%vaultx_symbol_<currency>%` | Configured currency symbol | `💎`, `🪙`, `$` |
| `%vaultx_multiplier_<currency>%` | Active event multiplier | `2.00` |
| `%vaultx_top_name_<rank>_<currency>%` | Leaderboard player name at rank (1-10) | `Notch` |
| `%vaultx_top_balance_<rank>_<currency>%` | Leaderboard balance at rank (1-10) | `1,000,000.00` |
| `%vaultx_dirty_money%` | Player dirty money balance | `250.00` |
| `%vaultx_stock_price_<commodity>%` | Real-time stock market price | `105.40` |


---

## 🛠️ Commands & Permissions

| Command | Description | Permission | Default Aliases |
| :--- | :--- | :--- | :--- |
| `/vaultx` | Main plugin command & admin controls | `vault.use` | `/vx` |
| `/money` | View your balances across currencies | `vault.command.money` | `/balance`, `/bal` |
| `/pay <player> <amount> [currency]` | Pay money to another player | `vault.command.pay` | None |
| `/baltop [currency]` | View global wealth leaderboard | `vault.top` | `/moneytop` |
| `/bank` | Manage shared group & town bank accounts | `vault.command.bank` | `/sharedbank` |
| `/loan` | Access bank loans, credit scores & repayments | `vault.command.loan` | `/loans` |
| `/mailbox` | Collect offline payments & mail notifications | `vault.command.mailbox` | `/mail` |
| `/escrow` | Initiate secure third-party player trades | `vault.command.escrow` | None |
| `/stocks` | View real-time commodities stock market | `vault.command.stocks` | `/stockmarket` |
| `/exchange` | Convert between custom currencies | `vault.convert` | `/forex`, `/convert` |
| `/eco <give\|take\|set> <player> <amount>` | Admin economy management | `vault.admin` | `/economy` |
| `/check write <amount>` | Issue a physical bank check item | `vault.command.check` | `/cheque` |
| `/payday` | Claim periodic group salary allowance | `vault.command.payday` | `/salary` |
| `/transactions` | View personal transaction audit log | `vault.command.transactions` | `/tx`, `/history` |
| `/subscribe` | Manage recurring payments & rent | `vault.command.subscribe` | `/subscriptions` |
| `/dynamicpricing` | View dynamic price scaling & inflation | `vault.admin` | `/dp`, `/dynamic` |
| `/blackmarket` | Launder dirty money at the Black Market | `vault.command.blackmarket` | `/launder`, `/marchenoir` |
| `/discord` | Link your Minecraft account to Discord | `vault.command.discord` | `/linkdiscord` |

---

## 🛠️ Building from Source

To compile VaultX locally:

```bash
git clone https://github.com/Skytoone/VaultX.git
cd VaultX
mvn clean package
```

The output JARs will be generated under `VaultX-API/target/` and `VaultX-Core/target/`.

---

## 📄 License

VaultX is licensed under the standard GNU General Public License (GPLv3).
