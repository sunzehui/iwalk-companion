plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    // :core -> :protocol，且两者都不允许出现 Android 依赖
    api(project(":protocol"))

    // 会话与演示传输是协程驱动的；标定 JSON 只用 kotlinx 的树 API（无代码生成）
    api(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.withType<Test>().configureEach {
    useJUnit()
}
