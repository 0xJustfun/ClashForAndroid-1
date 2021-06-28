@file:Suppress("UNUSED_VARIABLE")

import java.net.URL

allprojects {
    repositories {
        google()
        mavenCentral()

        maven {
            url = java.net.URI("https://dl.bintray.com/rikkaw/Libraries")
        }
        maven {
            url = java.net.URI("https://jitpack.io")
        }
    }
}

task("clean", type = Delete::class) {
    delete(rootProject.buildDir)
}

tasks.named<Wrapper>("wrapper") {
    distributionType = Wrapper.DistributionType.ALL

    doLast {
        val sha256 = URL("$distributionUrl.sha256").openStream()
            .use { it.reader().readText().trim() }

        file("gradle/wrapper/gradle-wrapper.properties")
            .appendText("distributionSha256Sum=$sha256")
    }
}