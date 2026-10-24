plugins {
    kotlin("jvm") version "1.9.20"
    id("com.lagradost.cloudstream3.gradle") version "3.0.0"
}

cloudstream {
    setRepoUrl("https://github.com/ytademagyar-netizen/DIZIPAL")
}

dependencies {
    implementation("com.github.BlissLabs:Cloudstream3-Libs:main")
}
