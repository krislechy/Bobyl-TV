import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

abstract class BuildDateSource : ValueSource<String, ValueSourceParameters.None> {
    override fun obtain(): String = ZonedDateTime.now(ZoneId.of("Europe/Moscow"))
        .format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))
}

/** The authored SVG defines shared geometry and the paths included in each status/action. */
abstract class GenerateWatchStatusIcons : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val svg: RegularFileProperty
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun generate() {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        }
        val root = factory.newDocumentBuilder().parse(svg.get().asFile).documentElement
        val viewport = root.getAttribute("viewBox").split(Regex("\\s+"))
        val paths = root.getElementsByTagName("path")
        val drawable = outputDirectory.dir("drawable").get().asFile.apply { mkdirs() }
        fun escape(value: String) = value.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")
        for (variant in listOf("active", "checked", "mark", "unmark")) {
            val xml = buildString {
                append("<vector xmlns:android=\"http://schemas.android.com/apk/res/android\" android:width=\"${viewport[2]}dp\" android:height=\"${viewport[3]}dp\" android:viewportWidth=\"${viewport[2]}\" android:viewportHeight=\"${viewport[3]}\">\n")
                for (index in 0 until paths.length) {
                    val path = paths.item(index) as Element
                    val variants = path.getAttribute("data-variants")
                    if (variants.isNotBlank() && variant !in variants.split(Regex("\\s+"))) continue
                    val color = path.getAttribute("stroke")
                    val geometry = path.getAttribute("d")
                    append("<path android:fillColor=\"#00000000\" android:pathData=\"${escape(geometry)}\" android:strokeColor=\"${escape(color)}\" android:strokeWidth=\"${path.getAttribute("stroke-width")}\" android:strokeLineCap=\"round\" android:strokeLineJoin=\"round\" />\n")
                }
                append("</vector>\n")
            }
            drawable.resolve("watch_status_$variant.xml").writeText(xml)
        }
    }
}

val generateWatchStatusIcons = tasks.register<GenerateWatchStatusIcons>("generateWatchStatusIcons") {
    svg.set(layout.projectDirectory.file("src/main/svg/watch_status.svg"))
    outputDirectory.set(layout.buildDirectory.dir("generated/res/watchStatus"))
}

android {
    namespace = "com.bobyl.tv"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.bobyl.tv"
        minSdk = 23
        targetSdk = 35
        versionCode = 56
        versionName = "2.3.8"
        buildConfigField("String", "BUILD_DATE", "\"${providers.of(BuildDateSource::class.java) {}.get()}\"")
        testInstrumentationRunner = "com.bobyl.tv.ParityInstrumentation"
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-debug"
            manifestPlaceholders["appLabel"] = "Бобыль Dev"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            manifestPlaceholders["appLabel"] = "Бобыль"
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        // Signed release build for direct Android TV installation.
        create("benchmark") {
            initWith(getByName("release"))
            applicationIdSuffix = ".benchmark"
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            manifestPlaceholders["appLabel"] = "Бобыль Benchmark"
            matchingFallbacks += listOf("release")
        }
        // Collect readable profile rules before R8 renames classes and methods.
        create("profile") {
            initWith(getByName("benchmark"))
            applicationIdSuffix = ".profile"
            isMinifyEnabled = false
            isShrinkResources = false
            manifestPlaceholders["appLabel"] = "Бобыль Profile"
        }
        create("tvRelease") {
            initWith(getByName("release"))
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

androidComponents {
    onVariants(selector().all()) { variant ->
        variant.sources.res?.addGeneratedSourceDirectory(
            generateWatchStatusIcons,
            GenerateWatchStatusIcons::outputDirectory,
        )
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.08.00")
    implementation(composeBom)

    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.tv:tv-material:1.1.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("org.jsoup:jsoup:1.23.2")
    implementation("io.coil-kt.coil3:coil-compose:3.6.1")
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.6.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.json:json:20240303")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
