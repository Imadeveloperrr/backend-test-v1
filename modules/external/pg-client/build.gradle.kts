tasks.jar {
    enabled = true
}

tasks.bootJar {
    enabled = false
}

dependencies {
    implementation(projects.modules.application)
    implementation(projects.modules.domain)
    implementation(libs.spring.boot.starter.web)

    // MockWebServer for HTTP client testing without external API dependency
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
}
