plugins {
    id(ThunderbirdPlugins.Library.kmp)
}

kotlin {
    android {
        namespace = "net.thunderbird.feature.mail.message.classification.api"
    }
}

codeCoverage {
    lineCoverage = 0
}
