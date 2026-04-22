# StructureDataExporter

面向 **GTNH** 的模组工程，与 **Structure Data Exporter（SDE）** 工作流配套：导出/管理结构数据，并在游戏内挂载 **Web 工作台** 静态页面。

## 构建

```powershell
.\gradlew build
```

开发客户端：

```powershell
.\gradlew runClient
```

## 工作台前端（WebStructureRenderer）

静态资源位于 `src/main/resources/assets/structuredataexporter/web`。同步方式由 `gradle.properties` 中 `sde.workbenchSync` 控制：

- **npm（默认）**：先 `git clone` / `git pull` 到目录 `dist-workbench/`（见 `sde.workbenchGitUrl` 等），再执行 `npm ci` 与 `npm run build:workbench`，最后拷贝产物。
- **release**：从 GitHub Release 下载 zip（需已发版）。

一键同步示例：

```powershell
.\gradlew copyWikiWorkbenchWeb
```

`dist-workbench/` 已列入 `.gitignore`，勿提交。

前端仓库：<https://github.com/Windorain/WebStructureRenderer>

## 许可

见仓库根目录 `LICENSE`（MIT）。
