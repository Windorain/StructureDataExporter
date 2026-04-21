plugins {
    id("com.gtnewhorizons.gtnhconvention")
}

// Packaged web UI lives in src/main/resources/.../web/; optional sync: ./gradlew copyWikiWorkbenchWeb (after wiki dist-workbench build).
tasks.register<Copy>("copyWikiWorkbenchWeb") {
    from(layout.projectDirectory.dir("../WikiMultiStructureRender/dist-workbench"))
    into(layout.projectDirectory.dir("src/main/resources/assets/structuredataexporter/web"))
    duplicatesStrategy = org.gradle.api.file.DuplicatesStrategy.INCLUDE
}
