package net.milkbowl.vault.skript;

import ch.njol.skript.Skript;
import ch.njol.skript.lang.ExpressionType;

public class SkriptVaultXHook {
    public static void register() {
        Skript.registerExpression(SkriptVaultXExpressions.class, Number.class, ExpressionType.COMBINED,
                "[the] gems [balance] of %offlineplayer%",
                "%offlineplayer%'s gems [balance]",
                "[the] tokens [balance] of %offlineplayer%",
                "%offlineplayer%'s tokens [balance]"
        );
    }
}
