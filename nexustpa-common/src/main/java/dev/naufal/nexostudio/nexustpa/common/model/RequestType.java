package dev.naufal.nexostudio.nexustpa.common.model;

/**
 * Type of TPA request.
 * Determines who moves (the "traveler") and who stays (the "destination player").
 */
public enum RequestType {

    /**
     * Requester teleports TO target.
     * Traveler = requester, destination player = target.
     */
    TPA,

    /**
     * Target teleports TO requester.
     * Traveler = target, destination player = requester.
     */
    TPAHERE
}
