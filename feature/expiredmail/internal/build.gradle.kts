plugins {
    id(ThunderbirdPlugins.Library.androidCompose)
}

android {
    namespace = "net.thunderbird.feature.expiredmail.internal"
    resourcePrefix = "expiredmail_"
}

dependencies {
    api(projects.feature.expiredmail.api)

    implementation(projects.core.ui.contract)
    implementation(projects.core.ui.compose.common)

    testImplementation(projects.core.ui.compose.testing)
}

codeCoverage {
    lineCoverage = 0
}
