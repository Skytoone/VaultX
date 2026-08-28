package net.milkbowl.vault.skript;

import ch.njol.skript.classes.Changer;
import ch.njol.skript.lang.Expression;
import ch.njol.skript.lang.SkriptParser;
import ch.njol.skript.lang.util.SimpleExpression;
import ch.njol.util.Kleenean;
import ch.njol.util.coll.CollectionUtils;
import org.bukkit.OfflinePlayer;
import org.bukkit.event.Event;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.MultiCurrencyEconomy;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;

public class SkriptVaultXExpressions extends SimpleExpression<Number> {

    private Expression<OfflinePlayer> playerExpr;
    private String currency;

    @SuppressWarnings("unchecked")
    @Override
    public boolean init(Expression<?>[] exprs, int matchedPattern, Kleenean isDelayed, SkriptParser.ParseResult parseResult) {
        playerExpr = (Expression<OfflinePlayer>) exprs[0];
        if (matchedPattern == 0 || matchedPattern == 1) {
            currency = "gems";
        } else {
            currency = "tokens";
        }
        return true;
    }

    @Override
    protected Number[] get(Event event) {
        OfflinePlayer player = playerExpr.getSingle(event);
        if (player == null) {
            return new Number[]{0.0};
        }

        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            return new Number[]{0.0};
        }
        Economy econ = rsp.getProvider();
        if (econ == null) {
            return new Number[]{0.0};
        }

        double balance = 0.0;
        if (econ instanceof MultiCurrencyEconomy) {
            balance = ((MultiCurrencyEconomy) econ).getCurrencyBalance(player, currency);
        }
        return new Number[]{balance};
    }

    @Override
    public boolean isSingle() {
        return true;
    }

    @Override
    public Class<? extends Number> getReturnType() {
        return Number.class;
    }

    @Override
    public String toString(Event event, boolean debug) {
        return currency + " balance of " + playerExpr.toString(event, debug);
    }

    @Override
    public Class<?>[] acceptChange(Changer.ChangeMode mode) {
        if (mode == Changer.ChangeMode.SET || mode == Changer.ChangeMode.ADD || mode == Changer.ChangeMode.REMOVE || mode == Changer.ChangeMode.RESET) {
            return CollectionUtils.array(Number.class);
        }
        return null;
    }

    @Override
    public void change(Event event, Object[] delta, Changer.ChangeMode mode) {
        OfflinePlayer player = playerExpr.getSingle(event);
        if (player == null) {
            return;
        }

        double amount = 0.0;
        if (delta != null && delta.length > 0 && delta[0] instanceof Number) {
            amount = ((Number) delta[0]).doubleValue();
        }

        RegisteredServiceProvider<Economy> rsp = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            return;
        }
        Economy econ = rsp.getProvider();
        if (econ == null) {
            return;
        }

        if (econ instanceof MultiCurrencyEconomy) {
            MultiCurrencyEconomy mcEcon = (MultiCurrencyEconomy) econ;
            if (mode == Changer.ChangeMode.SET) {
                double current = mcEcon.getCurrencyBalance(player, currency);
                if (current < amount) {
                    mcEcon.depositCurrencyPlayer(player, currency, amount - current);
                } else if (current > amount) {
                    mcEcon.withdrawCurrencyPlayer(player, currency, current - amount);
                }
            } else if (mode == Changer.ChangeMode.ADD) {
                mcEcon.depositCurrencyPlayer(player, currency, amount);
            } else if (mode == Changer.ChangeMode.REMOVE) {
                mcEcon.withdrawCurrencyPlayer(player, currency, amount);
            } else if (mode == Changer.ChangeMode.RESET) {
                double current = mcEcon.getCurrencyBalance(player, currency);
                mcEcon.withdrawCurrencyPlayer(player, currency, current);
            }
        }
    }
}
