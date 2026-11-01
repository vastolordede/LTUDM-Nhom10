# R48 + R54: Windows JavaFX distribution and CI

PRs run `gradle clean check build` using JDK 21 and validate `fx:id` and FXML
handlers. A version tag such as `v0.2.0` or manual CI dispatch additionally
builds a Windows **app-image ZIP** using JDK 21 `jpackage`. This is not yet an
MSI/EXE installer and does not distribute a code-signing certificate.

## Build locally on Windows

1. Install **JDK 21**, run `java -version`, `jpackage --version` and install
   Gradle 8.10.2 (or use a validated Gradle wrapper).
2. Run `gradle clean test :peer-client:installDist`.
3. Run `powershell -ExecutionPolicy Bypass -File scripts/build_windows.ps1`.
4. Unzip `dist/FileShare-P2P-windows.zip` and run
   `FileShare-P2P/FileShare-P2P.exe`.
5. Repeat on a **clean Windows 10/11 virtual machine without JDK installed**.
   Test JavaFX startup, HTTPS truststore selection, register/login,
   share/search/download, logout and local config preservation.

The app image includes the JDK Java runtime, Gradle application dependencies
and JavaFX jars/natives provided by Windows build. It does NOT include
Tracker/private certificate material. Ship external trusted certs/config
through a separate controlled installer. Keep `P2P_TICKET_PUBLIC_KEY_B64`
available on each uploading Peer (R43).

## Known release gates

- The current build is **not confirmed on Windows** or a clean VM.
- Never put Ed25519 private keys/passwords or `.env` files in the artifact.
- `check_fxml_bindings.py` is a structural check, not a JavaFX visual test.
- JUnit integration tests do not prove PostgreSQL migration on a real DB.
- Before tagging, check PR status and record CI build URL, ZIP SHA-256,
  screenshots, and SHA-256 transfer evidence in R58 matrix.

## Existing GitHub Actions workflow

This patch deliberately does NOT replace the pre-existing team-owned
`.github/workflows/p2p-ci.yml`. Its CI/Windows packaging jobs live in
`.github/workflows/p2p-pr36-extended-ci.yml` instead. This additional workflow may overlap with
the existing team CI on PRs; review and consolidate the two workflows
with the team before considering production/release readiness.
