# Release signing — canonical guide

> **This is the single source of truth for Hexora's release signing.**
> Future agents: read this before touching releases, workflows, or
> anything signing-related. The short red-line summary also lives in
> `AGENTS.md` §8.

## The one rule that overrides everything

**The release keystore and its password are never committed to this
repository — not as files, not Base64-in-a-text-file, not "just this
once", not because the repository is private.**

A leaked Android release key cannot be revoked: anyone holding it can
sign fake "updates" as Hexora forever. Private repos get new
collaborators, get made public, get backed up, and leak in ways nobody
planned. The key therefore lives only in:

1. **GitHub Actions secrets** (encrypted, write-only), and
2. The **owner's offline backup** (see below).

`.gitignore` already blocks `*.p12`, `*.pfx`, `*.jks`, `*.keystore`, and
`keystore.properties`. Do not circumvent it.

## Current setup (already configured — do not redo)

- Dedicated PKCS#12 keystore, alias **`hexora-release`**, RSA 2048,
  100-year validity. One password serves as both store and key password.
- Gradle (`app/build.gradle.kts`) has a `release` signing config that is
  activated **only** when CI provides these environment variables:
  `HEXORA_RELEASE_STORE_FILE` (path to the decoded keystore in the runner
  temp dir) and `HEXORA_RELEASE_KEYSTORE_PASSWORD`. Without them, release
  builds are unsigned — that is intentional for local builds.
- GitHub secret names (owner-chosen; **use exactly these**):

  | Secret | Content |
  | --- | --- |
  | `HEXORA_WEB_BROWSER_RELEASE_KEYSTORE_BASE64` | Base64 of the keystore file |
  | `HEXORA_WEB_BROWSER_RELEASE_KEYSTORE_PASSWORD` | The keystore/key password |

  They exist as repository secrets or organization secrets scoped to
  this repository — both work. Secret **names** are safe to mention
  anywhere; secret **values** must never appear in chat, logs, commits,
  issues, or screenshots.
- The owner downloaded the one-time backup ZIP
  (`hexora-release-key-backup`: `hexora-release.p12`, `password.txt`,
  `keystore-base64.txt`, `certificate-fingerprint.txt`) and keeps it
  offline. That ZIP *is* the key backup.

## How to ship a signed release

1. Bump `versionName` and **`versionCode`** (must strictly increase) in
   `app/build.gradle.kts`. Update version references in `README.md` and
   `AGENTS.md`.
2. Commit and push; wait for the **Build debug APK** CI to go green.
3. Start the signed build, one of two ways, both `main`-only (the job
   refuses any other ref, `if: github.ref == 'refs/heads/main'`):
   - Actions → **Build signed release** → Run workflow, with `main`
     selected; or
   - push a change to `signing/release-request.txt` on `main` — the touch
     is the trigger, and it exists because workflow dispatch is not always
     available. Bump the line in that file each time, so the next release
     is a new commit rather than an empty one.
4. In `.github/workflows/build-release-apk.yml`, CI will:
   - fail fast if either secret is missing,
   - decode the keystore into `$RUNNER_TEMP` (deleted with the runner),
   - run `./gradlew assembleRelease bundleRelease`,
   - verify every APK with `apksigner verify --print-certs` and the AAB
     with `jarsigner -verify`,
   - publish GitHub Release **`v<versionName>`** with
     `app-arm64-v8a-release.apk`, `app-armeabi-v7a-release.apk`,
     `app-x86_64-release.apk`, and `app-release.aab` (re-running uploads
     with `--clobber` to the same tag).
5. Verify the run is green and the release contains all four assets.
   Then hand the owner the release links.

## Debug vs. release signatures

- Debug builds are signed with a stable, CI-cached **debug** keystore
  (workflow `build-debug-apk.yml`), so debug updates install over each
  other.
- Release builds use the **dedicated release key** above.
- The two signatures differ, so Android refuses to install release over
  debug or vice versa ("signature mismatch"). The fix is to uninstall
  the other build first — this is expected, not a bug.

## "But Android still calls it unsafe / unknown source!"

Expected, and **not** a signing problem:

- A signature proves *integrity and update identity*: the APK was not
  modified after signing, and only the key holder can produce updates
  Android will accept over the installed app.
- The install warning judges the **source**: APKs opened from a browser
  or file manager always trigger the per-source "Install unknown apps"
  permission, and Play Protect may additionally scan/block sideloaded
  apps it has no reputation for — for every app, however well signed.
- The only way to make the prompt disappear is distribution through a
  trusted channel: **Google Play** (upload `app-release.aab`, enroll in
  Play App Signing, users install from the store) or a managed
  enterprise deployment. Do not attempt to silence it by disabling
  security settings, and do not re-sign or repackage to chase it.

## If something goes wrong

| Symptom | Likely cause / fix |
| --- | --- |
| Release workflow fails at "Check release signing secrets" | A secret is missing or its **name** differs. Secret names must match the table above exactly. Values are never shown by GitHub — if unsure, delete and recreate the secret from the owner's offline backup. |
| Signing fails / keystore cannot be opened | The Base64 secret is corrupted (extra whitespace is usually tolerated by the decoder, but a truncated paste is not). Recreate `..._KEYSTORE_BASE64` from `keystore-base64.txt` in the backup. |
| "App not installed" / package conflict on the phone | The other build flavor (debug vs release) is installed. Uninstall it first. |
| The **Build signed release** workflow is not listed in Actions | Manual dispatch requires the workflow file on the **default branch**. Merge it there first; releases are deliberately not buildable from an unmerged branch any more. |
| Owner lost the secrets | Re-add them from the offline backup ZIP (`keystore-base64.txt` + `password.txt`). |
| Owner lost the backup **and** the secrets | The signing identity is gone. A new key can be generated (below), but existing installs can **never** be updated — every user must uninstall/reinstall, and a published Play app cannot switch upload keys outside Play's own reset process. State this consequence and get explicit owner confirmation before doing anything. |

## Generating a key — only if there is no usable key anymore

The generator workflow that created this key has been **removed from the
repository**. It created a keystore on a runner and published it as a workflow
artifact, which was acceptable while the repository was private and became a
liability the moment it was public: anyone able to open the run could download
the key. The owner already holds the key and its offline backup, so the workflow
had no remaining job.

If a new key is ever genuinely needed, generate it **outside** GitHub and never
publish the file:

```bash
keytool -genkeypair -v -keystore hexora-release.p12 -storetype PKCS12 \
  -alias hexora-release -keyalg RSA -keysize 2048 -validity 36500
```

Then, with the owner's explicit confirmation that the old identity is being
abandoned (existing installs can never be updated afterwards): replace both
secrets with the new values, replace the offline backup, and state the
consequence in writing.

## Forks, copies and other people's builds

GitHub secrets are **per repository**. They are not copied by a fork, not
inherited by a clone, and not passed to a workflow run triggered by a pull
request from a fork. A fork of this repository therefore:

- cannot read, use or reproduce the release key — its own Actions run with its
  own (empty) secret store, and `Build signed release` fails fast at
  "Check release signing secrets";
- can still build **debug** APKs freely (they are signed with a throwaway debug
  key), which is enough to run a rebranded version on a phone;
- can publish its own release build only after adding **its own** keystore as
  secrets in **its own** repository.

The one thing a fork cannot do is produce an APK that Android will accept as an
update to this app, because that requires this key (or Play App Signing).
