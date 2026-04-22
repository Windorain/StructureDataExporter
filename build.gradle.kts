import groovy.json.JsonSlurper
import java.net.HttpURLConnection
import java.net.URI
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

plugins {
    id("com.gtnewhorizons.gtnhconvention")
}

// 工作台静态资源 → src/main/resources/.../web/
// 由 sde.workbenchSync 选择：npm（默认，本地 cmd/npm 构建）| release（见 gradle.properties）
val workbenchWebOut = layout.projectDirectory.dir("src/main/resources/assets/structuredataexporter/web")
val canonicalReleaseDir = layout.buildDirectory.dir("sde-workbench-release/canonical")

fun workbenchSyncMode(): String =
    (findProperty("sde.workbenchSync") as String?)?.trim()?.lowercase() ?: "npm"

fun npmWorkbenchDirPath(): String =
    (findProperty("sde.workbenchNpmDir") as String?)?.trim()
        ?: "dist-workbench"

fun workbenchGitCloneUrl(): String =
    (findProperty("sde.workbenchGitUrl") as String?)?.trim()
        ?: "https://github.com/Windorain/WebStructureRenderer.git"

fun workbenchGitBranch(): String =
    (findProperty("sde.workbenchGitBranch") as String?)?.trim().orEmpty()

/** 无仓库则 shallow clone，有则 git pull --ff-only（须本机已装 git） */
tasks.register("prepareWikiWorkbenchGitRepo") {
    group = "SDE web"
    description =
        "将 WebStructureRenderer 克隆或更新到 sde.workbenchNpmDir（默认 dist-workbench/）"
    // configuration cache：doLast 只捕获 String 等可序列化值，不引用脚本函数/Provider
    val workbenchRootAbs =
        layout.projectDirectory.dir(npmWorkbenchDirPath()).asFile.absoluteFile.normalize().path
    val cloneUrl = workbenchGitCloneUrl()
    val branch = workbenchGitBranch()

    doLast {
        val dir = File(workbenchRootAbs)
        val gitDir = File(dir, ".git")
        val parent = dir.parentFile ?: throw GradleException("无法解析 ${dir.absolutePath} 的父目录")

        fun gitExec(workingDir: File, command: List<String>) {
            val code = ProcessBuilder(command)
                .directory(workingDir)
                .inheritIO()
                .start()
                .waitFor()
            if (code != 0) {
                throw GradleException("命令失败（退出码 $code）：${command.joinToString(" ")}")
            }
        }

        if (!gitDir.exists()) {
            if (dir.exists()) {
                val children = dir.listFiles()
                if (children != null && children.isNotEmpty()) {
                    throw GradleException(
                        "${dir.absolutePath} 已存在且非空、但不是 git 仓库，请删除或清空后重试",
                    )
                }
            }
            val cmd = mutableListOf("git", "clone", "--depth", "1")
            if (branch.isNotEmpty()) {
                cmd.add("--branch")
                cmd.add(branch)
            }
            cmd.add(cloneUrl)
            cmd.add(dir.name)
            gitExec(parent, cmd)
        } else {
            gitExec(dir, listOf("git", "pull", "--ff-only"))
        }
    }
}

tasks.register<Exec>("buildWikiWorkbenchWebNpm") {
    group = "SDE web"
    description =
        "在 sde.workbenchNpmDir 执行 npm ci 与 npm run build:workbench（需本机 Node/npm；先由 prepareWikiWorkbenchGitRepo 拉取仓库）"
    dependsOn("prepareWikiWorkbenchGitRepo")
    val npmRootAbs =
        layout.projectDirectory.dir(npmWorkbenchDirPath()).asFile.absoluteFile.normalize().path
    workingDir = File(npmRootAbs)
    val win = System.getProperty("os.name").lowercase().contains("windows")
    if (win) {
        commandLine("cmd", "/c", "npm ci && npm run build:workbench")
    } else {
        commandLine("sh", "-c", "npm ci && npm run build:workbench")
    }
}

tasks.register("fetchWikiWorkbenchWebRelease") {
    group = "SDE web"
    description =
        "从 GitHub Release（latest）下载名称匹配的 .zip，解压并规范化到 build/.../canonical（须已发布 Release）"
    outputs.dir(canonicalReleaseDir)
    outputs.upToDateWhen { false }

    doLast {
        val repo = (findProperty("sde.workbenchReleaseRepo") as String?)?.trim()
            ?: "Windorain/WebStructureRenderer"
        val assetMatch = (findProperty("sde.workbenchReleaseAsset") as String?)?.trim()
            ?: "workbench"
        val explicitSubdir = (findProperty("sde.workbenchReleaseUnpackedSubdir") as String?)?.trim()

        val api = URI.create("https://api.github.com/repos/$repo/releases/latest").toURL()
        val conn = api.openConnection() as HttpURLConnection
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "StructureDataExporter-Gradle")
        conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        if (conn.responseCode != 200) {
            val err = conn.errorStream?.bufferedReader()?.readText().orEmpty()
            throw GradleException("GitHub API ${conn.responseCode} for $api: $err")
        }
        val body = conn.inputStream.bufferedReader().readText()
        val root = JsonSlurper().parseText(body) as Map<*, *>
        val assets = root["assets"] as? List<*> ?: emptyList<Any>()
        val zipAsset =
            assets.mapNotNull { it as? Map<*, *> }.firstOrNull { m ->
                val name = m["name"] as? String ?: ""
                name.contains(assetMatch, ignoreCase = true) && name.endsWith(".zip", ignoreCase = true)
            }
                ?: throw GradleException(
                    "Release 中未找到名称包含 \"$assetMatch\" 的 .zip。" +
                        "请在 https://github.com/$repo 发布 Release 并上传 zip，或修改 sde.workbenchReleaseAsset。",
                )
        val downloadUrl = zipAsset["browser_download_url"] as String

        val zipPath = layout.buildDirectory.file("sde-workbench-release/download.zip").get().asFile
        zipPath.parentFile.mkdirs()
        URI.create(downloadUrl).toURL().openStream().use {
            Files.copy(it, zipPath.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }

        val extractRoot = layout.buildDirectory.dir("sde-workbench-release/extracted").get().asFile
        extractRoot.deleteRecursively()
        extractRoot.mkdirs()
        sync {
            from(zipTree(zipPath))
            into(extractRoot)
        }

        fun resolvedDist(dir: File): File {
            if (!explicitSubdir.isNullOrEmpty()) {
                val sub = dir.resolve(explicitSubdir)
                if (sub.isDirectory) return sub
                throw GradleException("sde.workbenchReleaseUnpackedSubdir=$explicitSubdir 不是目录")
            }
            val nested = dir.resolve("dist-workbench")
            if (nested.isDirectory) return nested
            return dir
        }

        val resolved = resolvedDist(extractRoot)
        val canonical = canonicalReleaseDir.get().asFile
        canonical.deleteRecursively()
        resolved.copyRecursively(canonical)
    }
}

tasks.register<Copy>("copyWikiWorkbenchWeb") {
    group = "SDE web"
    description = "将 dist-workbench 内容拷贝到 resources（npm 构建 或 release 下载，由 sde.workbenchSync 决定）"
    duplicatesStrategy = org.gradle.api.file.DuplicatesStrategy.INCLUDE
    into(workbenchWebOut)
    when (workbenchSyncMode()) {
        "release", "github" -> {
            dependsOn("fetchWikiWorkbenchWebRelease")
            from(canonicalReleaseDir)
        }
        else -> {
            dependsOn("buildWikiWorkbenchWebNpm")
            from(layout.projectDirectory.dir(npmWorkbenchDirPath()).dir("dist-workbench"))
        }
    }
}

tasks.register("syncWikiWorkbenchWeb") {
    group = "SDE web"
    description = "与 copyWikiWorkbenchWeb 相同（别名，便于记忆）"
    dependsOn("copyWikiWorkbenchWeb")
}
