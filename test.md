You can test it locally in three ways, from quickest to most thorough.

## 1. Try it by hand in a sandbox IDE (best while you're developing)

```bash
./gradlew runIde
```

This opens a separate PyCharm Community 2025.1.6.2 with your plugin installed. It has its own config, so your normal IDE isn't affected. It needs a Python project to work on, and you can use the bundled sample tests:

```bash
cp -R scripts/fixture-project ~/pytest-sample
python3 -m venv ~/pytest-sample/.venv
~/pytest-sample/.venv/bin/pip install pytest-html-reporter
```

1. In the sandbox IDE, choose **File | Open** and pick `~/pytest-sample`.
2. Set `.venv` as the project interpreter.
3. Run **Tools | pytest-html-reporter | Run pytest with HTML Report**.

After you change the code, close the sandbox and run `./gradlew runIde` again.

## 2. Install the ZIP in your own PyCharm (closest to what users get)

```bash
./gradlew buildPlugin
```

Then go to **Settings | Plugins | ⚙ | Install Plugin from Disk…** and pick `build/distributions/pytest-html-reporter-0.1.0.zip`. No restart is needed. Uninstall it the same way before you install the Marketplace version later.

## 3. Automated checks

```bash
./gradlew test
```

That runs only the unit and platform tests. The end-to-end tests quietly return early and still show as passed unless you point them at real interpreters:

```bash
PYTEST_HTML_REPORTER_PYTHON=~/pytest-sample/.venv/bin/python \
PYTEST_HTML_REPORTER_PYTHON_WITHOUT_REPORTER=/path/to/python-with-pytest-only \
./gradlew test
```

The second interpreter needs pytest but **not** pytest-html-reporter, because it tests the offer to install the reporter.

Other checks:
- **Scripted sandbox run:** `scripts/smoke-test.sh` drives the whole workflow and saves screenshots plus a `results.json` under `build/smoke/`. `scripts/smoke-test.sh runSmokeTest262` does the same on PyCharm 2026.2.3.
- **Compatibility:** `./gradlew :verifyPlugin -PverifyIdes=PyCharm:2026.2.3` checks one IDE at a time. Each IDE is a 1–1.5 GB download.

One catch with option 1: `runIde` uses the 2025.1 SDK. If you point a plain `runIde` sandbox at 2026.2 or newer, the embedded report viewer won't render, because those versions need the installer build. The `runSmokeTest262` task is already set up for that.




### cmds
```bash
rm -rf ~/Documents/fixture-project/.idea
rm -rf ~/Documents/pytest-html-reporter-jetbrains/scripts/fixture-project/{.venv,.idea}


cd ~/Documents/pytest-html-reporter-jetbrains
./gradlew :runIde --args="$HOME/Documents/fixture-project"
```