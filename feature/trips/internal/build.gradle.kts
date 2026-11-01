plugins {
    id(ThunderbirdPlugins.Library.androidCompose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "net.thunderbird.feature.trips.internal"
    resourcePrefix = "trips_"

    // java.time, which the apps provide on older Android through desugaring; lint checks this module on its own.
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }
}

dependencies {
    api(projects.feature.trips.api)

    implementation(projects.core.ui.contract)
    implementation(projects.core.ui.compose.common)
    implementation(libs.jsoup)
    implementation(libs.kotlinx.serialization.json)

    coreLibraryDesugaring(libs.android.desugar.nio)

    testImplementation(projects.core.ui.compose.testing)
}

codeCoverage {
    lineCoverage = 0
}
