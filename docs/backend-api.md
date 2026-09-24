# 一期后端与前端接入

## 运行

1. 在根目录 `.env` 配置 MySQL、Redis、七牛云、SMTP。参考根目录 `.env.example`。
2. 在 `MYSQL_DATABASE` 指向的库执行 [schema.sql](../deploy/db/schema.sql)。脚本仅 `CREATE TABLE IF NOT EXISTS`，不会升级已有表。已有旧表需在停止旧版后端后执行一次 [时间字段与逻辑删除迁移](../deploy/db/migrations/20260911_audit_and_logic_delete.sql)，再启动新版后端；旧 `created_at` 会保留数据并改名为 `create_time`，历史 `update_time` 初始化为创建时间。随后执行 [秒精度迁移](../deploy/db/migrations/20260911_time_seconds.sql)，历史毫秒直接截去，不四舍五入；最后执行 [DATETIME 迁移](../deploy/db/migrations/20260913_audit_datetime.sql)，将时间列改为 `datetime DEFAULT CURRENT_TIMESTAMP`，更新时间增加 `ON UPDATE CURRENT_TIMESTAMP`。已执行的历史迁移不用重复执行；新建库只需执行最新 schema.sql。
3. 启动 Redis，再运行 `ImageHubApplication`。本机后端端口为 9000（来自 `.env`），默认配置为 8080。
4. 后端执行 `mvn clean verify` 独立构建，也可直接在 IDEA 启动，无需 Node.js；后端端口不再提供前端页面。
5. 前端首次执行 `npm --prefix frontend ci`，在 `frontend/.env.local` 设置 `IMAGE_HUB_API_TARGET=http://127.0.0.1:9000`，然后执行 `npm --prefix frontend run dev`。
6. 打开 `http://127.0.0.1:5173`，注册后到登录页输入用户名和密码。部署时执行 `npm --prefix frontend run build`，将 `frontend/dist/` 单独交给 Nginx 托管。

生产环境启用 HTTPS，前端同源代理 `/api/` 到后端。七牛云空间需公开读，`QINIU_DOMAIN` 为其已绑定访问域名。邮件开关 `MAIL_VERIFICATION_ENABLED=true` 且 SMTP 凭据有效时才能注册；不开启时返回明确错误，不跳过邮箱验证。

本机数据库名为 `image-hub`，已创建 `hub_user`、`hub_image`。不要误连默认名 `image_hub`。当前 Redis 为用户启动的实例，应用使用根目录配置的连接参数和数据库编号。

已完成时间字段、逻辑删除及秒精度迁移的旧库，还需执行一次 DATETIME 迁移。迁移按本项目 `Asia/Shanghai` 时区保留原时间；数据库连接应使用同一时区，避免 DATETIME 的本地时间被按其他时区解释。

## 接口约定

普通用户端前缀 `/api/app`，下表路径均相对于该前缀。管理端使用 `/api/admin`，健康检查使用公共路径 `/api/health`。响应沿用平台格式：

```json
{"code":200,"message":"success","data":{},"traceId":"...","extra":{}}
```

**HTTP 200 不代表业务成功**，客户端必须检查 `code`。认证头为 `Authorization: Bearer <token>`。业务码包括 400（参数或凭据错误）、401（登录失效）、404（记录不存在）、409（账号冲突）、413（文件过大）、429（请求频繁）、502/503（外部服务失败）。

| 方法     | 路径               | 请求 / 结果                                                                                                    |
|--------|------------------|------------------------------------------------------------------------------------------------------------|
| POST   | /auth/email-code | `{email}`；发送注册验证码                                                                                          |
| POST   | /auth/register   | `{username,email,password,code}`；成功后前端跳转登录页                                                                |
| POST   | /auth/login      | `{username,password}`；返回 `{token,expiresIn,user:{id,username,email,avatarUrl}}`                            |
| GET    | /auth/me         | 当前用户，不返回密码或哈希                                                                                              |
| POST   | /auth/logout     | 注销当前 Token                                                                                                 |
| POST   | /auth/avatar     | multipart 字段 `file`，免费上传专用头像；返回更新后的用户                                                                      |
| PUT    | /auth/avatar     | `{imageId}`，选择本人图库中的图片作为头像；返回更新后的用户                                                                        |
| POST   | /images          | multipart 字段 `file`，每个请求一张；返回图片记录                                                                          |
| GET    | /images          | `page=1&pageSize=24&search=&type=&sort=desc`，返回 `ImageListVO {page,totalBytes}`；type 为 JPG/PNG/WEBP/GIF 或空 |
| GET    | /images/quota    | 返回 `{total,remaining,used}`；总额由管理员配置，已用可能大于总额，剩余最低为 0                                                      |
| DELETE | /images/{id}     | 仅限拥有者，删除文件并逻辑删除记录                                                                                          |

图片结构：`{id,name,url,preview,type,size,width,height,createdAt}`，ID 为字符串，大小单位字节，时间为 ISO UTC，preview 使用同一公开原始 URL。`createdAt` 继续映射实体的 `createTime`，保持前端兼容。

列表返回 `ImageListVO`：`data.page` 复用 MyBatis-Plus `Page<ImageVO>`，包含 `{records,total,current,size,pages}`；`data.totalBytes` 为全部匹配图片的总字节数。列表与总大小共用用户、逻辑删除、搜索和图片类型条件，统计不受分页和排序影响。前端只请求 `/images`，图片数量取 `data.page.total`，大小取 `data.totalBytes`。

请求使用 page/pageSize（每页 1–100 张），sort 默认为 desc（最新优先），asc 为最早优先，同秒上传按 ID 稳定排序；分页 SQL 和 count 由父项目拦截器处理。类型不区分大小写，不支持的类型返回业务码 400。此次列表响应增加 VO 层，原 `data.records` 等分页字段移到 `data.page` 下，前后端需一起更新。

```json
{"code":200,"message":"success","data":{"page":{"records":[],"total":0,"current":1,"size":24,"pages":0},"totalBytes":0},"traceId":"...","extra":{}}
```

## 行为与失败处理

- 头像入口位于个人中心。已有数据库在更新后端前执行一次 [头像字段迁移](../deploy/db/migrations/20260924_user_avatar.sql)
  ；新建库直接使用最新 schema.sql。`avatarUrl` 是居中裁切的 256 × 256 缩略图地址，未设置或原图已删除时为 null。
- 新头像支持 JPG、PNG、WebP、GIF，原文件最大 10 MiB；浏览器在保存前自动居中裁切为最多 512 × 512 的 PNG 头像，GIF
  使用首帧，免去高像素照片的手动缩图。服务端仍校验真实内容及 18,874,368 像素上限。免费保存到独立 `avatars/{userId}/`
  目录，记录标记为 `AVATAR`，不出现在“我的图片”或图库统计中，不扣积分。图库选择只更新引用，不复制或删除原图。更换后清理旧的专用头像，清理失败保留记录供下次更换前重试。

- 用户名 3–32 字符且不含空白，邮箱校验成功后创建账号；用户名和邮箱有数据库唯一约束。密码最少 6 位，BCrypt 最大输入 72 UTF-8 字节，前后端均校验。
- 每次登录生成独立 Token，有效期 3 天，退出一个设备不影响其他设备。浏览器使用 localStorage 保存 Token；刷新时请求 me，401 时清理失效登录态。
- 注册验证码 6 位、有效期 5 分钟、同邮箱冷却 60 秒，验证后消费。服务端还按邮箱/账号/IP 限制认证请求频率，不信任客户端提供的 X-Forwarded-For。代理部署时应统一配置可信代理，否则 IP 限制按代理地址计算。
- 单张最大 10 MiB，前端每批最多 9 张，逐张请求并显示实际传输进度；99% 后等待服务器持久化完成才显示成功。部分失败仅重试失败项；连接中断后应先查询记录，以免重复上传。
- 服务端检查文件扩展名与检测类型、图片尺寸。JPG/PNG/GIF 使用 JDK ImageIO；WebP 使用 [TwelveMonkeys ImageIO](https://github.com/haraldk/TwelveMonkeys/releases)。
- 保存记录失败时尝试删除已上传对象。若补偿清理也失败，日志记录 imageId 供人工清理；MySQL 与七牛云不具备跨系统事务，进程在两次操作之间崩溃仍需人工核对孤立文件。
- 数据库存储完整 FileInfo，仅在后端使用，应用重启后仍可正确删除对象。云端删除失败时保持 `deleted=0`；云端删除成功后置 `deleted=1` 并更新 `update_time`，数据库行保留。若文件已删除但数据库操作失败，重试可完成逻辑删除。列表、分页和统计排除已删除记录。
- 外链公开访问，记录查询和删除必须登录且属于当前用户。源文件删除后浏览器/CDN 已缓存的内容可能延迟失效。
- 所有表包含 `create_time`、`update_time`、`deleted`（0 未删除 / 1 已删除）。时间列使用 `datetime`（允许 NULL），由数据库 `DEFAULT CURRENT_TIMESTAMP` 和 `ON UPDATE CURRENT_TIMESTAMP` 生成与维护，实体的时间字段仅用于读取，不参与通用插入和更新；上传入库后按所属用户回读记录，保证返回数据库实际保存的时间，MyBatis-Plus 通用查询自动过滤逻辑删除数据；自定义 SQL 显式处理。已删除账户不能重新登录，用户名和邮箱仍由原唯一约束保留。
- 每日上传积分和总容量配额尚未实现。

## 生产代理

本次路径调整需要前后端一起发布：旧 `/api/auth/**`、`/api/images/**` 已迁移到 `/api/app/auth/**`、`/api/app/images/**`，旧路径不再提供接口。Vite/Nginx 继续原样代理 `/api`，无需新增路径重写；Token 名称及登录态保持不变。

使用仓库中的 [Nginx 配置](../deploy/nginx/imghub.conf) 和 [部署教程](deploy-docker-compose.md)：前端 `dist/` 独立托管，`/api` 原路径转发到后端。模板对应宿主机后端端口 `9000`；直接以默认端口启动 JAR 时改为 `8080`。

只有已声明的页面回退到 `index.html`；缺失资源、未知页面和文档返回 404，API 错误不会被前端首页覆盖。首页要求重新验证缓存，带哈希的 assets 长期缓存。公网部署配置 HTTPS；代理层超限可能返回非 JSON 错误页，前端会转为通用错误提示。

## 验证

```powershell
mvn -s C:/personal-program/Maven/conf/settings.xml "-Dmaven.repo.local=C:/personal-program/Maven/repoBack" verify
npm --prefix frontend test
npm --prefix frontend run build
pwsh -NoProfile -File frontend/scripts/check-avatar.ps1
```

`BusinessFlowTest` 使用实际 HTTP、MyBatis 和 H2 执行一期流程，外部存储和邮件使用替身，避免自动测试向真实邮箱发送邮件或改动云端文件。基础设施测试验证原有配置与鉴权装配。

本次另以临时账号通过浏览器完成真实 MySQL/Redis 登录、七牛云上传、匿名图片加载、复制、刷新恢复、搜索、删除及退出验证，随后清理账号和测试图片。SMTP 实际投递尚未验证；注册成功后的前端跳转使用受控响应检查，后端注册/验证码消费由集成测试覆盖。
