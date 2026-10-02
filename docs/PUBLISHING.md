# Publishing to JetBrains Marketplace

How to release the plugin, from the first manual upload to automated updates. Publishing is always an explicit
action: nothing is uploaded by an ordinary push or pull request.

| | |
| --- | --- |
| Plugin name | `pytest-html-reporter` |
| Plugin ID | `io.github.prashanthsams.pytesthtmlreporter` - **permanent**; Marketplace never allows it to change |
| Vendor | Prashanth Sams |
| License | MIT |
| Source | https://github.com/prashanth-sams/pytest-html-reporter-jetbrains |
| Distribution | `build/distributions/pytest-html-reporter-<version>.zip` (from `./gradlew buildPlugin`) |

## 1. Prepare a release

1. Set `pluginVersion` in `gradle.properties` (semantic versioning; Marketplace rejects a version it already has).
   A pre-release suffix publishes to a custom channel: `0.2.0-beta.1` goes to the `beta` channel, `0.2.0` to the
   default (stable) channel.
2. In `CHANGELOG.md`, move the entries from `## [Unreleased]` into `## [<version>] - <date>`. That section becomes the
   plugin's change notes on Marketplace (`./gradlew patchChangelog` does the move if you prefer).
3. Run the checks and build the ZIP:

   ```bash
   ./gradlew clean :buildPlugin :test :verifyPluginStructure :verifyPluginProjectConfiguration
   # real end-to-end runs: one interpreter with pytest-html-reporter, one with pytest only
   PYTEST_HTML_REPORTER_PYTHON=/path/to/venv/bin/python \
   PYTEST_HTML_REPORTER_PYTHON_WITHOUT_REPORTER=/path/to/other/python ./gradlew :test
   ./gradlew :verifyPlugin                                              # every IDE in verifyIdes
   scripts/smoke-test.sh                                                # optional: sandbox IDE smoke test
   ```

   `verifyPlugin` downloads every IDE listed in `verifyIdes` (1-1.5 GB each). With little disk space, verify one at
   a time: `./gradlew :verifyPlugin -PverifyIdes=PyCharm:2026.2.3`. CI runs one IDE per job.
4. Install the ZIP in a real IDE once (<kbd>Settings</kbd> | <kbd>Plugins</kbd> | <kbd>⚙</kbd> |
   <kbd>Install Plugin from Disk…</kbd>) and try **Run pytest with HTML Report** on a project.

## 2. First upload (manual, one time)

JetBrains Marketplace requires the first version of a plugin to be uploaded through the website; the Gradle
`publishPlugin` task and the release workflow can only update a plugin that already exists.

1. Sign in at <https://plugins.jetbrains.com> with the JetBrains Account that will own the plugin.
2. Open the account menu (top right) and choose **Upload plugin**.
3. **Vendor profile.** The first time, accept the *JetBrains Marketplace Developer Agreement* and create a vendor
   profile: type *Individual* (or an organization, if one should own the plugin), name **Prashanth Sams**, a public
   contact email and the website `https://github.com/prashanth-sams`. Later uploads pick this vendor.
4. **Plugin details:**
   - **Plugin file:** `build/distributions/pytest-html-reporter-0.4.3.zip` (or the `-signed.zip` from
     `./gradlew signPlugin`, see section 3). The maximum size is 400 MB; this plugin is well under 1 MB.
   - **License:** choose **MIT**. Marketplace asks for the source code URL for open-source licenses:
     `https://github.com/prashanth-sams/pytest-html-reporter-jetbrains`.
   - **Tags:** see [MARKETPLACE.md](MARKETPLACE.md) (`Testing`, `Tools Integration`, `Viewer`, `Reporting`).
   - **Channel:** leave the default (Stable) for `0.4.3`.
   - **Hidden:** leave off, unless the plugin should stay unlisted after approval.
5. Submit. JetBrains reviews every new plugin against the
   [approval guidelines](https://plugins.jetbrains.com/legal/approval-guidelines) before it is listed; you get an
   email with the result. Fix any requested changes, bump the version and upload again.
6. While the review runs, complete the plugin page (<kbd>Edit</kbd> on the plugin's page):
   - Links: **Source code**, **Issue tracker** (`…/pytest-html-reporter-jetbrains/issues`),
     **Documentation** (`…/pytest-html-reporter-jetbrains#readme`).
   - **Media:** upload the screenshots from `docs/screenshots/` in the order given in
     [MARKETPLACE.md](MARKETPLACE.md) (Marketplace recommends 1280×800 or larger; the files there are 2880×1800, the
     settings capture 2560×1760).
   - Check that the description, change notes and compatibility range shown match this release.

## 3. Signing (recommended)

Marketplace signs every plugin it distributes. Signing with your own key as well lets the IDE show that the upload
came from you. The keys are generated once and kept outside the repository.

```bash
openssl genpkey -aes-256-cbc -algorithm RSA -out private_encrypted.pem -pkeyopt rsa_keygen_bits:4096
openssl rsa -in private_encrypted.pem -out private.pem
openssl req -key private.pem -new -x509 -days 365 -out chain.crt
```

The build reads the material only from environment variables:

| Variable | Content |
| --- | --- |
| `CERTIFICATE_CHAIN` | the full text of `chain.crt` |
| `PRIVATE_KEY` | the full text of `private.pem` |
| `PRIVATE_KEY_PASSWORD` | the password chosen for `private_encrypted.pem` |

Sign locally with:

```bash
export CERTIFICATE_CHAIN="$(cat chain.crt)" PRIVATE_KEY="$(cat private.pem)" PRIVATE_KEY_PASSWORD='…'
./gradlew :signPlugin :verifyPluginSignature  # build/distributions/pytest-html-reporter-<version>-signed.zip
```

Never commit these files (`.gitignore` excludes `*.pem`, `*.crt` and `*.key`). Renew the certificate before its
`-days` run out.

## 4. Later updates (Gradle / GitHub Actions)

Once the first version is on Marketplace:

1. Create a Marketplace token: <https://plugins.jetbrains.com/author/me/tokens> → **Generate Token**. It is shown
   only once.
2. In the GitHub repository: <kbd>Settings</kbd> | <kbd>Environments</kbd> | **New environment** named `marketplace`
   (optionally with *Required reviewers*, so every publish waits for an approval), and add these environment
   secrets: `PUBLISH_TOKEN`, `CERTIFICATE_CHAIN`, `PRIVATE_KEY`, `PRIVATE_KEY_PASSWORD`.
3. Release: prepare the version (section 1), merge it to `main`, then create a GitHub release with the tag
   `v<pluginVersion>` (for example `v0.1.1`). The **Release** workflow (`.github/workflows/release.yml`) checks that
   the tag matches `pluginVersion`, runs the tests, signs, publishes with `./gradlew publishPlugin`, and attaches the
   signed ZIP to the GitHub release.
   It can also be started by hand from the Actions tab; it then only runs when `publish` is typed into the
   confirmation field.

To publish from a workstation instead:

```bash
export PUBLISH_TOKEN='…' CERTIFICATE_CHAIN="$(cat chain.crt)" PRIVATE_KEY="$(cat private.pem)" PRIVATE_KEY_PASSWORD='…'
./gradlew :publishPlugin
```

Every update is checked by JetBrains before it becomes available; updates are usually approved faster than the first
upload. Marketplace also runs the Plugin Verifier against new IDE releases and emails the vendor when a published
version becomes incompatible.

## 5. Keeping up with new IDE versions

The plugin declares `since-build 251` and no `until-build`, following the IntelliJ Platform Gradle Plugin's guidance
for 2024.3+ (an upper bound would block installation after users update their IDE). To keep that promise:

1. When a new major IDE version is released (or reaches EAP), add it to `verifyIdes` in `gradle.properties`, or start
   the **Build** workflow by hand with *Extra IDE for the Plugin Verifier* set (for example `PyCharm:2026.3`).
2. Fix anything the verifier reports, then release a new version.
3. Raise `pluginSinceBuild` (and `platformVersion`) only when dropping old IDEs; the oldest supported IDE is also the
   SDK the plugin compiles against, and its bundled Kotlin standard library caps the `apiVersion` in
   `build.gradle.kts`.

## Release checklist

- [ ] `pluginVersion` bumped; `CHANGELOG.md` has the version's section.
- [ ] `./gradlew :buildPlugin :test :verifyPluginStructure :verifyPluginProjectConfiguration` passes.
- [ ] End-to-end tests pass with real interpreters (`PYTEST_HTML_REPORTER_PYTHON` and
      `PYTEST_HTML_REPORTER_PYTHON_WITHOUT_REPORTER`; without them those tests return early and still show as passed).
- [ ] Plugin Verifier: compatible with every IDE in `verifyIdes` (the Build workflow shows one job per IDE).
- [ ] ZIP installed and tried in an IDE.
- [ ] GitHub release `v<version>` created → Release workflow green → version visible on Marketplace after review.
