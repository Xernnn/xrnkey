import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.BuiltArtifactsLoader
import java.util.Properties
import javax.inject.Inject
import org.gradle.process.ExecOperations

plugins {
    // Kotlin is built into AGP 9: no kotlin-android plugin.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// The repo: android/ sits in it, and the core it builds is ../core.
val repoDir: File = rootProject.projectDir.parentFile

// The owner's release key, outside every repo in ../xrn-signing.properties next to the
// repo (storeFile, storePassword, keyAlias, keyPassword; storeFile relative to that file's
// folder). It is found only from the main checkout; a worktree's release build is unsigned.
val xrnSigningFile: File = repoDir.resolve("../xrn-signing.properties")
val xrnSigning: Properties? = xrnSigningFile.takeIf { it.isFile }?.let { f ->
    Properties().apply { f.inputStream().use { load(it) } }
}

// The trained models (data/lm/README.md) and the Japanese dictionary (data/ja/README.md):
// bundled into the APK at build time, never in git. -Pxrnkey.models=<dir> points elsewhere.
val modelDir: File = (findProperty("xrnkey.models") as String?)?.let(::File)
    ?: File(System.getProperty("user.home"), ".cache/xrnkey-lm/models")

// The engine built for this machine, for the JVM tests: the core, the JNI bridge and the
// small test models trained from made-up text (android/host/CMakeLists.txt).
val hostDir: File = layout.buildDirectory.dir("host").get().asFile
val hostSrc: File = rootProject.projectDir.resolve("host")

val configureHost = tasks.register<Exec>("configureHost") {
    description = "Configures the engine's build for this machine (JVM tests)."
    inputs.file(hostSrc.resolve("CMakeLists.txt"))
    outputs.file(hostDir.resolve("build.ninja"))
    commandLine("cmake", "-S", hostSrc, "-B", hostDir, "-G", "Ninja", "-DCMAKE_BUILD_TYPE=Release")
}

val buildHost = tasks.register<Exec>("buildHost") {
    description = "Builds the engine, its JNI bridge and the test models for this machine."
    dependsOn(configureHost)
    inputs.dir(repoDir.resolve("core"))
    inputs.dir(repoDir.resolve("tools"))
    inputs.dir(repoDir.resolve("data/lm"))
    inputs.dir(repoDir.resolve("data/ja"))
    inputs.dir(projectDir.resolve("src/main/cpp"))
    outputs.dir(hostDir.resolve("lm"))
    outputs.dir(hostDir.resolve("jni"))
    commandLine("cmake", "--build", hostDir, "-j", "4")
}

abstract class BundleModels : DefaultTask() {
    @get:Internal abstract val realDir: DirectoryProperty
    @get:Internal abstract val testDir: DirectoryProperty
    @get:InputFile abstract val emojiIndex: RegularFileProperty
    @get:InputFile abstract val emojiKeywords: RegularFileProperty
    /** The Japanese dictionary's licence notices (data/ja/NOTICE), which the settings show. */
    @get:InputFile abstract val japaneseNotice: RegularFileProperty
    @get:Input abstract val release: Property<Boolean>
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun bundle() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        emojiIndex.get().asFile.copyTo(out.resolve("emoji-index.tsv"))
        emojiKeywords.get().asFile.copyTo(out.resolve("emoji-keywords.tsv"))
        japaneseNotice.get().asFile.copyTo(out.resolve("ja-notice.txt"))
        val real = realDir.get().asFile
        val test = testDir.get().asFile
        val vi = real.resolve("vi-phone.xklm")
        val en = real.resolve("en.xklm")
        val ja = real.resolve("ja.xkdic")
        if (vi.isFile && en.isFile) {
            vi.copyTo(out.resolve("vi.xklm"))
            en.copyTo(out.resolve("en.xklm"))
        } else if (release.get()) {
            throw GradleException(
                "No trained models in $real (vi-phone.xklm and en.xklm are needed for a release " +
                    "build). Build them as data/lm/README.md says, or pass -Pxrnkey.models=<dir>.",
            )
        } else {
            logger.warn("xrnkey: no trained models in $real; this debug build bundles the small test models")
            test.resolve("vi.xklm").copyTo(out.resolve("vi.xklm"))
            test.resolve("en.xklm").copyTo(out.resolve("en.xklm"))
        }
        if (ja.isFile) {
            ja.copyTo(out.resolve("ja.xkdic"))
        } else if (release.get()) {
            throw GradleException(
                "No Japanese dictionary in $real (ja.xkdic is needed for a release build). Build " +
                    "it as data/ja/README.md says, or pass -Pxrnkey.models=<dir>.",
            )
        } else {
            logger.warn("xrnkey: no Japanese dictionary in $real; this debug build bundles the small test one")
            test.resolve("ja.xkdic").copyTo(out.resolve("ja.xkdic"))
        }
    }
}

// Every model in the built APK opens where it lies, as the keyboard opens it on the phone (by
// descriptor, at the offset AGP gave it), with the engine built for this machine: see
// host/check_apk_models.py. Assembling an APK runs it; a refused model fails the build.
abstract class CheckApkModels : DefaultTask() {
    @get:InputFiles abstract val apkDir: DirectoryProperty
    @get:Internal abstract val loader: Property<BuiltArtifactsLoader>
    @get:InputFile abstract val script: RegularFileProperty
    /** The Japanese dictionary's reader, which the script converts a sentence with. */
    @get:InputFile abstract val reader: RegularFileProperty
    @get:InputFile abstract val engine: RegularFileProperty
    @get:OutputFile abstract val report: RegularFileProperty
    @get:Inject abstract val exec: ExecOperations

    @TaskAction
    fun check() {
        val apks = loader.get().load(apkDir.get())?.elements?.map { File(it.outputFile) }.orEmpty()
        if (apks.isEmpty()) throw GradleException("xrnkey: no APK in ${apkDir.get()}")
        for (apk in apks) {
            exec.exec {
                commandLine("python3", "-I", script.get().asFile, apk, engine.get().asFile, reader.get().asFile, report.get().asFile)
            }
        }
    }
}

android {
    namespace = "com.xern.xrnkey"
    compileSdk = 35
    ndkVersion = "27.1.12297006"

    defaultConfig {
        applicationId = "com.xern.xrnkey"
        minSdk = 26
        targetSdk = 35
        versionCode = 20
        versionName = "0.9.0"
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake {
                // c++_shared: the core and the JNI bridge are two libraries. The job pool keeps
                // the native build to 4 compilers on a laptop whose RAM is shared.
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DCMAKE_JOB_POOLS=xk=4",
                    "-DCMAKE_JOB_POOL_COMPILE=xk",
                    "-DCMAKE_JOB_POOL_LINK=xk",
                )
            }
        }
    }

    externalNativeBuild {
        // The system CMake, through cmake.dir in local.properties: the core needs 3.27.
        cmake { path = file("src/main/cpp/CMakeLists.txt") }
    }

    signingConfigs {
        if (xrnSigning != null) {
            create("xrn") {
                storeFile = xrnSigningFile.parentFile.resolve(xrnSigning.getProperty("storeFile"))
                storePassword = xrnSigning.getProperty("storePassword")
                keyAlias = xrnSigning.getProperty("keyAlias")
                keyPassword = xrnSigning.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("xrn")?.let { signingConfig = it }
        }
    }

    // The models and the dictionary are memory-mapped straight from the APK, so they must stay
    // uncompressed.
    androidResources { noCompress += listOf("xklm", "xkdic") }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all { test ->
                // The engine, the test models and the emoji data are inputs: the tests run again
                // when the core or its data change, not only when the Kotlin does.
                test.inputs.files(buildHost).withPropertyName("hostEngine")
                test.inputs.files(repoDir.resolve("data/emoji/emoji-index.tsv"), repoDir.resolve("data/emoji/keywords.tsv"))
                    .withPropertyName("emojiData")
                test.systemProperty("java.library.path", hostDir.resolve("jni").path)
                test.systemProperty("xrnkey.testModels", hostDir.resolve("lm").path)
                test.systemProperty("xrnkey.realModels", modelDir.path)
                test.systemProperty("xrnkey.emojiIndex", repoDir.resolve("data/emoji/emoji-index.tsv").path)
                test.systemProperty("xrnkey.emojiKeywords", repoDir.resolve("data/emoji/keywords.tsv").path)
                (findProperty("xrnkey.shots") as String?)?.let { test.systemProperty("xrnkey.shots", it) }
                // Mozc's evaluation set (data/ja/README.md), for the Japanese converter's numbers.
                (findProperty("xrnkey.jaEval") as String?)?.let { test.systemProperty("xrnkey.jaEval", it) }
                test.maxHeapSize = "1536m"
                // A native library binds to one class loader: the Robolectric tests and the plain
                // JNI tests each need a JVM of their own.
                test.forkEvery = 1
                test.jvmArgs("--add-opens", "java.base/java.io=ALL-UNNAMED")
            }
        }
    }
}

androidComponents {
    onVariants { variant ->
        val release = variant.buildType == "release"
        val name = variant.name.replaceFirstChar { it.uppercase() }
        val bundle = tasks.register<BundleModels>("bundle${name}Models") {
            realDir.set(modelDir)
            emojiIndex.set(repoDir.resolve("data/emoji/emoji-index.tsv"))
            emojiKeywords.set(repoDir.resolve("data/emoji/keywords.tsv"))
            japaneseNotice.set(repoDir.resolve("data/ja/NOTICE"))
            testDir.set(hostDir.resolve("lm"))
            this.release.set(release)
            if (!release && !(modelDir.resolve("vi-phone.xklm").isFile && modelDir.resolve("ja.xkdic").isFile)) dependsOn(buildHost)
        }
        variant.sources.assets?.addGeneratedSourceDirectory(bundle, BundleModels::outputDir)
        val check = tasks.register<CheckApkModels>("check${name}ApkModels") {
            dependsOn(buildHost)
            apkDir.set(variant.artifacts.get(SingleArtifact.APK))
            loader.set(variant.artifacts.getBuiltArtifactsLoader())
            script.set(hostSrc.resolve("check_apk_models.py"))
            reader.set(repoDir.resolve("data/ja/build_dict.py"))
            engine.set(hostDir.resolve("jni/libxrnkey.so"))
            report.set(layout.buildDirectory.file("reports/apk-models-${variant.name}.txt"))
        }
        tasks.matching { it.name == "assemble$name" }.configureEach { dependsOn(check) }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
