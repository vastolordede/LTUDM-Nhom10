# ZIP17: Desktop trust import, Windows app-image packaging

- At login UI, `Import Tracker PUBLIC key` selects the public `.b64` file. Enter fingerprint obtained independently from Tracker operator. A wrong fingerprint MUST NOT be installed; a different existing trusted key is NOT overwritten silently. Restart Peer app after installing a new key.
- This import is not a substitute for authenticated Tracker TLS: production requires valid certificate/truststore configuration and a matching Tracker name; passwords are still provided securely outside the app.
- `scripts/build_windows.ps1` now selects the Gradle **versioned** peer-client JAR and uses a non-Application bootstrap entrypoint. It refuses to delete a previous app-image automatically.
- Build on a Windows host: `.\gradlew.bat clean :peer-client:installDist :peer-client:test`, `powershell -ExecutionPolicy Bypass -File scripts/build_windows.ps1`.
- Unzip `dist/FileShare-P2P-windows.zip`, launch the bundled executable on a CLEAN Windows VM without JDK/Gradle, test login, settings, public key import, search, share, download, resume and update. Artifact was NOT built/tested on Windows in this patch generation environment.
- TLS trust material / Ed25519 private keys must never be embedded in distributable ZIPs.
