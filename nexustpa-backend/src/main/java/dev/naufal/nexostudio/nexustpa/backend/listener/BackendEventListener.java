package dev.naufal.nexostudio.nexustpa.backend.listener;

import dev.naufal.nexostudio.nexustpa.backend.channel.BackendMessageSender;
import dev.naufal.nexostudio.nexustpa.backend.message.MessageManager;
import dev.naufal.nexostudio.nexustpa.backend.request.RequestManager;
import dev.naufal.nexostudio.nexustpa.backend.storage.ToggleRepository;
import dev.naufal.nexostudio.nexustpa.backend.teleport.PendingTeleportManager;
import dev.naufal.nexostudio.nexustpa.common.message.MessageKeys;
import dev.naufal.nexostudio.nexustpa.common.model.PendingTeleport;
import dev.naufal.nexostudio.nexustpa.common.model.RequestCancelReason;
import dev.naufal.nexostudio.nexustpa.common.model.TpaRequest;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Handles player join/quit events for pending teleport execution,
 * toggle cache management, and request cleanup.
 */
public class BackendEventListener implements Listener {

    private final JavaPlugin plugin;
    private final RequestManager requestManager;
    private final PendingTeleportManager pendingTeleportManager;
    private final ToggleRepository toggleRepository;
    private final BackendMessageSender messageSender;
    private final MessageManager messageManager;

    public BackendEventListener(JavaPlugin plugin, RequestManager requestManager,
                                PendingTeleportManager pendingTeleportManager,
                                ToggleRepository toggleRepository,
                                BackendMessageSender messageSender,
                                MessageManager messageManager) {
        this.plugin = plugin;
        this.requestManager = requestManager;
        this.pendingTeleportManager = pendingTeleportManager;
        this.toggleRepository = toggleRepository;
        this.messageSender = messageSender;
        this.messageManager = messageManager;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // Load toggle state from DB (async)
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            toggleRepository.loadIntoCache(player.getUniqueId());
        });

        // Check for pending teleport (cross-server arrival)
        PendingTeleport pending = pendingTeleportManager.consumePending(player.getUniqueId());
        if (pending != null) {
            // Schedule teleport 2 ticks later to let the player fully load
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                // 🟡 Re-fetch player after delay
                Player traveler = Bukkit.getPlayer(player.getUniqueId());
                if (traveler == null) return;

                Player destPlayer = Bukkit.getPlayer(pending.getDestinationPlayerUuid());
                if (destPlayer != null) {
                    traveler.teleport(destPlayer.getLocation());
                    messageManager.send(traveler, MessageKeys.TELEPORT_TELEPORTING,
                            MessageManager.playerPlaceholder(destPlayer.getName()));
                } else {
                    messageManager.send(traveler, MessageKeys.ERROR_TELEPORT_FAILED);
                }
            }, 2L);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();

        // Cleanup requests
        RequestManager.CleanupResult cleanup = requestManager.cleanupPlayer(player.getUniqueId());

        // Relay cancels for outgoing requests (notify mirrors)
        for (TpaRequest outgoing : cleanup.cancelledOutgoing()) {
            messageSender.sendRequestCancel(outgoing.getRequestId(),
                    RequestCancelReason.CANCELLED, outgoing.getTargetUuid());
        }

        // Relay disconnects for mirrors (notify authoritative)
        for (TpaRequest mirror : cleanup.orphanedMirrors()) {
            messageSender.sendRequestCancel(mirror.getRequestId(),
                    RequestCancelReason.TARGET_DISCONNECT, mirror.getRequesterUuid());
        }

        // Cleanup pending teleport
        pendingTeleportManager.removePending(player.getUniqueId());

        // Evict toggle cache
        toggleRepository.evictCache(player.getUniqueId());
    }
}
