pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "solstone-android"

include(":apps:validation-rogbid")
include(":apps:observer-scaffold")
include(":apps:watch")
include(":apps:phone")
include(":apps:glasses")
include(":core:model")
include(":core:sources")
include(":core:segment")
include(":core:spool")
include(":core:queue")
include(":core:diagnostics")
include(":core:crypto")
include(":core:pl")
include(":core:identity")
include(":core:observer")
include(":core:metadata")
include(":testing")
include(":platform:persistence-room")
include(":platform:pl-transport-conscrypt")
include(":platform:identity-file")
include(":platform:work")
include(":platform:metadata")
include(":platform:audio")
include(":platform:location")
include(":platform:camera-still")
include(":platform:camera-legacy")
include(":platform:camera2")
include(":platform:fgs")
include(":platform:power")
include(":harness")
include(":formfactor:shared")
include(":formfactor:glasses")
