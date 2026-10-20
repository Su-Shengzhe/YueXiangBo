# 智慧社区停车位共享与结算系统

当前交付阶段：开发环境基线与最小工程验证。业务需求以根目录七份设计文档和项目计划为准；此工程尚未实现登录、车位预约、支付退款、结算或 AI 功能。

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
.\mvnw.cmd -B -ntp clean verify

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

另一台电脑先运行工具链安装脚本，再在 IDEA 中注册同名 SDK，路径为 `.tools/jdk` 下实际 JDK 子目录。IDE 内运行服务时需设置 `local` profile，以及本地配置文件中的数据库、Redis、RabbitMQ 密码和 `PROBE_TOKEN`。推荐先使用启动脚本验证，避免逐个手工录入。

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
- Flyway 当前只建立 `env_probe`、`env_probe_message` 验证表，尚未建立设计文档中的业务表。未来业务迁移使用后续编号；已执行的迁移不可改写。

验证接口只在 `local` profile 下启用，后端及基础组件只绑定本机。它们不能作为生产业务接口发布。RabbitMQ 验证消息不是业务 Outbox；正式可靠事件机制仍按设计实施。

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

此 Dockerfile 将本地 Wrapper/JDK 21 构建的 JAR 放入锁定的 Java 21 JRE 镜像，使用非 root 用户。当前 Compose 只编排基础组件；完整应用容器部署、容器内服务地址和生产鉴权属于下一阶段，不应将本地配置直接当作上线配置。

## 资料入口

- [环境配置与验证记录](./环境配置与验证记录.md)
- [项目计划](./项目计划.md)
- [总体设计说明书](./总体设计说明书.md)
- [接口契约](./docs/openapi.yaml)
