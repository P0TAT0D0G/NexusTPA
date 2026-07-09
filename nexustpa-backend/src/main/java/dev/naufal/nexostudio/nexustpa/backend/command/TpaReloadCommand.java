package dev.naufal.nexostudio.nexustpa.backend.command;

import dev.naufal.nexostudio.nexustpa.backend.message.MessageManager;
import dev.naufal.nexostudio.nexustpa.common.message.MessageKeys;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

/**
 * /tpareload — Reloads config and messages. Admin only.
 */
public class TpaReloadCommand implements CommandExecutor {

    private final JavaPlugin plugin;
    private final MessageManager messageManager;

    public TpaReloadCommand(JavaPlugin plugin, MessageManager messageManager) {
        this.plugin = plugin;
        this.messageManager = messageManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("nexustpa.admin.reload")) {
            if (sender instanceof Player p) {
                messageManager.send(p, MessageKeys.ERROR_NO_PERMISSION);
            }
            return true;
        }

        plugin.reloadConfig();
        messageManager.reload();

        if (sender instanceof Player p) {
            messageManager.send(p, MessageKeys.ADMIN_CONFIG_RELOADED);
        } else {
            sender.sendMessage("NexusTPA config reloaded.");
        }

        return true;
    }
}
