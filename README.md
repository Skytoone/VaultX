# ⚡ VaultX & VaultX-API

**VaultX** is a modern, high-performance economy implementation and API extension for Minecraft Spigot / Paper servers, designed to extend the standard Vault API with **native multi-currency support**, **async operations**, and **custom events**.

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
