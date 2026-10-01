# 项目协作指令（ZCode / AI 助手）

## 敏感信息保护（强制）
- `local.properties`（含百度地图 AK `BAIDU_MAP_KEY`）、`*.jks` / `*.keystore`、`*.p12` / `*.pfx` / `*.pem`、`*.env`、`keystore.properties`、`secrets.properties` 为敏感文件：**一律不读取、不打印、不修改、不提交**。
- 需要变更其中的值时，提示用户自行编辑文件，不要代改。
- 本地 `.zcode/config.json`（未提交）已配置 PreToolUse 钩子，强制拦截上述文件的一切工具访问；命中时勿尝试绕过。
- 百度 AK 仅存于 `local.properties`，经 `app/build.gradle.kts` 的 manifestPlaceholders 注入，源码与文档零硬编码。

## 提交规范
- 本地 git 提交：中文描述 ≤50 字，使用 feat / fix / refactor / build / chore / docs 前缀，细粒度拆分；功能经构建验证通过后再提交。

## 项目索引
- 需求见 `doc/REQUIREMENTS.md`；架构与模块归纳见 `doc/ARCHITECTURE.md`（含各模块职责/关键类/依赖表，可直接索引无需重读代码）。
