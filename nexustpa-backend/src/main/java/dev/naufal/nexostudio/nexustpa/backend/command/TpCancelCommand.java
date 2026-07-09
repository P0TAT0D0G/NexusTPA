package dev.naufal.nexostudio.nexustpa.backend.command;

import dev.naufal.nexostudio.nexustpa.backend.channel.BackendMessageSender;
import dev.naufal.nexostudio.nexustpa.backend.message.MessageManager;
import dev.naufal.nexostudio.nexustpa.backend.request.RequestManager;
import dev.naufal.nexostudio.nexustpa.backend.roster.RosterCache;
import dev.naufal.nexostudio.nexustpa.common.message.MessageKeys;
import dev.naufal.nexostudio.nexustpa.common.model.RequestCancelReason;
import dev.naufal.nexostudio.nexustpa.common.model.TpaRequest;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

/**
 * /tpcancel — Cancel your outgoing TPA request.
 */
public class TpCancelCommand implements CommandExecutor {

    private final JavaPlugin plugin;
    private final RequestManager requestManager;
    private final RosterCache rosterCache;
    private final BackendMessageSender messageSender;
    private final MessageManager messageManager;

    public TpCancelCommand(JavaPlugin plugin, RequestManager requestManager,
                           RosterCache rosterCache, BackendMessageSender messageSender,
                           MessageManager messageManager) {
        this.plugin = plugin;
        this.requestManager = requestManager;
        this.rosterCache = rosterCache;
        this.messageSender = messageSender;
        this.messageManager = messageManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player requester)) return true;

        TpaRequest request = requestManager.getOutgoing(requester.getUniqueId());
        if (request == null) {
            messageManager.send(requester, MessageKeys.ERROR_NO_PENDING);
            return true;
        }

        if (!requestManager.cancel(request)) {
            messageManager.send(requester, MessageKeys.ERROR_REQUEST_INVALID);
            return true;
        }

        messageManager.send(requester, MessageKeys.TPA_CANCELLED);

        // Check if target is on same server
        boolean sameServer = rosterCache.isOnSameServer(request.getTargetUuid());

        if (sameServer) {
            Player target = Bukkit.getPlayer(request.getTargetUuid());
            if (target != null) {
                messageManager.send(target, MessageKeys.TPA_CANCELLED_TARGET,
                        MessageManager.playerPlaceholder(requester.getName()));
            }
        } else {
            // Cross-server: send cancel relay to target's server
            messageSender.sendRequestCancel(request.getRequestId(),
                    RequestCancelReason.CANCELLED, request.getTargetUuid());
        }

        return true;
    }
}
