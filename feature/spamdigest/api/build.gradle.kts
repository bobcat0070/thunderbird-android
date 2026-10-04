plugins {
    id(ThunderbirdPlugins.Library.kmp)
    alias(libs.plugins.tb.piisafe)
}

kotlin {
    explicitApi()

    android {
        namespace = "net.thunderbird.feature.spamdigest"
    }
}

codeCoverage {
    lineCoverage = 0
}
