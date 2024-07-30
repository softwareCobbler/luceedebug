plugins {
    java
    id("java-gradle-plugin")
}

dependencies {
    compileOnly(gradleApi())
}

gradlePlugin {
    plugins.create("buildPluginX") {
        id = name
        implementationClass = "xbuild.XBuild"
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
}
