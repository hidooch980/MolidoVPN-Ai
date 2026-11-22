# Xray Android AAR dependency-license audit

Audit date: 2026-11-22  
Molido source commit: `39f7f44bc04f7b6ba620e761093c83dfa8972601`  
Pinned AndroidLibXrayLite source commit: `48f35be868ee247e53a8036e284a8c2f97c80068`  
Go toolchain: `go1.26.7`  
Android NDK: `28.2.13676358`  
Target: Android ARM64, API 26  
AAR: `libv2ray.aar`  
AAR SHA-256: `b5ddb1314247b5020c3205a2f634871fbe7cf4571bfbf5fcc59d5caa34fb14cb`

## Evidence

The CI job generated `license-report.csv` using pinned `go-licenses@v1.6.0` against the checked-out wrapper's Go package tree. The CSV contains 62 dependency rows and has no header row. The AAR build completed and its only native library entry is `jni/arm64-v8a/libgojni.so`; ELF inspection reports ELF64, little-endian, AArch64. The AAR contains the compiled Go library plus Java classes and GeoIP/GeoSite data assets.

## License inventory

| License identifier | Rows |
|---|---:|
| BSD-3-Clause | 21 |
| MIT | 18 |
| Apache-2.0 | 13 |
| BSD-2-Clause | 3 |
| MPL-2.0 | 2 |
| GPL-3.0 | 2 |
| LGPL-3.0 | 2 |
| CC0-1.0 | 1 |
| **Total** | **62** |

Material findings from the report:

| Dependency | Reported license | Distribution review needed |
|---|---|---|
| `github.com/GFW-knocker/AndroidLibXrayLite` | LGPL-3.0 | Yes; wrapper's own license must be assessed |
| `github.com/sagernet/sing-shadowsocks` | GPL-3.0 | Yes; determine whether included package code is linked/distributed and resulting obligations |
| `github.com/sagernet/sing/common` | GPL-3.0 | Yes; determine package-level inclusion and obligations |
| `github.com/juju/ratelimit` | LGPL-3.0 | Yes; assess use/linking and corresponding-source obligations |
| `github.com/GFW-knocker/Xray-core` | MPL-2.0 | Yes; preserve notices and assess covered-file/source obligations |
| `github.com/xtls/reality` | MPL-2.0 | Yes; preserve notices and assess covered-file/source obligations |

## Limits and decision

- This is an automated inventory, not a legal opinion or a license-compatibility determination.
- The Go build-info module list records module graph metadata; by itself it does not prove that every listed module contributed object code to the final shared library.
- The `go-licenses report ./...` output is more specific than a raw module graph, but the final release review still needs a reproducible package-to-binary/SBOM mapping and inspection of the wrapper's complete source/license terms.
- The report's wrapper URL points to `LICENSE` at `HEAD` and the checkout metadata reports an empty module version. The exact checked-out commit is pinned in CI, but the corresponding license text must be archived and hash-verified for a distribution decision.
- No legal compatibility clearance is claimed. Do not redistribute this AAR or an APK containing it as a production release until a qualified review confirms the applicable obligations and the project has assembled all required notices/source offers.
- Keep Xray non-selectable and the release gate blocked until the legal and technical integration gates both pass.
