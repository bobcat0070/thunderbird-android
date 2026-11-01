plugins {
    id(ThunderbirdPlugins.Library.kmpCompose)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    android {
        namespace = "net.thunderbird.feature.trips"
    }

    sourceSets {
        commonMain.dependencies {
            api(projects.core.ui.navigation)
        }
    }
}

codeCoverage {
    lineCoverage = 0
}
