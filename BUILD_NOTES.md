# Build notes

- Pushes to `main` trigger the Android APK workflow.
- Debug APK is built on every run.
- Signed release APK is built when all Android signing repository secrets are configured.
- Keep the release keystore and its passwords backed up securely. Never commit them to this repository.
