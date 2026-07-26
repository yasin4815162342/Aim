plugins {
    // AGP 9.x has built-in Kotlin support — no separate org.jetbrains.kotlin.android
    // plugin needed or wanted (applying it alongside AGP 9 built-in Kotlin conflicts).
    id("com.android.application") version "9.2.1" apply false
}
