import xbuild.XBuild.GenerateConstants

plugins {
    java
    id("com.gradleup.shadow") version "8.3.0"
    id("org.owasp.dependencycheck") version "8.4.0" apply false
    id("buildPluginX")
}

allprojects {
    apply(plugin = "org.owasp.dependencycheck")
}

configure<org.owasp.dependencycheck.gradle.extension.DependencyCheckExtension> {
    format = org.owasp.dependencycheck.reporting.ReportGenerator.Format.ALL.toString()
}

repositories {
    // Use Maven Central for resolving dependencies.
    mavenCentral()
}

dependencies {
    // https://mvnrepository.com/artifact/org.junit.jupiter/junit-jupiter-api
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // https://mvnrepository.com/artifact/org.junit.jupiter/junit-jupiter-params
    testImplementation("org.junit.jupiter:junit-jupiter-params:5.11.0")
    // https://mvnrepository.com/artifact/com.github.docker-java/docker-java-core
    testImplementation("com.github.docker-java:docker-java-core:3.3.0")
    // https://mvnrepository.com/artifact/com.github.docker-java/docker-java-transport-httpclient5
    testImplementation("com.github.docker-java:docker-java-transport-httpclient5:3.3.0")
    // https://mvnrepository.com/artifact/com.google.http-client/google-http-client
    testImplementation("com.google.http-client:google-http-client:1.43.1")

    // https://mvnrepository.com/artifact/com.google.guava/guava
    implementation("com.google.guava:guava:32.1.2-jre")

    implementation("org.ow2.asm:asm:9.7.1")
    implementation("org.ow2.asm:asm-util:9.7.1")
    implementation("org.ow2.asm:asm-commons:9.7.1")
    
    // https://mvnrepository.com/artifact/javax.servlet.jsp/javax.servlet.jsp-api
    compileOnly("javax.servlet.jsp:javax.servlet.jsp-api:2.3.3")
    // https://mvnrepository.com/artifact/javax.servlet/javax.servlet-api
    compileOnly("javax.servlet:javax.servlet-api:3.1.0") // same as lucee deps


    compileOnly(files("extern/lucee-5.3.9.158-SNAPSHOT.jar"))
    compileOnly(files("extern/5.3.9.158-SNAPSHOT.jar"))

    // https://mvnrepository.com/artifact/org.eclipse.lsp4j/org.eclipse.lsp4j.debug
    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j.debug:0.23.1")

}

java {
    sourceCompatibility = JavaVersion.VERSION_11
}

tasks.compileJava {
    dependsOn("generateJavaConstantsFile")
    options.compilerArgs.add("-Xlint:unchecked")
    options.compilerArgs.add("-Xlint:deprecation")
}

tasks.test {
    dependsOn("shadowJar")

    useJUnitPlatform()

    // maxHeapSize = "1G" // infinite, don't care

    maxParallelForks = (Runtime.getRuntime().availableProcessors()).coerceAtLeast(1).also {
        println("Setting maxParallelForks to $it")
    }

    testLogging {
        events("passed")
        events("failed")
        showStandardStreams = true
    }
}

tasks.jar {
    manifest {
        attributes(
            mapOf(
                "Premain-Class" to "luceedebug.Agent",
                "Can-Redefine-Classes" to "true",
                "Bundle-SymbolicName" to "luceedebug-osgi",
                "Bundle-Version" to "2.0.1.1",
                "Export-Package" to "luceedebug.*"
            )
        )
    }
}

val luceedebugVersion = "2.0.15"
val libfile = "luceedebug-" + luceedebugVersion + ".jar"

// TODO: this should, but does not currently, participate in the `clean` task, so the generated file sticks around after invoking `clean`.
tasks.register<GenerateConstants>("generateJavaConstantsFile") {
    version = luceedebugVersion
    // n.b. this ends up in src/luceedebug/generated, rather than build/...
    // for the sake of ide autocomplete
    className = "luceedebug.generated.Constants"
}

tasks.register("printCurrentLibName") {
    println(libfile)
}

tasks.shadowJar {
    configurations = listOf(project.configurations.runtimeClasspath.get())
    setEnableRelocation(true)
    relocationPrefix = "luceedebug_shadow"
    archiveFileName.set(libfile)
}
