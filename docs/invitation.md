# 邀请注册与积分奖励

## 运营规则与入口

- 普通注册保持开放。个人中心提供稳定邀请码和 `/register?invite=...` 链接，老用户访问时按需生成邀请码。
- 邀请码可覆盖、手动修改或清空，以注册时提交的值为准。无效邀请码返回业务码 `40021`，校验发生在消费邮箱验证码之前。
- 只在新用户注册时绑定直接邀请关系；不补填、不改绑，也没有间接邀请奖励。
- 默认邀请人、新用户各获 20 积分；后台「配置管理 → 邀请设置」可分别设置正整数金额及总开关。
- 正常注册立即记入双方积分流水，复用现有余额体系。积分不限邀请次数、永久有效。
- 同一 IP、同一邀请人，滚动十分钟内第 3 个及之后的成功注册进入人工审核。统计成功的邀请注册，不统计失败请求；窗口使用数据库时间。
- 后台「邀请审核」展示双方用户名、注册 IP、时间、金额快照、可疑原因以及审核人和备注。通过同时给双方发奖，拒绝则双方不发本次奖励，账号保持正常。
- 审核结论不允许反向修改；重试相同结论不重复发奖。普通用户只看到自己参与的邀请、本人金额和状态，不看到 IP、邮箱、审核备注。
- 关闭活动不影响普通注册。此时带有效邀请码仍保存归属，但标记为「活动暂停」，重开后不补奖；已有待审核记录继续处理。
- 调价只影响之后注册的关系；待审核和已到账记录始终保留注册时的双方金额。

集中注册规则只能提示可疑，不能证明是小号。共享网络可能触发审核，换 IP 或放慢注册速度也可能绕过。审核时应结合记录判断，不直接封号。

## 数据库升级

新库执行 `deploy/db/schema.sql`；已有库在发布后端前执行一次：

```text
deploy/db/migrations/20260924_invitation_rewards.sql
```

升级增加 `hub_user.invite_code` 唯一索引、`hub_user_invitation` 表及三项活动配置。旧用户无需回填，旧账号没有受邀奖励。

邀请关系与双方收入在注册的同一数据库事务内保存。注册对同一邀请人取得行锁，保证多个实例并发时也只有前两次自动发奖；审核对邀请记录取得行锁。受邀用户唯一约束和积分流水业务唯一键共同防重。任意一方记账失败全部回滚，不留单边奖励。

活动配置在一个查询中直接读取数据库，三项更新在同一事务内提交，开关无需等待缓存失效。新收入无需改写 Redis 消耗缓存。

## 可信代理与真实 IP

生产环境使用 Tomcat 原生转发头处理，默认只信任回环地址。Nginx 模板已使用：

```nginx
proxy_set_header X-Real-IP $remote_addr;
proxy_set_header X-Forwarded-For $remote_addr;
```

必须由最后一跳可信代理覆盖转发头，不能直接使用客户端提供的 IP，也不能将全部私网地址或 `.*` 加入信任范围。

当前 Compose 通过宿主机回环端口转发到容器，Tomcat 看到的代理地址可能是 Docker 网桥网关。部署时查询实际网络网关：

```shell
docker inspect --format '{{range .NetworkSettings.Networks}}{{.Gateway}}{{end}}' "$(docker compose ps -q image-hub)"
```

确认该地址是宿主机 Nginx 到应用的实际来源后，在 `deploy/app.env` 设置精确正则。例如网关为 `172.19.0.1`：

```dotenv
TRUSTED_PROXY_REGEX=127[.]0[.]0[.]1|0:0:0:0:0:0:0:1|::1|172[.]19[.]0[.]1
```

修改后重建应用容器。保持应用端口仅绑定回环地址；不要对外开放绕过 Nginx 的后端入口。经过 CDN 或其他代理时，还应正确配置 Nginx
的可信来源和真实 IP 模块。

发布验收时使用两个不同外网来源测试邀请注册，确认后台记录不同的访客 IP；伪造客户端 `X-Forwarded-For`
不应改变记录。错误的代理配置会把多个访客合并统计，导致集中注册误判。

## 接口

| 方法与路径                                                          | 用途                                        |
|----------------------------------------------------------------|-------------------------------------------|
| GET `/api/app/auth/invitation?code=...`                        | 游客预览活动和邀请人，可不传邀请码                         |
| POST `/api/app/auth/register`                                  | 原参数增加可选 `inviteCode`；响应保持兼容               |
| GET `/api/app/invitations`                                     | 当前用户邀请码及活动规则                              |
| GET `/api/app/invitations/records?page=1&pageSize=10`          | 本人参与记录，平台原生分页                             |
| GET / PUT `/api/admin/settings/invitation`                     | `enabled`、`inviterPoints`、`inviteePoints` |
| GET `/api/admin/invitations?status=PENDING&page=1&pageSize=20` | 管理列表；空状态查询全部                              |
| POST `/api/admin/invitations/{id}/review`                      | `approved` 必填布尔值，`note` 为选填备注             |

邀请状态为 `PAID`、`PENDING`、`REJECTED`、`DISABLED`。新积分场景为 `INVITATION_REWARD`（邀请人）、`INVITEE_REWARD`
（受邀人）。用户编号和审核人只取服务端可信上下文。

## 验证

- `BusinessFlowTest` 的三个 `invitation*` 测试覆盖实际 HTTP 注册与配置、身份隔离、数据库事务回滚、重复注册/审批、金额快照、暂停及恢复、十分钟窗口和并发门槛。
- `EnvFileConfigurationTest.productionProxyTrustIsExplicit` 验证生产代理信任配置及精确覆盖。
- IDEA 编译、`npm --prefix frontend test`、`npm --prefix frontend run build`。
- 浏览器页面验收使用模拟 API，不写入运行中的数据库；覆盖有效/无效邀请码、个人中心桌面与移动布局、配置入口和审核弹窗。实际服务端逻辑由上述
  HTTP 与数据库测试验证。
