// SPDX-License-Identifier: MIT
// Part of the Messages Bridge contract (docs/CONTRACT.md).
package cz.teous.messages.bridge.contract

import android.content.Intent
import android.content.pm.PackageManager

/** The names and keys both apps agree on. Messages copies this file with the AIDL files. */
object BridgeContract {
    const val BRIDGE_PACKAGE = "cz.teous.messages.bridge"
    const val MESSAGES_PACKAGE = "cz.teous.messages"

    /** Binds the bridge's IBridge service. */
    const val ACTION_BIND = "cz.teous.messages.bridge.action.BIND"

    /**
     * The bridge's signature permission, granted to Messages by its certificate (knownSigner, API 31 and up). Messages
     * requests it with `<uses-permission>`. Below 31 the bridge checks the certificate in code instead.
     */
    const val PERMISSION_BIND = "cz.teous.messages.bridge.permission.BIND"

    /**
     * The bridge sends this to Messages, by package, when events wait and no listener is set. It carries nothing:
     * Messages binds, reads IBridge.events() and acks. A spoofed one only makes Messages look.
     */
    const val ACTION_EVENTS = "cz.teous.messages.bridge.action.EVENTS"

    /** SHA-256 of each app's release signing certificate, lower-case hex. A new key on either side breaks the trust. */
    const val BRIDGE_CERT_SHA256 = "60ec6cde560e55c50196c9023ef84a853c319612546bd9297ba0c9f953988b48"
    const val MESSAGES_CERT_SHA256 = "6361ec9efb37a88465baddf9a8e5968760d1da12bb95969df79b35019eb42f25"

    const val WHATSAPP = "whatsapp"
    const val SIGNAL = "signal"
    const val INSTAGRAM = "instagram"

    /** The intent Messages binds with. Check [isGenuineBridge] first: a package name alone proves nothing. */
    fun bindIntent(): Intent = Intent(ACTION_BIND).setPackage(BRIDGE_PACKAGE)

    /** Whether the installed bridge is signed with T's bridge key (or a key that rotated from it). */
    fun isGenuineBridge(pm: PackageManager, certSha256: String = BRIDGE_CERT_SHA256): Boolean =
        signedWith(pm, BRIDGE_PACKAGE, certSha256)

    /** Whether [pkg] is installed and signed with the certificate [certSha256]. */
    fun signedWith(pm: PackageManager, pkg: String, certSha256: String): Boolean =
        try {
            pm.hasSigningCertificate(pkg, hex(certSha256), PackageManager.CERT_INPUT_SHA256)
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

    /** Whether the app with [uid] is signed with [certSha256]. */
    fun uidSignedWith(pm: PackageManager, uid: Int, certSha256: String): Boolean =
        pm.hasSigningCertificate(uid, hex(certSha256), PackageManager.CERT_INPUT_SHA256)

    fun hex(digest: String): ByteArray {
        require(digest.length % 2 == 0) { "odd-length digest" }
        return ByteArray(digest.length / 2) { digest.substring(2 * it, 2 * it + 2).toInt(16).toByte() }
    }
}
