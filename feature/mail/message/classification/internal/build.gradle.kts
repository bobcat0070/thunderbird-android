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
        androidMain.dependencies {
            implementation(libs.koin.android)
        }
        commonTest.dependencies {
            implementation(libs.assertk)
        }
    }
}

codeCoverage {
    lineCoverage = 0
}
