package dev.naufal.nexostudio.nexustpa.backend.command;

import dev.naufal.nexostudio.nexustpa.backend.message.MessageManager;
import dev.naufal.nexostudio.nexustpa.backend.storage.ToggleRepository;
import dev.naufal.nexostudio.nexustpa.common.message.MessageKeys;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

/**
 * /tptoggle — Toggle incoming teleport requests on/off.
 * Persisted to MySQL via async thread.
 */
public class TpToggleCommand implements CommandExecutor {

    private final JavaPlugin plugin;
    private final ToggleRepository toggleRepository;
    private final MessageManager messageManager;

    public TpToggleCommand(JavaPlugin plugin, ToggleRepository toggleRepository,
                           MessageManager messageManager) {
        this.plugin = plugin;
        this.toggleRepository = toggleRepository;
        this.messageManager = messageManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) return true;

        boolean currentlyAccepting = toggleRepository.isAcceptingRequests(player.getUniqueId());
        boolean newState = !currentlyAccepting;

        // Async DB update
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            toggleRepository.setAcceptingRequests(player.getUniqueId(), newState);

            // 🟡 Hop back to main, re-fetch Player
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player p = Bukkit.getPlayer(player.getUniqueId());
                if (p != null) {
                    String key = newState ? MessageKeys.TOGGLE_ON : MessageKeys.TOGGLE_OFF;
                    messageManager.send(p, key);
                }
            });
        });

        return true;
    }
}
