plugins {
    id(ThunderbirdPlugins.Library.kmp)
    alias(libs.plugins.tb.piisafe)
}

kotlin {
    explicitApi()

    android {
        namespace = "net.thunderbird.feature.spamdigest"
    }
    sourceSets {
        commonMain.dependencies {
            api(projects.feature.impersonation.api)
        }
    }
}

codeCoverage {
    lineCoverage = 0
}
