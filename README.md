# Image Hub

基于 JDK 21、Spring Boot 3.5.0 的单模块 Maven 后端，以可执行 JAR 部署；React 前端独立构建，由 Nginx 托管。

## 项目规范

后端包分类、Service 接口与实现、模型命名和注释要求见 [项目规范](AGENTS.md)。

## 平台模块

继承 `io.github.wuhobin:platform-parent:1.0.5`，统一复用平台 BOM、Java 版本、Lombok 和 Maven 插件配置。安全模块暂时使用本地平台源码的 `1.0.0-SNAPSHOT`，包含多账号未匹配路由的默认拒绝规则；发布新版平台后再恢复为 BOM 管理，见 [管理后台说明](docs/admin.md)。

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
| spring-ai-openai 1.1.8 | 动态模型配置、OpenAI Images 兼容生图 |
| verification-spring-boot-starter | 仅使用邮件验证码，排除短信 SDK 与图片依赖 |

不引入 Quartz 定时任务模块。依赖版本由平台 BOM 管理；应用版本 `0.0.1-SNAPSHOT` 与平台版本独立，不要覆盖父工程的 `revision` 属性。

## 首次构建

后端独立执行 Maven 构建，不依赖 Node.js 或前端依赖。前端构建需要 Node.js 22.12+，执行 `npm --prefix frontend ci` 和 `npm --prefix frontend run build`，产物位于 `frontend/dist/`，单独部署到 Nginx。Maven 不再调用 npm，JAR 排除旧 `static/` 产物。

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

每日签到、积分收入规则及升级脚本见 [签到说明](docs/check-in.md)。

场景模板、提示词示例库、分类标签及升级脚本见 [模板库说明](docs/creation-templates.md)。

邀请注册、双方积分奖励、集中注册审核及升级脚本见 [邀请说明](docs/invitation.md)。

## MySQL 与 Redis

正常运行需要可用的 MySQL 和 Redis。先创建 `image_hub` 数据库（字符集 `utf8mb4`），为应用账号授予该库所需权限。业务表脚本为 `deploy/db/schema.sql`，包含管理员表、用户表、图片表和系统配置表。应用不会自动建库或建表，首次运行需在配置的数据库执行该脚本。本机 `.env` 使用 `image-hub` 数据库（已初始化），与默认名 `image_hub` 不同。

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

后端默认端口为 `8080`，可通过 `SERVER_PORT` 修改。本机 `.env` 配置为 `9000`；该端口提供 `/api`，不再提供前端页面。

开发时独立运行 `npm --prefix frontend run dev`，访问 `http://127.0.0.1:5173/` 使用热更新。在 `frontend/.env.local` 设置 `IMAGE_HUB_API_TARGET=http://127.0.0.1:9000`，由 Vite 转发 `/api`。IDEA 启动后端无需先构建前端；升级旧工作区时可清理以前生成的 `src/main/resources/static/`，首次执行 `mvn clean verify` 清除旧编译产物。

- 存活接口：`GET http://localhost:8080/api/health`，仅表示 HTTP 服务可响应，不检查数据库或 Redis 健康状态。
- 开发文档：`http://localhost:8080/doc.html`。
- 开发 OpenAPI：`http://localhost:8080/v3/api-docs/image-hub`。

前端页面由 Vite（开发）或 Nginx（部署）提供；只有后端 `dev` 配置启用接口文档。健康接口返回平台 `Result<T>`，支持 `X-Trace-Id` 透传。

## 生产环境

上传后端 JAR 和前端 `frontend/dist/` 到 Linux 服务器，分别使用 Docker Compose 和宿主机 Nginx 部署，见 [完整部署教程](docs/deploy-docker-compose.md)。前端页面与 `/api` 使用同一域名，由 Nginx 转发 API；复用已有 MySQL、Redis。

`application.yml` 存放公共配置，`application-prod.yml` 覆盖生产环境差异：关闭接口文档和框架默认静态资源映射、关闭调试及 Sa-Token 操作日志、隐藏 Spring Boot 错误响应细节，保留鉴权和 INFO 级别日志。

部署环境注入 MySQL、Redis、七牛云及 SMTP 环境变量后启动：

```shell
java -jar target/image-hub-0.0.1-SNAPSHOT.jar --spring.profiles.active=prod
```

也可设置系统环境变量 `SPRING_PROFILES_ACTIVE=prod`。本地 `.env` 默认使用 dev，生产部署应显式切换为 prod，避免同时启用 dev。生产配置不保存连接凭据，邮件验证码仍由 `MAIL_VERIFICATION_ENABLED` 控制，填写 SMTP 参数后设为 true。

## 鉴权

Sa-Token 默认开启，普通用户和管理员使用独立账号类型保护对应业务接口，请求头格式为 `Authorization: Bearer <token>`。`/api/health` 已放行，文档和 `/error` 等路径由平台白名单处理。

已实现邮箱验证码注册、用户名密码登录、当前用户查询和退出登录；注册后不自动登录。登录时独立生成 Token，3 天有效、无额外空闲超时，退出仅注销当前 Token。登录、注册和发送验证码路径精确放行，其余业务接口鉴权；图片记录按当前用户隔离。密码使用 BCrypt 存储。

平台异常处理使用 HTTP 200 搭配业务码，例如未登录 `code=401`、路由不存在 `code=404`。

管理入口为同域名 `/admin`，接口统一 `/api/admin/**`；本期提供用户搜索、分页和 Redis 剩余积分展示。升级建表、首次管理员初始化及接口约定见 [管理后台说明](docs/admin.md)。

普通用户接口统一 `/api/app/**`（认证 `/api/app/auth/**`、图片 `/api/app/images/**`），公共健康检查保持 `/api/health`。前后端需同步更新，旧 `/api/auth/**` 和 `/api/images/**` 不再提供接口。

## 验证码

本应用仅使用邮件验证码，可注入 `MailVerificationService`。保留 Spring Mail，POM 排除阿里云 `dypnsapi20170525` 短信 SDK 和 `tianai-captcha-springboot-starter`，短信和图片验证码明确关闭。

邮件开关默认关闭；在项目根目录 `.env` 填写以下变量后启用：

- `MAIL_VERIFICATION_ENABLED=true`
- `MAIL_USERNAME`：SMTP 登录账号，一般为邮箱地址。
- `MAIL_PASSWORD`：SMTP 授权码或服务商要求的密码。
- `MAIL_FROM_NAME`：默认 Image Hub。

当前 YAML 使用 QQ 邮箱的 `smtp.qq.com:465`、SSL 开启、STARTTLS 关闭，发件邮箱取 `MAIL_USERNAME`。`MAIL_HOST`、`MAIL_PORT`、`MAIL_FROM` 和 TLS 环境变量目前没有映射，单独修改这些变量不会覆盖 YAML 中的固定值。

验证码为 6 位数字，有效期 5 分钟、发送冷却 60 秒；按邮箱和业务场景存储在 Redis，校验成功后消费。邮件内容由调用方传入，须包含 `{code}`，可使用 `{expireMinutes}`。SMTP 参数以邮箱服务商要求为准；邮件与七牛云使用独立凭据。平台邮件实现会记录验证码，本应用关闭该业务包日志，并将 Sa-Token 日志限制为 WARN，避免记录 Token。当前工程尚未实现数据库 `email.enabled` 开关，启停由上述环境变量控制。

验证码 Starter 提供服务 Bean，不自动提供 HTTP 接口。本应用提供 `/api/app/auth/email-code` 发送注册验证码，注册接口原子消费验证码；已添加认证接口频率限制和精确白名单。

开发联调（`dev`）和测试（`test`）环境的邮件验证码固定为 `123456`，由 `DevelopmentVerificationConfig` 替换平台生成器；仍需先发送验证码，保留实际邮件投递、5 分钟有效期、60 秒冷却和成功后消费。邮件开关仍需启用，SMTP 和 Redis 仍需可用。启用 `prod` 时（包括同时启用 `dev/test`），或未指定这些环境时，继续使用平台随机验证码；不提供通用验证码绕过校验。切换环境后需要重启后端，再重新发送验证码。

## 文件存储

启动类已添加 `@EnableFileStorage`。唯一存储平台为 `qiniu-kodo-1`，使用 `dromara.x-file-storage.qiniu-kodo` 配置，文件前缀默认 `ImgHub/`，可通过 `QINIU_BASE_PATH` 覆盖；单文件上传限制为 10 MB。七牛云 SDK 版本由 `platform-dependencies-bom` 管理，采用当前 x-file-storage 2.3.0 适配器声明的 7.12.1。

启动前必须配置：

| 环境变量 | 用途 |
| --- | --- |
| QINIU_ACCESS_KEY | 七牛云 AccessKey |
| QINIU_SECRET_KEY | 七牛云 SecretKey |
| QINIU_BUCKET_NAME | 已创建的 Kodo 存储空间名称 |
| QINIU_DOMAIN | 已绑定的文件访问域名，包含协议并以 `/` 结尾，例如 `https://cdn.example.com/` |

项目不会创建存储空间或自动配置域名。使用 `OssTemplate` 进行文件操作；业务上传前显式调用平台 `FileUploadValidator` 做内容校验，配置大小限制本身不会自动执行内容检测。私有空间下载需要通过存储服务生成有时效的签名 URL，普通域名拼接不会自动获得访问权限。

## 累计上传积分

每个账号免费累计总积分由后台“配置管理”维护，初始值为 100，允许设为 0；修改对所有用户生效，已消耗积分不变，剩余最低为 0。成功上传扣 1 积分，失败不扣，删除不返还。前端在整批发送前查询 `GET /api/app/images/quota`；超过余额时整批不发送。后端对每张图片独立校验，耗尽返回业务码 `40301`。多个设备竞争时可能在批次中途停止，已成功的图片保留。

注册成功后尝试在 Redis 初始化累计消耗为 0，不覆盖已有记录；Redis 初始化失败仍返回注册成功，后续普通积分查询沿用恢复逻辑。

旧数据库在部署新版应用前执行一次 `deploy/db/migrations/20260914_upload_quota.sql`。旧图片的 `quota_charged` 为 0，上线后成功上传的图片为 1；首次建库直接使用最新 `schema.sql`。该标记仅用于恢复，不是前端显示字段；已删除记录不能物理清理，否则会影响消耗历史。

Redis Key 为 `image-hub:quota:{用户ID}`，不设置过期时间；值为 `u:累计消耗`，上传期间为 `u:包含预占的累计消耗:图片ID`，仅支持这一种格式。Redisson 看门狗锁让同一账号串行上传，不持有数据库长事务；另一个同时上传的请求返回 `409`，其他账号不受影响。正常积分查询读取配置缓存和 Redis 消耗；Key 缺失、损坏或上次上传进程中断时，取得锁后根据新版成功图片恢复消耗。Redis 不可用时拒绝新的上传，恢复后重试。

配置使用通用键值表 `hub_settings` 和父项目二级缓存（各配置键独立缓存 3 天），配置项由代码预置，管理员修改值；新增配置无需增加表字段。部署迁移及修改缓存流程见 [管理后台](docs/admin.md#配置管理)。积分接口保留 `total`、`remaining` 并增加 `used`；总额调低后，`used` 可以大于 `total`。

部署 Redis 时启用 AOF（`appendonly yes`、`appendfsync everysec`），持久化目录挂载到可靠磁盘并备份，积分实例避免使用会淘汰这些 Key 的缓存策略（建议 `noeviction`）。不要单独清理正在上传的积分/锁 Key；需要恢复 Redis 时暂停上传、停止旧实例后恢复，再启动服务。AOF 故障恢复可能丢失少量最近写入，本期接受这种极端情况的少量积分误差。

## AI 创作

网站首页 `/` 为 AI 创作主入口，上传图片位于 `/upload`，旧 `/create` 自动跳转首页；管理员模型配置 `/admin/models`。本期支持 GPT-Image-2 单张文生图，与上传共用积分，生成结果自动保存七牛，保存失败保留结果 24 小时供重试。升级迁移、加密主密钥和接口说明见 [AI 创作接入](docs/ai-creation.md)。

## 测试

公开作品广场、分享控制、做同款及数据库迁移见 [作品分享说明](docs/creation-sharing.md)。

```shell
mvn test
```

测试覆盖配置导入、profile 优先级、健康接口、文档开关、未登录拦截、七牛云及邮件服务装配，并确认短信 SDK、图片组件和 Quartz 未被引入。应用测试覆盖默认导入路径，避免读取开发者真实 .env；配置导入测试使用临时虚构文件。测试关闭数据库自动配置，使用 Mock Redis、虚构七牛云和 SMTP 参数，不执行云端上传或发送邮件。

业务集成测试使用 H2 MySQL 模式执行实际建表 SQL 和 Mapper；H2 的 DATETIME 默认精度与 MySQL 不同，测试额外执行 `h2-datetime-precision.sql` 对齐到秒，覆盖注册登录、多 Token 隔离、图片归属、分页筛选、四种格式、大小限制及上传回滚/删除重试；外部邮件和对象存储使用测试替身。基础设施测试继续排除数据库。自动化测试不发送真实邮件，也不连接生产云存储。

## 一期业务与前端联调

登录注册、上传记录与删除接口、数据库初始化、Vite/Nginx 代理和故障排查见 [后端接入说明](docs/backend-api.md)。前端已使用真实接口，启动方式见 [前端 README](frontend/README.md)。
