package com.pahebatcher.app.playback

/**
 * AQR-010 (DECISIONS 159) — which controllers may attach to the playback
 * `MediaSession` (Option 1: own package + platform-trusted only).
 *
 * Before this existed, media3's default accepted EVERY controller, so any app
 * on the device could connect a `MediaController` to the exported
 * [PaheMediaSessionService] and read the full playback `MediaMetadata` —
 * including the HLS playlist/segment URLs this app resolves (AGENTS §11).
 *
 * The gate runs on both of media3 1.11.1's supported rejection points
 * (both verified against the shipped `media3-session-1.11.1` bytecode):
 *
 * * `MediaSessionService.onGetSession(controllerInfo)` → returns `null`
 *   (media3's "no session for this controller" rejection), and
 * * `MediaSession.Callback.onConnect(session, controllerInfo)` → returns
 *   `ConnectionResult.reject()` (the library's supported rejection result).
 *
 * What still passes, and why it is verified rather than assumed:
 *
 * * **own package** — this app's own media3 controllers (uid == ours);
 * * **`isTrusted`** — both connection paths (the media3 binder
 *   `MediaSessionStub` AND the framework/legacy path
 *   `MediaSessionLegacyStub` that carries the media notification, lock
 *   screen, quick-settings tile, and media-key/Bluetooth dispatch) build
 *   their `ControllerInfo` with `isTrusted =
 *   MediaSessionManager.isTrustedForMediaControl(...)`, whose bytecode-verified
 *   rule trusts: uid 1000 (system — media-key dispatch), our own uid,
 *   holders of `STATUS_BAR_SERVICE` (SystemUI → notification/lock screen/
 *   quick settings), holders of `MEDIA_CONTENT_CONTROL`, and enabled
 *   notification listeners. System surfaces therefore arrive here trusted;
 *   a random third-party app arrives untrusted with a foreign package and is
 *   rejected.
 *
 * Note the match is EXACT: `"com.pahe.host"` must never match
 * `"com.pahe.host.evil"` — prefix matching would defeat the gate.
 * Pure function, no Android/media3 types, so `MediaSessionControllerPolicyTest`
 * runs on the JVM.
 */
internal object MediaSessionControllerPolicy {

    /**
     * @param ownPackage this app's package ([android.content.Context.getPackageName]),
     *   or `null` when it cannot be determined (the conservative case: never a
     *   wildcard).
     * @param controllerPackage the connecting controller's package
     *   (`ControllerInfo.getPackageName()`), or `null` when unknown.
     * @param trusted `ControllerInfo.isTrusted()` — the platform's
     *   media-control trust verdict for this controller.
     */
    fun isControllerAllowed(
        ownPackage: String?,
        controllerPackage: String?,
        trusted: Boolean,
    ): Boolean {
        if (trusted) return true
        if (ownPackage.isNullOrEmpty()) return false
        return controllerPackage != null && controllerPackage == ownPackage
    }
}
