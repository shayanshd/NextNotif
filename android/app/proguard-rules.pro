# NextNotif - app-specific R8/ProGuard rules for release builds.
#
# Coverage provided by third-party CONSUMER rules (already on the classpath,
# no need to duplicate here):
#
#   * OkHttp 4.12 (com.squareup.okhttp3:okhttp) ships consumer ProGuard rules
#     covering its internals (Okio, TLS, platform integrations).
#   * Jetpack Compose (androidx.compose:*) ships consumer R8 rules covering
#     reflection-based runtime access.
#   * kotlinx-coroutines ships consumer rules.
#   * AndroidX core / lifecycle / activity ship their own consumer rules.
#
# AGP additionally auto-generates -keep rules for manifest-declared
# components (MainActivity, SmsReceiver, BootReceiver,
# RelayForegroundService), so they survive minification without manual
# entries.
#
# The app uses no reflection, JNI, or dynamic class loading, so no extra
# -keep rules are required. If R8 ever reports missing classes or
# "unable to resolve" warnings, add targeted rules below.

# --- app-specific rules (none currently needed) ---
# Example:
# -keep class com.nextnotif.app.SomeClass { *; }
