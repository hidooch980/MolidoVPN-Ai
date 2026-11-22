package ai.molido.vpn.engine.process

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.Process
import ai.molido.vpn.core.EngineSlot

/**
 * Private process-isolation endpoint for native engines with incompatible Go runtimes.
 *
 * This service deliberately does not create a VPN interface, acquire a TUN descriptor,
 * accept profile secrets, or start a native engine. It only proves process separation
 * and reports NOT_IMPLEMENTED until a reviewed engine-specific IPC adapter is installed.
 */
abstract class NativeEngineProcessService : Service() {
    protected abstract val engineSlot: EngineSlot

    private val messenger by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Messenger(Handler(Looper.getMainLooper(), ::handleMessage))
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    private fun handleMessage(message: Message): Boolean {
        if (message.what != MSG_QUERY_STATUS) return false
        val reply = message.replyTo ?: return true
        val response = Message.obtain(null, MSG_STATUS)
        response.data = android.os.Bundle().apply {
            putString(KEY_ENGINE_SLOT, engineSlot.name)
            putString(KEY_READINESS, READINESS_NOT_IMPLEMENTED)
            putInt(KEY_PROCESS_ID, Process.myPid())
            putBoolean(KEY_TUN_ATTACHED, false)
        }
        return try {
            reply.send(response)
            true
        } catch (_: android.os.RemoteException) {
            false
        }
    }

    companion object {
        const val MSG_QUERY_STATUS = 1
        const val MSG_STATUS = 2
        const val KEY_ENGINE_SLOT = "engine_slot"
        const val KEY_READINESS = "readiness"
        const val KEY_PROCESS_ID = "process_id"
        const val KEY_TUN_ATTACHED = "tun_attached"
        const val READINESS_NOT_IMPLEMENTED = "NOT_IMPLEMENTED"
    }
}

class XrayEngineProcessService : NativeEngineProcessService() {
    override val engineSlot = EngineSlot.V2RAY_XRAY
}

class AmneziaEngineProcessService : NativeEngineProcessService() {
    override val engineSlot = EngineSlot.AMNEZIA_WG
}

class PsiphonEngineProcessService : NativeEngineProcessService() {
    override val engineSlot = EngineSlot.PSIPHON
}
