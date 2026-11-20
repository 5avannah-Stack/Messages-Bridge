package cz.teous.messages.bridge.core

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.util.Log
import cz.teous.messages.bridge.contract.AccountState
import cz.teous.messages.bridge.contract.Chat
import cz.teous.messages.bridge.contract.Event
import cz.teous.messages.bridge.contract.HistoryPage
import cz.teous.messages.bridge.contract.IBridgeListener
import cz.teous.messages.bridge.contract.LoginStep
import cz.teous.messages.bridge.contract.Message
import cz.teous.messages.bridge.contract.NetworkInfo
import cz.teous.messages.bridge.contract.SendRequest
import cz.teous.messages.bridge.contract.SendResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * The bridge's core, the same for every network: it owns each network's store and connector, turns what connectors
 * report into stored events, pushes them to Messages or wakes it, and decides when a network stays connected
 * (docs/OVERVIEW.md, Staying connected). IBridge's calls land here after the caller check.
 *
 * It never logs message text, numbers or names.
 */
class Hub(
    private val context: Context,
    private val root: File,
    factories: Map<String, ConnectorFactory>,
    /** Network calls: sends, connects, read receipts. */
    private val work: Executor,
    /** Holds running out, and batching pushes. */
    private val timer: ScheduledExecutorService,
    /** Tells Messages events wait, when no listener is set. */
    private val wakeMessages: () -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private inner class Slot(val id: String, val factory: ConnectorFactory) {
        val dir get() = File(root, "networks/$id")
        lateinit var store: NetworkStore
        lateinit var connector: Connector
        @Volatile var state: AccountState = AccountState()
        @Volatile var holdUntil = 0L
        @Volatile var loginId: String? = null
        @Volatile var wantDisconnect = false

        fun open() {
            dir.mkdirs()
            store = NetworkStore(context, id, dir)
            connector = factory.create(dir, Sink(this))
            state = account(id, if (connector.isLoggedIn) AccountState.SLEEPING else AccountState.LOGGED_OUT)
        }
    }

    private val slots: Map<String, Slot> = factories.mapValues { (id, f) -> Slot(id, f).apply { open() } }
    private val log = EventLog(context, File(root, "events.db"))
    private val lock = Any()
    @Volatile private var listener: IBridgeListener? = null
    private val outgoing = ArrayList<Event>()
    private var flushScheduled = false
    private var lastWakeMs = 0L
    @Volatile private var lastActivityMs = 0L

    private val _accounts = MutableStateFlow(slots.values.map { it.state })
    /** For the status page. */
    val accounts: StateFlow<List<AccountState>> = _accounts
    private val _attached = MutableStateFlow(false)
    /** Whether Messages holds a listener right now. */
    val attached: StateFlow<Boolean> = _attached

    init {
        // A send the process died in the middle of never finished; say so instead of leaving Messages waiting.
        for (slot in slots.values) for (q in slot.store.queuedSends()) {
            finishSend(slot, q.localId, q.chatId, null, "interrupted")
        }
    }

    val networkIds: Set<String> get() = slots.keys
    fun networks(): List<NetworkInfo> = slots.values.map { it.connector.info }
    fun accountStates(): List<AccountState> = slots.values.map { it.state }
    fun wakePackages(): Map<String, Set<String>> = slots.mapValues { it.value.connector.wakePackages }
    val lastActivity: Long get() = lastActivityMs

    private fun slot(network: String) = slots[network] ?: throw IllegalArgumentException("unknown network $network")

    // Login

    fun startLogin(network: String, flowId: String): LoginStep {
        val slot = slot(network)
        check(!slot.connector.isLoggedIn) { "already logged in" }
        require(slot.connector.info.loginFlows.any { it.id == flowId }) { "unknown login flow $flowId" }
        slot.loginId?.let { slot.connector.cancelLogin(it) }
        val loginId = UUID.randomUUID().toString()
        slot.loginId = loginId
        return slot.connector.startLogin(flowId, loginId)
    }

    fun submitLogin(loginId: String, stepId: String, values: Map<String, String>): LoginStep {
        val slot = slots.values.firstOrNull { it.loginId == loginId } ?: throw IllegalStateException("no such login")
        return slot.connector.submitLogin(loginId, stepId, values).also { onLoginStep(slot, it) }
    }

    fun cancelLogin(loginId: String) {
        val slot = slots.values.firstOrNull { it.loginId == loginId } ?: return
        slot.loginId = null
        slot.connector.cancelLogin(loginId)
    }

    private fun onLoginStep(slot: Slot, step: LoginStep) {
        if (step.type == LoginStep.TYPE_COMPLETE || step.type == LoginStep.TYPE_FAILED) {
            slot.loginId = null
            // History sync arrives in the minutes after pairing.
            if (step.type == LoginStep.TYPE_COMPLETE) hold(slot, HOLD_AFTER_LOGIN_MS)
        }
    }

    fun logout(network: String) {
        val slot = slot(network)
        try {
            if (slot.connector.isLoggedIn) slot.connector.logout()
        } finally {
            slot.connector.close()
            slot.store.close()
            slot.dir.deleteRecursively()
            log.dropNetwork(network)
            slot.open()
            setState(slot, account(network, AccountState.LOGGED_OUT))
        }
    }

    // Connecting

    /** Keeps [network], or every network when null, connected for [ms] from now. */
    fun wake(network: String?, ms: Long) {
        val targets = if (network == null) slots.values else listOf(slot(network))
        targets.forEach { hold(it, ms) }
    }

    fun connect(network: String) = hold(slot(network), HOLD_CONNECT_MS)

    private fun hold(slot: Slot, ms: Long) {
        slot.holdUntil = maxOf(slot.holdUntil, now() + ms)
        reconcile(slot)
        timer.schedule({ reconcile(slot) }, ms + 50, TimeUnit.MILLISECONDS)
    }

    /** Connects or lets go, by what wants the network now. A login in progress runs its own connection. */
    private fun reconcile(slot: Slot) {
        if (slot.loginId != null || !slot.connector.isLoggedIn) return
        val wanted = listener != null || now() < slot.holdUntil
        val s = slot.state.state
        val up = s == AccountState.CONNECTED || s == AccountState.CONNECTING || s == AccountState.TRANSIENT_DISCONNECT
        when {
            wanted && !up && s != AccountState.BAD_CREDENTIALS -> {
                slot.wantDisconnect = false
                setState(slot, account(slot.id, AccountState.CONNECTING, slot.state))
                work.execute {
                    try {
                        slot.connector.connect()
                    } catch (e: Exception) {
                        Log.w(TAG, "${slot.id}: connect failed (${e.javaClass.simpleName})")
                        setState(slot, account(slot.id, AccountState.TRANSIENT_DISCONNECT, slot.state, e.message))
                    }
                }
            }
            !wanted && up -> {
                slot.wantDisconnect = true
                work.execute { slot.connector.disconnect() }
                setState(slot, account(slot.id, AccountState.SLEEPING, slot.state))
            }
        }
    }

    private fun setState(slot: Slot, state: AccountState) {
        slot.state = state
        _accounts.value = slots.values.map { it.state }
        emit(Event.TYPE_ACCOUNT, slot.id, now())
    }

    // Reading

    fun chats(network: String, offset: Int, limit: Int): List<Chat> = slot(network).store.chats(offset.coerceAtLeast(0), limit.coerceIn(1, MAX_PAGE))

    fun history(network: String, chatId: String, beforeMs: Long, beforeId: String?, limit: Int): HistoryPage =
        slot(network).store.history(chatId, beforeMs, beforeId, limit.coerceIn(1, MAX_PAGE))

    fun openMedia(network: String, chatId: String, messageId: String): ParcelFileDescriptor {
        val file = slot(network).store.mediaFile(chatId, messageId) ?: throw IllegalStateException("media not ready")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    // Writing

    fun send(request: SendRequest): String {
        val slot = slot(request.network)
        check(slot.connector.isLoggedIn) { "not logged in" }
        require(request.chatId.isNotEmpty()) { "no chat" }
        val media = request.media
        if (media != null && slot.connector.info.capabilities and NetworkInfo.CAP_MEDIA == 0) {
            throw UnsupportedOperationException("${request.network} sends no media")
        }
        val localId = request.localId?.takeIf { it.isNotEmpty() } ?: UUID.randomUUID().toString()
        require(slot.store.send(localId) == null) { "localId already used" }
        // The copy happens before send() returns, so Messages may close its end (docs/CONTRACT.md, Sending).
        val file = media?.let { pfd ->
            val out = File(slot.store.outboxDir, localId.filter { it.isLetterOrDigit() || it == '-' })
            ParcelFileDescriptor.AutoCloseInputStream(pfd).use { input -> out.outputStream().use { input.copyTo(it) } }
            val max = slot.connector.info.maxMediaBytes
            if (max > 0 && out.length() > max) {
                out.delete()
                throw IllegalArgumentException("media larger than $max bytes")
            }
            out
        }
        slot.store.putSend(SendResult().apply {
            this.localId = localId
            chatId = request.chatId
            state = NetworkStore.QUEUED
            timeMs = now()
        })
        val out = Outgoing(localId, request.text, request.kind, file, request.mime, request.fileName, request.durationS, request.replyToId)
        hold(slot, HOLD_CONNECT_MS)
        work.execute {
            try {
                val id = slot.connector.send(request.chatId, out)
                finishSend(slot, localId, request.chatId, id, null)
            } catch (e: Exception) {
                Log.w(TAG, "${slot.id}: send failed (${e.javaClass.simpleName})")
                finishSend(slot, localId, request.chatId, null, e.message ?: e.javaClass.simpleName)
            } finally {
                file?.delete()
            }
        }
        return localId
    }

    private fun finishSend(slot: Slot, localId: String, chatId: String, messageId: String?, error: String?) {
        slot.store.putSend(SendResult().apply {
            this.localId = localId
            this.chatId = chatId
            state = if (messageId != null) SendResult.SENT else SendResult.FAILED
            this.messageId = messageId
            this.error = error
            timeMs = now()
        })
        emit(Event.TYPE_SEND, slot.id, now(), chatId, localId)
    }

    fun markRead(network: String, chatId: String, messageId: String) {
        val slot = slot(network)
        val message = slot.store.message(chatId, messageId) ?: throw IllegalArgumentException("unknown message")
        slot.store.markRead(chatId)
        emit(Event.TYPE_CHAT, network, now(), chatId)
        if (slot.connector.isLoggedIn) {
            hold(slot, HOLD_CONNECT_MS)
            work.execute {
                try {
                    slot.connector.markRead(chatId, message)
                } catch (e: Exception) {
                    Log.w(TAG, "$network: read receipt failed (${e.javaClass.simpleName})")
                }
            }
        }
    }

    // Events

    fun setListener(l: IBridgeListener?) {
        synchronized(lock) {
            listener?.asBinder()?.let { runCatching { it.unlinkToDeath(death, 0) } }
            listener = l
            l?.asBinder()?.linkToDeath(death, 0)
        }
        _attached.value = l != null
        slots.values.forEach { reconcile(it) }
    }

    private val death = android.os.IBinder.DeathRecipient { setListener(null) }

    fun events(afterSeq: Long, limit: Int): List<Event> = log.after(afterSeq, limit.coerceIn(1, MAX_PAGE)).mapNotNull(::materialize)

    fun ack(seq: Long) = log.ack(seq)

    /** Turns a stored row into the event Messages reads, from the network's current data; gone data drops it. */
    private fun materialize(row: EventLog.Row): Event? {
        val slot = slots[row.network] ?: return null
        val event = Event().apply {
            seq = row.seq
            type = row.type
            network = row.network
            timeMs = row.timeMs
        }
        when (row.type) {
            Event.TYPE_ACCOUNT -> event.account = slot.state
            Event.TYPE_CHAT -> event.chat = row.chatId?.let(slot.store::chat) ?: return null
            Event.TYPE_MESSAGE, Event.TYPE_MEDIA -> event.message = slot.store.message(row.chatId ?: return null, row.messageId ?: return null) ?: return null
            Event.TYPE_SEND -> event.send = slot.store.send(row.messageId ?: return null) ?: return null
            Event.TYPE_READ -> {
                event.chatId = row.chatId
                event.messageId = row.messageId
            }
            else -> return null
        }
        return event
    }

    /** Stores an event and queues it for the listener. Under the lock, so pushes keep seq order. */
    private fun emit(type: Int, network: String, timeMs: Long, chatId: String? = null, messageId: String? = null) {
        lastActivityMs = now()
        synchronized(lock) {
            val row = log.append(type, network, timeMs, chatId, messageId)
            queue(if (listener != null) materialize(row) else null)
        }
    }

    /** Login steps go only to a listener: they mean nothing later, so they get seq 0 and aren't stored. */
    private fun emitLogin(network: String, step: LoginStep) {
        lastActivityMs = now()
        if (listener == null) return
        queue(Event().apply {
            type = Event.TYPE_LOGIN
            this.network = network
            timeMs = now()
            login = step
        })
    }

    /** Batches pushes, so a history sync of thousands of messages makes a few binder calls, not thousands. */
    private fun queue(event: Event?) {
        synchronized(lock) {
            if (event != null) outgoing.add(event)
            if (!flushScheduled) {
                flushScheduled = true
                timer.schedule(::flush, FLUSH_MS, TimeUnit.MILLISECONDS)
            }
        }
    }

    private fun flush() {
        val (l, batch) = synchronized(lock) {
            flushScheduled = false
            val batch = outgoing.toList()
            outgoing.clear()
            listener to batch
        }
        if (l == null) {
            val t = now()
            if (log.pending() > 0 && t - lastWakeMs >= WAKE_GAP_MS) {
                lastWakeMs = t
                wakeMessages()
            }
            return
        }
        try {
            batch.chunked(PUSH_BATCH).forEach { l.onEvents(it.toTypedArray()) }
        } catch (e: RemoteException) {
            // Messages died; its events stay stored for the next time it reads.
            setListener(null)
        }
    }

    private inner class Sink(private val slot: Slot) : ConnectorSink {
        override fun state(state: AccountState) {
            // A disconnect the hub asked for is sleep, not a lost connection.
            val s = if (slot.wantDisconnect && state.state == AccountState.TRANSIENT_DISCONNECT) {
                account(slot.id, AccountState.SLEEPING, slot.state)
            } else {
                state.also { it.network = slot.id; if (it.sinceMs == 0L) it.sinceMs = now() }
            }
            setState(slot, s)
        }

        override fun chat(chat: Chat) {
            chat.network = slot.id
            slot.store.putChat(chat)
            emit(Event.TYPE_CHAT, slot.id, now(), chat.id)
        }

        override fun message(message: Message) {
            message.network = slot.id
            if (slot.store.putMessage(message)) {
                emit(Event.TYPE_MESSAGE, slot.id, now(), message.chatId, message.id)
            }
        }

        override fun media(chatId: String, messageId: String, file: File?) {
            slot.store.setMedia(chatId, messageId, file)
            emit(Event.TYPE_MEDIA, slot.id, now(), chatId, messageId)
        }

        override fun readElsewhere(chatId: String, messageId: String) {
            slot.store.markRead(chatId)
            emit(Event.TYPE_READ, slot.id, now(), chatId, messageId)
        }

        override fun login(step: LoginStep) {
            step.network = slot.id
            onLoginStep(slot, step)
            emitLogin(slot.id, step)
        }
    }

    fun close() {
        slots.values.forEach {
            it.connector.close()
            it.store.close()
        }
        log.close()
    }

    companion object {
        private const val TAG = "Bridge"
        const val HOLD_CONNECT_MS = 60_000L
        const val HOLD_AFTER_LOGIN_MS = 5 * 60_000L
        private const val MAX_PAGE = 500
        private const val PUSH_BATCH = 50
        private const val FLUSH_MS = 30L
        private const val WAKE_GAP_MS = 2_000L

        fun account(network: String, state: Int, from: AccountState? = null, error: String? = null) = AccountState().apply {
            this.network = network
            this.state = state
            accountId = from?.accountId
            displayName = from?.displayName
            this.error = error
            sinceMs = System.currentTimeMillis()
        }
    }
}
