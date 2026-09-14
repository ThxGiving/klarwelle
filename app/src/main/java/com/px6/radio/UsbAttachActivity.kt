package com.px6.radio

import android.app.Activity
import android.os.Bundle

/**
 * Invisible, **Direct-Boot-aware** receiver for `USB_DEVICE_ATTACHED`. Its only job is to exist so the
 * framework dispatches the attach event *to an Activity* at boot.
 *
 * Why this fixes the "USB dialog on every boot" on head units: Android does persist a "use by default"
 * grant to `usb_device_manager.xml`, but the in-memory permission map is only repopulated from that
 * file as a **side effect of delivering `USB_DEVICE_ATTACHED` to a registered Activity** — never to a
 * plain service/driver (Google issuetracker 62199815, "working as intended"). On a car head unit the
 * DAB stick (16C0:05DC) is already attached during **Direct Boot**, before unlock, so a non-Direct-Boot
 * activity would miss the event entirely and the grant would look "lost" every boot. This activity is
 * `directBootAware`, receives that event, and finishes immediately — no UI, no credential-encrypted
 * storage. After the user accepts the dialog once (with "use by default"), `UsbManager.hasPermission`
 * then returns true across reboots and omri never shows the dialog again.
 */
class UsbAttachActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Nothing to do — merely having received the attach intent is what reloads the saved grant.
        finish()
    }
}
