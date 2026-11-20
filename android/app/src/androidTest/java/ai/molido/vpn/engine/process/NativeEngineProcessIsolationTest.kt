package ai.molido.vpn.engine.process

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.Message
import android.os.Messenger
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.ServiceTestRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class NativeEngineProcessIsolationTest {
    @get:Rule val serviceRule = ServiceTestRule()

    private fun assertIsolated(service: Class<*>, expectedSlot: String) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val binder = serviceRule.bindService(Intent(context, service))
        val remote = Messenger(binder)
        val thread = HandlerThread("engine-process-test-callback").apply { start() }
        try {
            val responseRef = AtomicReference<Message?>()
            val latch = CountDownLatch(1)
            val callback = Messenger(Handler(thread.looper) { message ->
                responseRef.set(Message.obtain(message))
                latch.countDown()
                true
            })
            remote.send(Message.obtain(null, NativeEngineProcessService.MSG_QUERY_STATUS).apply {
                replyTo = callback
            })
            check(latch.await(5, TimeUnit.SECONDS)) { "Native engine process did not answer status query" }
            val response = requireNotNull(responseRef.get())
            assertEquals(NativeEngineProcessService.MSG_STATUS, response.what)
            assertEquals(expectedSlot, response.data.getString(NativeEngineProcessService.KEY_ENGINE_SLOT))
            assertEquals(
                NativeEngineProcessService.READINESS_NOT_IMPLEMENTED,
                response.data.getString(NativeEngineProcessService.KEY_READINESS)
            )
            assertNotEquals(Process.myPid(), response.data.getInt(NativeEngineProcessService.KEY_PROCESS_ID))
            assertFalse(response.data.getBoolean(NativeEngineProcessService.KEY_TUN_ATTACHED))
        } finally {
            thread.quitSafely()
        }
    }

    @Test fun xrayUsesSeparateProcessAndRemainsUnavailable() =
        assertIsolated(XrayEngineProcessService::class.java, "V2RAY_XRAY")

    @Test fun amneziaUsesSeparateProcessAndRemainsUnavailable() =
        assertIsolated(AmneziaEngineProcessService::class.java, "AMNEZIA_WG")

    @Test fun psiphonUsesSeparateProcessAndRemainsUnavailable() =
        assertIsolated(PsiphonEngineProcessService::class.java, "PSIPHON")
}
