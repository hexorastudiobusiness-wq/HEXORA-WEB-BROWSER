# Release signing

Hexora's release builds are signed with a dedicated release key. The
keystore and its password are **never committed to this repository** — not
even though the repository is private. Anyone who obtains the release key
can sign fake updates as this app forever, and a leaked Android release key
cannot be revoked.

Instead, the key lives in the repository's encrypted GitHub Actions
secrets (Settings → Secrets and variables → Actions):

| Secret | Content |
| --- | --- |
| `HEXORA_WEB_BROWSER_RELEASE_KEYSTORE_BASE64` | Base64 encoding of the PKCS#12 release keystore |
| `HEXORA_WEB_BROWSER_RELEASE_KEYSTORE_PASSWORD` | Keystore/key password (one password for both) |

The **Build signed release** workflow decodes the keystore into the
runner's temporary directory, builds `assembleRelease` + `bundleRelease`,
verifies every APK signature with `apksigner`, publishes the signed files
to a GitHub Release, and the temporary keystore disappears with the runner.

## One-time setup (browser only, nothing to install)

1. Run **Actions → Generate release key → Run workflow**. It creates the
   keystore on the runner and offers a `hexora-release-key-backup`
   artifact download (kept only 1 day).
2. Download and extract that ZIP and keep it somewhere safe and offline —
   it *is* the release key backup. Losing the key means the installed app
   can never be updated with a differently-signed build.
3. Open `keystore-base64.txt`, copy everything, and create the
   `HEXORA_WEB_BROWSER_RELEASE_KEYSTORE_BASE64` secret. Open `password.txt`, copy it,
   and create the `HEXORA_WEB_BROWSER_RELEASE_KEYSTORE_PASSWORD` secret.
4. Delete the **Generate release key** workflow run so the key does not
   remain on GitHub's artifact storage.
5. Run **Actions → Build signed release → Run workflow**.

Release-signed builds use a different certificate than the debug builds,
so Android will not install a release build over the debug app: uninstall
the debug version first (or install the release build on a clean device).
