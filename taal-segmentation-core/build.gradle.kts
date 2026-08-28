plugins { kotlin("jvm") }

dependencies { testImplementation(kotlin("test")) }

kotlin { jvmToolchain(17) }

tasks.test {
    useJUnitPlatform()
    testLogging { showStandardStreams = true }
}
