# WireGuard GoBackend exposes native JNI methods whose names must remain stable.
-keep class com.wireguard.android.backend.GoBackend { *; }
-keep class com.wireguard.android.backend.GoBackend$VpnService { *; }
