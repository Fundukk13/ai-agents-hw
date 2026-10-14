plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.serialization") version "2.2.20"
    application
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
}

group = "edu.hse"
version = "1.0.0"

repositories { mavenCentral() }

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    implementation("org.xerial:sqlite-jdbc:3.53.4.0")
    testImplementation(platform("org.junit:junit-bom:6.0.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin { jvmToolchain(17) }
application { mainClass.set("edu.hse.agent.cli.MainKt") }
tasks.test { useJUnitPlatform() }
tasks.named<JavaExec>("run") { standardInput = System.`in` }

ktlint {
    version.set("1.8.0")
    outputToConsole.set(true)
}

tasks.register<JavaExec>("runLocal") {
    group = "application"
    description = "Run the agent with local Ollama"
    dependsOn(tasks.named("classes"))
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set(application.mainClass)
    args("start")
    environment("OPENAI_BASE_URL", "http://127.0.0.1:11434/v1/")
    environment("OPENAI_API_KEY", "ollama")
    environment("OPENAI_MODEL", providers.gradleProperty("localModel").getOrElse("gpt-oss:20b"))
    environment("OPENAI_REASONING_EFFORT", "low")
    standardInput = System.`in`
}
