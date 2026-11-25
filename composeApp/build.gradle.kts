import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File
import java.io.FileInputStream
import java.security.KeyStore
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

val keystoreProps = Properties()
listOf(
    rootProject.file("keystore.properties"),
    rootProject.file("android_keystore.props"),
    project.file("keystore.properties"),
    project.file("android_keystore.props")
).firstOrNull { it.isFile }?.let { propFile ->
    FileInputStream(propFile).use { keystoreProps.load(it) }
}

fun resolveSigningParam(envKeys: List<String>, propKeys: List<String>): String? {
    for (k in envKeys) {
        val v = System.getenv(k)?.trim()
        if (!v.isNullOrEmpty()) return v
    }
    for (k in propKeys) {
        val v = keystoreProps.getProperty(k)?.trim()
        if (!v.isNullOrEmpty()) return v
    }
    return null
}

val rawStorePath = resolveSigningParam(
    envKeys = listOf("CM_KEYSTORE_PATH", "ANDROID_KEYSTORE_PATH", "KEYSTORE_PATH", "STORE_FILE"),
    propKeys = listOf("storeFile", "CM_KEYSTORE_PATH", "KEYSTORE_PATH")
)

val resolvedKeystoreFile: File? = sequence {
    if (!rawStorePath.isNullOrEmpty()) {
        val direct = file(rawStorePath)
        if (direct.isAbsolute) {
            yield(direct)
        } else {
            yield(rootProject.file(rawStorePath))
            yield(direct)
        }
    }
    yield(rootProject.file("keystore.jks"))
    yield(rootProject.file("release.keystore"))
    yield(rootProject.file("android_keystore.jks"))
    yield(project.file("keystore.jks"))
    yield(File("/tmp/keystore.jks"))
}.firstOrNull { it.isFile && it.length() > 0L }

val resolvedStorePassword = resolveSigningParam(
    envKeys = listOf("CM_KEYSTORE_PASSWORD", "ANDROID_KEYSTORE_PASSWORD", "KEYSTORE_PASSWORD", "STORE_PASSWORD"),
    propKeys = listOf("storePassword", "CM_KEYSTORE_PASSWORD", "KEYSTORE_PASSWORD", "password")
)

val configuredKeyAlias = resolveSigningParam(
    envKeys = listOf("CM_KEY_ALIAS", "ANDROID_KEY_ALIAS", "KEY_ALIAS"),
    propKeys = listOf("keyAlias", "CM_KEY_ALIAS", "KEY_ALIAS", "alias")
)

val resolvedKeyAlias: String? = if (resolvedKeystoreFile != null && !resolvedStorePassword.isNullOrEmpty()) {
    var detectedAlias: String? = null
    for (ksType in listOf(KeyStore.getDefaultType(), "PKCS12", "JKS").distinct()) {
        try {
            val ks = KeyStore.getInstance(ksType)
            FileInputStream(resolvedKeystoreFile).use { fis ->
                ks.load(fis, resolvedStorePassword.toCharArray())
            }
            detectedAlias = if (!configuredKeyAlias.isNullOrEmpty() && ks.containsAlias(configuredKeyAlias)) {
                configuredKeyAlias
            } else {
                val aliases = ks.aliases()
                if (aliases.hasMoreElements()) aliases.nextElement() else configuredKeyAlias
            }
            if (!detectedAlias.isNullOrEmpty()) break
        } catch (_: Throwable) {
        }
    }
    detectedAlias ?: configuredKeyAlias
} else {
    configuredKeyAlias
}

val resolvedKeyPassword = resolveSigningParam(
    envKeys = listOf("CM_KEY_PASSWORD", "ANDROID_KEY_PASSWORD", "KEY_PASSWORD"),
    propKeys = listOf("keyPassword", "CM_KEY_PASSWORD", "KEY_PASSWORD")
) ?: resolvedStorePassword

val hasValidReleaseSigning = resolvedKeystoreFile != null &&
    !resolvedStorePassword.isNullOrEmpty() &&
    !resolvedKeyAlias.isNullOrEmpty() &&
    !resolvedKeyPassword.isNullOrEmpty()

val resolvedVersionCode = System.getenv("BUILD_NUMBER")?.trim()?.toIntOrNull()
    ?: System.getenv("GITHUB_RUN_NUMBER")?.trim()?.toIntOrNull()?.let { it + 100 }
    ?: 1

val resolvedVersionName = System.getenv("APP_VERSION")?.trim()?.takeIf { it.isNotEmpty() } ?: "1.0"

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "ComposeApp"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.ui)
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
        }

        androidMain.dependencies {
            implementation("androidx.activity:activity-compose:1.9.3")
            implementation("androidx.core:core-ktx:1.13.1")
            implementation("androidx.health.connect:connect-client:1.1.0-alpha07")
            implementation("androidx.credentials:credentials:1.3.0")
            implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
        }
    }
}

android {
    namespace = "cz.mares.zdravipm"
    compileSdk = 36

    defaultConfig {
        applicationId = "cz.mares.zdravipm"
        minSdk = 26
        targetSdk = 36
        versionCode = resolvedVersionCode
        versionName = resolvedVersionName
    }

    signingConfigs {
        if (hasValidReleaseSigning) {
            val releaseConfig = findByName("release") ?: create("release")
            releaseConfig.storeFile = resolvedKeystoreFile
            releaseConfig.storePassword = resolvedStorePassword
            releaseConfig.keyAlias = resolvedKeyAlias
            releaseConfig.keyPassword = resolvedKeyPassword
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = if (hasValidReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        if (hasValidReleaseSigning) {
            getByName("debug") {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
