plugins {
    id(ThunderbirdPlugins.Library.androidCompose)
}

android {
    namespace = "net.thunderbird.feature.plusaddress.internal"
    resourcePrefix = "plusaddress_"
}

dependencies {
    api(projects.feature.plusaddress.api)

    implementation(projects.core.ui.contract)
    implementation(projects.core.ui.compose.common)
    implementation(libs.okhttp)

    testImplementation(projects.core.testing)
    testImplementation(projects.core.ui.compose.testing)
}

codeCoverage {
    lineCoverage = 0
}
