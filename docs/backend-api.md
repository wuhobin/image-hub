# 一期后端与前端接入

## 运行

1. 在根目录 `.env` 配置 MySQL、Redis、七牛云、SMTP。参考根目录 `.env.example`。
2. 在 `MYSQL_DATABASE` 指向的库执行 [schema.sql](../src/main/resources/db/schema.sql)。脚本仅 `CREATE TABLE IF NOT EXISTS`，不会清空数据；后续字段变更应写迁移 SQL。
3. 启动 Redis，再运行 `ImageHubApplication`。本机后端端口为 9000（来自 `.env`），默认配置为 8080。
4. 在 `frontend/.env.local` 设置 `IMAGE_HUB_API_TARGET=http://127.0.0.1:9000`；然后在 frontend 下执行 `npm install`、`npm run dev`。
5. 打开 `http://127.0.0.1:5173`，注册后到登录页输入用户名和密码。

生产环境启用 HTTPS，前端同源代理 `/api/` 到后端。七牛云空间需公开读，`QINIU_DOMAIN` 为其已绑定访问域名。邮件开关 `MAIL_VERIFICATION_ENABLED=true` 且 SMTP 凭据有效时才能注册；不开启时返回明确错误，不跳过邮箱验证。

本机数据库名为 `image-hub`，已创建 `hub_user`、`hub_image`。不要误连默认名 `image_hub`。当前 Redis 为用户启动的实例，应用使用根目录配置的连接参数和数据库编号。

## 接口约定

前缀 `/api`。响应沿用平台格式：

```json
{"code":200,"message":"success","data":{},"traceId":"...","extra":{}}
```

**HTTP 200 不代表业务成功**，客户端必须检查 `code`。认证头为 `Authorization: Bearer <token>`。业务码包括 400（参数或凭据错误）、401（登录失效）、404（记录不存在）、409（账号冲突）、413（文件过大）、429（请求频繁）、502/503（外部服务失败）。

| 方法 | 路径 | 请求 / 结果 |
| --- | --- | --- |
| POST | /auth/email-code | `{email}`；发送注册验证码 |
| POST | /auth/register | `{username,email,password,code}`；成功后前端跳转登录页 |
| POST | /auth/login | `{username,password}`；返回 `{token,expiresIn,user:{id,username,email}}` |
| GET | /auth/me | 当前用户，不返回密码或哈希 |
| POST | /auth/logout | 注销当前 Token |
| POST | /images | multipart 字段 `file`，每个请求一张；返回图片记录 |
| GET | /images | `page=1&pageSize=24&search=&type=`，type 为 JPG/PNG/WEBP/GIF 或空 |
| GET | /images/stats | 当前用户全部图片的 `{totalCount,totalBytes}` |
| DELETE | /images/{id} | 仅限拥有者，删除文件和记录 |

图片结构：`{id,name,url,preview,type,size,width,height,createdAt}`，ID 为字符串，大小单位字节，时间为 ISO UTC，preview 使用同一公开原始 URL。

列表直接返回 MyBatis-Plus `Page<ImageVO>`，主要字段为 `{records,total,current,size,pages}`，total 为筛选匹配数。请求仍使用 page/pageSize（每页 1–100 张），按上传时间、ID 倒序；分页 SQL 和 count 由父项目拦截器处理。全量数量和大小通过 `/images/stats` 单独获取，不受筛选或分页影响。

## 行为与失败处理

- 用户名 3–32 字符且不含空白，邮箱校验成功后创建账号；用户名和邮箱有数据库唯一约束。密码最少 6 位，BCrypt 最大输入 72 UTF-8 字节，前后端均校验。
- 每次登录生成独立 Token，有效期 3 天，退出一个设备不影响其他设备。浏览器使用 localStorage 保存 Token；刷新时请求 me，401 时清理失效登录态。
- 注册验证码 6 位、有效期 5 分钟、同邮箱冷却 60 秒，验证后消费。服务端还按邮箱/账号/IP 限制认证请求频率，不信任客户端提供的 X-Forwarded-For。代理部署时应统一配置可信代理，否则 IP 限制按代理地址计算。
- 单张最大 10 MiB，前端每批最多 9 张，逐张请求并显示实际传输进度；99% 后等待服务器持久化完成才显示成功。部分失败仅重试失败项；连接中断后应先查询记录，以免重复上传。
- 服务端检查文件扩展名与检测类型、图片尺寸。JPG/PNG/GIF 使用 JDK ImageIO；WebP 使用 [TwelveMonkeys ImageIO](https://github.com/haraldk/TwelveMonkeys/releases)。
- 保存记录失败时尝试删除已上传对象。若补偿清理也失败，日志记录 imageId 供人工清理；MySQL 与七牛云不具备跨系统事务，进程在两次操作之间崩溃仍需人工核对孤立文件。
- 数据库存储完整 FileInfo，仅在后端使用，应用重启后仍可正确删除对象。云端删除失败时保留记录；若文件已删除但数据库操作失败，重试可完成记录删除。
- 外链公开访问，记录查询和删除必须登录且属于当前用户。源文件删除后浏览器/CDN 已缓存的内容可能延迟失效。
- 每日上传额度和总容量配额尚未实现。

## 生产代理示例

前端 `npm run build` 后将 dist 部署到静态服务器，后端单独运行：

```nginx
server {
    listen 80;
    server_name imagehub.example.com;
    root /srv/imagehub/frontend/dist;
    client_max_body_size 11m;

    location /api/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_read_timeout 120s;
    }

    location / {
        try_files $uri $uri/ /index.html;
    }
}
```

示例后端端口为 8080，按部署配置调整；公网部署由网关/Nginx 配置 HTTPS。代理层超限可能返回非 JSON 错误页，前端会转为通用错误提示。

## 验证

```powershell
mvn -s C:/personal-program/Maven/conf/settings.xml "-Dmaven.repo.local=C:/personal-program/Maven/repoBack" verify
npm --prefix frontend test
npm --prefix frontend run build
```

`BusinessFlowTest` 使用实际 HTTP、MyBatis 和 H2 执行一期流程，外部存储和邮件使用替身，避免自动测试向真实邮箱发送邮件或改动云端文件。基础设施测试验证原有配置与鉴权装配。

本次另以临时账号通过浏览器完成真实 MySQL/Redis 登录、七牛云上传、匿名图片加载、复制、刷新恢复、搜索、删除及退出验证，随后清理账号和测试图片。SMTP 实际投递尚未验证；注册成功后的前端跳转使用受控响应检查，后端注册/验证码消费由集成测试覆盖。
