# 每日签到

个人中心主动签到，每个北京时间自然日限领一次。默认每日 10 积分；连续第 7、14、21… 天额外 30 积分。漏签后重新从第 1
天累计，不支持补签。收入永久保留，与基础积分一起用于上传和 AI 创作。

后台「系统设置 → 签到设置」可修改每日奖励及七天额外奖励，均为正整数。修改只影响之后的领取，已领取金额使用签到快照。配置复用现有二级缓存。

## 数据库

新库执行 `deploy/db/schema.sql`。已有库执行一次 `deploy/db/migrations/20260921_daily_check_in.sql` 后重启后端，无需回填历史记录。

`hub_user_check_in` 保存签到日期、连续天数和实际奖励；用户与签到日期唯一。`hub_quota_usage.direction` 区分 `INCOME` 与
`EXPENSE`，金额均为正数。每日奖励和七天奖励各写一条收入明细。

签到在同一事务中锁定当前用户行，保存签到和收入；任一步失败整体回滚。重复请求返回当日领取结果，不重复发放。余额为基础积分 +
累计收入 − 已消费 − 预留积分；收入来自数据库，不依赖 Redis 余额缓存保存。

## 接口

- `GET /api/app/check-in`：今日状态、连续天数、奖励配置、当日可领/已领总额和下一次北京时间零点的毫秒时间戳。
- `POST /api/app/check-in`：领取今日奖励。用户取登录态，不接受指定日期、用户或奖励金额。
- `GET /api/app/quota/records`：沿用分页接口，增加收入方向和两种签到场景。
- `GET/PUT /api/admin/settings/check-in`：管理员读取、修改 `dailyPoints` 与 `bonusPoints`。

回归验证：`mvn -Dtest=BusinessFlowTest,AdminFlowTest test`、`npm --prefix frontend run build`；浏览器验收覆盖签到、积分刷新和移动布局。
