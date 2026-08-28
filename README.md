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
        <groupId>com.github.Skynex</groupId>
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
    compileOnly 'com.github.Skynex:VaultX-API:1.1.1'
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

    }
}
```

### 3. Using 1-Line `VaultXHook` Helper

```java
import net.milkbowl.vault.util.VaultXHook;
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
import net.milkbowl.vault.util.VaultXHook;

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
import net.milkbowl.vault.util.VaultXHook;
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

---

## 🛠️ Building from Source

To compile VaultX locally:

```bash
git clone https://github.com/Skynex/VaultX.git
cd VaultX
mvn clean package
```

The output JARs will be generated under `VaultX-API/target/` and `VaultX-Core/target/`.

---

## 📄 License

VaultX is licensed under the standard GNU General Public License (GPLv3).
