plugins {
    id(ThunderbirdPlugins.Library.androidCompose)
}

android {
    namespace = "net.thunderbird.feature.mailboxrules.internal"
    resourcePrefix = "mailboxrules_"
}

dependencies {
    api(projects.feature.mailboxrules.api)

    implementation(projects.core.ui.contract)
    implementation(projects.core.ui.compose.common)

    testImplementation(projects.core.ui.compose.testing)
}

codeCoverage {
    lineCoverage = 0
}
