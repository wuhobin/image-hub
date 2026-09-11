# Image Hub

基于 JDK 21、Spring Boot 3.5.0 的单模块 Maven 单体应用，以可执行 JAR 部署。

## 项目规范

后端包分类、Service 接口与实现、模型命名和注释要求见 [项目规范](AGENTS.md)。

## 平台模块

继承 `io.github.wuhobin:platform-parent:1.0.0-SNAPSHOT`，统一复用平台 BOM、Java 版本、Lombok 和 Maven 插件配置。

| 模块 | 接入能力 |
| --- | --- |
| platform-common | 公共工具，由平台模块传递引入 |
| platform-webmvc | Web、统一响应、异常处理、TraceId、Log4j2 |
| knife4j-spring-boot-starter | API 文档，由 platform-webmvc 传递引入 |
| mybatis-plus-spring-boot-starter | MyBatis-Plus、分页和 SQL 拦截器；应用显式使用 HikariCP |
| mysql-connector-j | MySQL JDBC 驱动，runtime 范围 |
| redis-spring-boot-starter | RedisCache、JSON 模板、Redisson 等能力 |
| xlock-spring-boot-starter | 注解及编程式分布式锁 |
| sa-token-spring-boot-starter | Bearer Token 登录态、鉴权，Redis 持久化 |
| oss-spring-boot-starter + qiniu-java-sdk | 七牛云 Kodo 对象存储、上传内容校验 |
| verification-spring-boot-starter | 仅使用邮件验证码，排除短信 SDK 与图片依赖 |

不引入 Quartz 定时任务模块。依赖版本由平台 BOM 管理；应用版本 `0.0.1-SNAPSHOT` 与平台版本独立，不要覆盖父工程的 `revision` 属性。

## 首次构建

本地需安装 Node.js 22.12+，先执行 `npm --prefix frontend ci` 安装锁定的前端依赖（更新锁文件后重新执行，Windows 上先停止 Vite）。Maven 构建会自动执行前端 `build:backend`，将产物写入 `src/main/resources/static/`，再随 JAR 打包；服务器只需要 Java。

以下命令在 `image-hub` 目录执行，假定两个工程位于同一父目录：

```shell
mvn -f ../platform-boot-starter/platform-dependencies-bom/pom.xml install -B -ntp
mvn -f ../platform-boot-starter/pom.xml -pl platform-webmvc,platform-starter/mybatis-plus-spring-boot-starter,platform-starter/redis-spring-boot-starter,platform-starter/xlock-spring-boot-starter,platform-starter/sa-token-spring-boot-starter,platform-starter/oss-spring-boot-starter,platform-starter/verification-spring-boot-starter -am install -DskipTests -B -ntp
mvn clean verify
```

父 POM 通过本地 Maven 仓库解析，准备好平台依赖后可以独立构建。修改平台源码后需重新安装对应模块。

### IDEA 与命令行仓库一致性

平台安装和应用构建必须使用相同的 `settings.xml` 及本地仓库。IDEA 命令中的 `-Dmaven.repo.local` 会覆盖 settings 中的 `localRepository`；例如将平台安装到 `repoBack`，而 IDEA 从 `repo` 构建，就可能读取同版本的旧父 POM/BOM，报验证码依赖或 Spring Boot 插件缺少版本。

遇到此类错误，先按 IDEA 输出的 `-s`、`-Dmaven.repo.local` 参数执行上面的两步平台安装，再构建应用。例如当前机器的应用构建命令为：

```powershell
mvn -s C:/personal-program/Maven/conf/settings.xml "-Dmaven.repo.local=C:/personal-program/Maven/repoBack" clean install
```

`-U` 仅检查远端快照，不会同步另一个本地仓库。无需在业务 POM 中逐个补平台依赖版本；保持父工程与 BOM 最新即可。

## MySQL 与 Redis

正常运行需要可用的 MySQL 和 Redis。先创建 `image_hub` 数据库（字符集 `utf8mb4`），为应用账号授予该库所需权限。业务表脚本为 `deploy/db/schema.sql`，包含用户表和图片表。应用不会自动建库或建表，首次运行需在配置的数据库执行该脚本。本机 `.env` 使用 `image-hub` 数据库（已初始化），与默认名 `image_hub` 不同。

连接参数在 `src/main/resources/application.yml` 中通过占位符注入，配置参考见 `.env.example`。本项目已使用 `spring.config.import` 显式导入进程工作目录下的 `.env`，无需另装 dotenv 依赖或 IDEA EnvFile 插件。

IDEA 运行配置的 Working directory 应为 `C:/IdeaProjects/personal/image-hub`（或指向该目录的 `$MODULE_WORKING_DIR$`）。从其他目录启动时，可设置 `ENV_FILE` 为 `.env` 的绝对路径，例如 `--ENV_FILE=C:/IdeaProjects/personal/image-hub/.env`。文件可选，部署时也可只注入系统环境变量；环境变量和命令行参数优先于文件。

`.env` 按 Java Properties 的 `KEY=value` 格式读取，不使用 `export` 或给值额外加引号；反斜线需按 Properties 规则转义。文件内的 `SPRING_PROFILES_ACTIVE` 已映射到 Spring profile；生产启动仍应显式指定 `--spring.profiles.active=prod`，覆盖本地 dev 设置。`.env` 继续由 Git 忽略，不放入 resources 或打包进 JAR。

| 环境变量 | 默认值或要求 |
| --- | --- |
| MYSQL_HOST / MYSQL_PORT | localhost / 3306 |
| MYSQL_DATABASE | image_hub |
| MYSQL_USERNAME | 必填，填写实际数据库账号 |
| MYSQL_PASSWORD | 必填，不在项目中保存真实密码 |
| MYSQL_URL | 可选，覆盖完整 JDBC URL，包括数据库 TLS 参数 |
| REDIS_HOST / REDIS_PORT | localhost / 6379 |
| REDIS_PASSWORD | 按服务端认证设置 |
| REDIS_DATABASE | 0 |

当前 YAML 未映射 REDIS_USERNAME 和 REDIS_SSL_ENABLED；需要 ACL 用户名或 TLS 时，应先补充对应的 spring.data.redis.username / ssl.enabled 配置。

Redis、分布式锁、Sa-Token 和验证码共享 `spring.data.redis` 配置。当前数据源为 HikariCP，Druid 依赖仍由平台传递引入，但不创建 Druid 数据源。后续 Mapper 接口使用 `@Mapper`，XML 放在 `src/main/resources/mapper` 下即可被加载。

## 启动

在 PowerShell 中设置连接参数后启动，例如：

```powershell
$env:MYSQL_PASSWORD = '<数据库密码>'
$env:MYSQL_USERNAME = '<数据库账号>'
$env:QINIU_ACCESS_KEY = '<七牛云 AccessKey>'
$env:QINIU_SECRET_KEY = '<七牛云 SecretKey>'
$env:QINIU_BUCKET_NAME = '<七牛云存储空间名称>'
$env:QINIU_DOMAIN = 'https://<已绑定的文件访问域名>/'
# 如 Redis 配置了认证，还需设置 REDIS_PASSWORD。
mvn spring-boot:run "-Dspring-boot.run.profiles=dev"
```

也可在 IDEA 中运行 `com.aurora.imagehub.ImageHubApplication`，设置上述环境变量和 `SPRING_PROFILES_ACTIVE=dev`。

```shell
mvn clean verify
java -jar target/image-hub-0.0.1-SNAPSHOT.jar
```

默认端口为 `8080`，可通过 `SERVER_PORT` 修改。本机 `.env` 配置为 `9000`，启动后访问 `http://127.0.0.1:9000/` 即为前端；`/login`、`/register`、`/history` 支持直接访问和刷新，接口使用同源 `/api`。

IDEA 直接运行前，执行一次 `npm --prefix frontend run build:backend`，再由 IDEA 编译资源。开发时仍可运行 `npm --prefix frontend run dev` 使用 Vite 热更新。`static/` 为生成目录，构建时清空重建，已加入 Git 忽略，不放手写资源。

- 存活接口：`GET http://localhost:8080/api/health`，仅表示 HTTP 服务可响应，不检查数据库或 Redis 健康状态。
- 开发文档：`http://localhost:8080/doc.html`。
- 开发 OpenAPI：`http://localhost:8080/v3/api-docs/image-hub`。

所有环境通过 `FrontendWebConfig` 提供前端页面和资源；只有 `dev` 配置启用接口文档。健康接口返回平台 `Result<T>`，支持 `X-Trace-Id` 透传。

## 生产环境

上传 JAR 到 Linux 服务器并使用 Docker Compose 部署，见 [完整部署教程](docs/deploy-docker-compose.md)。可直接使用 `deploy/` 下的 Compose 文件及参数模板，复用已有 MySQL、Redis。

`application.yml` 存放公共配置，`application-prod.yml` 覆盖生产环境差异：关闭接口文档和框架默认静态资源映射（前端显式映射仍启用）、关闭调试及 Sa-Token 操作日志、隐藏 Spring Boot 错误响应细节，保留鉴权和 INFO 级别日志。

部署环境注入 MySQL、Redis、七牛云及 SMTP 环境变量后启动：

```shell
java -jar target/image-hub-0.0.1-SNAPSHOT.jar --spring.profiles.active=prod
```

也可设置系统环境变量 `SPRING_PROFILES_ACTIVE=prod`。本地 `.env` 默认使用 dev，生产部署应显式切换为 prod，避免同时启用 dev。生产配置不保存连接凭据，邮件验证码仍由 `MAIL_VERIFICATION_ENABLED` 控制，填写 SMTP 参数后设为 true。

## 鉴权

Sa-Token 默认开启，单账号模式保护业务接口，请求头格式为 `Authorization: Bearer <token>`。`/api/health` 已放行，文档和 `/error` 等路径由平台白名单处理。

已实现邮箱验证码注册、用户名密码登录、当前用户查询和退出登录；注册后不自动登录。登录时独立生成 Token，3 天有效、无额外空闲超时，退出仅注销当前 Token。登录、注册和发送验证码路径精确放行，其余业务接口鉴权；图片记录按当前用户隔离。密码使用 BCrypt 存储。

平台异常处理使用 HTTP 200 搭配业务码，例如未登录 `code=401`、路由不存在 `code=404`。

## 验证码

本应用仅使用邮件验证码，可注入 `MailVerificationService`。保留 Spring Mail，POM 排除阿里云 `dypnsapi20170525` 短信 SDK 和 `tianai-captcha-springboot-starter`，短信和图片验证码明确关闭。

邮件开关默认关闭；在项目根目录 `.env` 填写以下变量后启用：

- `MAIL_VERIFICATION_ENABLED=true`
- `MAIL_USERNAME`：SMTP 登录账号，一般为邮箱地址。
- `MAIL_PASSWORD`：SMTP 授权码或服务商要求的密码。
- `MAIL_FROM_NAME`：默认 Image Hub。

当前 YAML 使用 QQ 邮箱的 `smtp.qq.com:465`、SSL 开启、STARTTLS 关闭，发件邮箱取 `MAIL_USERNAME`。`MAIL_HOST`、`MAIL_PORT`、`MAIL_FROM` 和 TLS 环境变量目前没有映射，单独修改这些变量不会覆盖 YAML 中的固定值。

验证码为 6 位数字，有效期 5 分钟、发送冷却 60 秒；按邮箱和业务场景存储在 Redis，校验成功后消费。邮件内容由调用方传入，须包含 `{code}`，可使用 `{expireMinutes}`。SMTP 参数以邮箱服务商要求为准；邮件与七牛云使用独立凭据。平台邮件实现会记录验证码，本应用关闭该业务包日志，并将 Sa-Token 日志限制为 WARN，避免记录 Token。当前工程尚未实现数据库 `email.enabled` 开关，启停由上述环境变量控制。

验证码 Starter 提供服务 Bean，不自动提供 HTTP 接口。本应用提供 `/api/auth/email-code` 发送注册验证码，注册接口原子消费验证码；已添加认证接口频率限制和精确白名单。

## 文件存储

启动类已添加 `@EnableFileStorage`。唯一存储平台为 `qiniu-kodo-1`，使用 `dromara.x-file-storage.qiniu-kodo` 配置，文件前缀默认 `base/`，可通过 `QINIU_BASE_PATH` 覆盖；单文件上传限制为 10 MB。七牛云 SDK 版本由 `platform-dependencies-bom` 管理，采用当前 x-file-storage 2.3.0 适配器声明的 7.12.1。

启动前必须配置：

| 环境变量 | 用途 |
| --- | --- |
| QINIU_ACCESS_KEY | 七牛云 AccessKey |
| QINIU_SECRET_KEY | 七牛云 SecretKey |
| QINIU_BUCKET_NAME | 已创建的 Kodo 存储空间名称 |
| QINIU_DOMAIN | 已绑定的文件访问域名，包含协议并以 `/` 结尾，例如 `https://cdn.example.com/` |

项目不会创建存储空间或自动配置域名。使用 `OssTemplate` 进行文件操作；业务上传前显式调用平台 `FileUploadValidator` 做内容校验，配置大小限制本身不会自动执行内容检测。私有空间下载需要通过存储服务生成有时效的签名 URL，普通域名拼接不会自动获得访问权限。

## 测试

```shell
mvn test
```

测试覆盖配置导入、profile 优先级、健康接口、文档开关、未登录拦截、七牛云及邮件服务装配，并确认短信 SDK、图片组件和 Quartz 未被引入。应用测试覆盖默认导入路径，避免读取开发者真实 .env；配置导入测试使用临时虚构文件。测试关闭数据库自动配置，使用 Mock Redis、虚构七牛云和 SMTP 参数，不执行云端上传或发送邮件。

业务集成测试使用 H2 MySQL 模式执行实际建表 SQL 和 Mapper，覆盖注册登录、多 Token 隔离、图片归属、分页筛选、四种格式、大小限制及上传回滚/删除重试；外部邮件和对象存储使用测试替身。基础设施测试继续排除数据库。自动化测试不发送真实邮件，也不连接生产云存储。

## 一期业务与前端联调

登录注册、上传记录与删除接口、数据库初始化、Vite/Nginx 代理和故障排查见 [后端接入说明](docs/backend-api.md)。前端已使用真实接口，启动方式见 [前端 README](frontend/README.md)。
