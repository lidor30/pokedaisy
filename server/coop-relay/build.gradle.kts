import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.9.24"
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<KotlinCompile>().configureEach {
    kotlinOptions.jvmTarget = "17"
}

application {
    mainClass.set("com.pokedaisy.relay.MainKt")
}

dependencies {
    implementation("org.java-websocket:Java-WebSocket:1.5.7")
    runtimeOnly("org.slf4j:slf4j-nop:2.0.13")
    testImplementation("junit:junit:4.13.2")
}
