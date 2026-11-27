// CI only: look up Maven Central artifacts on Google's public mirror of Central before Central itself.
// Central rate-limits shared GitHub runner IPs (429 Too Many Requests): release-phone dry run
// 46031342679 failed twice at :app:mergeFastNativeLibs on three Supabase poms Central refused, and
// Robolectric's own downloads hit the same limit in #226. The mirror is the one
// .github/scripts/robolectric_prefetch.sh already uses. It is added AHEAD of the build's own
// repositories, so anything the mirror lacks (a release minutes old) still falls through to
// google(), mavenCentral() and the rest as before. Nothing in settings.gradle.kts changes, so a
// laptop or a stranger's clone resolves exactly as it did.
// Workflows copy this file into ~/.gradle/init.d/ before the first ./gradlew.
val centralMirror = "https://maven-central.storage-download.googleapis.com/maven2/"

fun org.gradle.api.artifacts.dsl.RepositoryHandler.centralMirrorFirst() {
    val mirror = maven {
        name = "GoogleCentralMirror"
        url = uri(centralMirror)
        content {
            // Google's own Android artifacts are not on Central; skip the mirror for them.
            excludeGroupByRegex("androidx\\..*")
            excludeGroupByRegex("com\\.android\\..*")
        }
    }
    remove(mirror)
    addFirst(mirror)
}

beforeSettings {
    pluginManagement.repositories.centralMirrorFirst()
    dependencyResolutionManagement.repositories.centralMirrorFirst()
}
