plugins {
    id("com.android.application") version "8.5.2" apply false
    id("com.android.library") version "8.5.2" apply false
    id("com.android.test") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.multiplatform") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.28" apply false
    id("androidx.baselineprofile") version "1.3.3" apply false
    id("com.google.gms.google-services") version "4.5.0" apply false
    id("com.google.firebase.crashlytics") version "3.0.7" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.6"
    id("com.diffplug.spotless") version "6.25.0"
    id("jacoco")
}

detekt {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
    baseline = file("$rootDir/config/detekt/baseline.xml")
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    reports {
        html.required.set(true)
        sarif.required.set(true)
    }
}

// Build directories are excluded from the walk itself, not just filtered afterwards: the release
// job checks out termux-packages under build/, and following its dangling symlinks fails the task.
// Going back to target("**/*.kt") + targetExclude would bring that failure back.
fun spotlessSources(pattern: String) =
    fileTree(rootDir) {
        include(pattern)
        exclude("**/build/**", "**/.gradle/**", ".worktree/**")
    }

spotless {
    kotlin {
        target(spotlessSources("**/*.kt"))
        ktlint("1.2.1")
            .setEditorConfigPath("$rootDir/.editorconfig")
    }
    kotlinGradle {
        target(spotlessSources("**/*.gradle.kts"))
        ktlint("1.2.1")
    }
}
