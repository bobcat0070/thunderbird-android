plugins {
    id(ThunderbirdPlugins.Library.kmp)
    alias(libs.plugins.tb.piisafe)
}

kotlin {
    android {
        namespace = "net.thunderbird.feature.spamdigest.internal"
        androidResources.enable = true
    }
    sourceSets {
        commonMain.dependencies {
            api(projects.feature.spamdigest.api)
            implementation(projects.core.logging.api)
        }
        commonTest.dependencies {
            implementation(projects.core.testing)
            implementation(projects.core.logging.testing)
        }
        androidMain.dependencies {
            implementation(libs.androidx.work.runtime)
        }
    }
}

codeCoverage {
    lineCoverage = 0
}
