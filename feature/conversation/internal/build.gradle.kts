plugins {
    id(ThunderbirdPlugins.Library.androidCompose)
}

android {
    namespace = "net.thunderbird.feature.conversation.internal"
    resourcePrefix = "conversation_"
}

dependencies {
    api(projects.feature.conversation.api)

    implementation(projects.core.ui.contract)
    implementation(projects.core.ui.compose.common)

    testImplementation(projects.core.ui.compose.testing)
}

codeCoverage {
    lineCoverage = 0
}
