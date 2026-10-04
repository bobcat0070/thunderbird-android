plugins {
    id(ThunderbirdPlugins.Library.kmp)
    alias(libs.plugins.tb.piisafe)
}

kotlin {
    explicitApi()

    android {
        namespace = "net.thunderbird.feature.impersonation"
    }
}

codeCoverage {
    lineCoverage = 0
}
