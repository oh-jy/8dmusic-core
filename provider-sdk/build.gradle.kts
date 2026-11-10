plugins { `java-library` }

group = "org.eightdmusic"
version = "1.0.0"

if (rootProject != project) layout.buildDirectory.set(rootProject.layout.projectDirectory.dir("build/provider-sdk"))

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
