import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val updateRepository = providers.gradleProperty("updateRepository").orElse("shellvon/prismaura-tv").get()
val updateMirrorBase = providers.gradleProperty("updateMirrorBase").orElse("").get().trimEnd('/')
require(updateRepository.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))) { "updateRepository must be owner/repo" }
fun quoted(value: String) = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
val signingPath = providers.environmentVariable("PRISM_KEYSTORE_PATH").orNull
val signingPassword = providers.environmentVariable("PRISM_KEYSTORE_PASSWORD").orNull
val signingAlias = providers.environmentVariable("PRISM_KEY_ALIAS").orNull
val signingKeyPassword = providers.environmentVariable("PRISM_KEY_PASSWORD").orNull
val distributionSigning = listOf(signingPath, signingPassword, signingAlias, signingKeyPassword).all { !it.isNullOrBlank() }

android {
    namespace = "tv.prism"
    compileSdk = 34
    defaultConfig {
        applicationId = "tv.prism"
        minSdk = 23
        targetSdk = 34
        versionCode = providers.gradleProperty("appVersionCode").orElse("1").get().toInt()
        versionName = providers.gradleProperty("appVersionName").orElse("0.1.0").get()
        buildConfigField("String", "UPDATE_REPOSITORY", quoted(updateRepository))
        buildConfigField("String", "UPDATE_MIRROR_BASE", quoted(updateMirrorBase))
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildFeatures { compose = true; buildConfig = true }
    if (distributionSigning) signingConfigs.create("distribution") {
        storeFile = file(signingPath!!)
        storePassword = signingPassword
        keyAlias = signingAlias
        keyPassword = signingKeyPassword
    }
    buildTypes {
        debug {
            if (distributionSigning) signingConfig = signingConfigs.getByName("distribution")
        }
        release {
            if (distributionSigning) signingConfig = signingConfigs.getByName("distribution")
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

val requireReleaseKey = tasks.register("requireReleaseKey") {
    doLast { check(distributionSigning) { "Release requires the four signing environment variables pointing to the existing app keystore; reuse its key to support updates. See docs/DEVELOPMENT.md" } }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(requireReleaseKey) }

tasks.register("prepareReleaseAssets") {
    group = "publishing"
    description = "Build and validate the APK, release notes and mirror manifest"
    dependsOn("assembleRelease", "lintRelease")
    doLast {
        val output = layout.buildDirectory.dir("release-assets").get().asFile.apply { mkdirs() }
        val apk = layout.buildDirectory.file("outputs/apk/release/app-release.apk").get().asFile
        val metadataFile = layout.buildDirectory.file("outputs/apk/release/output-metadata.json").get().asFile
        val metadata = JsonSlurper().parse(metadataFile) as Map<*, *>
        val element = (metadata["elements"] as List<*>).single() as Map<*, *>
        val version = element["versionName"] as String
        val tag = providers.environmentVariable("RELEASE_TAG").orElse("v$version").get()
        check(tag.matches(Regex("v\\d+\\.\\d+\\.\\d+")) && tag == "v$version") { "Release tag must match versionName" }
        val changelog = rootProject.file("CHANGELOG.md").readText()
        val notes = Regex("^## Unreleased[^\\n]*\\n(.*?)(?=^## |\\z)", setOf(RegexOption.MULTILINE, RegexOption.DOT_MATCHES_ALL))
            .find(changelog)?.groupValues?.get(1)?.trim().orEmpty()
        check(notes.isNotBlank()) { "Write release notes under CHANGELOG.md / Unreleased first" }
        check(updateMirrorBase.isEmpty() || updateMirrorBase.startsWith("https://")) { "Release mirror must use HTTPS" }
        apk.copyTo(output.resolve("PrismAura.apk"), overwrite = true)
        val digest = MessageDigest.getInstance("SHA-256")
        apk.inputStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        val sha256 = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        val download = if (updateMirrorBase.isNotEmpty()) "$updateMirrorBase/$tag/PrismAura.apk"
            else "https://github.com/$updateRepository/releases/download/$tag/PrismAura.apk"
        val manifest = linkedMapOf(
            "tag_name" to tag, "draft" to false, "prerelease" to false,
            "html_url" to "https://github.com/$updateRepository/releases/tag/$tag", "body" to notes,
            "assets" to listOf(mapOf("name" to "PrismAura.apk", "state" to "uploaded", "size" to apk.length(),
                "digest" to "sha256:$sha256", "browser_download_url" to download)),
        )
        output.resolve("latest.json").writeText(JsonOutput.prettyPrint(JsonOutput.toJson(manifest)) + "\n")
        output.resolve("notes.md").writeText(notes + "\n")
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.tv:tv-material:1.0.0")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
