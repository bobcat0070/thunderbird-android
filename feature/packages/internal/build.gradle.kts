plugins {
    id(ThunderbirdPlugins.Library.androidCompose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "net.thunderbird.feature.packages.internal"
    resourcePrefix = "packages_"
}

dependencies {
    api(projects.feature.packages.api)

    implementation(projects.core.ui.contract)
    implementation(projects.core.ui.compose.common)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(projects.core.ui.compose.testing)
}

codeCoverage {
    lineCoverage = 0
}
