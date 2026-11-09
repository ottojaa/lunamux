rootProject.name = "Lunamux"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        // JediTerm (headless VT emulator used server-side for AI assistant
        // state detection) is published to JetBrains' IntelliJ dependencies
        // repository, not Maven Central.
        maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies") {
            mavenContent {
                includeGroupAndSubgroups("org.jetbrains.jediterm")
            }
        }
    }
}

// Lunula toolkit source. Pass -Plunula.toolkit.useArtifacts=true to resolve the published artifacts
// from Maven Central. Otherwise a sibling checkout (../../lunula/develop, then ../../lunula/main) is
// used as a composite build. With no checkout the build stops: the published Lunula (0.2.79) has dead
// tab hotkeys and no sidebar right-click, and building against it silently is how that went unnoticed.
// -Plunula.toolkit.path=<dir> names a checkout explicitly; a path that holds no checkout fails the
// build here, because quietly falling back to the published artifacts would build against a
// different toolkit than the one asked for.
val toolkitOverride: String? = settings.providers.gradleProperty("lunula.toolkit.path").orNull
val useArtifacts: Boolean = settings.providers.gradleProperty("lunula.toolkit.useArtifacts").orNull == "true"
fun isToolkit(path: String) = File(rootDir, path).resolve("settings.gradle.kts").exists()
if (toolkitOverride != null && !useArtifacts && !isToolkit(toolkitOverride)) {
    throw GradleException(
        "lunula.toolkit.path=$toolkitOverride (relative to $rootDir) holds no Lunula checkout. " +
            "Fix the path, drop the property, or pass -Plunula.toolkit.useArtifacts=true."
    )
}
val toolkitPath: String? = if (useArtifacts) null else
    listOfNotNull(toolkitOverride, "../../lunula/develop", "../../lunula/main").firstOrNull(::isToolkit)
if (toolkitPath == null && !useArtifacts) {
    throw GradleException(
        "No Lunula checkout found at ../../lunula/develop or ../../lunula/main. The published Lunula " +
            "(0.2.79) has no working tab hotkeys or sidebar right-click. Check out Lunula there, set " +
            "-Plunula.toolkit.path=<dir>, or pass -Plunula.toolkit.useArtifacts=true to build against it anyway."
    )
}
if (toolkitPath != null) {
    includeBuild(toolkitPath) {
        dependencySubstitution {
            substitute(module("se.soderbjorn.lunula:lunula-core")).using(project(":lunula-core"))
            substitute(module("se.soderbjorn.lunula:lunula-store")).using(project(":lunula-store"))
            substitute(module("se.soderbjorn.lunula:lunula-web")).using(project(":lunula-web"))
            substitute(module("se.soderbjorn.lunula:lunula-compose")).using(project(":lunula-compose"))
        }
    }
}

include(":web")
include(":server")
include(":clientServer")
include(":client")
include(":electron")
include(":electron-main")
include(":terminal-core")
include(":terminal-emulator")
include(":terminal-view")
include(":androidApp")
include(":examples:snake-mcp")

