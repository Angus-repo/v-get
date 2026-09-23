# V-Get build and signing rules

- Preserve the signing identity used by the user-installed V-Get 1.2.1 APK.
- Before delivering an APK, restore the privately retained `V-Get-debug-signing.keystore` and follow `docs/SIGNING.md`.
- Never generate a replacement signing key, silently use a machine's default debug key, or change the pinned certificate to make a build pass.
- Do not commit, log, or upload the keystore/private key to this public repository or build artifacts. Repository secrets and private backups are the supported storage locations.
- Keep application ID `com.vget.app`; increment `versionCode` when releasing changed application behavior.
- Verify the final APK certificate with `apksigner verify --print-certs` and compare it with the fingerprint in `docs/SIGNING.md` before delivery.
- Unit tests and lint may run without the signing key. APK packaging must use the retained key.
