plugins {
    `java-library`
    id("com.gradleup.shadow") version "9.6.1"
}

group = "app.jer.slackbridge"
version = "1.2.0"
description = "Two-way Slack chat bridge and Slack account linking, forked from HCCore."

dependencies {
    implementation("com.slack.api:bolt-socket-mode:1.52.0")
    implementation("org.java-websocket:Java-WebSocket:1.6.0")

    // Provided by Paper at runtime
    compileOnly("io.papermc.paper:paper-api:26.3.build.222-stable")

    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks {
    // Run shadowJar on build
    build {
        dependsOn(shadowJar)
    }

    compileJava {
        options.encoding = Charsets.UTF_8.name()
        // Paper 26.3 runs on Java 25
        options.release.set(25)
    }

    test {
        useJUnitPlatform()
        // The env vars click-to-link uses, which Bolt also reads on its own
        environment("SLACK_CLIENT_ID", "123.456")
        environment("SLACK_CLIENT_SECRET", "test-secret")
        environment("SLACK_SCOPES", "chat:write")
        environment("SLACK_USER_SCOPES", "users:read,chat:write")
        environment("SLACK_REDIRECT_URI", "https://example.com/slack/callback")
    }

    javadoc {
        options.encoding = Charsets.UTF_8.name()
    }

    processResources {
        filteringCharset = Charsets.UTF_8.name()
        val props = mapOf("name" to rootProject.name, "version" to version)
        inputs.properties(props)
        filesMatching("plugin.yml") {
            expand(props)
        }
    }

    shadowJar {
        archiveBaseName.set("SlackBridge")
        archiveClassifier.set("")

        dependencies {
            // Paper already ships slf4j and routes it into the server log
            exclude(dependency("org.slf4j:.*"))
            // Compile-time annotations only
            exclude(dependency("org.jetbrains:annotations"))
            exclude(dependency("com.google.errorprone:.*"))
        }

        // Keep shaded libraries from clashing with other plugins' copies
        val prefix = "app.jer.slackbridge.libs"
        relocate("com.slack.api", "$prefix.slack")
        relocate("com.google.gson", "$prefix.gson")
        relocate("okhttp3", "$prefix.okhttp3")
        relocate("okio", "$prefix.okio")
        relocate("kotlin", "$prefix.kotlin")
        relocate("org.java_websocket", "$prefix.java_websocket")
        relocate("org.apache.commons", "$prefix.commons")

        mergeServiceFiles()
        exclude("META-INF/*.kotlin_module", "META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "META-INF/versions/*/module-info.class",
            "module-info.class")
    }

    jar {
        archiveClassifier.set("thin")
    }
}

repositories {
    mavenCentral()

    // Papermc
    maven("https://repo.papermc.io/repository/maven-public/")
}
