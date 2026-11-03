plugins {
    java
    id("io.quarkus")
    id("com.diffplug.spotless") version "8.10.4"
    checkstyle
    pmd
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

val quarkusPlatformGroupId = providers.gradleProperty("quarkusPlatformGroupId").get()
val quarkusPlatformArtifactId = providers.gradleProperty("quarkusPlatformArtifactId").get()
val quarkusPlatformVersion = providers.gradleProperty("quarkusPlatformVersion").get()

dependencies {
    implementation(enforcedPlatform("${quarkusPlatformGroupId}:${quarkusPlatformArtifactId}:${quarkusPlatformVersion}"))
    implementation("io.quarkus:quarkus-rest")
    implementation("io.quarkus:quarkus-rest-jackson")
    implementation("io.quarkus:quarkus-grpc")
    implementation("io.quarkus:quarkus-arc")
    implementation("io.quarkus:quarkus-container-image-jib")
    implementation("org.neo4j:neo4j-ogm-quarkus:4.2.9")

    implementation("io.quarkus:quarkus-messaging-kafka")

    testImplementation("io.quarkus:quarkus-junit")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("io.rest-assured:rest-assured")
    testImplementation("io.quarkus:quarkus-test-kafka-companion")
}

group = "net.explorviz"
version = "1.0.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

sourceSets["main"].java {
    srcDir("build/classes/java/quarkus-generated-sources/grpc")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    systemProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager")
    jvmArgs("--add-opens", "java.base/java.lang=ALL-UNNAMED")

    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
tasks.withType<JavaCompile>().configureEach {
    options.release = 21
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
}

fun registerGitHook(taskName: String, hookFile: String, targetHook: String) =
    tasks.register<Copy>(taskName) {
        from("code-analysis/$hookFile")
        into(".git/hooks")
        rename { targetHook }
    }

registerGitHook("registerPreCommitHook", "pre-commit.sh", "pre-commit")
registerGitHook("registerPreMergeCommitHook", "pre-commit.sh", "pre-merge-commit")

tasks.named("quarkusGenerateCode") {
    dependsOn("registerPreCommitHook")
    dependsOn("registerPreMergeCommitHook")
}

pmd {
    toolVersion = "7.23.0"
    isIgnoreFailures = false

    // Empty ruleset is necessary for exclude-pattern
    // https://stackoverflow.com/questions/32247190/pmd-exclude-pattern-with-gradle
    ruleSets = listOf()
    ruleSetConfig = resources.text.fromFile("code-analysis/pmd.xml")
    sourceSets = listOf(java.sourceSets.main.get())
}

checkstyle {
    toolVersion = "13.4.0"
    isIgnoreFailures = false
    maxWarnings = 0
    config = resources.text.fromFile("code-analysis/checkstyle.xml")
    configDirectory.set(file("code-analysis"))
    configProperties = mapOf(
        "org.checkstyle.google.suppressionfilter.config" to file("code-analysis/checkstyle-suppressions.xml").path,
    )
    sourceSets = listOf(java.sourceSets.main.get())
}

spotless {
    java {
        target("src/*/java/**/*.java")
        googleJavaFormat().reflowLongStrings()
        formatAnnotations()
    }

    flexmark {
        target("**/*.md")
        flexmark()
    }

    format("misc") {
        target(
            "*.gradle",
            "*.gradle.kts",
            ".gitattributes",
            ".gitignore",
            ".git-blame-ignore-revs",
            "src/main/resources/application.properties",
        )

        trimTrailingWhitespace()
        leadingTabsToSpaces()
        endWithNewline()
    }
}
