package dev.naufal.nexostudio.nexustpa.common.message;

/**
 * String constants for messages.yml key paths.
 * Referenced by both proxy (for future use) and backend (for MessageManager lookups).
 * Keys use dot-notation matching the YAML nesting structure.
 */
public final class MessageKeys {

    private MessageKeys() {
    }

    // --- Prefix ---
    public static final String PREFIX = "prefix";

    // --- TPA ---
    public static final String TPA_SENT = "tpa.sent";
    public static final String TPA_RECEIVED = "tpa.received";
    public static final String TPA_ACCEPTED = "tpa.accepted";
    public static final String TPA_ACCEPTED_SENDER = "tpa.accepted-sender";
    public static final String TPA_DENIED = "tpa.denied";
    public static final String TPA_DENIED_SENDER = "tpa.denied-sender";
    public static final String TPA_CANCELLED = "tpa.cancelled";
    public static final String TPA_CANCELLED_TARGET = "tpa.cancelled-target";
    public static final String TPA_EXPIRED = "tpa.expired";
    public static final String TPA_EXPIRED_TARGET = "tpa.expired-target";

    // --- TPA Here ---
    public static final String TPA_HERE_SENT = "tpa-here.sent";
    public static final String TPA_HERE_RECEIVED = "tpa-here.received";

    // --- Toggle ---
    public static final String TOGGLE_ON = "toggle.on";
    public static final String TOGGLE_OFF = "toggle.off";

    // --- Errors ---
    public static final String ERROR_PLAYER_NOT_FOUND = "error.player-not-found";
    public static final String ERROR_SELF_REQUEST = "error.self-request";
    public static final String ERROR_COOLDOWN_ACTIVE = "error.cooldown-active";
    public static final String ERROR_COOLDOWN_CANCEL = "error.cooldown-cancel";
    public static final String ERROR_TOGGLE_OFF = "error.toggle-off";
    public static final String ERROR_NO_PENDING = "error.no-pending";
    public static final String ERROR_MULTIPLE_PENDING = "error.multiple-pending";
    public static final String ERROR_ALREADY_PENDING = "error.already-pending";
    public static final String ERROR_NO_PERMISSION = "error.no-permission";
    public static final String ERROR_NETWORK_NOT_READY = "error.network-not-ready";
    public static final String ERROR_TELEPORT_FAILED = "error.teleport-failed";
    public static final String ERROR_REQUEST_INVALID = "error.request-invalid";
    public static final String ERROR_TARGET_DISCONNECTED = "error.target-disconnected";

    // --- Teleport ---
    public static final String TELEPORT_TELEPORTING = "teleport.teleporting";
    public static final String TELEPORT_CANCELLED_DISCONNECT = "teleport.cancelled-disconnect";

    // --- Request cancelled by new request ---
    public static final String TPA_CANCELLED_NEW = "tpa.cancelled-new";

    // --- Admin ---
    public static final String ADMIN_CONFIG_RELOADED = "admin.config-reloaded";
}
