plugins {
    id(ThunderbirdPlugins.Library.jvm)
    alias(libs.plugins.android.lint)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(projects.backend.api)

    implementation(projects.core.common)
    implementation(projects.core.logging.api)
    implementation(projects.feature.mail.folder.api)
    api(projects.mail.common)

    api(libs.okhttp)
    implementation(libs.okio)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(projects.core.logging.testing)
    testImplementation(projects.mail.testing)
    testImplementation(projects.backend.testing)

    testImplementation(libs.okhttp.mockwebserver)
}

codeCoverage {
    branchCoverage = 0
    lineCoverage = 0
}
