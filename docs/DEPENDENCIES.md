# Third-party dependency register

## Android runtime

| Component | Version | License | Purpose | Review status |
|---|---|---|---|---|
| `com.wireguard.android:tunnel` | `1.0.20260102` | Apache-2.0 | Official embeddable WireGuard Android tunnel library and userspace backend | Source/API reviewed; Android device validation pending |
| `androidx.core:core-ktx` | `1.15.0` | Apache-2.0 | Android platform compatibility helpers | Declared |
| `androidx.activity:activity-ktx` | `1.10.0` | Apache-2.0 | Android activity integration | Declared |
| `com.android.tools:desugar_jdk_libs` | `2.0.3` | Apache-2.0 | Java API desugaring required by upstream tunnel library | Declared |
| `junit:junit` | `4.13.2` | EPL-1.0 | Local JVM unit tests | Test only |

## WireGuard integration notes

- Upstream embedding guidance: https://www.wireguard.com/embedding/
- Upstream Android source mirror: https://github.com/WireGuard/wireguard-android (the upstream README identifies the canonical source at https://git.zx2c4.com/wireguard-android).
- Maven Central coordinates: `com.wireguard.android:tunnel:1.0.20260102`.
- The upstream AAR contributes `com.wireguard.android.backend.GoBackend$VpnService` through manifest merging. This is the sole VPN service/TUN provider in the initial WireGuard integration.
- The application controller is the only app-level caller of the adapter. No second VPN service is declared.
- R8 keep rules preserve GoBackend and its JNI entry points for the native userspace library.
- The configuration is parsed in memory and is not logged or written to disk by this adapter.
- This source integration is not evidence of successful packet flow, handshake, DNS correctness, leak prevention, or production readiness. Those require a real authorized profile, a deployed endpoint, and physical Android tests.
