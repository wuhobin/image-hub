# Image Hub：后端 Docker Compose + 前端 Nginx 独立部署

适用场景：本地 Windows 分别构建后端 JAR 和前端 dist，Linux 服务器运行，复用已有 MySQL 和 Redis。Compose 只管理后端应用，宿主机 Nginx 托管前端并将同域名 `/api` 转发到后端。图片仍由浏览器直接访问七牛云。

管理后台使用同一域名的 `/admin`。已有部署升级时，先执行管理员表迁移，再发布前后端并更新 Nginx 路由；首次管理员需手动初始化，步骤见 [管理后台说明](admin.md)。

采用 Java 21 官方运行镜像挂载 JAR，服务器无需 Maven、JDK、Node.js 或项目源码，也无需自己构建镜像。后端切换 JAR 版本后重建容器；前端切换静态目录即可，无需重启后端。挂载 JAR 是 [Eclipse Temurin 官方镜像支持的用法](https://hub.docker.com/_/eclipse-temurin)。

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

已安装 Docker 的服务器跳过安装步骤，不要重装已有数据库环境。Ubuntu/Debian 如尚未安装宿主机 Nginx，执行：

```bash
sudo apt-get install -y nginx
sudo systemctl enable --now nginx
```

以下示例使用 SSH 账号 `deploy`、服务器地址 `SERVER_IP`，请替换为自己的值。登录服务器，创建目录：

```bash
sudo mkdir -p /opt/image-hub
sudo chown "$(id -u):$(id -g)" /opt/image-hub
cd /opt/image-hub
mkdir -p releases/20260910-001 frontend/releases/20260910-001 nginx logs
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
├── nginx/imghub.conf     # 安装前编辑的 Nginx 配置
├── frontend/
│   ├── current -> releases/20260910-001/dist
│   └── releases/20260910-001/dist/
└── releases/
    ├── 20260910-001/app.jar
    └── 20260910-002/app.jar
```

## 2. 本地打包并上传

后端需要 Java 21、Maven；前端需要 Node.js 22.12+。两者独立构建，Maven 不再运行 npm。首次构建或更新前端锁文件后执行 `npm --prefix frontend ci`（Windows 上先停止 Vite）。

在本地 PowerShell 中执行：

```powershell
Set-Location C:/IdeaProjects/personal/image-hub
& 'C:/personal-program/Maven/bin/mvn.cmd' -s 'C:/personal-program/Maven/conf/settings.xml' '-Dmaven.repo.local=C:/personal-program/Maven/repoBack' clean verify
if ($LASTEXITCODE -ne 0) { throw '打包失败，请先修复构建错误' }

npm.cmd --prefix frontend ci
if ($LASTEXITCODE -ne 0) { throw '前端依赖安装失败' }
npm.cmd --prefix frontend test
if ($LASTEXITCODE -ne 0) { throw '前端测试失败' }
npm.cmd --prefix frontend run build
if ($LASTEXITCODE -ne 0) { throw '前端构建失败' }

Get-FileHash ./target/image-hub-0.0.1-SNAPSHOT.jar -Algorithm SHA256

# 上传部署文件及无密钥模板，不要上传本地真实 .env。
scp ./deploy/compose.yaml deploy@SERVER_IP:/opt/image-hub/compose.yaml
scp ./deploy/.env.example deploy@SERVER_IP:/opt/image-hub/.env.example
scp ./deploy/app.env.example deploy@SERVER_IP:/opt/image-hub/app.env.example
scp ./deploy/nginx/imghub.conf deploy@SERVER_IP:/opt/image-hub/nginx/imghub.conf
scp ./target/image-hub-0.0.1-SNAPSHOT.jar deploy@SERVER_IP:/opt/image-hub/releases/20260910-001/app.jar
scp -r ./frontend/dist deploy@SERVER_IP:/opt/image-hub/frontend/releases/20260910-001/
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
PUBLISH_HOST=127.0.0.1
APP_MEMORY=1g
```

`APP_VERSION` 只控制后端 JAR，必须与后端上传目录一致。宿主机的 `9000` 映射到容器固定端口 `8080`；修改 `HTTP_PORT` 时同时更新 Nginx 的 `proxy_pass` 端口。容器内始终使用 prod，命令行参数会覆盖误填的 dev profile。已有部署需将原来的 `PUBLISH_HOST=0.0.0.0` 改为 `127.0.0.1` 并重建容器。

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
| QINIU_BASE_PATH | 文件前缀，默认 `ImgHub/` |
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

后端端口不再返回前端页面；先确认健康接口正常，再启用下面的 Nginx 站点。

期望 HTTP 请求成功，JSON 中 `code` 为 200、`data.status` 为 `UP`。该接口只代表 Web 应用可响应，不验证 MySQL、Redis、七牛云或 SMTP；上线前仍需按业务流程验证数据库操作、缓存、文件上传和邮件投递。本 Compose 没有内置健康检查，`ps` 显示 running 不能单独证明应用已就绪。

### 启用前端站点

将域名指向服务器，在 `nginx/imghub.conf` 中替换 `imagehub.example.com`。Nginx 在宿主机运行，站点根目录只指向前端 dist，不能指向包含 app.env 的项目目录。模板包含页面刷新、静态资源缓存、11 MiB 请求体上限和 `/api` 代理；`proxy_pass` 不附加 `/`，保留后端的 `/api` 前缀。[Nginx 官方说明](https://nginx.org/en/docs/http/ngx_http_proxy_module.html#proxy_pass)

```bash
cd /opt/image-hub
test -s frontend/releases/20260910-001/dist/index.html || exit 1
chmod -R a+rX frontend/releases/20260910-001
ln -s releases/20260910-001/dist frontend/current
nano nginx/imghub.conf
sudo install -m 644 nginx/imghub.conf /etc/nginx/conf.d/imghub.conf
sudo nginx -t && sudo systemctl reload nginx

curl --fail --show-error -H 'Host: imagehub.example.com' http://127.0.0.1/api/health
curl -I -H 'Host: imagehub.example.com' http://127.0.0.1/history
```

`current` 链接只在首次创建；已有前端按第 6 节切换。若服务器已有同域名站点，将模板中的 root 和 location 合并到现有 server，避免重复 server_name。公网使用时在该站点配置有效 TLS 证书、443 监听和 HTTP 到 HTTPS 跳转，然后执行 `nginx -t` 并重载。云安全组及主机防火墙开放 80/443，后端 9000 保持仅本机访问。

浏览器打开 `https://imagehub.example.com/`，确认 `/login`、`/register`、`/history`、`/profile` 可直接访问和刷新，JS/CSS 加载正常。`/api/app/auth/me` 未登录仍返回 JSON `code=401`；未知 API 由后端返回 JSON，缺失静态资源、未知页面及生产文档路径返回 404，不回退为首页。默认日志不包含请求认证头。

模板按 Nginx 直接面对客户端配置转发头；若前面另有 CDN/网关，需要按真实代理链配置可信来源。后端目前不信任客户端自报的 IP 头，详见 [接口约定](backend-api.md)。

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

## 6. 独立发布与回滚

### 只更新后端

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

### 只更新前端

本地执行 `npm --prefix frontend test`、`npm --prefix frontend run build`。服务器创建 `frontend/releases/20260910-002`，然后上传：

```powershell
scp -r ./frontend/dist deploy@SERVER_IP:/opt/image-hub/frontend/releases/20260910-002/
```

服务器切换前先确认产物完整，保留旧哈希资源以支持已经打开页面的懒加载，再原子替换链接：

```bash
cd /opt/image-hub
test -s frontend/releases/20260910-002/dist/index.html || exit 1
cp -an frontend/current/assets/. frontend/releases/20260910-002/dist/assets/
chmod -R a+rX frontend/releases/20260910-002
ln -s releases/20260910-002/dist frontend/current.next
mv -Tf frontend/current.next frontend/current
```

不修改 `APP_VERSION`，不重启 Java，也无需重载 Nginx。首页使用 `no-cache`，浏览器重新验证；带哈希的资源长期缓存。旧资源按部署保留策略清理，至少覆盖仍需支持的旧页面和回滚窗口。

前端回滚时将链接指回旧版本；同样先保留新版本页面可能仍请求的哈希资源：

```bash
cd /opt/image-hub
cp -an frontend/current/assets/. frontend/releases/20260910-001/dist/assets/
ln -s releases/20260910-001/dist frontend/current.next
mv -Tf frontend/current.next frontend/current
```

涉及 API 不兼容变更时，仍需协调前后端发布顺序和版本，独立产物不意味着接口可以随意变更。

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
| 页面 404 或返回后端 JSON | 访问 Nginx 域名而非 9000；检查 current 链接、dist/index.html 和站点是否加载 |
| 页面正常但 API 502 | 检查后端健康接口及 Nginx proxy_pass 端口；本模板要求 Nginx 运行在宿主机 |
| JS/CSS 请求 404 | 检查是否完整上传 dist，以及发布时是否保留旧哈希资源 |

## 验证边界

仓库测试使用替身验证后端鉴权和路由边界，不连接生产存储或发送邮件。部署前分别执行后端 `mvn clean verify`、前端 `npm test` 和 `npm run build`；在目标服务器执行 `docker compose config --quiet`、`nginx -t` 及上述 HTTP 检查。实际证书、容器网络、数据库和云服务连通性仍需在目标环境验证。
