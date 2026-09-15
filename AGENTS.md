# 项目规范

## 工具使用

修改、阅读、编译、运行项目时，优先使用 idea-mcp 服务；服务不可用时再退回传统方式。

## 后端包结构

根包为 `com.aurora.imagehub`。Java 包名统一小写，类名使用 PascalCase，VO、BO 后缀保持大写。

- `model.entity`：数据库实体，如 `UserAccount`、`ImageFile`，与表字段对应，不直接作为接口响应。
- 每张业务表必须包含 `create_time`、`update_time`、`deleted`；时间由数据库 `DEFAULT CURRENT_TIMESTAMP` 和 `ON UPDATE CURRENT_TIMESTAMP` 生成与维护，数据库时间列使用不带精度参数的 `datetime`，不加 `NOT NULL`，按 MySQL 默认秒精度保存；实体自行声明 `createTime`、`updateTime`，使用 `@TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)` 只读映射，不继承带时间自动填充的父项目 `BaseEntity`，不添加应用时间处理器；写入后需要返回时间时重新查询数据库；实体声明 `@TableLogic Integer deleted = 0`。MyBatis-Plus 全局配置 0 未删除、1 已删除；自定义 SQL 显式过滤 `deleted = 0`，逻辑删除同时更新 `update_time`。唯一性占用检查仍包含已删除数据。
- `model.vo`：对外返回的数据，类名以 `VO` 结尾，如 `UserVO`、`ImageVO`；不得包含密码哈希、内部存储信息等敏感字段。
- `model.bo`：业务内部计算、组合或传递的数据，类名以 `BO` 结尾，如 `ImageDimensionsBO`；按实际需要定义，不为每个实体机械复制一套字段。
- `model.param`：接口请求参数，类名以 `Param` 结尾，声明 Bean Validation 约束；不在 Controller 或 Service 中嵌套定义请求模型。
- VO、BO、Param 统一使用普通 Java 类，不使用 `record`。字段使用 private，采用 Lombok `@Getter`、`@Setter` 和 `@NoArgsConstructor`；存在实际构造调用时再添加 `@AllArgsConstructor`。校验和文档注解放在字段上；含密码、验证码或 Token 的类不生成暴露这些字段的 `toString`。
- 不使用 `model.response` 包，接口返回对象统一归入 `model.vo`；外层响应继续复用平台 `Result<T>`。
- 分页直接复用父项目的 MyBatis-Plus `Page<T>`、`PageUtils` 和分页拦截器，不定义业务专属分页 VO，不手写 LIMIT/OFFSET 或分页 count；额外业务统计使用独立接口。
- `service`：只放实体相关的 Service 接口，按实体命名，如 `UserAccountService`、`ImageFileService`。
- `service.impl`：对应实现类，以 `ServiceImpl` 结尾，统一继承 MyBatis-Plus `ServiceImpl<Mapper, Entity>` 并实现业务接口；业务接口继承 `IService<Entity>`，Mapper 继承 `BaseMapper<Entity>`。实体显式声明表名及主键策略，确保通用方法映射正确。Controller 和其他调用方依赖接口，不直接依赖实现类。
- 通用 CRUD 供服务端内部复用；对外业务仍通过含权限校验、验证码校验和云端文件处理的业务方法，图片删除不得用通用 `removeById` 替代。
- `controller`：HTTP 路由、参数校验、登录用户提取和统一响应封装；账户、文件等业务逻辑交给 Service。
- `mapper`：数据库读写，使用 `model.entity`；用户所属资源的读写和统计必须带用户条件。
- `ratelimit`：频率限制组件，如 `AttemptLimiter`，不放在 Service 包。
- `exception`：异常处理与业务异常扩展，如 `UploadExceptionHandler`，不放在 Controller 包。
- `config`：框架及应用配置。

## 注释与行为约束

后续新增或修改代码时，相邻字段声明之间、相邻方法之间，以及字段与方法之间，均保留一个空行；字段或方法的注释、注解与对应声明保持紧邻，不在其间插入空行。

所有依赖注入字段使用所注入类型的完整名称，并采用 lowerCamelCase，禁止缩写或泛称。例如 `ImageMapper imageMapper`、`UserAccountService userAccountService`、`OssTemplate ossTemplate`、`FileUploadValidator fileUploadValidator`、`ObjectMapper objectMapper`。`ObjectProvider<T>` 使用目标类型名称加 `Provider`，如 `mailVerificationServiceProvider`。构造器参数及测试中的注入、Mock/Spy 字段遵循同一规则。

重要类补充中文 Javadoc，说明职责和边界；Service 接口说明关键业务约定。关键方法必须补充中文方法注释，说明职责、重要约束及必要的异常或副作用；Java 方法使用 Javadoc。权限校验、密码处理、会话隔离、额度计算、缓存更新、上传补偿和删除重试等重要逻辑应解释原因，避免仅复述代码。

密码、验证码及 Token 不得写入日志；接口不直接暴露实体。注册成功不自动登录，退出仅注销当前 Token。图片操作的用户 ID 必须来自可信登录态；删除图片时先删除存储文件，再将数据库记录标记为已删除，云端删除失败保留记录以便重试。

结构重构应保持 HTTP 路径、请求字段、响应 JSON 和业务码兼容。修改后通过 IDEA 编译，并运行相关测试；后端整体重构运行 `mvn clean verify`。不为尚未使用的功能添加占位类或额外依赖。
