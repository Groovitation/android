## Plan

1. Add a native bridge entrypoint for Google sign-in that first tries Credential Manager.
2. Exchange successful Google ID tokens with the backend for a Groovitation session, then route the WebView through native-authenticate.
3. Fall back to the external browser OAuth flow when Credential Manager is unavailable, canceled, or fails.
4. Add/update unit coverage and include the mandatory Android version bump.
