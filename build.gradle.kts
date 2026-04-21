plugins {
    id("com.gtnewhorizons.gtnhconvention")
}

val wikiWorkbenchDist = layout.projectDirectory.dir("../WikiMultiStructureRender/dist-workbench")

tasks.register<Copy>("copyWikiWorkbenchWeb") {
    from(wikiWorkbenchDist)
    into(layout.projectDirectory.dir("src/main/resources/assets/structuredataexporter/web"))
    duplicatesStrategy = org.gradle.api.file.DuplicatesStrategy.INCLUDE
    onlyIf { wikiWorkbenchDist.asFile.exists() }
}

tasks.named("processResources") {
    dependsOn("copyWikiWorkbenchWeb")
}
