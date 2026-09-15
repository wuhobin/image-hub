# 管理后台

后台与网站共用域名：`https://imagehub.example.com/admin`。登录页可以公开访问，只有独立的管理员账号能登录。前端仍在 `frontend/` 中独立构建，由 Nginx 托管；管理页面按需加载，不进入后端 JAR。

登录后 `/admin` 自动进入 `/admin/users`，保留搜索和分页参数。后台共用桌面侧栏、顶部当前位置和手机抽屉导航；用户管理是独立业务页面。当前只显示已上线的导航项。

## 增加管理页面

在 `frontend/src/pages/admin/` 新增具体页面，在 `AdminApp.tsx` 的 `/admin` 父路由下添加子路由，并在 `AdminLayout.tsx` 的 `navigation` 添加对应导航项。新页面通过 `<Outlet />` 显示在公共框架内，只负责业务内容，不重复实现登录校验、侧栏或顶部导航。部署时将新路径加入 `deploy/nginx/imghub.conf` 的管理页面规则，确保直接访问和刷新可用；接口仍统一 `/api/admin/**`。

安装 `agent-browser` 并启动前端后，可执行 `powershell -File frontend/scripts/check-admin-layout.ps1 -BaseUrl http://127.0.0.1:5173`，检查路由、导航选中态、搜索分页恢复、手机菜单键盘操作及退出。脚本使用独立浏览器和模拟接口，不读写真实管理员数据。

## 部署与初始账号

新数据库执行 `deploy/db/schema.sql`；已有数据库在更新后端前执行 `deploy/db/migrations/20260914_admin_account.sql`，增加 `hub_admin` 表。然后发布后端 JAR、前端 dist，并更新 `deploy/nginx/imghub.conf` 中的管理页面路由。

系统没有默认管理员、管理员注册或普通用户提权接口。首次管理员由部署人员手动入库，密码必须是 BCrypt 哈希。

可在可信的本机终端用 Apache `htpasswd` 交互式生成哈希（Ubuntu/Debian 由 `apache2-utils` 提供）：

```bash
htpasswd -nBC 12 operator
```

按提示输入两次密码；不要使用将明文密码放进命令参数的 `-b`。输出为 `operator:$2y$12$...`，仅复制冒号后的完整哈希。本项目的 Hutool BCrypt 支持该格式，也可以使用项目已有的 `BCrypt.hashpw(password, BCrypt.gensalt(12))` 生成。不要把真实密码或哈希提交到仓库。

在数据库客户端执行，替换占位符后再运行：

```sql
INSERT INTO hub_admin (username, password_hash)
VALUES ('operator', '<粘贴完整 BCrypt 哈希>');
```

用户名为 3–32 个字符，密码至少 6 个字符、最多 72 个 UTF-8 字节；建议初始密码使用至少 12 位随机字符。时间和逻辑删除字段使用数据库默认值。管理员用户名与普通用户用户名可以相同，但凭据、身份及会话互不通用。

发布后访问 `/admin`，使用新建账号登录。开发环境访问 Vite 的 `/admin`（通常为 `http://127.0.0.1:5173/admin`），不是后端端口。

## 接口与会话

所有管理接口使用 `/api/admin/**`，响应复用平台 `Result<T>`，请求使用 `Authorization: Bearer <管理员 Token>`。

普通用户端统一使用 `/api/app/**`，例如 `/api/app/auth/login` 和 `/api/app/images`；对应 `LOGIN` 账号类型。管理端 `/api/admin/**` 对应 `ADMIN`，公共健康检查保持 `/api/health`。页面地址仍为 `/` 和 `/admin`。

| 方法与路径 | 用途 |
| --- | --- |
| `POST /api/admin/auth/login` | `username`、`password` 登录；唯一匿名管理接口 |
| `GET /api/admin/auth/me` | 当前管理员 |
| `POST /api/admin/auth/logout` | 注销当前管理员 Token |
| `GET /api/admin/users` | 搜索及分页查询用户 |

用户查询参数：`search`（默认空，最长 254 字符）、`page`（默认 1）、`pageSize`（默认 20，范围 1–100）。关键词不区分大小写，按用户名或邮箱包含匹配；百分号等字符按普通文本匹配。返回 MyBatis-Plus `Page`，记录字段为 `id`、`username`、`email`、`createTime`、`remaining`，按注册时间和 ID 倒序，不包含已删除用户、密码哈希或总额度。

管理员采用 Sa-Token 独立的 `admin` 账号类型，普通用户保持原登录类型。浏览器分别保存 `imagehub.admin.token` 和普通用户 Token；两边登录、退出及过期互不清理对方会话。管理请求每次验证管理员记录仍有效。登录有 IP 和账号频率限制。

管理专属类按层放到 `controller.admin`、`service.admin`、`service.impl.admin`、`mapper.admin`、`model.*.admin`、`config.admin`；前端放到 `pages/admin` 和 `lib/admin`。共用的登录参数、API 客户端及额度缓存继续复用。

`AdminSecurityConfig` 只声明账号类型及路径。平台的 `SecurityAutoConfiguration` 统一处理登录校验、白名单和未匹配 Controller 的默认拒绝；业务 Service 通过 `currentAdmin()` 检查管理员记录是否有效。新增管理业务也应调用该方法，不要在 Controller 再添加一套路径拦截。

当前安全模块使用本地平台源码的 `1.0.0-SNAPSHOT`，其余模块仍由父 POM `1.0.5` 管理。构建前在 `platform-boot-starter` 目录执行 `mvn -pl platform-starter/sa-token-spring-boot-starter -am clean install`，与应用使用相同 Maven 仓库。仅使用旧版 `1.0.5` 安全模块没有未匹配路由保护；平台发布包含该修复的新版本后，再将安全模块恢复为 BOM 管理。

## 剩余额度

用户注册写入数据库后，尝试用 Redis `SET NX` 初始化 100 次额度，不覆盖已有余额。Redis 初始化失败仍返回注册成功；用户后续正常查询额度或上传时，沿用现有恢复逻辑。

管理列表只批量读取当前页用户的 `image-hub:quota:v1:{用户ID}`，不查询图片消耗、不补写 Redis，也不获取上传锁。上传中的 `剩余次数:图片ID` 取冒号前的次数。缺失或无法解析的记录返回 `remaining: null`，页面显示 `—`；0 明确显示为 0。Redis 读取失败返回业务码 503，页面提示重试，不把故障伪装成所有用户都没有额度。

本期只提供用户搜索与分页，没有用户修改、禁用、恢复、删除或额度调整入口。
