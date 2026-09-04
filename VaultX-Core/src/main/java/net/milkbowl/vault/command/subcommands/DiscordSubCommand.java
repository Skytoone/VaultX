package net.milkbowl.vault.command.subcommands;

import net.milkbowl.vault.Vault;
import net.milkbowl.vault.command.SubCommand;
import net.milkbowl.vault.command.VaultXCommand;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collections;
import java.util.List;

public class DiscordSubCommand implements SubCommand {

    private final VaultXCommand parent;

    public DiscordSubCommand(VaultXCommand parent) {
        this.parent = parent;
    }

    @Override
    public String getName() {
        return "discord";
    }

    @Override
    public List<String> getAliases() {
        return Collections.emptyList();
    }

    @Override
    public String getPermission() {
        return "vault.command.discord";
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(parent.getMsg("general.only-players", "§cOnly players can execute this command."));
            return true;
        }

        net.milkbowl.vault.discord.DiscordManager dm = Vault.getDiscordManager();
        if (dm == null) {
            sender.sendMessage("§cDiscord manager is disabled.");
            return true;
        }

        if (args.length >= 2 && args[1].equalsIgnoreCase("status")) {
            if (dm.isLinked(player.getUniqueId())) {
                player.sendMessage(parent.getMsg("discord.already-linked", "§a&l[Discord] §fYour account is linked to tag: §e%tag%")
                        .replace("%tag%", dm.getLinkedDiscordTag(player.getUniqueId())));
            } else {
                player.sendMessage(parent.getMsg("discord.not-linked", "§cYour account is not linked to Discord yet. Use /vx discord link."));
            }
            return true;
        }

        String code = dm.generateLinkCode(player.getUniqueId());
        if (code == null) {
            player.sendMessage(parent.getMsg("discord.link-code-ratelimit", "§c[Discord] §fVeuillez attendre 1 minute avant de générer un nouveau code."));
            return true;
        }
        long expire = parent.getPlugin().getConfig().getLong("discord.account-linking.link-code-expire-minutes", 10);
        player.sendMessage(parent.getMsg("discord.link-code-generated",
                "§a§l[Discord] §fVotre code de vérification est : §e§l%code% §7(expire dans %expire% minutes).")
                .replace("%code%", code)
                .replace("%expire%", String.valueOf(expire)));
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String label, String[] args) {
        return Collections.emptyList();
    }
}
