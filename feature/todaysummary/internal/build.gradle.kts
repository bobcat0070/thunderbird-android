plugins {
    id(ThunderbirdPlugins.Library.android)
}

android {
    namespace = "net.thunderbird.feature.todaysummary.internal"
    resourcePrefix = "todaysummary_"

    // java.time, which the apps provide on older Android through desugaring; lint checks this module on its own.
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }
}

dependencies {
    api(projects.feature.todaysummary.api)
    implementation(libs.androidx.core.ktx)
    implementation(projects.core.logging.api)
    implementation(libs.koin.android)
    coreLibraryDesugaring(libs.android.desugar.nio)

    testImplementation(projects.core.testing)
}

codeCoverage {
    lineCoverage = 0
}
