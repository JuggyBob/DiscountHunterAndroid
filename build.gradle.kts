// Root build file. Plugins are declared here (without applying them) so the single
// application module can pick the versions it needs from the version catalog.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
