plugins {
    id(ThunderbirdPlugins.Library.androidCompose)
}

android {
    namespace = "net.thunderbird.feature.newsletter.internal"
    resourcePrefix = "newsletter_"
}

dependencies {
    api(projects.feature.newsletter.api)

    implementation(projects.core.ui.contract)
    implementation(projects.core.ui.compose.common)

    testImplementation(projects.core.ui.compose.testing)
}

codeCoverage {
    lineCoverage = 0
}
