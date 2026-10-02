package org.user14923929.partforge.ui

data class OssLibrary(val name: String, val description: String, val license: String, val url: String)

val OPEN_SOURCE_LIBRARIES = listOf(
    OssLibrary(
        "Jetpack Compose",
        "UI toolkit, Material 3 and Material Icons (core)",
        "Apache-2.0",
        "https://developer.android.com/jetpack/compose",
    ),
    OssLibrary(
        "AndroidX Activity",
        "Activity Compose and the Activity Result API",
        "Apache-2.0",
        "https://developer.android.com/jetpack/androidx/releases/activity",
    ),
    OssLibrary(
        "AndroidX Lifecycle",
        "ViewModel and lifecycle-aware Compose state",
        "Apache-2.0",
        "https://developer.android.com/jetpack/androidx/releases/lifecycle",
    ),
    OssLibrary(
        "AndroidX Core KTX",
        "Kotlin extensions for the Android framework",
        "Apache-2.0",
        "https://developer.android.com/jetpack/androidx/releases/core",
    ),
    OssLibrary(
        "Kotlin Standard Library",
        "Kotlin language runtime",
        "Apache-2.0",
        "https://github.com/JetBrains/kotlin",
    ),
    OssLibrary(
        "kotlinx.coroutines",
        "Asynchronous programming and flows",
        "Apache-2.0",
        "https://github.com/Kotlin/kotlinx.coroutines",
    ),
)
