plugins {
    java
    id("com.google.protobuf") version "0.9.4" apply false
    id("io.quarkus") version "3.27.0" apply false
}

allprojects {
    group = "com.hts"
    version = "1.0.0"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")

    tasks.withType<JavaCompile> {
        sourceCompatibility = "17"
        targetCompatibility = "17"
    }

    dependencies {
        implementation("io.netty:netty-buffer:4.1.100.Final")
        testImplementation("org.junit.jupiter:junit-jupiter:5.10.0")
    }
}

