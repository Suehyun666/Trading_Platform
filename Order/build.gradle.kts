plugins {
    id("java")
    id("com.google.protobuf") version "0.9.4"
    id("application")
}

group = "com.hts.order"
version = "1.0.0"

repositories {
    google()
    gradlePluginPortal()
    mavenCentral()
}

dependencies {
    // Netty
    implementation("io.netty:netty-all:4.1.108.Final")
    implementation("io.netty:netty-transport-native-epoll:4.1.108.Final:linux-x86_64")

    // Protobuf
    implementation("com.google.protobuf:protobuf-java:3.25.3")
    implementation("com.google.protobuf:protobuf-java-util:3.25.3")

    // gRPC Client
    implementation("io.grpc:grpc-netty:1.62.2")
    implementation("io.grpc:grpc-protobuf:1.62.2")
    implementation("io.grpc:grpc-stub:1.62.2")
    implementation("javax.annotation:javax.annotation-api:1.3.2")

    // PostgreSQL + HikariCP
    implementation("org.postgresql:postgresql:42.7.3")
    implementation("com.zaxxer:HikariCP:5.1.0")

    // jOOQ
    implementation("org.jooq:jooq:3.19.6")

    // Redis (Lettuce - 비동기 지원)
    implementation("io.lettuce:lettuce-core:6.3.2.RELEASE")

    // Logging
    implementation("org.slf4j:slf4j-api:2.0.12")
    implementation("ch.qos.logback:logback-classic:1.5.3")

    // Metrics
    implementation("io.micrometer:micrometer-core:1.12.4")
    implementation("io.micrometer:micrometer-registry-prometheus:1.12.4")

    // Config
    implementation("com.typesafe:config:1.4.3")

    // Dependency Injection
    implementation("com.google.inject:guice:5.1.0")

    // Guava (for MurmurHash3)
    implementation("com.google.guava:guava:32.1.2-jre")
    implementation("com.google.guava:guava-gwt:32.1.2-jre")

    // Flyway
    implementation("org.flywaydb:flyway-core:10.10.0")
    implementation("org.flywaydb:flyway-database-postgresql:10.10.0")

    // Test
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

tasks.compileJava {
    options.encoding = "UTF-8"
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.25.3"
    }
    plugins {
        create("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:1.62.2"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                create("grpc")
            }
        }
    }
}

application {
    mainClass.set("com.hts.order.Main")
    applicationDefaultJvmArgs = listOf(
        "-XX:+UseG1GC",
        "-XX:MaxGCPauseMillis=50",
        "-Xms4g",
        "-Xmx4g",
        "-XX:+AlwaysPreTouch",
        "-XX:G1HeapRegionSize=16M",
        "-XX:InitiatingHeapOccupancyPercent=45",
        "-XX:+ParallelRefProcEnabled",
        "-XX:+UnlockExperimentalVMOptions",
        "-XX:G1NewSizePercent=30",
        "-XX:G1MaxNewSizePercent=40",
        "-Dio.netty.allocator.type=pooled",
        "-Dio.netty.leakDetection.level=disabled"
    )
}
