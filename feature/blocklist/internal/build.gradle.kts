plugins {
    id(ThunderbirdPlugins.Library.kmp)
    alias(libs.plugins.tb.piisafe)
}

kotlin {
    android {
        namespace = "net.thunderbird.feature.blocklist.internal"
    }
    sourceSets {
        commonMain.dependencies {
            api(projects.feature.blocklist.api)
            implementation(projects.core.preference.api)
        }
        commonTest.dependencies {
            implementation(projects.core.testing)
        }
        androidMain.dependencies {
            implementation(libs.okhttp)
        }
    }
}

codeCoverage {
    lineCoverage = 0
}
