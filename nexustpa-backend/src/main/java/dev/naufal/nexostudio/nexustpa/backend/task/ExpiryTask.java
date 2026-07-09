package dev.naufal.nexostudio.nexustpa.backend.task;

import dev.naufal.nexostudio.nexustpa.backend.channel.BackendMessageSender;
import dev.naufal.nexostudio.nexustpa.backend.config.BackendConfig;
import dev.naufal.nexostudio.nexustpa.backend.message.MessageManager;
import dev.naufal.nexostudio.nexustpa.backend.request.RequestManager;
import dev.naufal.nexostudio.nexustpa.backend.teleport.PendingTeleportManager;
import dev.naufal.nexostudio.nexustpa.common.message.MessageKeys;
import dev.naufal.nexostudio.nexustpa.common.model.RequestCancelReason;
import dev.naufal.nexostudio.nexustpa.common.model.TpaRequest;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.List;

/**
 * Scheduled task running every second (20 ticks) on the main thread.
 * Handles request expiry, orphaned mirror cleanup, and pending teleport TTL.
 */
public class ExpiryTask extends BukkitRunnable {

    private final JavaPlugin plugin;
    private final RequestManager requestManager;
    private final PendingTeleportManager pendingTeleportManager;
    private final BackendMessageSender messageSender;
    private final MessageManager messageManager;
    private final BackendConfig config;

    public ExpiryTask(JavaPlugin plugin, RequestManager requestManager,
                      PendingTeleportManager pendingTeleportManager,
                      BackendMessageSender messageSender, MessageManager messageManager,
                      BackendConfig config) {
        this.plugin = plugin;
        this.requestManager = requestManager;
        this.pendingTeleportManager = pendingTeleportManager;
        this.messageSender = messageSender;
        this.messageManager = messageManager;
        this.config = config;
    }

    @Override
    public void run() {
        int timeout = config.getRequestTimeout();

        // Expire authoritative requests
        List<TpaRequest> expired = requestManager.expireOld(timeout);
        for (TpaRequest req : expired) {
            // Notify requester
            Player requester = Bukkit.getPlayer(req.getRequesterUuid());
            if (requester != null) {
                messageManager.send(requester, MessageKeys.TPA_EXPIRED,
                        MessageManager.playerPlaceholder(req.getTargetName()));
            }

            // If target is on same server, notify directly and clean incoming
            Player target = Bukkit.getPlayer(req.getTargetUuid());
            if (target != null) {
                messageManager.send(target, MessageKeys.TPA_EXPIRED_TARGET,
                        MessageManager.playerPlaceholder(req.getRequesterName()));
                requestManager.removeFromIncoming(req.getTargetUuid(), req);
            }

            // Send cancel relay to mirror's server (if cross-server)
            messageSender.sendRequestCancel(req.getRequestId(),
                    RequestCancelReason.EXPIRED, req.getTargetUuid());
        }

        // Expire orphaned mirrors (safety net — timeout + 30s buffer)
        // 🟡 Convention: notify target locally before removal
        List<TpaRequest> expiredMirrors = requestManager.expireOldMirrors(timeout, 30);
        for (TpaRequest mirror : expiredMirrors) {
            Player target = Bukkit.getPlayer(mirror.getTargetUuid());
            if (target != null) {
                messageManager.send(target, MessageKeys.TPA_EXPIRED_TARGET,
                        MessageManager.playerPlaceholder(mirror.getRequesterName()));
            }
        }

        // Cleanup expired pending teleports
        pendingTeleportManager.cleanupExpired();

        // Cleanup stale transfers (failed CONNECT_RESPONSE or network partition)
        List<TpaRequest> staleTransfers = requestManager.cleanupStaleTransfers(config.getPendingTeleportTtl() + 15, plugin.getLogger());
        for (TpaRequest req : staleTransfers) {
            Player traveler = Bukkit.getPlayer(req.getTravelerUuid());
            if (traveler != null) {
                messageManager.send(traveler, MessageKeys.ERROR_TELEPORT_FAILED);
                if (req.isMirror()) {
                    messageSender.sendRequestCancel(req.getRequestId(), RequestCancelReason.TARGET_DISCONNECT, req.getRequesterUuid());
                }
            } else {
                java.util.UUID relayTarget = req.isMirror() ? req.getRequesterUuid() : req.getTargetUuid();
                messageSender.sendRequestCancel(req.getRequestId(), RequestCancelReason.TARGET_DISCONNECT, relayTarget);
            }
        }
    }
}
