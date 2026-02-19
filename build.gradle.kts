buildscript {
    configurations.all {
        resolutionStrategy {
            force("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
            force("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
            force("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.8.0")
        }
    }
}

plugins {
    id("com.android.application") version "9.0.1" apply false
    id("org.jetbrains.kotlin.android") version "2.2.10" apply false
}

subprojects {
    configurations.all {
        resolutionStrategy {
            force("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
            force("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
            force("org.jetbrains.kotlinx:kotlinx-coroutines-core-jvm:1.8.0")
        }
    }
}
