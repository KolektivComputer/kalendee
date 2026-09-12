import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication

val displayName = providers.gradleProperty("kalendee.displayName").orElse("Kalendee")
val pomDescription = providers.gradleProperty("kalendee.description")
    .orElse("Self-hosted CalDAV server with multiplatform clients.")
val projectUrl = providers.gradleProperty("kalendee.url")
    .orElse("https://git.yuri.capital/kolektiv/kalendee")
val scm = providers.gradleProperty("kalendee.scm")
    .orElse("scm:git:https://git.yuri.capital/kolektiv/kalendee.git")
val licenseName = providers.gradleProperty("kalendee.licenseName").orElse("AGPL-3.0-only")
val licenseUrl = providers.gradleProperty("kalendee.licenseUrl")
    .orElse("https://www.gnu.org/licenses/agpl-3.0.txt")

configure<PublishingExtension> {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set(displayName.map { "$it (${project.name})" })
            description.set(pomDescription)
            url.set(projectUrl)
            licenses {
                license {
                    name.set(licenseName)
                    url.set(licenseUrl)
                }
            }
            developers {
                developer {
                    id.set("kolektiv")
                    name.set("Kolektiv")
                    organization.set("Kolektiv")
                }
            }
            scm {
                url.set(projectUrl)
                connection.set(scm)
                developerConnection.set(scm)
            }
        }
    }

    repositories {
        mavenLocal()

        val yuriUser = providers.gradleProperty("kalendee.publishing.yuriCapitalRepoUsername")
            .orElse(providers.environmentVariable("YURI_CAPITAL_REPO_USERNAME"))
        val yuriPass = providers.gradleProperty("kalendee.publishing.yuriCapitalRepoPassword")
            .orElse(providers.environmentVariable("YURI_CAPITAL_REPO_PASSWORD"))
        if (yuriUser.isPresent && yuriPass.isPresent) {
            val user = yuriUser.get()
            val pass = yuriPass.get()
            val snapshot = project.version.toString().contains("SNAPSHOT", ignoreCase = true)
            maven {
                name = if (snapshot) "yuriSnapshots" else "yuriReleases"
                url = uri(
                    if (snapshot) "https://repo.yuri.capital/repository/maven-snapshots/"
                    else "https://repo.yuri.capital/repository/maven-releases/",
                )
                credentials {
                    username = user
                    password = pass
                }
            }
        }
    }
}

// Signing is intentionally not configured; Nexus does not require it. Add the
// `signing` plugin and sign(publishing.publications) for public registries.
