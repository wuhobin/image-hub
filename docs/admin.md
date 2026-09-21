# 管理后台

后台与网站共用域名：`https://imagehub.example.com/admin`。登录页可以公开访问，只有独立的管理员账号能登录。前端仍在 `frontend/` 中独立构建，由 Nginx 托管；管理页面按需加载，不进入后端 JAR。

登录后 `/admin` 自动进入 `/admin/users`，保留搜索和分页参数。后台共用桌面侧栏、顶部当前位置和手机抽屉导航；用户管理与配置管理（`/admin/settings`）是独立业务页面。

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
| `GET /api/admin/settings` | 读取全局设置和更新时间 |
| `PUT /api/admin/settings` | 修改免费总积分 |

用户查询参数：`search`（默认空，最长 254 字符）、`page`（默认 1）、`pageSize`（默认 20，范围 1–100）。关键词不区分大小写，按用户名或邮箱包含匹配；百分号等字符按普通文本匹配。返回 MyBatis-Plus `Page`，记录字段为 `id`、`username`、`email`、`createTime`、`remaining`，按注册时间和 ID 倒序，不包含已删除用户、密码哈希或总积分。

管理员采用 Sa-Token 独立的 `admin` 账号类型，普通用户保持原登录类型。浏览器分别保存 `imagehub.admin.token` 和普通用户 Token；两边登录、退出及过期互不清理对方会话。管理请求每次验证管理员记录仍有效。登录有 IP 和账号频率限制。

管理专属类按层放到 `controller.admin`、`service.admin`、`service.impl.admin`、`mapper.admin`、`model.*.admin`、`config.admin`；前端放到 `pages/admin` 和 `lib/admin`。共用的登录参数、API 客户端及积分缓存继续复用。

`AdminSecurityConfig` 只声明账号类型及路径。平台的 `SecurityAutoConfiguration` 统一处理登录校验、白名单和未匹配 Controller 的默认拒绝；业务 Service 通过 `currentAdmin()` 检查管理员记录是否有效。新增管理业务也应调用该方法，不要在 Controller 再添加一套路径拦截。

当前安全模块使用本地平台源码的 `1.0.0-SNAPSHOT`，其余模块仍由父 POM `1.0.5` 管理。构建前在 `platform-boot-starter` 目录执行 `mvn -pl platform-starter/sa-token-spring-boot-starter -am clean install`，与应用使用相同 Maven 仓库。仅使用旧版 `1.0.5` 安全模块没有未匹配路由保护；平台发布包含该修复的新版本后，再将安全模块恢复为 BOM 管理。

## 剩余积分

用户注册写入数据库后，尝试用 Redis `SET NX` 初始化 `u:0`（累计消耗 0 次），不覆盖已有记录。Redis 初始化失败仍返回注册成功；用户后续正常查询积分或上传时，沿用现有恢复逻辑。

管理列表只批量读取当前页用户的 `image-hub:quota:{用户ID}`，结合配置计算 `max(免费总积分 - 累计消耗, 0)`；不查询图片消耗、不补写 Redis，也不获取上传锁。只接受 `u:累计消耗[:预占图片ID]` 格式，预占计入消耗；不再按固定总额换算旧格式余额。缺失或无法解析的记录返回 `remaining: null`，页面显示 `—`；0 明确显示为 0。Redis 读取失败返回业务码 503。

用户管理只提供搜索与分页，没有用户修改、禁用、恢复、删除或单个用户积分调整入口；全局免费总积分在配置管理中维护。

## 配置管理

页面采用顶部分类 Tab、右侧保存/撤销操作和下方分区表单的布局，当前提供“积分设置”。分类通过 `group` 查询参数定位（例如 `/admin/settings?group=upload`），未知分类回到默认项。Tab 支持左右方向键与 Home/End，手机端横向滚动。后续在 `AdminSettings.tsx` 的 `settingsGroups` 注册分类及对应组件，各分类独立维护表单、校验和保存；只展示已接入的分类。

`hub_settings` 每行保存一个配置项：`config_key` 为唯一配置键，`config_value` 为文本值，`description` 说明用途，另含自增主键、创建/更新时间与逻辑删除字段。配置键由代码预置，管理员只修改已接入的配置值，没有任意创建、删除配置键的接口；唯一键也保留已逻辑删除项的占用。

免费总积分使用 `upload.free-total`，不依赖固定主键。初始值为 100，允许 0–2147483647 的整数；设为 0 暂停普通用户新上传，已有图片仍可查看、删除。修改对全部用户生效，不清空累计消耗、不重发积分；已预占的上传允许完成，后续上传按新配置校验。降低后再提高积分仍保留真实历史消耗。前台积分响应新增 `used`，避免通过总额减余额错误推算实际消耗。

修改请求为 `PUT /api/admin/settings`，JSON 示例：`{"freeUploadQuota":200}`。读取和修改均返回 `{"freeUploadQuota":200,"updateTime":"2026-09-15 10:00:00"}`（仍包在 `Result.data` 中）。读取管理页面直接查数据库；上传和列表计算复用配置缓存。缺失或无效配置返回 503，不静默退回 100。

复用父项目 `TwoLevelCacheTemplate`，命名实例 `imageHubSettings`，各项使用独立 key `image-hub:settings:{config_key}`，缓存文本值，key 不添加版本号。启用 `platform.redis.two-level-cache.enabled`，本地和 Redis 缓存 TTL 均为 3 天。遵循 Nexora 的流程：事务写入前 `evictRequired`，失败返回 503、不修改数据库；事务提交后 `replaceAfterCommitBestEffort` 再次清理并写入新值，广播其他实例失效。只处理被修改项的缓存。提交后缓存故障不回滚数据库，可能暂时读到旧配置，不能保证分钟内生效。缓存读取遇到 Redis 基础设施故障会回源数据库；上传消耗 Redis 故障仍拒绝上传。

新数据库使用完整 `deploy/db/schema.sql`。已有旧积分列 `free_upload_quota` 的数据库执行一次 `deploy/db/migrations/20260915_settings_key_value.sql`，保留原积分和审计信息；该结构迁移不能重复执行。尚未有配置表的旧数据库先执行 `20260915_system_settings.sql`，再执行上述键值迁移。先停止所有旧后端实例，再迁移并启动新版，前端与 Nginx 一同更新；不要混跑旧版，新配置表和 Redis 格式不能被旧后端读取。不需要批量删除用户积分 Key。回滚旧后端前必须停掉新版、恢复旧表结构并清理或转换新格式积分记录，再利用图片记录恢复。

后续新增系统配置只需在建库与升级 SQL 中预置新键、默认值和说明，在业务层接入解析/校验，并按需增加管理请求字段和页面控件，无需增加表字段。内部 `SystemSettingsService.getValue(configKey)` 复用按键缓存，业务方法负责类型和范围；例如整数、布尔、文本或 JSON 都以文本存储。每项保存只更新对应记录并刷新该键，其他配置不受影响。数据库连接、凭据等启动参数仍使用环境配置，不通过此接口管理。
