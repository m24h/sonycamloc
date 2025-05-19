pluginManagement {
    repositories {
        maven {
            setUrl("${rootProject.projectDir}/maven/")
        }
        mavenLocal()
        maven {
            setUrl("https://maven.aliyun.com/repository/central")
        }
        maven {
            setUrl("https://maven.aliyun.com/repository/public")
        }
        maven {
            setUrl("https://maven.aliyun.com/repository/gradle-plugin")
        }
        maven {
            setUrl("https://maven.aliyun.com/repository/apache-snapshots")
        }
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven {
            setUrl("${rootProject.projectDir}/maven/")
        }
        mavenLocal()
        maven {
            setUrl("https://maven.aliyun.com/repository/central")
        }
        maven {
            setUrl("https://maven.aliyun.com/repository/public")
        }
        maven {
            setUrl("https://maven.aliyun.com/repository/gradle-plugin")
        }
        maven {
            setUrl("https://maven.aliyun.com/repository/apache-snapshots")
        }
        google()
        mavenCentral()
    }
}
//="${rootProject.projectDir}/"
rootProject.name = "SonyCamLoc"
include(":app")
