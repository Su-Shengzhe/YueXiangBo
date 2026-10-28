# 悦享泊——智慧社区车位共享与结算平台

> 2026-10-23：M1基础修复及测试已完成，详见[M1阶段基础修复与测试记录](M1阶段基础修复与测试记录.md)。

> 2026-10-22：已新增首条业务演示链路。先看[业务演示启动与验收](docs/业务演示启动与验收.md)、[本轮实施记录](业务闭环实施与验证记录.md)和[第一批业务契约](docs/contracts/业务契约-M1.md)。业务前端5174、网关9090；下文原有命令仍用于独立的最小工程探针环境。AI当前为模型桩，资源管理已扩展；正式结算继续按项目计划开发。


当前代码阶段：第5—6周资源管理与权限完善。已补注册、多角色与版本化授权、车辆维护、车位发布审核、共享窗口、分页报价和管理页面，并保留原预约支付入离场链路；正式结算对账和真实AI仍待后续阶段。当次结果见[第5—6周验收记录](第5—6周实施与验收记录.md)。需求范围与实际实现的差异见[M1需求与接口初始基线](docs/M1需求与接口初始基线.md)，本轮验证见[第3—4周实施与验收记录](第3—4周实施与验收记录.md)。下文环境探针与业务演示为独立入口。

## 已固定的环境

| 工具 | 版本 |
|---|---|
| JDK | Eclipse Temurin 21.0.12.1+1 |
| Node.js / npm | 24.22.0 / 11.19.1 |
| Maven Wrapper 使用的 Maven | 3.9.16 |
| Spring Boot / Spring Cloud | 4.0.8 / 2025.1.3 |
| Vue / Vite / TypeScript | 3.5.44 / 8.3.4 / 5.9.3 |
| vue-tsc / Vue 插件 / Element Plus | 3.3.13 / 6.0.10 / 2.14.7 |
| 浏览器测试 | Playwright 1.64.1，使用已安装的 Microsoft Edge |

安装包 URL 和校验值见 `scripts/toolchain.lock.json`。容器使用 `deploy/images.lock.json` 中的不可变摘要。Spring JDBC、Flyway、数据库驱动、AMQP、Redis 依赖由 Boot BOM 管理，Cloud 组件由 Cloud BOM 管理。

## 目录

```text
backend/
  registry/          Eureka 注册中心
  gateway/           Gateway WebFlux，lb://parking-service
  parking-service/   Spring JDBC + Flyway + Redis + RabbitMQ 验证接口
frontend/            Vue + TypeScript + Element Plus 验证页面
deploy/              本地基础组件 Compose、镜像摘要、运行镜像 Dockerfile
scripts/             工具链、环境初始化、启动停止、集成和浏览器测试
docs/                OpenAPI 契约与脱敏验证证据
.tools/              本地工具和依赖缓存，不入版本管理
.runtime/            本机进程信息、运行日志和截图，不入版本管理
```

## 第一次启动

在项目根目录使用 **PowerShell 7**。Docker Desktop 使用 Linux 容器，执行安装步骤需要访问官方软件源。其他电脑需要自行安装 Docker Desktop 和 Edge。

```powershell
# 此电脑已安装项目工具链；其他电脑运行此脚本下载同一锁定版本。
.\scripts\Install-Toolchain.ps1

# 点号和脚本之间有空格；仅切换当前终端，不改系统 PATH。
. .\scripts\Use-Toolchain.ps1
java -version
node -v
npm.cmd -v
.\mvnw.cmd -v

# 仅首次生成随机本地凭据；已有文件不会覆盖。
.\scripts\Initialize-Local.ps1
.\scripts\Build-Backend.ps1

Push-Location frontend
npm.cmd ci
npm.cmd run build
Pop-Location

# 启动并等待基础组件；然后启动本地 Java 进程。
.\scripts\Start-Local.ps1
.\scripts\Invoke-Smoke.ps1
.\scripts\Test-Frontend.ps1

# 前台启动页面；另一个终端执行停止脚本。
.\scripts\Start-Frontend.ps1
```

前端地址为 `http://127.0.0.1:5173`。页面验证令牌取自本机 `deploy/.env.local` 中的 `PROBE_TOKEN`，仅用于开发验证，不是正式登录机制。不要将这个文件上传、提交到仓库或放入截图。页面不会将令牌写入浏览器存储。

每次开新终端先执行 `. .\scripts\Use-Toolchain.ps1`；未执行时仍可能使用旧的全局 JDK 17/Node.js 18。这是项目级隔离，不是全局升级。

## IDEA

本机 IntelliJ IDEA 2024.1 已增加 `Community-Parking-JDK-21`，项目 `.idea` 配置指定此 SDK、Java 21、UTF-8 和 Maven Wrapper。导入根目录 `pom.xml` 后核对 Project SDK、各模块继承的 SDK、Maven Importer/Runner；本次未通过 IDE 界面实际打开和运行工程。

另一台电脑先运行工具链安装脚本，再在 IDEA 中注册同名 SDK，路径为 `.tools/jdk` 下实际 JDK 子目录。IDE 内运行验证服务时需设置 `local,probe` profiles，以及本地配置文件中的数据库、Redis、RabbitMQ 密码和 `PROBE_TOKEN`。推荐先使用启动脚本验证，避免逐个手工录入。

## 本地端口

| 组件 | 宿主地址 |
|---|---|
| Vite | 127.0.0.1:5173 |
| 网关 | 127.0.0.1:8080 |
| Eureka | 127.0.0.1:8761 |
| parking-service | 127.0.0.1:8082 |
| MySQL | 127.0.0.1:13306 → 容器 3306 |
| Redis | 127.0.0.1:16379 → 容器 6379 |
| RabbitMQ AMQP | 127.0.0.1:25672 → 容器 5672 |
| RabbitMQ 管理页 | 127.0.0.1:25673 → 容器 15672 |

本机已有其他项目占用 5672/15672，本项目使用独立端口。此配置用于本地开发；正式部署文档中的公网入口、TLS、三个业务数据库和正式权限仍需后续实现。

## 验证范围

- Maven 测试：H2 验证事务提交、异常回滚、输入校验。它不替代 MySQL 集成测试。
- `Invoke-Smoke.ps1`：真实网关、Eureka、MySQL、Redis、RabbitMQ；检查写读、回滚、权限、消息消费等。
- `Test-Frontend.ps1`：真实 Edge 操作页面保存记录，刷新后重新读取；运行前须已启动后端。
- `docs/openapi.yaml`：本阶段验证接口契约，可以导入支持 OpenAPI 3.1 的接口工具。尚未接入在线接口文档 starter。
- Flyway 的验证迁移在 `db/probe/V001__environment_probe.sql`，仅 `probe` profile 启用。已执行的 V001 内容保持不变；未来业务迁移使用独立迁移目录和明确的数据库方案，不能直接复用验证表作为业务结构。

验证接口要求 `probe` 与 `local` 或 `container` 同时启用；网关探针路由也由 `probe` profile 控制。本地服务绑定回环地址；容器服务监听容器网络，宿主端口仅发布到回环地址。RabbitMQ 验证消息不是业务 Outbox；正式可靠事件机制仍按设计实施。

## 一次执行完整验收

```powershell
.\scripts\Verify-Project.ps1 -CleanContainers -ExerciseStartupFailure
```

依次执行脚本回归、后端测试与构建、前端 `npm ci` 与构建、本地重启及集成测试、Edge 测试、启动故障注入与恢复、独立临时容器集成测试，以及失败报告和旧构建拒绝检查。该命令会重启本项目 Java 服务；不会停止其他项目。临时容器使用随机端口、独立凭据和全新数据卷，结束时仅删除本次 `community-parking-check-*` 项目的容器与卷。本地开发数据卷保留。

每次运行的结果与日志位于 `.runtime/verifications/<运行编号>/`，子检查分别保存在 `checks`、`browser-checks`、`recovery-checks`、`container-checks`。失败也保存结果；`.runtime/smoke-result.json` 是最近一次检查状态，历史证据以独立运行目录为准。

`Start-Local.ps1` 会预检 JAR 和端口，等待健康及网关路由就绪；重复运行可复用本脚本启动且 JAR 一致的健康进程。修改代码并重新构建后，执行 `Stop-Local.ps1` 再启动。启动中途失败只停止本次新建的 Java 进程。

## 修改锁定版本和本地配置

工具安装始终读取已有 `scripts/toolchain.lock.json`，不会自行选择最新版本。升级时明确修改对应版本、解压目录、官方安装包 URL 与 SHA-256；修改 Maven 时同时更新 `.mvn/wrapper/maven-wrapper.properties` 的 URL 和校验值。运行安装脚本和完整验收通过后，再提交这一组变更。下载残留、校验失败的文件和旧工具备份保留在 `.tools`，不会当作有效安装直接跳过检查。

修改基础组件镜像摘要后执行 `Initialize-Local.ps1 -SyncImages`，只同步镜像并备份原配置，保留凭据。配置缺字段、重复键、镜像漂移或端口重复会提前报错；如果凭据文件丢失且已有项目数据卷，脚本拒绝生成新密码，必须恢复原文件。

## 停止和再次运行

```powershell
.\scripts\Stop-Local.ps1
docker compose --env-file deploy/.env.local -f deploy/compose.yaml stop
```

停止不会删除数据卷。前端在启动终端按 Ctrl+C。不要为了重新运行而清空 Docker 数据或删除本地凭据；已有数据库卷会保留首次初始化的密码。

## 容器 Java 21

```powershell
.\scripts\Build-AppImage.ps1 -Module parking-service
```

默认先重新运行后端测试与构建，再将 JAR 放入锁定的 Java 21 JRE 镜像，使用非 root 用户。`-UseVerifiedBuild` 可复用已通过构建，但必须通过源码指纹和 JAR SHA-256 校验。构建记录同时保存 Git 提交、源码文件摘要、JAR 摘要和实际镜像 ID；工作区未提交时，以源码指纹确定本次输入。

`deploy/compose.apps.yaml` 提供三个验证应用的容器编排，容器内使用服务名连接依赖。运行 `Test-Container.ps1` 可独立执行全新数据卷验证，并检查关闭 `probe` 后验证接口不可访问。该配置用于开发验收，生产鉴权、TLS、备份与正式业务服务仍需后续实现。

## 资料入口

- [环境配置与验证记录](./环境配置与验证记录.md)
- [工程优化与验证记录](./工程优化与验证记录.md)
- [项目计划](./项目计划.md)
- [总体设计说明书](./总体设计说明书.md)
- [接口契约](./docs/openapi.yaml)
