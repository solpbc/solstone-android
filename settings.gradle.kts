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
include(":apps:wear")
include(":apps:glasses")
include(":core:model")
include(":core:sources")
include(":core:segment")
include(":core:calendar")
include(":core:obsidian")
include(":core:screenshots")
include(":core:spool")
include(":core:queue")
include(":core:diagnostics")
include(":core:crypto")
include(":core:pl")
include(":core:push")
include(":core:identity")
include(":core:observer")
include(":core:metadata")
include(":core:gate")
include(":core:watch-courier")
include(":testing")
include(":platform:persistence-room")
include(":platform:pl-transport-conscrypt")
include(":platform:identity-file")
include(":platform:work")
include(":platform:metadata")
include(":platform:audio")
include(":platform:screen")
include(":platform:location")
include(":platform:calendar")
include(":platform:obsidian")
include(":platform:screenshots")
include(":platform:camera-still")
include(":platform:camera-legacy")
include(":platform:camera2")
include(":platform:fgs")
include(":platform:power")
include(":harness")
include(":formfactor:shared")
include(":formfactor:glasses")
include(":formfactor:phone")
include(":platform:wearable")
