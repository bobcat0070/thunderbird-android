plugins {
    id(ThunderbirdPlugins.Library.kmp)
    alias(libs.plugins.tb.piisafe)
}

kotlin {
    android {
        namespace = "net.thunderbird.feature.impersonation.internal"
    }
    sourceSets {
        commonMain.dependencies {
            api(projects.feature.impersonation.api)
        }
        commonTest.dependencies {
            implementation(projects.core.testing)
        }
    }
}

codeCoverage {
    lineCoverage = 0
}
