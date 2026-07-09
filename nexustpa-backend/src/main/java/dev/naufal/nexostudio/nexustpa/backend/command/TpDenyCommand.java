package dev.naufal.nexostudio.nexustpa.backend.command;

import dev.naufal.nexostudio.nexustpa.backend.channel.BackendMessageSender;
import dev.naufal.nexostudio.nexustpa.backend.message.MessageManager;
import dev.naufal.nexostudio.nexustpa.backend.request.RequestManager;
import dev.naufal.nexostudio.nexustpa.common.message.MessageKeys;
import dev.naufal.nexostudio.nexustpa.common.model.RequestState;
import dev.naufal.nexostudio.nexustpa.common.model.TpaRequest;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * /tpdeny [player] — Deny an incoming TPA request.
 */
public class TpDenyCommand implements CommandExecutor, TabCompleter {

    private final JavaPlugin plugin;
    private final RequestManager requestManager;
    private final BackendMessageSender messageSender;
    private final MessageManager messageManager;

    public TpDenyCommand(JavaPlugin plugin, RequestManager requestManager,
                         BackendMessageSender messageSender, MessageManager messageManager) {
        this.plugin = plugin;
        this.requestManager = requestManager;
        this.messageSender = messageSender;
        this.messageManager = messageManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player target)) return true;

        List<TpaRequest> pending = requestManager.getIncoming(target.getUniqueId());

        if (pending.isEmpty()) {
            messageManager.send(target, MessageKeys.ERROR_NO_PENDING);
            return true;
        }

        TpaRequest request;
        if (pending.size() == 1) {
            request = pending.get(0);
        } else if (args.length >= 1) {
            // Find by requester name — scan roster is not needed,
            // just match against pending request requester names
            request = pending.stream()
                    .filter(r -> r.getRequesterName().equalsIgnoreCase(args[0]))
                    .findFirst()
                    .orElse(null);
            if (request == null) {
                messageManager.send(target, MessageKeys.ERROR_NO_PENDING);
                return true;
            }
        } else {
            messageManager.send(target, MessageKeys.ERROR_MULTIPLE_PENDING);
            return true;
        }

        if (!requestManager.deny(request)) {
            messageManager.send(target, MessageKeys.ERROR_REQUEST_INVALID);
            return true;
        }

        messageManager.send(target, MessageKeys.TPA_DENIED,
                MessageManager.playerPlaceholder(request.getRequesterName()));

        if (request.isMirror()) {
            // Cross-server: send DENIED resolve to authoritative
            messageSender.sendRequestResolve(request.getRequestId(),
                    request.getRequesterUuid(), RequestState.DENIED, false);
        } else {
            // Same-server: notify requester directly
            org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> {
                Player requester = org.bukkit.Bukkit.getPlayer(request.getRequesterUuid());
                if (requester != null) {
                    messageManager.send(requester, MessageKeys.TPA_DENIED_SENDER,
                            MessageManager.playerPlaceholder(target.getName()));
                }
            });
        }

        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender,
                                                @NotNull Command command,
                                                @NotNull String label,
                                                @NotNull String[] args) {
        if (args.length == 1 && sender instanceof Player player) {
            List<TpaRequest> pending = requestManager.getIncoming(player.getUniqueId());
            String prefix = args[0].toLowerCase();
            return pending.stream()
                    .map(TpaRequest::getRequesterName)
                    .filter(name -> name.toLowerCase().startsWith(prefix))
                    .toList();
        }
        return Collections.emptyList();
    }
}
