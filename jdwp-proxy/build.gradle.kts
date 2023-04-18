plugins {
    scala
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.scala-lang:scala3-library_3:3.0.1")
    //testImplementation("org.scalatest:scalatest_3:3.2.9")
    //testImplementation("junit:junit:4.13")
    implementation("commons-collections:commons-collections:3.2.2")
}


tasks.withType<JavaCompile> {
    sourceCompatibility = JavaVersion.VERSION_11.toString()
    targetCompatibility = JavaVersion.VERSION_11.toString()
}

tasks.jar {
    // gpt says this includes the scala runtime in the output jar
    from(configurations.runtimeClasspath.get().filter { it.name.endsWith("jar") }.map { zipTree(it) })
}
