package ai.molido.vpn.engine

import android.Manifest
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Guards the installed, dependency-merged manifest against accidentally shipping
 * more than one Android VPN service. This is a regression gate, not proof that
 * Molido owns TUN centrally: the current upstream GoBackend remains the provider.
 */
@RunWith(AndroidJUnit4::class)
class SingleVpnServiceManifestTest {
    @Suppress("DEPRECATION")
    @Test
    fun mergedApplicationManifestDeclaresExactlyOneVpnService() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val services = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_SERVICES)
            .services
            .orEmpty()

        val vpnServices = services.filter {
            it.permission == Manifest.permission.BIND_VPN_SERVICE
        }

        assertEquals(
            "The merged APK manifest must expose exactly one Android VPN service",
            1,
            vpnServices.size
        )
        assertEquals(
            "Until the central-service migration is complete, the pinned upstream GoBackend is the sole TUN provider",
            "com.wireguard.android.backend.GoBackend\$VpnService",
            vpnServices.single().name
        )
    }
}
