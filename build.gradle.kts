// Seit AGP 9 bringt das Android-Plugin Kotlin selbst mit; ein separates
// kotlin.android-Plugin wird ausdruecklich abgelehnt. Nur der Compose-Compiler
// muss weiterhin eigens angewendet werden.
plugins {
    id("com.android.application") version "9.3.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
}
