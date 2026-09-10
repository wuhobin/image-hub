# Image Hub：上传 JAR 后使用 Docker Compose 部署

适用场景：本地 Windows 打包，Linux 服务器运行，复用已有 MySQL 和 Redis。Compose 只管理 image-hub 应用，不创建或修改数据库服务。

采用 Java 21 官方运行镜像挂载 JAR，服务器无需 Maven、JDK 或项目源码，也无需自己构建镜像。每次发布保存一个独立版本目录，切换版本后重建容器即可。该方式是 [Eclipse Temurin 官方镜像支持的用法](https://hub.docker.com/_/eclipse-temurin)。

## 1. 准备服务器

服务器需要能访问 MySQL、Redis、七牛云和 QQ SMTP。先准备已有数据库、应用数据库账号，以及七牛云空间和访问域名。默认至少为应用预留约 1 GiB 内存，并为系统及其他服务保留余量；最终资源应按业务负载调整。

检查 Docker：

```bash
sudo docker version
sudo docker compose version
```

要求 Linux Docker Engine 20.10+、Compose 2.30.0+。这里使用 `docker compose`，不使用旧版 `docker-compose`。Compose 的 `env_file.format: raw` 从 2.30.0 开始支持，可以保留密码中的 `$` 等字符。[Compose 官方说明](https://docs.docker.com/reference/compose-file/services/#format)

未安装 Docker 时，按服务器系统选择 [Docker Engine 安装教程](https://docs.docker.com/engine/install/)。Ubuntu 可按[官方 Ubuntu 步骤](https://docs.docker.com/engine/install/ubuntu/)配置软件源，再安装：

```bash
# 前提：已按上面的官方步骤配置 Docker APT 软件源。
sudo apt-get update
sudo apt-get install docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
sudo systemctl enable --now docker
sudo docker compose version
```

已安装 Docker 的服务器跳过安装步骤，不要重装已有数据库环境。

以下示例使用 SSH 账号 `deploy`、服务器地址 `SERVER_IP`，请替换为自己的值。登录服务器，创建目录：

```bash
sudo mkdir -p /opt/image-hub
sudo chown "$(id -u):$(id -g)" /opt/image-hub
cd /opt/image-hub
mkdir -p releases/20260910-001 logs
sudo chown 10001:10001 logs
sudo chmod 750 logs
```

容器以 UID/GID `10001:10001` 运行，日志目录需要允许该用户写入。其余文件保持部署账号所有即可。

最终目录如下：

```text
/opt/image-hub/
├── compose.yaml
├── .env                 # Compose 参数：版本、镜像、宿主机端口
├── app.env              # 业务连接参数和密钥，仅服务器保存
├── logs/
└── releases/
    ├── 20260910-001/app.jar
    └── 20260910-002/app.jar
```

## 2. 本地打包并上传

在本地 PowerShell 中执行：

```powershell
Set-Location C:/IdeaProjects/personal/image-hub
& 'C:/personal-program/Maven/bin/mvn.cmd' -s 'C:/personal-program/Maven/conf/settings.xml' '-Dmaven.repo.local=C:/personal-program/Maven/repoBack' clean verify
if ($LASTEXITCODE -ne 0) { throw '打包失败，请先修复构建错误' }

Get-FileHash ./target/image-hub-0.0.1-SNAPSHOT.jar -Algorithm SHA256

# 上传部署文件及无密钥模板，不要上传本地真实 .env。
scp ./deploy/compose.yaml deploy@SERVER_IP:/opt/image-hub/compose.yaml
scp ./deploy/.env.example deploy@SERVER_IP:/opt/image-hub/.env.example
scp ./deploy/app.env.example deploy@SERVER_IP:/opt/image-hub/app.env.example
scp ./target/image-hub-0.0.1-SNAPSHOT.jar deploy@SERVER_IP:/opt/image-hub/releases/20260910-001/app.jar
```

如果使用非默认 SSH 端口，`scp` 加 `-P 端口`，`ssh` 加 `-p 端口`。没有 scp 时也可使用 SFTP 工具上传至相同位置。

必须上传 `.jar`，不要上传 `.jar.original`。前者包含 Spring Boot 及平台依赖，服务器不需要再下载 `platform-parent` 或平台 Starter。首次本地构建如果缺平台快照，请先按项目 README 安装平台 BOM 和所需模块。

在服务器核对文件：

```bash
cd /opt/image-hub
chmod 644 releases/20260910-001/app.jar
sha256sum releases/20260910-001/app.jar
```

两端 SHA-256 应一致，字母大小写不影响比较。

## 3. 填写生产参数

仅首次部署复制模板；后续升级保留已有配置：

```bash
cd /opt/image-hub
cp -n .env.example .env
cp -n app.env.example app.env
chmod 600 .env app.env
nano .env
nano app.env
```

`.env` 只控制部署：

```dotenv
APP_VERSION=20260910-001
JAVA_IMAGE=eclipse-temurin:21-jre-jammy
HTTP_PORT=9000
PUBLISH_HOST=0.0.0.0
APP_MEMORY=1g
```

`APP_VERSION` 必须与上传目录一致。宿主机的 `9000` 映射到容器固定端口 `8080`；更换外部端口只修改 `HTTP_PORT`。容器内始终使用 prod，命令行参数会覆盖误填的 dev profile。

`app.env` 根据 `app.env.example` 填写真实值：

| 参数 | 应填写的内容 |
| --- | --- |
| MYSQL_HOST / MYSQL_PORT | 数据库服务器地址和端口 |
| MYSQL_DATABASE | 已存在的数据库名，和服务器实际名称一致 |
| MYSQL_USERNAME / MYSQL_PASSWORD | 应用数据库账号及密码 |
| REDIS_HOST / REDIS_PORT / REDIS_DATABASE | 已有 Redis 的地址、端口和数据库编号 |
| SPRING_DATA_REDIS_PASSWORD | Redis 密码；无认证时删除这一行 |
| SPRING_DATA_REDIS_USERNAME | 可选，Redis ACL 用户名 |
| SPRING_DATA_REDIS_SSL_ENABLED | Redis 是否使用 TLS，默认 false |
| QINIU_ACCESS_KEY / QINIU_SECRET_KEY | 七牛云凭据 |
| QINIU_BUCKET_NAME / QINIU_DOMAIN | 存储空间及已绑定的域名；域名带协议并以 `/` 结尾 |
| QINIU_BASE_PATH | 文件前缀，例如 `base/` |
| MAIL_USERNAME / MAIL_PASSWORD | QQ 邮箱账号及 SMTP 授权码 |
| MAIL_VERIFICATION_ENABLED | SMTP 参数准备好后设为 true，否则 false |

这里使用 Spring 标准的 `SPRING_DATA_REDIS_PASSWORD`，因此即使当前 YAML 的 `password` 行被注释，Redis 密码仍能生效。不是继续填写未映射的 `REDIS_PASSWORD`。

当前邮件配置固定为 `smtp.qq.com:465`、SSL 开启，邮箱需先开通 SMTP。更换 SMTP 服务商时，可在 app.env 中设置 `SPRING_MAIL_HOST`、`SPRING_MAIL_PORT`、`SPRING_MAIL_PROPERTIES_MAIL_SMTP_SSL_ENABLE`、`SPRING_MAIL_PROPERTIES_MAIL_SMTP_STARTTLS_ENABLE` 覆盖 Spring 配置。

`app.env` 使用原始 `KEY=value`：不加引号、不写 `export`、不在值后写行内注释；密码中的 `$` 和反斜线直接填写。不要把这份文件交给本地 Spring Properties 导入方式解析，两者的转义规则不同。

Compose 的 `.env` 用于替换 Compose 文件中的变量，不会自动把全部业务变量传给容器；这里通过 `env_file: app.env` 明确传入。容器不挂载 `.env` 或 app.env 文件，也不需要 IDEA 插件。[Docker 环境变量说明](https://docs.docker.com/compose/how-tos/environment-variables/set-environment-variables/)

### MySQL、Redis 的地址怎么填

- 在另一台服务器或云数据库：填容器可访问的内网地址，并允许应用服务器的连接来源。
- 直接安装在同一宿主机：可填 `host.docker.internal`，Compose 已配置 Linux host-gateway。服务必须监听宿主机可被 Docker 网桥访问的地址，并允许该网段访问；只监听 `127.0.0.1` 时仍然连不上。
- 在其他 Docker 容器：让应用加入数据库所在的 Docker 网络，再使用其容器服务名，或使用已映射到宿主机且网桥可访问的端口。不同 Compose 项目的默认网络不会自动互通。

**不要将 MYSQL_HOST 或 REDIS_HOST 填成 localhost。** 容器内 localhost 指向应用容器自身。数据库名称中有连字符也要原样填写，不要擅自把 `image-hub` 改成 `image_hub`。

## 4. 启动与验证

```bash
cd /opt/image-hub

# 仅校验，不输出展开后的密钥。
sudo docker compose config --quiet

# 首次拉取 Java 运行镜像。
sudo docker compose pull
sudo docker compose up -d

sudo docker compose ps
sudo docker compose logs --tail=100 -f image-hub
```

看到 Spring Boot 的 `Started ImageHubApplication` 后按 Ctrl+C 退出日志查看，容器会继续运行。

在服务器测试：

```bash
curl --fail --show-error http://127.0.0.1:9000/api/health
```

期望 HTTP 请求成功，JSON 中 `code` 为 200、`data.status` 为 `UP`。该接口只代表 Web 应用可响应，不验证 MySQL、Redis、七牛云或 SMTP；上线前仍需按业务流程验证数据库操作、缓存、文件上传和邮件投递。本 Compose 没有内置健康检查，`ps` 显示 running 不能单独证明应用已就绪。

若使用默认 `PUBLISH_HOST=0.0.0.0`，在云服务器安全组和主机防火墙中放行所选 TCP 端口，再访问 `http://SERVER_IP:9000/api/health`。Docker 发布端口可能绕过部分 UFW 规则，公网访问限制应结合云安全组或 Docker 防火墙链设置，见 [Docker 防火墙说明](https://docs.docker.com/engine/network/packet-filtering-firewalls/)。

正式域名访问通常在宿主机配置 Nginx/Caddy 和 HTTPS，再把 PUBLISH_HOST 改为 `127.0.0.1`，反代到 `127.0.0.1:9000`。若反向代理本身也在容器内，应使用共享网络连接服务，不能用代理容器自己的 127.0.0.1。

prod 下 `/doc.html`、`/v3/api-docs` 不开放，属于预期行为。

## 5. 日志、停止与修改配置

```bash
cd /opt/image-hub
sudo docker compose logs --tail=200 image-hub
sudo tail -n 100 logs/error.log
sudo docker compose stop
sudo docker compose start
```

控制台日志由 Docker local 驱动轮转，应用文件日志保存在宿主机 logs 下。应用自身按日期和大小轮转文件，但历史压缩日志仍需按服务器保留策略定期归档或清理。

修改 app.env 或 .env 后，执行：

```bash
sudo docker compose config --quiet
sudo docker compose up -d --force-recreate image-hub
```

只执行 `restart` 不会重新注入修改后的环境变量。容器配置中可查看运行时环境，故不要公开 `docker inspect` 或 `docker compose config` 的完整输出。

## 6. 发布新版本与回滚

每次使用新的版本目录，例如 `20260910-002`。不要覆盖正在运行版本的 app.jar，避免运行中的类加载受到影响。

服务器先创建目录：

```bash
cd /opt/image-hub
mkdir -p releases/20260910-002
```

本地重新 `clean verify`，上传新 JAR：

```powershell
scp ./target/image-hub-0.0.1-SNAPSHOT.jar deploy@SERVER_IP:/opt/image-hub/releases/20260910-002/app.jar
```

服务器确认哈希、设置可读权限，然后修改 .env 中 `APP_VERSION=20260910-002`：

```bash
cd /opt/image-hub
chmod 644 releases/20260910-002/app.jar
sha256sum releases/20260910-002/app.jar
nano .env
sudo docker compose config --quiet
sudo docker compose up -d --force-recreate image-hub
sudo docker compose logs --tail=100 image-hub
curl --fail --show-error http://127.0.0.1:9000/api/health
```

不需要 `docker compose build`，因为这里挂载的是 JAR，没有 Dockerfile。一次只运行一个应用容器，切换版本会有短暂停机。启动前镜像缺失或 JAR 文件缺失时先解决文件问题。

回滚时，将 .env 的 APP_VERSION 改回 `20260910-001`，再执行 `up -d --force-recreate image-hub`。保留旧目录；只回滚 JAR 不会回滚数据库、Redis 数据或云存储内容，后续业务涉及表结构变更时需要另行评估兼容性。

JAVA_IMAGE 使用滚动的 Java 21 标签，首次验证后可记录镜像 digest 并填入 `.env`，例如 `eclipse-temurin@sha256:<实际摘要>`，使运行环境可复现。常规 JAR 更新不用 pull；更新 Java 镜像时再 pull 并验证。

## 7. 常见错误

| 现象 | 检查方式 |
| --- | --- |
| env_file 不支持 format | Compose 低于 2.30.0，升级 Compose 插件 |
| JAR 路径不存在或 unable to access jarfile | 检查 APP_VERSION、releases 目录和上传文件名；文件必须存在且可读 |
| logs permission denied | 确认 logs 目录 UID/GID 为 10001:10001 |
| MySQL connection refused / access denied | 检查内网地址、3306、账号权限、数据库是否存在和网络放行 |
| Redis NOAUTH / WRONGPASS | 使用 SPRING_DATA_REDIS_PASSWORD；ACL 场景补用户名；不要只设置 REDIS_PASSWORD |
| 邮件认证失败 | 使用 SMTP 授权码，检查 QQ 邮箱已开通 SMTP 和服务器出站 465 |
| Could not resolve placeholder | 检查 app.env 是否存在、变量名是否与 YAML 一致；文件必须通过 env_file 注入 |
| 应用端口不通 | 先看启动日志，再查 HTTP_PORT、端口占用、云安全组和防火墙 |
| 容器退出 137 | 检查是否 OOM；APP_MEMORY 默认 1g，按实际负载增大或减少应用资源消耗 |

## 本次文件验证范围

部署模板不包含实际密钥，也没有连接你的服务器。已使用官方 Compose v2.39.4 CLI（下载后核对发布的 SHA-256）及虚构参数通过 config 校验，检查 prod profile、端口 8080、JAR 只读挂载、密码特殊字符序列化及缺失 APP_VERSION 时拒绝加载。部署密钥/日志/JAR 的 Git 忽略规则及 IDEA 编译也已通过。

本地机器没有 Docker Engine，未启动容器；实际镜像拉取、网络连通性和生产启动仍需在目标 Linux 服务器按本教程验证。
