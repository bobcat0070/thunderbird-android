plugins {
    id(ThunderbirdPlugins.Library.androidCompose)
}

android {
    namespace = "net.thunderbird.feature.attachments.internal"
    resourcePrefix = "attachments_"
}

dependencies {
    api(projects.feature.attachments.api)

    implementation(projects.core.ui.contract)
    implementation(projects.core.ui.compose.common)

    testImplementation(projects.core.ui.compose.testing)
}

codeCoverage {
    lineCoverage = 0
}
