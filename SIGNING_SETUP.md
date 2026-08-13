# CI signing setup

GitHub Actions requires a persistent Android signing key supplied through repository Actions secrets. Do not commit keystores or passwords to this repository.

Required repository secrets:

- `ANDROID_SIGNING_KEYSTORE_BASE64` — base64-encoded persistent keystore file.
- `ANDROID_SIGNING_STORE_PASSWORD` — keystore password.
- `ANDROID_SIGNING_KEY_ALIAS` — signing-key alias.
- `ANDROID_SIGNING_KEY_PASSWORD` — signing-key password.

The workflow validates the restored certificate and the final APK against the established Local Baby Monitor signer fingerprint before uploading an artifact. If any secret is missing or the certificate does not match, the build fails instead of falling back to an ephemeral runner debug key.
