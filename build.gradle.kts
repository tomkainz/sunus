// Since AGP 9 the Android plugin ships Kotlin itself and rejects a separate
// kotlin.android plugin. Only the Compose compiler still has to be applied
// on its own.
plugins {
    id("com.android.application") version "9.3.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
}
