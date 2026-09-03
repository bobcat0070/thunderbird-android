plugins {
    id(ThunderbirdPlugins.Library.kmp)
}

kotlin {
    android {
        namespace = "net.thunderbird.feature.mail.message.classification.internal"
    }
    sourceSets {
        commonMain.dependencies {
            api(projects.feature.mail.message.classification.api)
            implementation(libs.koin.core)
        }
        commonTest.dependencies {
            implementation(libs.assertk)
        }
    }
}

codeCoverage {
    lineCoverage = 0
}
