# Release signing

Hexora's release builds are signed with a dedicated release key. The
keystore and its passwords are **never committed to this repository** — not
even though the repository is private. Anyone who obtains the release key
can sign fake updates as this app forever, and a leaked Android release key
cannot be revoked.

Instead, the key lives in the repository's encrypted GitHub Actions
secrets (Settings → Secrets and variables → Actions):

| Secret | Content |
| --- | --- |
| `HEXORA_RELEASE_KEYSTORE_BASE64` | Base64 encoding of the PKCS#12 release keystore |
| `HEXORA_RELEASE_STORE_PASSWORD` | Keystore password |
| `HEXORA_RELEASE_KEY_ALIAS` | Key alias (`hexora-release`) |
| `HEXORA_RELEASE_KEY_PASSWORD` | Key password |

The **Build signed release** workflow decodes the keystore into the
runner's temporary directory, builds `assembleRelease` + `bundleRelease`,
verifies every APK signature with `apksigner`, publishes the signed files
to a GitHub Release, and the temporary keystore disappears with the runner.

## Owner checklist (one time)

1. Keep an offline backup of the keystore and its password in a password
   manager or an equally safe place. If the key is lost, the installed app
   can never be updated with a differently-signed build.
2. Make sure the four secrets above exist. GitHub shows only their names,
   never their values.
3. Run **Actions → Build signed release → Run workflow**.

Release-signed builds use a different certificate than the debug builds,
so Android will not install a release build over the debug app: uninstall
the debug version first (or install the release build on a clean device).
