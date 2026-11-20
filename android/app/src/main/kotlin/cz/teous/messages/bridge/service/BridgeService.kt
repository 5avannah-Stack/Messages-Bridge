package cz.teous.messages.bridge.service

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import cz.teous.messages.bridge.BuildConfig
import cz.teous.messages.bridge.contract.AccountState
import cz.teous.messages.bridge.contract.BridgeInfo
import cz.teous.messages.bridge.contract.Chat
import cz.teous.messages.bridge.contract.Event
import cz.teous.messages.bridge.contract.HistoryPage
import cz.teous.messages.bridge.contract.IBridge
import cz.teous.messages.bridge.contract.IBridgeListener
import cz.teous.messages.bridge.contract.LoginStep
import cz.teous.messages.bridge.contract.NetworkInfo
import cz.teous.messages.bridge.contract.SendRequest
import cz.teous.messages.bridge.hub

/**
 * The service Messages binds (docs/CONTRACT.md). On API 31 and up the system lets only holders of the knownSigner
 * permission bind it; [LegacyBridgeService] stands in on 29 and 30. Either way every call passes [CallerCheck] first.
 */
open class BridgeService : Service() {
    private val binder by lazy { Binder(CallerCheck(this)) }

    override fun onBind(intent: Intent): IBinder = binder

    private inner class Binder(private val check: CallerCheck) : IBridge.Stub() {
        private val hub get() = this@BridgeService.hub

        /**
         * Checks the caller, then runs [block]. Binder carries only a few exception types back to the caller and crashes
         * this process on any other, so the rest (an IOException, a network library's error) go back as
         * IllegalStateException.
         */
        private inline fun <T> call(block: () -> T): T {
            check.enforce()
            return try {
                block()
            } catch (e: SecurityException) {
                throw e
            } catch (e: IllegalArgumentException) {
                throw e
            } catch (e: IllegalStateException) {
                throw e
            } catch (e: UnsupportedOperationException) {
                throw e
            } catch (e: Exception) {
                throw IllegalStateException(e.message ?: e.javaClass.simpleName)
            }
        }

        override fun info(): BridgeInfo = call {
            BridgeInfo().apply {
                contractVersion = IBridge.VERSION
                minClientVersion = 1
                appVersion = BuildConfig.VERSION_NAME
                appVersionCode = BuildConfig.VERSION_CODE.toLong()
            }
        }

        override fun networks(): Array<NetworkInfo> = call { hub.networks().toTypedArray() }

        override fun accounts(): Array<AccountState> = call { hub.accountStates().toTypedArray() }

        override fun startLogin(network: String, flowId: String): LoginStep = call { hub.startLogin(network, flowId) }

        override fun submitLogin(loginId: String, stepId: String, values: Bundle?): LoginStep = call {
            val map = values?.keySet()?.associateWith { values.getString(it) ?: "" } ?: emptyMap()
            hub.submitLogin(loginId, stepId, map)
        }

        override fun cancelLogin(loginId: String) = call { hub.cancelLogin(loginId) }

        override fun connect(network: String) = call { hub.connect(network) }

        override fun logout(network: String) = call { hub.logout(network) }

        override fun chats(network: String, offset: Int, limit: Int): Array<Chat> = call { hub.chats(network, offset, limit).toTypedArray() }

        override fun history(network: String, chatId: String, beforeTimeMs: Long, beforeId: String?, limit: Int): HistoryPage = call { hub.history(network, chatId, beforeTimeMs, beforeId, limit) }

        override fun send(request: SendRequest): String = call { hub.send(request) }

        override fun markRead(network: String, chatId: String, messageId: String) = call { hub.markRead(network, chatId, messageId) }

        override fun openMedia(network: String, chatId: String, messageId: String): ParcelFileDescriptor = call { hub.openMedia(network, chatId, messageId) }

        override fun setListener(listener: IBridgeListener?) = call { hub.setListener(listener) }

        override fun events(afterSeq: Long, limit: Int): Array<Event> = call { hub.events(afterSeq, limit).toTypedArray() }

        override fun ack(seq: Long) = call { hub.ack(seq) }
    }
}

/** The same service for API 29 and 30, where knownSigner doesn't exist; only one of the two is enabled. */
class LegacyBridgeService : BridgeService()
