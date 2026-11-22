plugins {
    id(ThunderbirdPlugins.Library.androidCompose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "net.thunderbird.feature.mailboxrules"
}

dependencies {
    api(projects.core.ui.navigation)
}

codeCoverage {
    lineCoverage = 0
}
