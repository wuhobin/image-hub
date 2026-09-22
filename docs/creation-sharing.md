# AI 作品公开分享

## 使用入口

- 顶部「作品广场」为 `/explore`，游客可分页浏览，按发布时间倒序排列。
- 创作完成后打开「作品详情」，或进入「创作记录」打开作品，即可设置公开分享。
- 分享默认公开提示词，作者可以取消勾选并保存。发布不会额外消耗积分。
- 独立分享页为 `/share/{shareId}`，展示作者用户名、AI 作品、模型和参数，以及作者允许公开的提示词。
- 「一键做同款」仅带入公开提示词和可用参数，不复制原参考图。游客先登录，登录与注册切换保留所选内容；用户确认提交才创建任务并计费。原模型或参数不可用时，提示已切换至当前可用选项。
- 后台「作品分享」为 `/admin/creations`，可查看公开和已下架作品并执行下架。

## 可见性与撤销

历史作品默认私有，仅作者能主动公开本人生成成功且原图未删除的 AI 作品。普通上传图片不能在这里发布。

关闭提示词后，公开 SQL 查询和响应均不包含提示词内容；不返回账户 ID、邮箱、任务请求编号、参考图、供应商地址、密钥或存储定位信息。图片仍使用既有云存储
URL，其路径结构不在本次改造范围内。

撤销后移出广场，原分享页失效；重新发布生成新链接，旧链接不恢复。管理员下架的作品不能由作者重新公开。原图片删除成功后，公开列表和详情通过关联条件立即排除该作品；云端删除失败保留原记录与分享，供作者重试。

公开接口不缓存响应，页面切回前台重新读取，做同款前再次确认公开状态。撤销只控制网站展示和接口读取，不撤回已下载内容、已打开页面的副本或已经获得的原始图片链接。

## 接口

| 方法与路径                                              | 权限和用途                                |
|----------------------------------------------------|--------------------------------------|
| GET `/api/app/public/creations?page=1&pageSize=12` | 游客列表，分页大小 1–50                       |
| GET `/api/app/public/creations/{shareId}`          | 游客详情，不可见作品统一返回业务码 404                |
| PUT `/api/app/generations/{id}/share`              | 作者发布或更新，JSON `{"promptPublic":true}` |
| DELETE `/api/app/generations/{id}/share`           | 作者幂等撤销                               |
| GET `/api/admin/creations?page=1&pageSize=20`      | 有效管理员列表                              |
| POST `/api/admin/creations/{shareId}/block`        | 有效管理员下架，记录操作人和分享编号                   |

所有返回沿用 `Result<T>` 和平台分页。匿名白名单仅覆盖公开读取路径；用户发布、撤销和管理接口继续分别校验用户及管理员会话。

## 部署

已有数据库先备份并停止旧后端，执行一次 `deploy/db/migrations/20260922_creation_sharing.sql`，再部署新版后端。新库使用最新
`deploy/db/schema.sql`。迁移只增加分享字段和索引，不自动公开历史作品；应用不会自动执行迁移。

同步发布前端及 `deploy/nginx/imghub.conf`，包含作品广场、分享详情及后台作品分享路由，使直接访问和刷新可用。

## 验证

- `BusinessFlowTest.publicSharingRespectsVisibilityOwnershipDeletionAndAdminTakedown` 使用真实 HTTP 与 H2
  SQL，覆盖匿名访问、隐私、越权、撤销、删除失败重试、管理下架和积分不变；不调用真实模型或云存储。
- `powershell -ExecutionPolicy Bypass -File frontend/scripts/check-creation-sharing.ps1` 在独立 agent-browser
  会话中使用虚构接口，验证发布、撤销、匿名浏览、登录保留做同款内容、移动布局和管理下架。默认访问本机 Vite
  `http://127.0.0.1:5173`，可传入 `-BaseUrl`。
