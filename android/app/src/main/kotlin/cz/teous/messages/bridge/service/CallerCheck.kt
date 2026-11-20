package cz.teous.messages.bridge.service

import android.content.Context
import android.os.Binder
import cz.teous.messages.bridge.R
import cz.teous.messages.bridge.contract.BridgeContract

/**
 * Lets only Messages in (docs/CONTRACT.md, Binding and trust). The calling uid must be Messages' package and signed
 * with one of the trusted certificates, the same list the knownSigner permission names. On API 31 and up the system
 * has already checked that permission at bind time; this check runs on every call on every version, so 29 and 30,
 * where the permission can't help, are as closed as 31.
 */
class CallerCheck(private val context: Context) {
    private val trusted: List<String> = context.resources.getStringArray(R.array.trusted_callers).map { it.lowercase() }

    fun enforce(uid: Int = Binder.getCallingUid()) {
        if (!allowed(uid)) throw SecurityException("only Messages may use the bridge")
    }

    fun allowed(uid: Int): Boolean {
        if (uid == android.os.Process.myUid()) return true
        val pm = context.packageManager
        val packages = pm.getPackagesForUid(uid) ?: return false
        if (BridgeContract.MESSAGES_PACKAGE !in packages) return false
        return trusted.any { BridgeContract.uidSignedWith(pm, uid, it) }
    }
}
