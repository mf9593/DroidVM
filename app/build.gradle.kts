@file:Suppress("UnstableApiUsage")

import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.application)
    // Compose is here for one screen: the Markdown notes editor and the cards that render what
    // it wrote, which come from a Compose-only library. Everything else stays Java and Views.
    // Kotlin itself needs no plugin -- AGP 9 compiles it out of the box and already owns the
    // "kotlin" extension -- so only the Compose compiler plugin is applied, pinned to the same
    // Kotlin version AGP carries.
    alias(libs.plugins.kotlin.compose)
}

// Build to a location that supports symlinks (Android external storage doesn't).
// Other machines keep Gradle's default; DROIDVM_BUILD_DIR overrides both.
val preferredBuildDir = file(
    System.getenv("DROIDVM_BUILD_DIR") ?: "/data/data/com.acside/files/home/droidvm-build/app"
)
if (preferredBuildDir.parentFile?.canWrite() == true) buildDir = preferredBuildDir

fun runGit(vararg args: String): String {
    require(rootDir.resolve(".git").exists()) { "Not a git repository: $rootDir" }
    val proc = ProcessBuilder("git", *args)
        .directory(rootDir)
        .redirectErrorStream(false)
        .start()
    val output = proc.inputStream.bufferedReader().readText().trim()
    val exitCode = proc.waitFor()
    require(exitCode == 0) { "git ${args.joinToString(" ")} failed with exit code $exitCode" }
    return output
}

// Tolerant variant: returns null instead of throwing, for git calls that may
// legitimately fail (e.g. describe on a checkout with no tags).
fun runGitOrNull(vararg args: String): String? =
    runCatching { runGit(*args) }.getOrNull()

val gitCommitCount = runGit("rev-list", "--count", "HEAD").toInt()
val gitShortSha = runGit("rev-parse", "--short", "HEAD")

// describe fails on a checkout without tags (shallow clone, or tags not fetched);
// fall back to a 0.0.0 base so the build still works and the version is clearly
// marked as tag-less. Exclude the rolling `dev` tag (dev-release.yml recreates it
// at HEAD each push) so it never shadows real version tags in the version name.
val gitDescribe = (runGitOrNull("describe", "--long", "--tags", "--exclude=dev")
    ?: "0.0.0-$gitCommitCount-g$gitShortSha")
    .removePrefix("v").removePrefix("V")
// Release version pinned for the 1.0.0 build. The git-derived values above are kept
// so the commit this APK came from remains visible in the build log, but the store
// version must not move on every commit once a release line exists.
val generatedVersionName: String = "1.0.1"
val generatedVersionCode: Int = 10001

println("Version name: $generatedVersionName")
println("Version code: $generatedVersionCode")

android {
    namespace = "cn.classfun.droidvm"
    compileSdk {
        version = release(37) {
        }
    }

    defaultConfig {
        applicationId = "cn.classfun.droidvm"
        minSdk = 33
        targetSdk = 37
        versionCode = generatedVersionCode
        versionName = generatedVersionName

        // Pin the build-tools that carry aarch64 host binaries (36.0.0's aidl is x86_64 only,
        // which this aarch64 workspace cannot execute). 37.0.0 also matches compileSdk 37.
        buildToolsVersion = "37.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // NDK 29 as published is an x86_64-host package only; this workspace builds on an
        // aarch64 host, where the patched r27d package is the one that has linux-aarch64
        // toolchains. CMakeLists needs nothing newer than r27.
        ndkVersion = "27.3.13750724"
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++20"
                arguments += "-DANDROID_STL=c++_static"
                arguments += "-DDROIDVM_VERSION=${versionName}"
                arguments += "-DCMAKE_POLICY_VERSION_MINIMUM=3.5"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // Prefer the shared debug keystore on this device, then the standard one. A checkout
    // without either still assembles (an unsigned release APK) instead of failing.
    val debugKeystore = listOfNotNull(
        System.getenv("DROIDVM_KEYSTORE"),
        "/data/data/com.acside/files/home/.android/debug.keystore",
        System.getProperty("user.home") + "/.android/debug.keystore"
    ).map(::file).firstOrNull { it.isFile }
    signingConfigs {
        create("debugKeystore") {
            storeFile = debugKeystore
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            if (debugKeystore != null) signingConfig = signingConfigs.getByName("debugKeystore")
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlin {
        compilerOptions {
            jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11
        }
    }
    buildFeatures {
        aidl = true
        buildConfig = true
        compose = true
    }
    sourceSets {
        getByName("main") {
            // Third-party source kept in the tree rather than pulled as an artifact, in its own
            // root so that "not ours, and under its own licence" is structural. See its README.
            kotlin.srcDir("src/main/vendor")
        }
    }
    testOptions {
        unitTests {
            // Lets a unit test cover a class that logs. The alternative -- keeping every testable
            // class free of android.util.Log -- stopped being tenable at the H.264 side channel,
            // whose whole subject is a socket and a thread outliving the object that owned them,
            // and which says so out loud when they do. Nothing here asserts on a stub's return
            // value; the stubs are only there so the class under test can be built at all.
            isReturnDefaultValues = true
        }
    }
    packaging {
        jniLibs {
            // Extract native libs to a real on-disk dir so lbx (shipped as
            // liblbx.so) is an executable file the app can run from its own
            // nativeLibraryDir -- no root, no daemon needed for a URL fetch.
            useLegacyPackaging = true
            // The upstream terminal-emulator AAR (com.github.termux.termux-app)
            // ships a 4 KB-aligned libtermux.so (LOAD p_align 0x1000), which
            // Android 15+ flags on install. The in-tree codetool/terminal-emulator
            // build links with max/common-page-size=16384, and app/src/main/jniLibs
            // carries that 0x4000 build. Pick it over the AAR's copy.
            pickFirsts += "lib/arm64-v8a/libtermux.so"
            // libz is a public NDK library and the system one is already 16 KB
            // aligned. The NDK 27 prebuilt is 4 KB aligned and was the only
            // remaining lib that tripped the Android 15+ 16 KB install check.
            excludes += "lib/arm64-v8a/libz.so"
            // The in-app VNC client links vncclient statically; it does not use
            // libvncserver. Shipping it here shadows the crosvm prebuilt under
            // LD_LIBRARY_PATH and crashes the crosvm VNC server in its ZRLE path.
            excludes += "lib/arm64-v8a/libvncserver.so"
        }
    }
    androidResources {
        // prebuilt-<abi>-comptime.zip is a build-time input only: Gradle unpacks
        // it into jniLibs (lib/<abi>/liblbx.so). Never ship it as an APK asset.
        // Also exclude the legacy *.7z (replaced by *.tar.xz) so a stale archive
        // lingering in the prebuilts submodule can't double the APK size. The
        // runtime prebuilt-<abi>.tar.xz + .json stay packaged as assets.
        ignoreAssetsPatterns += listOf("*-comptime.zip", "*.7z")
    }
}

abstract class CopyNativeBinAssetsTask : DefaultTask() {
    @get:InputDirectory
    abstract val cmakeOutputDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val outDir = outputDir.get().asFile
        outDir.deleteRecursively()
        val cmakeDir = cmakeOutputDir.get().asFile
        if (!cmakeDir.exists()) return
        val binaries = setOf("droidvm", "daemon")
        cmakeDir.walkTopDown()
            .filter { it.name in binaries && it.isFile }
            .forEach { src ->
                val abi = src.parentFile.name
                val dest = File(outDir, "bin/$abi/${src.name}")
                dest.parentFile.mkdirs()
                src.copyTo(dest, overwrite = true)
            }
    }
}

// Unpack each prebuilt-<abi>-comptime.zip from the prebuilts submodule into
// jniLibs/<abi>/. These are files that must be executable from the app's
// nativeLibraryDir (currently liblbx.so) -- the data-dir copy extracted from the
// runtime tar.xz isn't executable by the app's untrusted_app SELinux domain.
// Build-time extraction (unlike a first-run extract) is fine because the package
// installer, not the app, populates nativeLibraryDir.
abstract class UnpackComptimeJniLibsTask : DefaultTask() {
    @get:Inject
    abstract val archives: ArchiveOperations

    @get:Inject
    abstract val fs: FileSystemOperations

    @get:InputDirectory
    abstract val prebuiltsDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun unpack() {
        val outDir = outputDir.get().asFile
        outDir.deleteRecursively()
        val prebuilts = prebuiltsDir.get().asFile
        val re = Regex("^prebuilt-(.+)-comptime\\.zip$")
        val zips = prebuilts.listFiles { f -> f.isFile && re.matches(f.name) } ?: return
        for (zip in zips) {
            val abi = re.find(zip.name)!!.groupValues[1]
            fs.copy {
                from(archives.zipTree(zip))
                into(File(outDir, abi))
            }
        }
    }
}

// Native-dev hook: when DroidVM-Prebuilt-Root is checked out at the repo root
// with a non-empty auto-build/ (someone is editing a native util locally),
// regenerate the prebuilt-* artifacts into the prebuilts submodule before they
// are packaged. Pure-Java devs don't have this dir, so the CI-published
// submodule artifacts are used as-is (the task no-ops via onlyIf).
abstract class RegenPrebuiltsTask : DefaultTask() {
    @get:Inject
    abstract val exec: ExecOperations

    // The working directory only; the sources Gradle should hash are declared below. Hashing the
    // whole root would drag in manual-build/, which carries hundreds of megabytes of binaries the
    // script never reads.
    @get:Internal
    abstract val prebuiltRoot: DirectoryProperty

    /** What the script builds from: a change here is the only reason to pack again. */
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val autoBuildSources: DirectoryProperty

    /** The script itself, so editing it invalidates the last run too. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val autoBuildScript: RegularFileProperty

    @get:OutputDirectory
    abstract val prebuiltsOut: DirectoryProperty

    @TaskAction
    fun regen() {
        val root = prebuiltRoot.get().asFile
        val out = prebuiltsOut.get().asFile
        logger.lifecycle("DroidVM-Prebuilt-Root detected; regenerating prebuilts via auto-build.py")
        exec.exec {
            workingDir = root
            commandLine("python3", "auto-build.py", "--out", out.absolutePath)
        }
    }
}

// Fetch the terminal font (Maple Mono NL NF) at build time so CI (./gradlew) and
// local builds behave identically -- it used to live only in build.sh, which CI
// never runs. Download is best-effort: a failure logs a warning and the app falls
// back to the system monospace, so it never breaks the build.
abstract class FetchTerminalFontTask : DefaultTask() {
    @get:Inject
    abstract val archives: ArchiveOperations

    @get:Input
    abstract val url: Property<String>

    @get:Input
    abstract val sha256: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun fetch() {
        val ttf = File(outputDir.get().asFile, "fonts/MapleMonoNL-NF-Regular.ttf")
        if (ttf.isFile && sha256Hex(ttf) == sha256.get()) return
        // The font is a cosmetic asset and the app already falls back to the
        // system monospace when it is absent. Downloading the ~50 MB release
        // zip on a slow/blocked network stalls the whole build at this task
        // (seen on-device: 30+ minutes with no further task output), so treat
        // "not cached" as "use the fallback" and never block packaging on it.
        logger.warn("Terminal font not cached; skipping download (system monospace will be used)")
    }

    private fun sha256Hex(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(8192)
            var n = ins.read(buf)
            while (n >= 0) { md.update(buf, 0, n); n = ins.read(buf) }
        }
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }
}

val prebuiltRootDir = rootProject.layout.projectDirectory.dir("DroidVM-Prebuilt-Root")
val prebuiltsSubmoduleDir = rootProject.layout.projectDirectory.dir("app/src/main/assets/prebuilts")
val regenPrebuilts = tasks.register<RegenPrebuiltsTask>("regenPrebuilts") {
    description = "Regenerate prebuilts from DroidVM-Prebuilt-Root if it exists and has auto-build/ content"
    prebuiltRoot.set(prebuiltRootDir)
    autoBuildSources.set(prebuiltRootDir.dir("auto-build"))
    autoBuildScript.set(prebuiltRootDir.file("auto-build.py"))
    prebuiltsOut.set(prebuiltsSubmoduleDir)
    // Only when a native dev has checked out DroidVM-Prebuilt-Root with source
    // under auto-build/. Otherwise, the published submodule artifacts are used.
    onlyIf {
        val autoBuild = prebuiltRootDir.dir("auto-build").asFile
        prebuiltRootDir.file("auto-build.py").asFile.isFile &&
            autoBuild.isDirectory &&
            (autoBuild.listFiles()?.any { it.name != ".gitignore" } == true)
    }
}
// Hung on the two tasks that actually read the prebuilts dir, not on preBuild: preBuild is a
// dependency of every JVM unit test run as well, and packing an APK payload is not something a
// test of the rules engine should wait for.

androidComponents {
    onVariants { variant ->
        val variantName = variant.name.replaceFirstChar { it.uppercase() }
        val unpackComptimeTask = tasks.register<UnpackComptimeJniLibsTask>(
            "unpackComptimeJniLibs${variantName}"
        ) {
            description = "Unpack prebuilt-<abi>-comptime.zip into jniLibs/<abi>/ for ${variant.name}"
            dependsOn(regenPrebuilts)

            prebuiltsDir.set(prebuiltsSubmoduleDir)
            outputDir.set(
                layout.buildDirectory.dir("generated/comptime_jnilibs/${variant.name}")
            )
        }
        variant.sources.jniLibs?.addGeneratedSourceDirectory(
            unpackComptimeTask, UnpackComptimeJniLibsTask::outputDir
        )
        // The other reader of the prebuilts dir. Declared here rather than on preBuild so that a
        // unit test run never packs anything.
        tasks.matching { it.name == "merge${variantName}Assets" }
            .configureEach { dependsOn(regenPrebuilts) }
        val fetchFontTask = tasks.register<FetchTerminalFontTask>(
            "fetchTerminalFont${variantName}"
        ) {
            description = "Fetch terminal font for ${variant.name}"
            url.set("https://github.com/subframe7536/maple-font/releases/download/v7.9/MapleMonoNL-NF.zip")
            sha256.set("aa3b096bc92df8503d77482b285a0567bafa6e83230d969700f455e610b1f655")
            outputDir.set(layout.buildDirectory.dir("generated/font_assets/${variant.name}"))
        }
        variant.sources.assets?.addGeneratedSourceDirectory(
            fetchFontTask, FetchTerminalFontTask::outputDir
        )
        val copyNativeTask = tasks.register<CopyNativeBinAssetsTask>(
            "copyNativeBinAssets${variantName}"
        ) {
            description = "Copy native bin assets for ${variant.name}"
            dependsOn("externalNativeBuild${variantName}")
            cmakeOutputDir.set(
                layout.buildDirectory.dir(
                    "intermediates/cmake/${variant.name}/obj"
                )
            )
            outputDir.set(
                layout.buildDirectory.dir(
                    "generated/droidvm_assets/${variant.name}"
                )
            )
        }
        variant.sources.assets?.addGeneratedSourceDirectory(
            copyNativeTask, CopyNativeBinAssetsTask::outputDir
        )
    }

}

tasks.register("renameApk") {
    dependsOn("assembleRelease")
    doLast {
        val apkDir = layout.buildDirectory.dir("outputs/apk/release").get().asFile
        val signedApk = File(apkDir, "app-release.apk")
        val targetApk = File("/storage/emulated/0/Download/code/droidvm/app/release/app-debug-signed.apk")
        targetApk.parentFile.mkdirs()
        if (signedApk.exists()) {
            signedApk.copyTo(targetApk, overwrite = true)
            println("APK copied to: ${targetApk.absolutePath}")
        } else {
            println("APK not found at: ${signedApk.absolutePath}")
        }
    }
}

dependencies {
    implementation(libs.activity)
    implementation(libs.annotation.jvm)
    implementation(libs.appcompat)
    implementation(libs.auto.service.annotations)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.animation)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.constraintlayout)
    implementation(libs.libsu.core)
    implementation(libs.libsu.nio)
    implementation(libs.libsu.service)
    // The renderer itself lives in src/main/vendor; these are what it needs.
    implementation(libs.markdown.parser)
    implementation(libs.kotlinx.collections.immutable)
    implementation(libs.material)
    implementation(libs.okhttp3)
    implementation(libs.snakeyaml)
    implementation(libs.xz)
    implementation(libs.zstd) { artifact { type = "aar" } }
    testImplementation(libs.zstd)
    implementation(libs.termux.emulator)
    implementation(libs.termux.view)
    testImplementation(libs.junit)
    annotationProcessor(libs.auto.service)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.ext.junit)
}
