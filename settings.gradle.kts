pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "dediren"

include("apps:cli")
include("modules:archimate")
include("modules:contracts")
include("modules:core")
include("modules:schema-cache")
include("modules:uml")
include("modules:plugins:archimate-oef-export")
include("modules:plugins:elk-layout")
include("modules:plugins:generic-graph")
include("modules:plugins:svg-render")
include("modules:plugins:uml-xmi-export")
include("test-support")
include("testbeds:plugin-runtime")
include("tools:dist")
