# Carbon Connector — 三方服务连接器平台 设计文档

> ⚠️ **归档文件（快照，不再更新）**：本文已于 v1.8 拆分为 `docs/` 下的 8 个文件，
> 正本入口是 [00-设计总览.md](../00-设计总览.md)。此处仅保留单文件版本供对照与检索。

> 版本：v1.8（补 §4.7 DSL 的 Java 类型映射：`sealed interface NodeDef` + `record`；设计定稿，未开始编码）
> 修订：v1.0 初版 14 章 → v1.1 重写 §9 工程结构、统一响应体/主键/字段约定、补 P0 骨架里程碑 → v1.2 新增 `framework` 层（dag/script/expression/http）、试调入口从 connector 移到 engine 以消除循环依赖 → v1.3 DAG 引擎改名 `carbon-framework-dag`，与业务模块 `carbon-flow` 彻底区分 → v1.4 `carbon-stats` 并入 `carbon-invokelog`（业务模块 8→7），并修正 `/api/logs/{traceId}` 的循环依赖 → v1.5 技术选型逐一落定：补 §4.2 渲染规则与边界（8 条）、引入 Aviator 作为表达式核（带安全 spike 门禁）、记录 HTTP 客户端与编排引擎的选型理由与退路 → v1.6 编排引擎**改为封装 LiteFlow core**（读官方文档后修正 v1.5 的四条错判，错判记录保留在 §9.9）：DSL 收敛为结构化树形图、`onError=GOTO` 换成 `LOOP` 节点、发布走 chain 注册 + Redis 广播刷新、步骤信息由 `CmpStep` 映射 → v1.7 **JDK 17 改为 JDK 21（LTS）**：DSL 模型用 `sealed interface` + `record` 让编译期穷尽节点类型；相应锁定 `groovy.version ≥ 4.0.21`、`lombok ≥ 1.18.30`；虚拟线程暂不开启（HttpClient5 连接池已是瓶颈，且 JDK 21 上 `synchronized` 会 pin 载体线程 JEP 491 到 24 才修） → v1.8 补 §4.7「DSL 的 Java 类型映射」：`sealed interface NodeDef` + 8 个 `record` 承载 8 种节点，`ElCompiler` 的 `switch` 不带 `default`，新增节点类型时编译期就拦住
> 定位：**连接器平台**——用页面配置的方式接入外部三方服务，把「URL + 鉴权 + 参数 + 报文」沉淀为可复用的连接器与接口，
> 业务系统只按名称调用平台网关，不接触三方地址与密钥。同时提供 DAG 编排与定时调度能力。

---

## 1. 决策基线

| 维度 | 选定 | 理由 |
|---|---|---|
| 产品定位 | 连接器平台（非 Postman 精简版） | 核心价值是"把三方服务封装成企业内可复用能力"，而非个人调接口工具 |
| **工程结构** | **Maven 多模块，对齐 RuoYi-Vue-Plus 5.X**：`admin` / `common`（基础设施）/ **`framework`（自研二方包）** / `modules`（业务域）/ `extend` | 团队零学习成本；技术能力与业务代码按“能否脱离本项目复用”硬隔开，详见 §9 |
| 后端 | Spring Boot 3.5.x / **Java 21（LTS）** / MyBatis-Plus / MySQL 8 / Redis 7（Redisson） | 21 与 17 功能上零差异，选 21 是为了 `sealed` + `record` + switch 模式匹配——DSL 的 7 种节点可写成密封接口，编译器强制穷尽，把“文档约定”变成“编译期保证”；RuoYi 5.6.2 的 `common-*` 无 17-only 依赖，可直达。虚拟线程暂不开启，理由见 §9.9 |
| 前端 | Vue3.5 + TS + Vite + Element Plus + Pinia + Monaco Editor + LogicFlow + ECharts | 目录规范对齐 `plus-ui`；Element Plus 与 LogicFlow 均为 Vue3 原生 |
| 平台登录 | 不做（内部工具） | 由 Nginx/内网访问控制兜底，见 §7.4 风险 |
| 调用入口 | 平台试调 + 对外网关 + DAG 编排 + Quartz 调度 | 三类使用场景：配置期调试、业务侧调用、自动化流转 |
| 三方鉴权 | None / ApiKey / Basic / Bearer / OAuth2 ClientCredentials / 自定义登录换 Token | 覆盖国内主流三方服务形态 |
| 加工深度 | `{{var}}` 模板 + JSONPath 抽取 + Groovy 前后置脚本 | 透传不够用、脚本更灵活，二者结合 |
| 渲染与表达式 | **壳自研**（`TemplateRenderer`，输出 JSON 树/URL/header，兼顾表单元数据） + **核可插拔**（默认 Aviator，兜底自研） | 无开源件同时满足「JSON 结构化 + 表达式 + 沙箱 + 可枚举变量」，详见 §4.2 |
| 脚本引擎 | Groovy 4.0.x（≥ 4.0.21）+ 沙箱加固 | 团队熟悉度最高，与 Java 互通最好；Groovy 3.x 在 JDK 21 上有已知坑，因此版本下限硬约束 |
| 编排模型 | DAG 可视化画布（LogicFlow），**限结构化树形图**（禁回边、禁交叉边） | 分支必须汇聚、重复执行用 `LOOP` 框：画布可读、可校验、可编译成 EL |
| 编排引擎 | **LiteFlow core 封装**：`carbon-framework-dag` = DSL→EL 编译 + chain 注册 + 步骤映射 | 买执行器内核（重试/超时/并行/步骤信息/事件回调开箱），省约 1000–1300 行；代价是表达力收敛为树，详见 §9.9 |
| HTTP 客户端 | Apache HttpClient 5（显式关自动重试与重定向） | 连接池可控、池满快速失败；不带 Kotlin 运行时，详见 §9.9 |
| 调度 | Quartz JDBC 集群 + `JobHandler` 适配层 | 多实例安全、动态 cron；不引 SnailJob 以少一个部署单元，日后换调度中心只改适配层 |
| 网关鉴权 | AppKey + HMAC-SHA256 签名 | 平台不做用户登录，但对外必须可鉴权、可审计、可限流 |
| 主键 / 响应 / 转换 | 雪花 ID、`R<T>`、Bo/Vo + MapStruct-Plus、`BaseMapperPlus` | 照搬 RuoYi 约定，见 §9.5 |
| 交付 | P0 骨架 → P1 连接器+网关 → P2 DAG 编排 → P3 调度+观测 | 每阶段可独立验收上线 |

---

## 2. 总体架构

```
┌──────────────┐
│  carbon-ui   │──/api/**──┐
│ (Vue3 + TS)  │           │
└──────────────┘           │
                           ├──> carbon-admin.jar（唯一可执行单元）
┌──────────────┐           │      ├─ carbon-common-*      12 个基础设施模块（对齐 RuoYi）
│  业务系统    │──/gw/**────┘      ├─ carbon-framework-*   4 个自研二方包（业务无关）
└──────────────┘                  │     dag / script / expression / http
                                  └─ carbon-modules/      7 个业务域模块
                                        ├─ carbon-system      平台设置/字典/操作日志
                                        ├─ carbon-connector   环境/连接器/接口/鉴权/应用
                                        ├─ carbon-engine      调用流水线（鉴权解析·HTTP·抽取）
                                        ├─ carbon-gateway     签名·防重放·限流·OpenAPI
                                        ├─ carbon-invokelog   调用日志（异步落库）+ 看板聚合
                                        ├─ carbon-flow        编排定义/版本/发布/运行记录 + 节点桥接
                                        └─ carbon-job         Quartz + JobHandler
        MySQL 8                          Redis 7
   （元数据 + 调用日志）        （Token 缓存 / 防重放 / 限流 / 并发锁 / SSE 会话）
```

分层原则：`common-*` 是**基础设施适配**（Spring/ORM/Redis/Web 集成 + 企业约定），`framework-*` 是**业务无关的自研二方包**（解析、校验、执行、传输），`modules/*` 才是**业务语义**。详见 §9.1。

### 2.1 关键设计约束

**单一执行出口。** 试调（`/api/operations/{id}/debug`）、网关（`/gw/**`）、编排节点（`CONNECTOR_CALL`）三种调用一律经过
`engine.InvokeService.invoke(InvokeContext)`。鉴权、模板渲染、重试、超时、日志、脱敏只有一份实现，避免三条链路的语义漂移。

**配置与运行分离。** 页面配置的是"定义"（可编辑）；编排发布后生成不可变的 `flow_version`；调度与网关只执行已发布版本。
连接器/接口的修改对进行中的调用无影响（执行前一次性快照）。

**失败可诊断。** 每次调用产生 `traceId`，日志表记录脱敏后的真实 URL、请求头、请求体、响应体、耗时、错误码。
平台试调面板可直接回放并生成 cURL。

---

## 3. 数据模型

MySQL 8 / utf8mb4 / InnoDB。动态结构用 JSON 列，MyBatis-Plus 侧用 `JacksonTypeHandler` 并开启 `autoResultMap = true`。

> **通用字段约定（照搬 RuoYi）**：下列 DDL 仅为逻辑结构示意，实际脚本见 `script/sql/carbon.sql`。统一替换为：
> - 主键：雪花 ID `id BIGINT NOT NULL PRIMARY KEY`（无 `AUTO_INCREMENT`），实体上 `@TableId(type = IdType.ASSIGN_ID)`
> - 审计：`create_by` / `create_time` / `update_by` / `update_time`（`BaseEntity` + `AuditMetaObjectHandler` 自动填充）
> - 逻辑删除：`del_flag CHAR(1) NOT NULL DEFAULT '0'`（下文 `deleted TINYINT` 统一改名），实体上 `@TableLogic`
> - 字段名 snake_case，实体属性 camelCase（MP 全局配置开启）

### 3.1 配置域

```sql
-- 环境：提供 {{env.xxx}} 变量，连接器 base_url 与 path 中使用
CREATE TABLE env (
  id              BIGINT PRIMARY KEY AUTO_INCREMENT,
  code            VARCHAR(64)  NOT NULL COMMENT '唯一编码，如 dev/test/prod',
  name            VARCHAR(128) NOT NULL,
  base_url_prefix VARCHAR(512) NULL    COMMENT '可选，渲染时拼在 base_url 前',
  vars            JSON         NULL    COMMENT '{ "appId": "...", "region": "cn" }',
  is_default      TINYINT      NOT NULL DEFAULT 0,
  remark          VARCHAR(512) NULL,
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted         TINYINT      NOT NULL DEFAULT 0,
  UNIQUE KEY uk_env_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

```sql
-- 连接器：一个三方系统 = 一个连接器
CREATE TABLE connector (
  id              BIGINT PRIMARY KEY AUTO_INCREMENT,
  code            VARCHAR(64)  NOT NULL COMMENT '网关路径使用，全局唯一',
  name            VARCHAR(128) NOT NULL,
  category        VARCHAR(64)  NULL    COMMENT '分组，如 支付/物流/CRM',
  base_url        VARCHAR(512) NOT NULL COMMENT '支持模板，如 https://{{env.host}}/api',
  default_headers JSON         NULL    COMMENT 'Map<String,String>，所有接口共享',
  timeout_ms      INT          NOT NULL DEFAULT 10000,
  retry           JSON         NULL    COMMENT '{maxAttempts,backoffMs,backoffMultiplier,retryOn:[429,500,502,503,504]}',
  status          TINYINT      NOT NULL DEFAULT 0 COMMENT '0草稿 1启用',
  remark          VARCHAR(512) NULL,
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted         TINYINT      NOT NULL DEFAULT 0,
  UNIQUE KEY uk_connector_code (code),
  KEY idx_connector_category (category)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

```sql
-- 连接器鉴权：与连接器 1:1，独立表便于整列加密与按类型扩展
CREATE TABLE connector_auth (
  id          BIGINT PRIMARY KEY AUTO_INCREMENT,
  connector_id BIGINT      NOT NULL,
  auth_type   VARCHAR(32)  NOT NULL COMMENT 'none|api_key|basic|bearer|oauth2_cc|login_token',
  config      JSON         NOT NULL COMMENT '结构随 auth_type 变化；敏感字段单独加密，见 §7',
  status      TINYINT      NOT NULL DEFAULT 1,
  created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_auth_connector (connector_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

`config` 的 JSON 结构（示例）：

```jsonc
// api_key
{ "in": "header", "name": "X-API-KEY", "value": "ENC:v1:..." }
// basic
{ "username": "u", "password": "ENC:v1:..." }
// bearer
{ "token": "ENC:v1:...", "prefix": "Bearer" }
// oauth2_cc
{ "tokenUrl": "https://{{env.host}}/oauth/token", "clientId": "id",
  "clientSecret": "ENC:v1:...", "scope": "read",
  "tokenField": "$.access_token", "expiresField": "$.expires_in", "expiresFallbackSec": 3600 }
// login_token
{ "loginMethod": "POST", "loginUrl": "https://{{env.host}}/login",
  "loginBody": "{\"user\":\"{{env.user}}\",\"pwd\":\"{{auth.pwd}}\"}",
  "tokenField": "$.data.token", "tokenHeader": "Authorization",
  "tokenPrefix": "Bearer ", "expiresField": "$.data.exp", "expiresFallbackSec": 7200 }
```

```sql
-- 接口（Operation）：连接器下的一个可调用能力
CREATE TABLE connector_operation (
  id                BIGINT PRIMARY KEY AUTO_INCREMENT,
  connector_id      BIGINT       NOT NULL,
  code              VARCHAR(64)  NOT NULL,
  name              VARCHAR(128) NOT NULL,
  method            VARCHAR(8)   NOT NULL COMMENT 'GET|POST|PUT|DELETE|PATCH',
  path              VARCHAR(512) NOT NULL COMMENT '支持模板，如 /order/{{input.orderId}}',
  query_params      JSON         NULL COMMENT 'Map<String,String 模板>',
  headers           JSON         NULL COMMENT '接口级 Header，覆盖连接器默认',
  body_type         VARCHAR(16)  NOT NULL DEFAULT 'none' COMMENT 'none|json|form|urlencoded|raw',
  body_template     MEDIUMTEXT   NULL COMMENT 'JSON 模板；渲染时先解析为 JSON 树再逐叶子替换 {{...}}（类型透传/自动转义），见 §4.2.1',
  response_extract  JSON         NULL COMMENT '[{"name":"orderId","jsonpath":"$.data.id","default":null}]',
  pre_script        TEXT         NULL COMMENT 'Groovy，可改 ctx.request 与 ctx.vars',
  post_script       TEXT         NULL COMMENT 'Groovy，可改 ctx.response 与 ctx.vars',
  script_timeout_ms INT          NOT NULL DEFAULT 2000,
  timeout_ms        INT          NULL COMMENT '为空则继承连接器',
  retry             JSON         NULL COMMENT '为空则继承连接器',
  status            TINYINT      NOT NULL DEFAULT 0,
  created_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted           TINYINT      NOT NULL DEFAULT 0,
  UNIQUE KEY uk_op_connector_code (connector_id, code),
  KEY idx_op_connector (connector_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

```sql
-- 调用方应用：业务系统接入网关的凭证
CREATE TABLE app (
  id                 BIGINT PRIMARY KEY AUTO_INCREMENT,
  name               VARCHAR(128) NOT NULL,
  app_key            VARCHAR(64)  NOT NULL COMMENT '暴露给业务方，如 ck_ 前缀',
  app_secret_enc     VARCHAR(512) NOT NULL COMMENT 'AES-GCM 密文',
  allowed_connectors JSON         NULL COMMENT '[1,5,9]，null 表示不限制',
  rate_limit_qps     INT          NOT NULL DEFAULT 50,
  ip_whitelist       JSON         NULL COMMENT '["10.0.0.0/8"]，null 表示不限制',
  status             TINYINT      NOT NULL DEFAULT 1,
  created_at         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at         DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_app_key (app_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 3.2 编排域

```sql
CREATE TABLE flow (
  id                BIGINT PRIMARY KEY AUTO_INCREMENT,
  code              VARCHAR(64)  NOT NULL,
  name              VARCHAR(128) NOT NULL,
  draft_dsl         JSON         NOT NULL COMMENT '画布当前草稿',
  published_version INT          NULL    COMMENT '当前发布版本号，null 表示未发布',
  status            TINYINT      NOT NULL DEFAULT 0,
  created_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at        DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  deleted           TINYINT      NOT NULL DEFAULT 0,
  UNIQUE KEY uk_flow_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 发布快照：不可变，调度与网关只执行最近一次发布版本
CREATE TABLE flow_version (
  id           BIGINT PRIMARY KEY AUTO_INCREMENT,
  flow_id      BIGINT      NOT NULL,
  version      INT         NOT NULL,
  dsl          JSON        NOT NULL,
  published_by VARCHAR(64) NULL,
  published_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_flow_version (flow_id, version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE flow_run (
  id           BIGINT PRIMARY KEY AUTO_INCREMENT,
  trace_id     VARCHAR(32)  NOT NULL,
  flow_id      BIGINT       NOT NULL,
  version      INT          NOT NULL,
  trigger_type VARCHAR(16)  NOT NULL COMMENT 'manual|cron|gw|webhook',
  trigger_by   VARCHAR(128) NULL    COMMENT 'appKey / job 名 / 手工操作者',
  status       VARCHAR(16)  NOT NULL COMMENT 'RUNNING|SUCCESS|PARTIAL|FAILED',
  input        JSON         NULL,
  output       JSON         NULL,
  error        TEXT         NULL,
  started_at   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  finished_at  DATETIME(3)  NULL,
  KEY idx_run_flow_time (flow_id, started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE flow_step_run (
  id               BIGINT PRIMARY KEY AUTO_INCREMENT,
  flow_run_id      BIGINT      NOT NULL,
  node_id          VARCHAR(64) NOT NULL,
  node_type        VARCHAR(32) NOT NULL,
  connector_id     BIGINT      NULL,
  operation_id     BIGINT      NULL,
  attempt          INT         NOT NULL DEFAULT 1 COMMENT '重试的第几次',
  exec_index       INT         NOT NULL DEFAULT 0 COMMENT '同一节点在同一次运行中的第几次执行（LOOP/FOREACH 展开），由 StepMapper 按 CmpStep 队列顺序生成',
  request_summary  JSON        NULL COMMENT '脱敏后的 method/url/headers/body',
  response_summary JSON        NULL COMMENT '脱敏后的 status/body',
  status           VARCHAR(16) NOT NULL COMMENT 'SUCCESS|FAILED|SKIPPED',
  duration_ms      INT         NULL,
  error            TEXT        NULL,
  KEY idx_step_run (flow_run_id, node_id, exec_index)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

### 3.3 调度与日志

```sql
CREATE TABLE job (
  id              BIGINT PRIMARY KEY AUTO_INCREMENT,
  flow_id         BIGINT       NOT NULL,
  name            VARCHAR(128) NOT NULL COMMENT 'Quartz jobKey = carbon:{id}',
  cron            VARCHAR(64)  NOT NULL,
  timezone        VARCHAR(64)  NOT NULL DEFAULT 'Asia/Shanghai',
  misfire_policy  VARCHAR(32)  NOT NULL DEFAULT 'DO_NOTHING' COMMENT 'DO_NOTHING|FIRE_ONCE',
  concurrency     VARCHAR(16)  NOT NULL DEFAULT 'DISALLOW' COMMENT 'ALLOW|DISALLOW',
  params          JSON         NULL COMMENT '作为 flow 的 input',
  enabled         TINYINT      NOT NULL DEFAULT 0,
  created_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at      DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 按月分区，30 天归档/清理
CREATE TABLE invoke_log (
  id                 BIGINT      NOT NULL,
  trace_id           VARCHAR(32) NOT NULL,
  source             VARCHAR(16) NOT NULL COMMENT 'CONSOLE|GW|FLOW',
  app_id             BIGINT      NULL,
  flow_run_id        BIGINT      NULL,
  connector_id       BIGINT      NULL,
  operation_id       BIGINT      NULL,
  method             VARCHAR(8)  NULL,
  real_url           VARCHAR(1024) NULL COMMENT '脱敏后（query 中敏感值替换）',
  req_headers_masked JSON        NULL,
  req_body           MEDIUMTEXT  NULL COMMENT '≤64KB，超出截断并追加 __TRUNCATED__',
  resp_status        INT         NULL,
  resp_body          MEDIUMTEXT  NULL COMMENT '同上',
  duration_ms        INT         NULL,
  err_code           VARCHAR(64) NULL,
  err_msg            VARCHAR(1024) NULL,
  created_at         DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id, created_at),
  KEY idx_log_trace (trace_id),
  KEY idx_log_conn_time (connector_id, created_at),
  KEY idx_log_source_time (source, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
PARTITION BY RANGE COLUMNS (created_at) (
  PARTITION p_init VALUES LESS THAN ('2025-01-01')
  -- 由定时任务每月自动新增分区、删除 30 天前分区
);
```

---

## 4. 核心链路设计

### 4.1 InvokeService 执行流水线

```
InvokeContext(in connector, op, env, auth, input, source, traceId, overrideScript)
   │
   1. 参数采集与校验      按 operation 的 path/query/header/body 声明，检查必填、类型
   │
   2. 前置脚本（可选）     Groovy：可读改写 ctx.request / ctx.vars
   │
   3. 模板渲染            URL / query / headers / body 全部渲染 {{...}}
   │
   4. 鉴权注入            AuthResolver 取 token（Redis 缓存），注入 header 或 query
   │
   5. HTTP 执行           Apache HttpClient 5 连接池，按 retry 策略重试
   │
   6. 后置脚本（可选）     Groovy：可改写 ctx.response.body
   │
   7. 响应抽取            JSONPath 抽取为 data；抽取失败按 EXTRACT_FAILED 判定
   │
   8. 统一包装            管理端返回 R<T>、网关返回 GwResult<T>（§4.5），含 providerStatus / costMs / traceId
   │
   9. 异步落日志          log 队列 → 批量写 invoke_log（脱敏 + 截断）
```

**重试策略**：默认 `maxAttempts=0`（不重试）。开启后仅对「连接异常 / 读超时 / 429 / 5xx（白名单可配）」重试；
退避 `backoffMs × backoffMultiplier^n`，上限 3 次。**POST 非幂等接口默认不重试**，需在 operation 显式 `retry.idempotent=true` 才允许。

**401 自愈**：仅对 `oauth2_cc` / `login_token` 两种可刷新凭据，收到 401/403 时强制失效缓存 → 重新取 token → 重试一次（不叠加到 general retry）。

### 4.2 模板渲染（`{{}}`）：壳自研 + 求值器可插拔

**要解决的问题**：连接器接口在页面上配置时，URL / query / header / body 不是定值而是模板；每次调用要把 `{{...}}` 换成这一次的实际值，才能发出去（流水线第 3 步）。没有它，一个接口就只能对应一次固定调用，"连接器"退化为硬编码。

**为什么不用现成模板引擎**（选型记录）

| 候选 | 不用的原因 |
|---|---|
| Mustache.java / Handlebars.java / Freemarker / Velocity / Jinjava | 输出域都是 **String**，而我们要交出的是一棵 **JSON 树 + 一条已编码 URL + 一组 header**。压成字符串再拼回去，等于把 JSON 转义与类型判定重新手做一遍；且 Mustache 的 `{{x}}` **默认 HTML 转义**、无表达式能力、无法枚举模板引用的变量 |
| commons-text `StringSubstitutor` | 只有 `${x}` 简单替换，无表达式、无默认值、无作用域优先级 |
| Jsonnet / sjsonnet | **唯一原生输出 JSON 且天然沙箱**（纯函数、无 IO 无反射），但要引入全新语言 + Scala 运行时，与页面 `{{}}` 约定不符 |
| Json-e / Jolt | Json-e 的 Java 端不成熟；Jolt 是**响应重映射**工具，不是请求模板 |
| SpEL / OGNL | `T(java.lang.Runtime)` 等可触达系统能力，历史上多次 RCE，**永不采用** |

结论：**没有开源件同时满足「JSON 结构化输出 + 表达式能力 + 沙箱 + 可枚举变量引用」**，所以壳自研（约 200 行纯函数 + 可穷举单测），核买现成。

**三层职责（严格分离，任一层可独立替换）**

| 层 | 实现 | 输入 → 输出 | 负责 | 不负责 |
|---|---|---|---|---|
| **壳** `TemplateRenderer` | 自研（`carbon-framework-expression`） | 模板 + 变量池 → JSON 树 / URL / header | 扫 `{{}}`、切分字面量与表达式、类型判定、JSON 转义、URL 编码、缺失变量报错、**枚举变量引用**（供表单生成与参数校验） | 不做任何计算，连 `1+1` 都不会算 |
| **核** 表达式求值 | `AviatorEvaluatorAdapter`（默认）/ `SimpleExpressionEvaluator`（兜底） | 单个表达式 + 变量池 → Java 值 | 算术/比较/逻辑/三元/函数调用/路径取值 | 不知道模板边界、不拼报文、不做编码 |
| **脚本** `GroovySandbox` | Groovy（`carbon-framework-script`） | `ctx` + 多行脚本 → 改写后的 `ctx` | 多行逻辑、循环、异常处理、复杂对象构造 | 不参与模板渲染 |

**边界纪律**：壳**不调用** Groovy，Groovy **不感知**壳的内部结构，Aviator **只**被壳与 DAG `CONDITION` 调用、**不直接暴露给用户配置**。三者通过 `Map<String,Object>` 变量池通信，不共享对象——将来任一层要换（Aviator→QLExpress、Groovy→GraalJS）只改一个模块。

**求值器契约**

```java
public interface ExpressionEvaluator {
    Object  eval(String expr, Map<String, Object> vars);   // 白名单函数已注册
    boolean test(String expr, Map<String, Object> vars);   // CONDITION 节点用，非 boolean 结果按 PARAM_INVALID
}
```

默认 `AviatorEvaluatorAdapter`；兜底 `SimpleExpressionEvaluator`（自研，仅路径取值 + `??` 默认 + 白名单函数）。切换只改 `EngineAutoConfiguration` 一行。

**Aviator 准入条件（P0 安全 spike，硬门禁）**：必须通过下列 6 条逃逸用例才启用，否则回退 `SimpleExpressionEvaluator`：
`new File('/etc/passwd')`、`Runtime.getRuntime().exec(...)`、`System.exit(0)`、`Class.forName(...)`、死循环、`Thread.sleep(99999)`。
加固手段：`AviatorEvaluatorInstance.setFunctionMissing(null)`（切断「未注册函数 → 反射调用」这条退路）、`Options.FEATURE_SET` 收窄特性、`Options.ALLOWED_CLASS_SET` 限定可访问类。

**语法**

```text
{{input.orderId}}
{{input.page ?? 1}}
{{env.host}}
{{vars.token}}
{{now()}}
{{hmacSha256(env.secret, input.raw)}}
{{jsonpath(input.payload, '$.a.b')}}
```

**作用域优先级**（高 → 低）：`input` > `vars` > `auth` > `env` > 内置常量

**函数白名单**（注册在壳内，与求值器实现无关；Aviator 与自研核共用同一份）

| 函数 | 说明 |
|---|---|
| `now()` / `now('yyyy-MM-dd HH:mm:ss')` | 当前时间（服务器时区） |
| `timestamp()` | 毫秒时间戳 |
| `uuid()` | 无横线 UUID |
| `randomInt(a,b)` | 闭区间随机 |
| `md5(s)` / `sha256(s)` | 十六进制小写 |
| `hmacSha256(key, data)` | 十六进制小写 |
| `base64(s)` / `base64Decode(s)` | 标准 base64 |
| `urlEncode(s)` / `urlDecode(s)` | RFC3986（一般不需要手写，见 §4.2.1 #4） |
| `jsonpath(json, path)` | 取值失败返回 null |
| `upper/lower/trim/replace(s, a, b)` | 字符串工具 |

#### 4.2.1 渲染规则与边界（实现期最容易返工的部分）

| # | 情况 | 规则 |
|---|---|---|
| 1 | 占位符**独占整个值**（`"k":{{x}}`） | 类型透传：`{"page":{{input.page ?? 1}}}` 渲染为**数字** `1`；`{"d":{{vars.obj}}}` 直接把 Map/List 塞入 |
| 2 | 占位符**嵌在字符串中**（`"A-{{x}}-B"`） | 逐占位符转成字符串后拼接 |
| 3 | JSON 转义 | **禁止在字符串层面整体替换**：`body_template` 先解析为 JSON 树，再对每个叶子字符串渲染，转义由 Jackson 序列化保证 |
| 4 | URL 编码 | `path` 段与 `query` 值由渲染器**自动 RFC3986 encode**；`body` 不 encode；不依赖用户手写 `urlEncode()` |
| 5 | 变量缺失 | 无 `??` 默认值时抛 `PARAM_INVALID`（错误信息含变量路径与在模板中的位置），**不得静默渲染为空串** |
| 6 | `}}` 出现在表达式内 | `{{jsonpath(a, '$.b}')}}`、`{{x ?? '}'}}` 必须正确解析 → 用**引号感知扫描器**，禁止正则 `\{\{(.*?)\}\}` |
| 7 | 与脚本的先后 | 前置脚本在**渲染前**执行，改的是模板与 `vars`；脚本里读到的是 `{{...}}` 原文，不是渲染结果 |
| 8 | 渲染器性质 | 无状态纯函数（可单测）；`{{}}` 扫描结果按 `operationId + hash(template)` 缓存 token 列表，避免每次调用重扫 |

**表单元数据复用**：同一份扫描结果（`List<VarRef>`：`scope` + `path` + `hasDefault`）同时用于 ① 前端按 `{{input.x}}` 自动生成入参表单（§5.2）、② 调用前必填与类型校验、③ 试调页预填。**这是壳必须自研的第二个理由**——开源模板引擎拿不到这份结构化变量引用清单。

**Header 合并顺序**（后者覆盖前者）：
`connector.default_headers` → 鉴权注入 → `operation.headers` → 运行时入参 headers

**URL 拼装**：`render(env.base_url_prefix) + render(connector.base_url) + render(operation.path)`；
`path` 段渲染后自动 encode，`query` 值一律 URLEncode，不做智能猜测。

**渲染后置校验**：URL 渲染完成后交给 §7 SSRF 校验；body 渲染结果必须能再次被 Jackson 解析（否则 `PARAM_INVALID`）。


### 4.3 鉴权解析（AuthResolver）

| 类型 | 行为 | 缓存 |
|---|---|---|
| `none` | 不注入 | — |
| `api_key` | 注入指定 header 或 query | — |
| `basic` | `Authorization: Basic base64(user:pass)` | — |
| `bearer` | `Authorization: <prefix><token>`（静态） | — |
| `oauth2_cc` | POST `tokenUrl` + `grant_type=client_credentials` | Redis |
| `login_token` | 调自定义登录接口，按 JSONPath 取 token/过期时间 | Redis |

**Token 缓存与 single-flight**
```
key  = conn:token:{connectorId}:{authType}
ttl  = (expires_in - 60s)  [下限 30s]
lock = conn:token:lock:{connectorId}   [SETNX px=10000]

getToken():
  v = redis.get(key); if v: return v
  if redis.setnx(lock): 
      try { tok = fetchFromProvider(); redis.set(key, tok, ttl); return tok }
      finally { redis.del(lock) }
  else:
      // 等锁持有者写完，最多等 3s，超时降级为自己拉一次
      sleep 50ms 轮询 key，最多 60 次；仍未拿到则自行 fetchFromProvider()
```
Redis 不可用时降级为进程内 Caffeine 缓存（TTL 同），保证可用性优先。

### 4.4 Groovy 沙箱（`carbon-framework-script`）

> 与 §4.2 的分工：Groovy 负责**多行逻辑脚本**（前置/后置/`SCRIPT` 节点），表达式的**单行求值**由 `ExpressionEvaluator`（默认 Aviator）负责，两者不互相调用。

**编译期加固**
- `SecureASTCustomizer`：禁止 `import` 白名单之外的包、禁止 `*` 导入、禁止 `for/while/do` 无限循环（仅允许 `each`/`times` 与有界 `for`）、禁止 `System.exit`、禁止 `Class.forName` 与 `.metaClass`、禁止 `synchronized`、禁止 `Thread` 相关类
- `ImportCustomizer` 仅允许：`java.util.*`、`java.time.*`、`groovy.json.*`、`java.math.BigDecimal`、`java.math.RoundingMode`
- 禁止 `getClass()`、`newInstance()`、`invokeMethod()` 动态调用

**运行期加固**
- 每次脚本在独立线程池执行，`Future.get(scriptTimeoutMs)` 超时后 `cancel(true)` 并**丢弃该线程**（recreate executor，防止被 interrupt 忽略的脚本污染池）
- 执行线程的 `ThreadLocal` 上下文执行后必须清理
- 每次调用记录脚本 hash 与耗时，`SCRIPT_ERROR` 错误码返回简明堆栈（不含内部类路径）

**编译缓存**：Caffeine，key = `sha256(script)`，value = `Class<?>`，上限 500 条，access 过期 1h。

**上下文对象**
```groovy
ctx.request  = [method:'POST', url:'...', headers:[...], body:'{...}']
ctx.response = [status:200, headers:[...], body:'{...}']   // 仅后置脚本
ctx.vars     = [:]        // 可写，供 response_extract 与编排变量池使用
ctx.input    = [...]      // 只读视图
ctx.env      = [...]      // 只读视图
ctx.utils    = [json:'', jsonpath:'', digest:'', log:'']
ctx.log('...')            // 写入 traceId 关联日志
```

**开关**：`platform.script.enabled=false` 全局关闭；关闭后 pre/post 脚本字段保留但执行时跳过并记录告警。

### 4.5 统一响应与错误码

**两套响应体，刻意不合并**：

| 场景 | 类型 | 结构 |
|---|---|---|
| 管理端 `/api/**` | `R<T>`（照搬 RuoYi） | `{ code: 200\|500, msg, data }` |
| 网关 `/gw/**`（**对外契约**） | `GwResult<T>` | `{ code: 语义码, message, data, traceId, providerStatus, costMs }` |

理由：网关是外部系统依赖的稳定契约，不能被内部 `code: 200/500` 语义演进带崩。

```json
// 网关响应示例
{
  "code": "0",
  "message": "ok",
  "data": { "orderId": "123" },
  "traceId": "a1b2c3d4e5f6",
  "providerStatus": 200,
  "costMs": 123
}
```

| code | HTTP | 触发条件 |
|---|---|---|
| `0` | 200 | 成功 |
| `CONNECTOR_NOT_FOUND` | 404 | connectorCode 不存在或未启用 |
| `OPERATION_NOT_FOUND` | 404 | operationCode 不存在或未启用 |
| `PARAM_INVALID` | 400 | 必填缺失 / 类型不匹配 / 模板渲染失败 |
| `AUTH_FAILED` | 502 | 取 Token 失败（网络/凭证错误） |
| `PROVIDER_TIMEOUT` | 504 | 读超时或连接超时 |
| `PROVIDER_HTTP_4XX` | 502 | 三方返回 4xx（401 自愈后仍失败） |
| `PROVIDER_HTTP_5XX` | 502 | 三方返回 5xx |
| `EXTRACT_FAILED` | 502 | 响应抽取命中率 0 且未设 default |
| `SCRIPT_ERROR` | 500 | 脚本编译/执行失败或超时 |
| `SIGN_INVALID` | 401 | 签名不匹配 / 时间窗外 / nonce 重放 / appKey 无效 |
| `RATE_LIMITED` | 429 | 应用级 QPS 超限 |
| `FLOW_TIMEOUT` | 504 | 编排整体超时 |
| `INTERNAL_ERROR` | 500 | 兜底 |

网关侧 `code` 与 HTTP 状态码同时返回，业务方既可看 HTTP 也可看语义码。管理端异常统一由 `GlobalExceptionHandler` 把 `ServiceException` 转为 `R.fail(code, msg)`，`traceId` 通过响应头 `X-Trace-Id` 回传。

### 4.6 网关签名协议

```
X-App-Key   : ck_xxxxxxxx
X-Timestamp : 1730000000000         (毫秒)
X-Nonce     : 8f2c...               (UUID，≤64 字符)
X-Sign      : Base64(HmacSHA256(appSecret, stringToSign))

stringToSign = HTTP_METHOD              + "\n"
             + canonicalPathAndQuery    + "\n"   // path 原样 + ? + 按 key 字典序拼接的 query
             + X-Timestamp              + "\n"
             + X-Nonce                  + "\n"
             + HEX_LOWER(SHA256(rawBody))          // 空 body 取 SHA256("")
```

**校验顺序**
1. `appKey` 存在且启用 → 取密文解密 secret
2. `|now - timestamp| ≤ 5min`
3. 重算签名，`MessageDigest.isEqual` 常量时间比较
4. Redis `SET gw:nonce:{appKey}:{nonce} 1 NX PX 600000`，已存在 → `SIGN_INVALID`（重放）
5. IP 白名单校验（支持 CIDR）
6. 应用级令牌桶限流（Redis，`rate_limit_qps`）
7. 连接器白名单（`allowed_connectors`）

**网关入参约定**
- `path` 变量：从 query 与 JSON body 顶层字段中采集，同名 body 优先
- 允许通过 header `X-Carbon-Env` 指定环境（默认取 `is_default=1`）
- 网关不解析业务语义，只做「变量填充 + 抛出」，响应体即统一响应结构

### 4.7 DAG 编排

**执行内核 = LiteFlow core 封装**：`carbon-framework-dag` 只做「DSL → EL 编译 + chain 注册 + 步骤映射」，不自己写执行器（选型与代价见 §9.9）。由此 DSL 的表达能力被限定为**结构化树形图**：串行、并行、分支、菱形汇聚、循环。**没有 GOTO 回跳**——需要重复执行时用 `LOOP` 节点。

**DSL**（仍是 `nodes` + `edges`，前端 LogicFlow 可直接编辑；发布时由 `ElCompiler` 编译成 EL）

```json
{
  "version": 1,
  "nodes": [
    { "id": "n1", "type": "START" },
    { "id": "n2", "type": "CONNECTOR_CALL", "label": "拉取订单",
      "connector": "crm", "operation": "listOrder",
      "input": { "pageNo": "{{input.pageNo ?? 1}}", "status": "PAID" },
      "output": { "orders": "$.data.list", "total": "$.data.total" },
      "onError": "STOP",
      "retry": { "maxAttempts": 2, "backoffMs": 200 } },
    { "id": "n3", "type": "CONDITION",
      "expr": "vars.orders != nil && size(vars.orders) > 0",
      "branches": { "true": "n4", "false": "n6" } },
    { "id": "n4", "type": "LOOP", "maxTimes": 3,
      "continueWhen": "vars.lastFailed == true", "body": "n5", "bodyEnd": "n5" },
    { "id": "n5", "type": "SCRIPT", "lang": "groovy", "timeoutMs": 2000,
      "script": "vars.total = vars.total + vars.item.amount" },
    { "id": "n6", "type": "END" }
  ],
  "edges": [
    { "source": "n1", "target": "n2" },
    { "source": "n2", "target": "n3" },
    { "source": "n4", "target": "n6" }
  ],
  "timeoutMs": 60000
}
```

**节点类型**

| type | 说明 | 出边 | 编译为 EL |
|---|---|---|---|
| `START` | 入口，唯一 | 1 条 | 链首 |
| `END` | 出口，≥1 个 | 0 条 | 链尾；多个 `END` = 各分支各自终止 |
| `CONNECTOR_CALL` | 调某个接口，`input` 映射入参，`output` 用 JSONPath 抽取进 `vars` | 1 条 | 一个节点组件，`connector/operation/映射` 通过 `.data(json)` 挂在节点上 |
| `CONDITION` | `expr` 布尔表达式（§4.2 的 `ExpressionEvaluator`，默认 Aviator 核），`branches.true/false` | 2 条 | `IF(cond, 真分支, 假分支)` |
| `LOOP` | 重复执行子图，`maxTimes` + `continueWhen`；用 `body`/`bodyEnd` 圈定子图（**不画回边**，子图收尾由 `bodyEnd` 声明） | 子图内部边 + 1 条出边 | `WHILE(LoopGuard).DO(body)`；`LoopGuard` 判「未超次数 && continueWhen」 |
| `FOREACH` | 遍历 `items`，`itemVar` 每次写入 `vars`，`concurrency` 1..10；同样用 `body`/`bodyEnd` | 子图内部边 + 1 条出边 | `ITERATOR(items).DO(body)`（`concurrency>1` 用 `.parallel(true)`） |
| `SCRIPT` | Groovy，读写 `vars` | 1 条 | script 节点（`carbon-framework-script` 沙箱执行） |
| `DELAY` | `delayMs` 固定或 `randomMs` 区间 | 1 条 | `DelayComponent`（受 `.maxWaitMilliseconds` 保护） |
| `PARALLEL`（P3） | 多分支并发后 join | 2..N 条 + 汇聚边 | `WHEN(...)`，汇聚点取父级后继 |

**DSL 的 Java 类型映射（`sealed` + `record`）**

8 种节点 = 1 个密封接口 + 8 个 `record`。这是 JDK 21 的主要收益兑现处：节点类型的“全集”写进类型系统，而不是台账。

```java
// carbon-framework-dag: dsl/NodeDef.java
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = As.PROPERTY, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = StartNode.class,   name = "START"),
    @JsonSubTypes.Type(value = EndNode.class,     name = "END"),
    @JsonSubTypes.Type(value = CallNode.class,    name = "CONNECTOR_CALL"),
    @JsonSubTypes.Type(value = CondNode.class,    name = "CONDITION"),
    @JsonSubTypes.Type(value = LoopNode.class,    name = "LOOP"),
    @JsonSubTypes.Type(value = ForeachNode.class, name = "FOREACH"),
    @JsonSubTypes.Type(value = ScriptNode.class,  name = "SCRIPT"),
    @JsonSubTypes.Type(value = DelayNode.class,   name = "DELAY")
})
public sealed interface NodeDef permits StartNode, EndNode, CallNode, CondNode,
        LoopNode, ForeachNode, ScriptNode, DelayNode {
    String id();
}

public record LoopNode(String id, int maxTimes, String continueWhen,
                       String body, String bodyEnd) implements NodeDef {}

public record CallNode(String id, String label, String connector, String operation,
                       Map<String, Object> input, Map<String, String> output,
                       OnError onError, Retry retry) implements NodeDef {}
```

消费端（`ElCompiler`）**不写 `default` 分支**：

```java
String el = switch (node) {
    case StartNode   s -> "";
    case EndNode     e -> "";
    case CallNode    c -> callEl(c);
    case CondNode    c -> ifEl(c);
    case LoopNode    l -> whileEl(l);
    case ForeachNode f -> iteratorEl(f);
    case ScriptNode  s -> scriptEl(s);
    case DelayNode   d -> delayEl(d);
};
```

**三条纪律**

1. **新增节点类型 = 改 4 处**：`NodeDef permits` 列表、`GraphValidator` 的结构规则、`ElCompiler` 的 `switch`、前端 `constant/nodeType.ts`。前三处由编译器强制提醒（`switch` 不穷尽 → 编译失败），第四处靠 §5.3 的画布节点注册表，加类型时表单处会因缺 schema 报错。
2. **反序列化入口唯一**：只在 `FlowLoader`（读 `flow_version.dsl`）与 `FlowSaveService`（读请求体）里调 `ObjectMapper.readValue(json, DagDsl.class)`，其余代码只接触密封类型，不接触 `JsonNode`。
3. **不可变**：`record` 全 `final`，编译后的 chain 可被 `ChainRegistry` 跨线程共享，无需防御性拷贝；需要“改一个字段”时用 `with` 式静态工厂返回新实例，不提供 setter。

**保存前校验**（`/api/flows/{id}/validate`，前端每次保存也跑）

1. 恰好 1 个 `START`，≥1 个 `END`
2. 非 `START` 节点必有入边；非 `END` 节点必有出边；无孤立节点
3. 所有 `CONNECTOR_CALL` 引用的 connector/operation 存在且 `status=1`
4. `CONDITION.branches`、`LOOP/FOREACH.body`+`bodyEnd` 目标存在；`body` 内每个节点都能到达 `bodyEnd`；`bodyEnd` 不得连到容器之外（子图收尾由声明表达，不画回边）；`LOOP` 的子图不得包含其自身
5. 表达式语法可解析；Groovy 脚本可编译
6. 节点数 ≤ 500，`timeoutMs` ≤ 600000
7. **结构化校验**（编译成 EL 的前提，逐条报出违例节点对）
   - **无回边**：每条边满足 `topoIndex(source) < topoIndex(target)`（容器子图独立计算拓扑序，但子图自身也无环）
   - **无交叉边**：两条并行的分支之间不得交叉连接
   - **分支必须汇聚**：多入边节点必须是其全部入边来源的最近公共支配点，否则报「分支未在同一节点汇聚，无法编译为结构化流程」
   - 报错文案必须给出画布指引，例如「`n4 → n6` 与 `n5 → n7` 交叉，请让两条分支汇合到同一节点」

**执行语义**

- 变量池 → LiteFlow 上下文：`flow.dag.FlowContext`（`input` 只读 / `vars` 可写 / `env` 所选环境变量），编译期把 `{{vars.x}}` 映射为对该上下文的取值
- `CONDITION` 未命中分支下的所有节点，在 `flow_step_run` 记 `SKIPPED`（`StepMapper` 用「DSL 全节点集 − `CmpStep` 队列」推导，含循环内多次执行按 `execIndex` 展开）
- `onError`：`STOP`（抛 `DagNodeException` → LiteFlow 中断 → `flow_run=FAILED`）/ `CONTINUE`（组件内部吞掉，写 `vars.<nodeId>.failed=true`，整体 `PARTIAL`）。**没有 `GOTO`**：需要「失败后再做点什么」，用 `CONDITION` 判 `vars.<nodeId>.failed`
- **三方 4xx/5xx 是业务结果，不是技术异常**：`CONNECTOR_CALL` 默认不抛异常，把 `status/body` 写进 `vars.<nodeId>`；只有连接失败/读超时才抛 `DagNodeException`
- 重试：节点 `retry` 编译为 `.retry(n, "…DagNodeException")`；LiteFlow 的 retry 不带退避，`backoffMs` 由组件内部 `sleep` 实现
- 超时：整体 `timeoutMs` → `.maxWaitMilliseconds(timeoutMs)`；节点级 → `node.maxWaitMilliseconds(x)`；中断由 LiteFlow 负责，不再自建 watchdog
- 并发策略：`DISALLOW` 时用 Redis `flow:lock:{flowId}` 抢锁，抢不到直接返回 `RATE_LIMITED`
- 步进推送：`onSuccess` / `onError` 回调 → `StepEventPublisher` → SSE `/api/flows/runs/{runId}/stream`
  `{"nodeId":"n2","execIndex":0,"status":"RUNNING","vars":{...},"costMs":123}`，前端画布实时高亮 + 变量 Inspector

**版本与热更新**

- `chainId = flow:{flowId}:v{version}`，**只有已发布版本才注册 chain**；草稿不注册
- 发布 = `ElCompiler` 生成 EL → `LiteflowMetaOperator.reloadOneChain(chainId, el)` → 发 Redis pub/sub `carbon:flow:chain:reload`，**每个实例收到后各自 reload**（chain 在 JVM 内存里，官方明示多节点必须逐节点刷新）
- 刷新是平滑的：正在跑的请求仍走旧 chain，后续请求切新 chain；旧版本 chain 保留一个发布周期后清理
- 回滚 = 把历史版本的 EL 重新注册并切换 `flow.published_version`（版本记录本身不可变）

**实现分布**（对应 §9.3）

| 能力 | 所在模块 |
|---|---|
| DSL 模型 `DagDsl/NodeDef/EdgeDef/NodeType`、`GraphValidator`（含结构化校验）、`ElCompiler`、`ChainRegistry`（注册 + 广播）、`StepMapper`（`CmpStep`→`flow_step_run`）、`FlowContext` | `carbon-framework-dag` |
| 控制流组件 `StartCmp/EndCmp/ConditionCmp/LoopCmp/LoopGuardCmp/ForeachCmp/DelayCmp` + `AbstractDagNodeCmp`（统一处理 `.data()` 解析、异常包装、耗时上报） | `carbon-framework-dag`（零业务依赖，依赖 `liteflow-core` 2.16.x） |
| `CONNECTOR_CALL` / `SCRIPT` 两个业务组件（桥接 engine / 沙箱） | `carbon-flow` |
| 定义/版本/发布/回滚/运行记录入库、SSE 推送、`DagRunListener` 实现 | `carbon-flow` |


### 4.8 Quartz 调度

- `spring-boot-starter-quartz` + `JobStoreTX`（JDBC，`org.quartz.impl.jdbcjobstore.StdJDBCDelegate`）+ `isClustered=true`
- 任务定义在 `job` 表，`JobSyncService` 在应用启动后与每次 CRUD 后同步到 Quartz：
  `JobKey = carbon:job:{id}`，`CronTrigger` 按 `cron` + `timezone` 生成
- 并发策略：`@DisallowConcurrentExecution` 无法动态开关，改为**在 Job 内部用 Redis 锁**实现 `DISALLOW`（与 flow 的并发策略复用同一把锁）
- `misfire_policy`：`DO_NOTHING`（`withMisfireHandlingInstructionDoNothing`）/ `FIRE_ONCE`
- 「立即执行一次」→ `scheduler.triggerJob(jobKey)`，不修改 `nextFireTime`
- 集群时钟要求：各节点 NTP 同步；偏差 >1s 会在日志告警

---

## 5. 前端设计

### 5.1 路由与页面

**P1**
| 路由 | 页面 |
|---|---|
| `/dashboard` | 概览：连接器数、今日调用量、成功率、P95 耗时、TOP 5 慢接口、最近失败列表 |
| `/connectors` | 连接器列表：分组折叠、关键词搜索、启停开关、复制 |
| `/connectors/:id/edit` | 四 Tab：基本信息 / 鉴权 / 默认 Header / 超时重试 |
| `/connectors/:id/operations/:opId` | 接口编辑 + 试调（核心页面） |
| `/envs` | 环境变量管理（JSON 键值表格） |
| `/apps` | 应用管理：AppKey 展示、Secret 一次性显示 + 重置、限流、IP 白名单 |
| `/logs` | 调用日志：多条件筛选 + 详情抽屉（脱敏报文、curl 复制、traceId 追踪） |
| `/docs` | 网关接入文档（渲染 OpenAPI 3，含各类语言签名示例） |

**P2**：`/flows`（列表）、`/flows/:id/design`（画布）、`/flows/:id/runs`（运行历史）
**P3**：`/jobs`（任务管理）、`/settings`（平台设置）

### 5.2 接口编辑 + 试调页布局

```
┌──────────────────────────────────────────────────────────────────┐
│ 面包屑：CRM 连接器 / 查询订单        [保存] [生成 cURL] [复制为接口] │
├──────────────────────────────┬───────────────────────────────────┤
│ 左：请求定义（Tab）           │ 右：试调面板                       │
│  ● 基本  ○ Query ○ Header     │  环境: [prod ▾]  [发送]           │
│  ● Body  ○ 前置脚本           │  ┌ 入参表单（按模板变量自动生成）┐ │
│  ○ 后置脚本 ○ 响应抽取        │  │ orderId  [__________]        │ │
│                              │  │ pageNo   [1_________]        │ │
│  Body：Monaco(JSON)          │  └──────────────────────────────┘ │
│  ┌─────────────────────────┐ │  ┌ 响应 ────────────────────────┐ │
│  │ {                       │ │  │ Pretty│Raw│Headers│Timing   │ │
│  │   "orderId": "{{input.  │ │  │ { "code":"0", ... }          │ │
│  │     orderId}}"          │ │  └──────────────────────────────┘ │
│  └─────────────────────────┘ │  耗时 123ms · provider 200 · traceId│
└──────────────────────────────┴───────────────────────────────────┘
```

要点：
- **变量自动识别**：扫描 body/path/query 中的 `{{input.xxx}}`，自动生成入参表单（类型从 `paramMeta` 带出，缺省 string）
- **JSONPath 实时校验**：响应抽取表格中填入 path 后，用上次响应即时预览取值结果，标红未命中项
- **脚本编辑**：Monaco Groovy 语法高亮 + 「用上次响应测试脚本」按钮
- **cURL 生成**：完整还原 method/url/headers/body，敏感 header 值以 `$CARBON_SECRET` 占位

### 5.3 编排画布（LogicFlow）

- 布局：左节点面板（拖拽） / 中画布 / 右属性面板 / 顶栏（校验·试跑·发布·版本）
- 自定义节点：按 type 不同配色与图标（`CONNECTOR_CALL` 显示「连接器.接口」、`LOOP` 显示循环框 + 次数角标）
- 连线规则（前端拦截 + 后端二次校验）：禁止自环、**禁止回边**（不能连回上游节点）、**禁止交叉边**（两条并行分支不得交叉连接）、`CONDITION` 出边必须标注 `true`/`false`；连边非法时立即高亮并提示原因
- 无 `GOTO`：重复执行统一用 `LOOP` 节点（属性面板只填「最多几次」+「继续条件」）
- 校验结果：点击报错项定位并高亮对应节点（结构化校验的「分支未汇聚」需同时高亮两个分支末端与预期汇聚点）
- 试跑：底部抽屉输入 JSON → 执行 → 画布节点依次闪烁（SSE）→ 变量池变量树（可展开）
- 发布：弹窗填写版本说明，确认后生成 `flow_version`，右上角显示当前发布版本

---

## 6. API 设计

统一前缀 `/api`，响应体为 `R<T>`（RuoYi 约定，见 §4.5），`data` 承载业务数据；异常统一 HTTP 200 + `code:500`。

**分页与参数约定（照搬 RuoYi）**：
- 分页入参：`pageNum`（默认 1）、`pageSize`（默认 10）、`orderByColumn`、`isAsc` → 封装为 `PageQuery`
- 分页出参：`TableDataInfo<T>` = `{ total, rows, code, msg }`
- 列表查询参数封装为 `XxxBo`（含 `searchValue`、`params` 扩展槽）

### 6.1 配置域

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/connectors` | 分页；参数 `keyword, category, status` + `pageNum, pageSize` |
| POST | `/api/connectors` | 新建，返回 id |
| GET | `/api/connectors/{id}` | 详情（含 auth） |
| PUT | `/api/connectors/{id}` | 更新 |
| DELETE | `/api/connectors/{id}` | 逻辑删除（有启用接口时拒绝） |
| PUT | `/api/connectors/{id}/status` | `{status:0\|1}` 启停；启用前校验配置完整性 |
| POST | `/api/connectors/{id}/auth/test` | 主动取 Token / 校验鉴权，返回是否成功与耗时 |
| GET | `/api/connectors/{id}/operations` | 接口列表 |
| POST | `/api/operations` | 新建接口 |
| GET/PUT/DELETE | `/api/operations/{id}` | 详情/更新/删除 |
| POST | `/api/operations/{id}/debug` | **试调**：`{envCode, pathParams, query, headers, body, preScriptOverride, postScriptOverride}` |
| GET/POST/PUT/DELETE | `/api/envs[/{id}]` | 环境管理 |
| POST | `/api/envs/{id}/set-default` | 设为默认环境 |
| GET/POST/PUT/DELETE | `/api/apps[/{id}]` | 应用管理 |
| POST | `/api/apps/{id}/reset-secret` | 重置密钥（旧 secret 立即失效） |
| GET | `/api/logs` | 分页；`source, connectorId, operationId, traceId, success, startTime, endTime` |
| GET | `/api/logs/{traceId}` | 该 traceId 下的**调用明细**（多行，按时间）+ 每行的 `flowRunId`（可空）。**不含编排步骤**——步骤树由前端再调 `/api/flow-runs/{runId}` |
| GET | `/api/stats/overview` | 看板数据（由 `carbon-invokelog` 的 `StatsController` 提供，路径不变） |
| GET | `/v3/api-docs/gateway` | 网关 OpenAPI 3（按已启用连接器动态生成） |

### 6.2 编排域（P2）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET/POST | `/api/flows` | 列表/新建 |
| GET/PUT | `/api/flows/{id}` | 读/存草稿（`dsl` 整体覆盖） |
| POST | `/api/flows/{id}/validate` | 返回 `{valid, errors:[{nodeId,code,message}]}`；结构化校验失败时 `code` 为 `EDGE_BACKWARD` / `EDGE_CROSSING` / `NO_CONVERGENCE`，`message` 带画布指引 |
| POST | `/api/flows/{id}/publish` | `{remark}` → 生成新版本 |
| GET | `/api/flows/{id}/versions` | 版本列表 |
| POST | `/api/flows/{id}/rollback` | `{version}` → 复制为 draft |
| POST | `/api/flows/{id}/run` | 异步试跑，返回 `{runId, traceId}` |
| GET | `/api/flows/runs/{runId}/stream` | **SSE** 步进推送 |
| GET | `/api/flows/{id}/runs` | 运行历史分页 |
| GET | `/api/flow-runs/{runId}` | 运行详情 + 步骤明细 |

### 6.3 调度域（P3）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET/POST | `/api/jobs` | 列表/新建（创建即注册 Quartz） |
| PUT/DELETE | `/api/jobs/{id}` | 更新（重注册 trigger）/删除 |
| PUT | `/api/jobs/{id}/status` | 启停 |
| POST | `/api/jobs/{id}/trigger` | 立即执行一次 |

### 6.4 网关（对外）

| 方法 | 路径 | 说明 |
|---|---|---|
| ANY | `/gw/{connectorCode}/{operationCode}` | 签名鉴权；body 透传；变量从 query + JSON body 顶层采集 |
| POST | `/gw/flows/{flowCode}` | 触发已发布编排，body 作为 flow `input` |
| GET | `/gw/health` | 无鉴权存活探针（不暴露配置信息） |

---

## 7. 安全设计

### 7.1 密钥管理
- 主密钥 `CARBON_MASTER_KEY`（32 字节 base64）由环境变量注入；启动时缺失/长度错误直接拒绝启动
- AES-256-GCM：随机 12 字节 IV，密文格式 `v1:base64(iv):base64(ciphertext+tag)`，AAD 绑定 `table:column:id` 防密文搬移
- **落地方案**：`carbon-common-encrypt` 的 `@EncryptField` + `EncryptTypeHandler`（MyBatis 存取自动加解密），业务代码零侵入
- 加密字段：`connector_auth.config` 内 `password/token/clientSecret/value`、`app.app_secret_enc`
- **响应侧**：`carbon-common-sensitive` 的 `@Sensitive(strategy = SensitiveStrategy.PASSWORD)` 保证 Vo 序列化自动打码，避免密钥误传前端
- 密钥轮换：预留 `v2:` 前缀与 `KeyVersionResolver`，P3 之后实现

### 7.2 日志脱敏
- 固定正则（大小写不敏感）：`password|passwd|secret|token|authorization|sign|signature|cookie|app_?secret`
- 按 `connector_auth` 的字段名动态追加脱敏键
- `invoke_log.real_url` 的 query 中命中脱敏键的值替换为 `****`
- Header 整体脱敏：`Authorization`、`Cookie`、`X-Api-Key` 直接替换 `****`（保留前缀如 `Bearer ****` 便于排查）
- Body 超过 64KB 截断并追加 `\n__TRUNCATED__(originalSize=xxx)`

### 7.3 SSRF 防护
- 仅允许 `http`/`https`
- `platform.egress.deny-cidr` 可配置开关（默认关闭以便接入内网三方）：开启后解析目标 IP 并拒绝 `127.0.0.0/8`、`10.0.0.0/8`、`172.16.0.0/12`、`192.168.0.0/16`、`169.254.0.0/16`、`::1`、`fc00::/7`
- 禁止重定向（`HttpClient` followRedirects=NEVER），避免跳转绕过 CIDR 校验
- 响应体读取上限 10MB，超限按 502 报错

### 7.4 已知风险（必须在部署说明中强调）
**平台页面不做登录**，`/api/**` 无鉴权。上线要求：
- 仅内网可访问；`/api/**` 由 Nginx + IP 白名单 + Basic Auth 或 SSO 反向代理保护
- `/gw/**` 与 `/api/**` 必须网络隔离（不同域名/端口），避免网关凭证风控外泄到页面侧
- `CARBON_MASTER_KEY` 不写入代码仓库与镜像层，使用 K8s Secret / 配置中心

---

## 8. 可观测性

| 维度 | 方案 |
|---|---|
| 日志 | Logback JSON 格式，`traceId` 贯穿（`MDC`，由 `TraceIdFilter` 生成/透传 `X-Trace-Id`） |
| 指标 | Micrometer + Prometheus：`carbon_invoke_total{connector,code}`、`carbon_invoke_duration_seconds`、`carbon_flow_run_total{status}`、`carbon_token_refresh_total`、`carbon_chain_registered{flowId,version}`（各实例已注册 chain 数，用于核对广播一致性） |
| 健康检查 | `/actuator/health`（DB / Redis / Quartz 就绪探针）；`/actuator/carbon-chains` 列出本实例已注册的 `flow:{id}:v{n}`，排查「某节点未刷新」 |
| 告警（P3） | 失败率 > 阈值 & 连续 N 次失败 → Webhook（企业微信/钉钉） |
| 日志归档 | 每日凌晨清理 30 天前分区；归档可导出 OSS（P3 可选） |

---

## 9. 工程结构（参考 RuoYi-Vue-Plus 5.X）

### 9.1 选型理由

对照 `dromara/RuoYi-Vue-Plus` 5.X 分支的实际骨架（根 `pom.xml` + `ruoyi-admin` / `ruoyi-common`(24 子模块) / `ruoyi-modules`(5 业务模块) / `ruoyi-extend`(2 扩展)），
本平台采用**同构的多模块 Maven 结构 + 同套编码约定**，但按"无登录、无多租户、连接器为核心"裁剪模块集合。

好处：团队对 RuoYi 生态零学习成本；`carbon-common-*` 可直接搬运 RuoYi 官方实现（`R<T>`、`BaseMapperPlus`、`BaseEntity`、`@EncryptField`、`@Sensitive`、`@RepeatSubmit` 等）；后续若要补登录/多租户，按 §9.6 的路径加模块即可。

**代码分三类，边界不看“是不是工具类”，只看“能不能脱离本项目单独用”**：

| 类别 | 目录 | 特征 | 例子 |
|---|---|---|---|
| 基础设施 | `carbon-common-*` | 与 Spring/MyBatis/Redis/Web 的**适配**，带企业约定（`R<T>`、`BaseEntity`、`@Log`） | `common-core`、`common-mybatis`、`common-encrypt` |
| **自研二方包** | **`carbon-framework-*`** | **业务无关的技术能力**，核心能力类**不依赖 Spring 容器**（纯 POJO + 构造器注入，单测无需启动上下文），不含业务名词，可整包搬到别的项目 | **`framework-dag`（编排编译与适配，内核用 LiteFlow core）、`framework-script`（Groovy 沙箱）、`framework-expression`（模板/表达式）、`framework-http`（HTTP 执行器）** |
| 业务域 | `carbon-modules/*` | 有明确业务目标，对应一个业务能力或一个外部契约 | `carbon-engine`（调接口）、`carbon-gateway`（对外网关）、`carbon-connector`（接入配置） |

判断一个包该放哪里，依次问三个问题：

1. **它有业务名词吗？**（连接器 / 环境 / 应用 / 编排定义 / 任务 / 日志）→ 有，就是 `modules`
2. **它能脱离 carbon 单独发版、单测里不启动 Spring 就能跑通吗？** → 能，就是 `framework`
3. **它只是把某个三方库包一层让它符合本公司习惯吗？**（`R<T>`、分页、加解密注解）→ 是，就是 `common`

推论：`carbon-framework-*` **不得依赖任何 `carbon-common-*`**（除 `carbon-common-bom` 管版本）与任何 `carbon-modules/*`；它自定义异常（`DagExecutionException` / `ScriptException` / `RenderException` / `HttpExecuteException`），由上层转成 `ServiceException`。这条是“二方包”与“公司内部工具库”的分界线。

**命名铁律：`dag` 是技术，`flow` 是业务**，两个字不得混用：

| 名称 | 含义 | 关键词 |
|---|---|---|
| `carbon-framework-dag` | 编排编译与执行适配（**它并不知道“流程”存在**，只认节点和边；执行内核用 LiteFlow core） | `DagDsl` / `GraphValidator` / `ElCompiler` / `ChainRegistry` / `StepMapper` / 控制流组件 |
| `carbon-modules/carbon-flow` | 编排业务域（有 `flow_*` 表、有版本与发布） | `Flow` / `FlowVersion` / `FlowRun` / `FlowController` |

依赖方向只有一个：`carbon-flow` → `carbon-framework-dag`，**永不可逆**。

### 9.2 目录骨架

```text
carbon/                                       ← 根目录（Maven 根工程 artifactId: carbon）
├── pom.xml                                   packaging=pom, version=${revision}, flatten-maven-plugin
├── carbon-admin/                             ← 【启动模块】唯一可执行 jar
├── carbon-common/                            ← 【公共模块聚合】
│   ├── pom.xml                               artifactId: carbon-common（packaging=pom）
│   ├── carbon-common-bom/                    ← 依赖版本统一管理
│   ├── carbon-common-core/                   ← R<T>/PageQuery/异常/工具/校验分组
│   ├── carbon-common-web/                    ← BaseController/全局异常/过滤器/i18n
│   ├── carbon-common-mybatis/                ← BaseEntity/BaseMapperPlus/MP 插件/JSON TypeHandler
│   ├── carbon-common-redis/                  ← Redisson 封装/缓存/锁(Lock4j)/限流
│   ├── carbon-common-json/                   ← Jackson 全局配置（Long→String 等）
│   ├── carbon-common-doc/                    ← SpringDoc + javadoc 注释即文档
│   ├── carbon-common-encrypt/                ← @EncryptField 字段级加解密
│   ├── carbon-common-sensitive/              ← @Sensitive 序列化脱敏
│   ├── carbon-common-log/                    ← @Log 操作日志切面
│   ├── carbon-common-sse/                    ← SSE 会话管理（编排步进推送）
│   └── carbon-common-idempotent/             ← @RepeatSubmit 幂等
├── carbon-framework/                         ← 【自研二方包聚合】业务无关，可整包复用
│   ├── pom.xml                               artifactId: carbon-framework（packaging=pom）
│   ├── carbon-framework-dag/                 ← 编排编译与适配（技术）：DSL 模型 + GraphValidator（含结构化校验）
│   │                                            + ElCompiler（DSL→EL）+ ChainRegistry（注册/广播）+ StepMapper + 控制流组件
│   ├── carbon-framework-script/                ← Groovy 沙箱：编译缓存 + SecureASTCustomizer + 超时中断
│   ├── carbon-framework-expression/            ← 模板渲染 + 表达式求值 + 函数白名单
│   └── carbon-framework-http/                  ← HttpClient5 封装：连接池/精确超时/重试退避/响应体截断
├── carbon-modules/                           ← 【业务域聚合】每个模块 = 一个业务能力
│   ├── pom.xml                               artifactId: carbon-modules（packaging=pom）
│   ├── carbon-system/                        ← 平台设置/字典/操作日志
│   ├── carbon-connector/                     ← 配置域：环境/连接器/接口/鉴权/应用（叶子业务模块）
│   ├── carbon-engine/                        ← 调用流水线（唯一对外调用出口）
│   ├── carbon-gateway/                       ← 对外网关（签名/防重放/限流/OpenAPI）
│   ├── carbon-invokelog/                     ← 调用日志（写侧）+ 看板聚合（读侧）
│   ├── carbon-flow/                          ← 编排业务域：定义/版本/发布/运行记录/SSE + 节点桥接
│   └── carbon-job/                           ← 调度（Quartz + JobHandler 适配层）
├── carbon-extend/                            ← 【独立部署单元聚合】
│   ├── pom.xml
│   └── carbon-monitor-admin/                 ← Spring Boot Admin 监控台（可选部署）
├── carbon-ui/                                ← 前端 Vue3+TS（不参与 Maven 构建）
├── script/
│   ├── sql/carbon.sql                        ← 初始化脚本（DBA 手工执行）
│   ├── sql/carbon_update.sql                 ← 增量补丁
│   └── docker/docker-compose.yml             ← mysql8 + redis7 + admin + monitor
└── docs/DESIGN.md
```

**根 `pom.xml` 要点**（与 RuoYi 一致）

```xml
<groupId>com.carbon</groupId>
<artifactId>carbon</artifactId>
<version>${revision}</version>
<packaging>pom</packaging>
<properties>
    <revision>1.0.0</revision>
    <java.version>21</java.version>
    <spring-boot.version>3.5.x</spring-boot.version>
    <mybatis-plus.version>3.5.x</mybatis-plus.version>
    <satoken.version/>  <!-- 不引入 -->
    <groovy.version>4.0.x</groovy.version>  <!-- 硬下限 4.0.21（JDK 21 兼容），禁止回落 3.x -->
    <lombok.version>1.18.x</lombok.version>  <!-- 由 BOM 管理；下限 1.18.30（JDK 21） -->
    <flatten-maven-plugin.version>1.6.0</flatten-maven-plugin.version>
</properties>
<modules>
    <module>carbon-admin</module>
    <module>carbon-common</module>
    <module>carbon-framework</module>
    <module>carbon-modules</module>
    <module>carbon-extend</module>
</modules>
```

- 版本号单点维护：`${revision}` + `flatten-maven-plugin`（`update` 阶段、`resolveCiFriendliesOnly` 模式）
- 依赖版本集中在 `carbon-common-bom`，业务模块引用时**不写 version**
- 环境 profile 三套（`local` / `dev` / `prod`），注入 `profiles.active`，启动时 `--spring.profiles.active=@profiles.active@`
- 注解处理器：`lombok` + `therapi-runtime-javadoc-scribe`（让 JavaDoc 变成接口文档）+ `spring-boot-configuration-processor`

### 9.3 模块职责与依赖方向

**公共模块**

| 模块 | 职责 | 关键类 |
|---|---|---|
| `carbon-common-bom` | 依赖版本统一管理 | — |
| `carbon-common-core` | 基础域对象/常量/异常/工具/校验分组 | `R<T>`、`PageQuery`、`TableDataInfo<T>`、`ServiceException`、`GlobalConstants`、`StringUtils`、`StreamUtils`、`AddGroup`、`EditGroup` |
| `carbon-common-web` | Web 横切 | `BaseController`、`GlobalExceptionHandler`、`CorsFilter`、`I18nUtils` |
| `carbon-common-mybatis` | ORM 增强 | `BaseEntity`、`BaseMapperPlus<T,V>`、`MybatisPlusConfig`（分页/乐观锁/防全表更新）、`JsonTypeHandler`、`AuditMetaObjectHandler`、`DataPermissionHelper`（预留） |
| `carbon-common-redis` | 缓存与分布式协同 | `RedisUtils`、`RedissonConfig`、`LockTemplate`（Lock4j）、`CaffeineCacheConfig`、`RateLimiterClient` |
| `carbon-common-json` | 序列化 | `JacksonConfig`、`BigNumberSerializer`、`LocalDateTime` 全局格式 |
| `carbon-common-doc` | 接口文档 | `SpringDocConfig`、`OpenApiGroupConfig`（`admin` / `gateway` 两组） |
| `carbon-common-encrypt` | 字段加解密 | `@EncryptField`、`EncryptTypeHandler`、`AesEncryptor`、`EncryptInterceptor` |
| `carbon-common-sensitive` | 序列化脱敏 | `@Sensitive`、`SensitiveStrategy`、`SensitiveJsonSerializer` |
| `carbon-common-log` | 操作日志 | `@Log`、`BusinessType`、`LogAspect`、`SysOperLogEvent` |
| `carbon-common-sse` | SSE 会话 | `SseEmitterManager`、`SseMessageDto` |
| `carbon-common-idempotent` | 幂等 | `@RepeatSubmit`、`RepeatSubmitAspect`、`RepeatSubmitInterceptor` |

**自研二方包（`carbon-framework-*`，业务无关）**

| 模块 | 职责 | 关键类 |
|---|---|---|
| `carbon-framework-dag` | 编排编译与执行适配：模型、校验、EL 编译、chain 注册、步骤映射、控制流组件。**不含持久化 / 不含 HTTP / 不含“连接器”概念** | `dsl/DagDsl`、`dsl/NodeDef`（`sealed interface`）、`dsl/node/{StartNode,EndNode,CallNode,CondNode,LoopNode,ForeachNode,ScriptNode,DelayNode}`（`record`）、`dsl/EdgeDef`、`dsl/NodeType`、`validator/GraphValidator`（含结构化校验）、`validator/ValidationError`、`compiler/ElCompiler`、`registry/ChainRegistry`、`registry/ChainReloadListener`（Redis pub/sub）、`step/StepMapper`、`context/FlowContext`、`cmp/AbstractDagNodeCmp`、`cmp/{StartCmp,EndCmp,ConditionCmp,LoopCmp,LoopGuardCmp,ForeachCmp,DelayCmp}`、`spi/DagRunListener`、`error/DagNodeException` |
| `carbon-framework-script` | Groovy 沙箱：白名单、编译缓存、超时中断 | `GroovySandbox`、`SandboxCompiler`（SecureASTCustomizer + ImportCustomizer）、`ScriptContext`、`ScriptCompileCache`、`ScriptTimeoutGuard`、`error/ScriptException` |
| `carbon-framework-expression` | 模板渲染壳 + 表达式求值核（两者严格分离，见 §4.2） | `TemplateRenderer`、`TemplateScanner`（引号感知扫描器）、`VarRef`（变量引用元数据，供表单生成/校验）、`ExpressionEvaluator`（接口）、`AviatorEvaluatorAdapter`（默认实现）、`SimpleExpressionEvaluator`（兜底实现）、`BuiltinFunctions`（函数白名单）、`PathReader`（`a.b[0].c` 取值）、`RenderContext`、`error/RenderException` |
| `carbon-framework-http` | HTTP 传输层 | `HttpExecutor`、`HttpRequestSpec`、`HttpResult`（status/headers/body/耗时）、`RetryPolicy`、`Backoff`、`ResponseTruncator`、`error/HttpExecuteException`；底层 Apache HttpClient 5，**必须显式 `disableAutomaticRetries()` + `disableRedirectHandling()`**（两者的默认值都与本平台策略相反） |

**业务模块（`carbon-modules/*`，有明确业务目的）**

| 模块 | 职责 | 关键类 / 关键表 |
|---|---|---|
| `carbon-system` | 平台设置、字典、操作日志（**不含用户/角色/菜单**） | `SysConfig`、`SysDictType`、`SysDictData`、`SysOperLog` + Controller/Service/Mapper |
| `carbon-connector` | 接入配置域 CRUD（**叶子业务模块**） | `Env`、`Connector`、`ConnectorOperation`、`ConnectorAuth`、`App`；`ConnectorController`、`OperationController`、`EnvController`、`AppController`；对外暴露 `ConnectorConfigProvider`（供 engine 只读） |
| `carbon-engine` | 调用流水线，**唯一对外调用出口**；含平台试调入口 | `controller/InvokeDebugController`、`core/InvokeService`、`core/InvokeContext`、`core/InvokeResult`、`auth/AuthResolver`、`auth/TokenProvider`、`auth/OAuth2ClientCredentialsProvider`、`auth/LoginTokenProvider`、`extract/ResponseExtractor`、`mask/MaskingUtils`、`config/EngineAutoConfiguration`（装配 `framework-expression` / `framework-http` / `framework-script`） |
| `carbon-gateway` | 对外网关 | `GwController`、`GwFlowController`、`SignatureVerifier`、`NonceReplayGuard`、`IpWhitelistMatcher`、`GwRateLimiter`、`GatewayOpenApiController`、`GwResult<T>`、`GwErrorCode` |
| `carbon-invokelog` | **调用日志与看板**（叶子模块）：写侧（异步落库/分区）+ 读侧（明细查询 + 聚合统计） | `InvokeLog`、`bo/LogQueryBo`、`vo/{InvokeLogVo,OverviewVo,TopSlowVo,FailItemVo}`、`InvokeLogMapper`（明细 + 6 个聚合查询）、`service/{IInvokeLogService,IStatsService}` + impl、`writer/AsyncLogWriter`（有界队列 + 批量落库）、`partition/LogPartitionManager`、`controller/{InvokeLogController,StatsController}` |
| `carbon-flow` | 编排业务域：定义/版本/发布/回滚/运行记录/SSE；把业务能力桥接成组件 | `controller/FlowController`、`FlowRunController`、`domain/{Flow,FlowVersion,FlowRun,FlowStepRun}`、`bo/`、`vo/`、`mapper/`、`service/impl/FlowServiceImpl`、`FlowRunServiceImpl`、`cmp/ConnectorCallCmp`、`cmp/ScriptCmp`、`sse/SseFlowRunListener`（实现 `DagRunListener`）、`config/FlowModuleConfiguration`（注册两个组件 + 订阅 chain 刷新广播） |
| `carbon-job` | 调度 | `JobMeta`、`IJobService`、`handler/JobHandler`（接口）、`handler/FlowJobHandler`、`quartz/FlowQuartzJob`、`quartz/JobSyncService`、`quartz/QuartzConfig` |

> **为什么 `stats` 不单独成模块**：它没有任何自己的表/实体，只在 `invoke_log` 上做 `GROUP BY`，且改日志表索引/分区时必然要同时改聚合 SQL；看板与明细是**同一条排查路径**（发现异常 → 下钻明细）。合到 `carbon-invokelog` 后包内分 `controller/domain/mapper/service/writer/partition` 即可表达读写职责，对外路径 `/api/stats/overview` 与 `/api/logs/**` 均不变。

**依赖方向（必须无环，严格分层）**

```text
L5   carbon-admin         启动类 + 全局配置（Quartz/Caffeine/TraceIdFilter），无业务代码
        │
L4   ├─ carbon-gateway ──► engine（签名校验后调 InvokeService）
     ├─ carbon-flow    ──► engine、framework-dag、framework-script
     └─ carbon-job     ──► engine / flow（触发执行）
        │
L3   carbon-engine     ──► connector（只读配置）、framework-expression、framework-http、framework-script
     carbon-invokelog  被 engine/gateway/flow/job 写入；自身是叶子，只依赖 common-*
        │
L2   carbon-connector  叶子业务模块（不得依赖 engine / gateway / flow）
        │
L1   carbon-common-*   各层均可依赖
L0   carbon-framework-dag / -script / -expression / -http   零 carbon 依赖
```

（`carbon-invokelog` 是**被写入方**：engine / gateway / flow / job 都依赖它写日志，它自己只依赖 `common-*`。）

硬约束：
- **`carbon-framework-*` 不依赖任何 `carbon-*` 模块**（除 `carbon-common-bom` 管版本）→ 这是“二方包”与“内部工具库”的硬边界；异常体系自建，由上层转成 `ServiceException`
- `carbon-framework-dag` **不碰数据库、不碰 HTTP、不知道“连接器”**；需要外部能力时通过组件 SPI / `DagRunListener` 反向注入，实现类放在 `carbon-flow`
- `carbon-connector` 是**叶子业务模块**，**不得依赖** `engine`/`gateway`/`flow`（因此**试调接口 `InvokeDebugController` 放在 `carbon-engine`**，而不在 connector）
- `carbon-invokelog` 是叶子，只依赖 `common-*`；**它不得读 `flow_step_run` / `flow_run` 等业务表**（否则与 `carbon-flow → carbon-invokelog` 构成循环）。因此 `/api/logs/{traceId}` 只返回 `invoke_log` 明细 + 其中可空的 `flow_run_id`，编排步骤由前端再调 `/api/flow-runs/{runId}` 获取
- `carbon-engine` 不依赖 `gateway`/`flow`/`job`——网关与编排都是它的**调用方**
- `carbon-admin` 内**不写业务逻辑**，只有启动类、全局 `@Configuration`（`WebMvcConfig`、`QuartzConfig`、`CaffeineConfig`）、`TraceIdFilter`、`StartupRunner`；各业务模块自带 `XxxAutoConfiguration`

### 9.4 模块内部包规范

**CRUD 类模块**（`carbon-connector` / `carbon-system` / `carbon-flow` / `carbon-job` / `carbon-invokelog`）

```text
src/main/java/com/carbon/connector/
├── controller/
│   ├── ConnectorController.java
│   ├── OperationController.java
│   ├── EnvController.java
│   ├── AppController.java
├── config/ConnectorConfigProviderImpl.java  ← 实现 engine 定义的只读接口
├── domain/
│   ├── Connector.java  ConnectorOperation.java  ConnectorAuth.java  Env.java  App.java
│   ├── bo/ConnectorBo.java  OperationBo.java  DebugReqBo.java …
│   └── vo/ConnectorVo.java  OperationVo.java  DebugRespVo.java …
├── mapper/ConnectorMapper.java  …            ← extends BaseMapperPlus<Connector, ConnectorVo>
├── service/IConnectorService.java  …
│   └── impl/ConnectorServiceImpl.java  …
├── enums/AuthType.java  BodyType.java  HttpMethodEnum.java  NodeType.java
└── constant/ConnectorConstants.java
```

分层规则：`controller → service(接口) → service.impl → mapper`；`domain` 纯数据；`bo` 只做入参（带 `@AutoMapper`），`vo` 只做出参；**禁止 controller 直接调 mapper**。

**能力类模块**（`carbon-engine` / `carbon-gateway`）按能力分层，不套 Bo/Vo：

```text
src/main/java/com/carbon/engine/
├── controller/InvokeDebugController.java     ← 平台试调入口（放 engine，防止 connector 反向依赖）
├── core/InvokeService.java  InvokeContext.java  InvokeResult.java
├── auth/AuthResolver.java  TokenProvider.java
│   ├── OAuth2ClientCredentialsProvider.java  LoginTokenProvider.java
├── extract/ResponseExtractor.java  ExtractRule.java
├── mask/MaskingUtils.java
└── config/EngineAutoConfiguration.java
```

**自研二方包**（`carbon-framework-*`）只按能力分包，**没有** controller/service/mapper/bo/vo 概念：

```text
src/main/java/com/carbon/framework/dag/
├── dsl/{DagDsl,NodeDef,EdgeDef,NodeType}.java
├── dsl/node/{StartNode,EndNode,CallNode,CondNode,LoopNode,ForeachNode,ScriptNode,DelayNode}.java  ← record，NodeDef 的 permits 全集
├── validator/{GraphValidator,ValidationError}.java
├── compiler/ElCompiler.java
├── registry/{ChainRegistry,ChainReloadListener}.java
├── step/{StepMapper,DagStep}.java
├── context/FlowContext.java
├── cmp/{AbstractDagNodeCmp,StartCmp,EndCmp,ConditionCmp,LoopCmp,LoopGuardCmp,ForeachCmp,DelayCmp}.java
├── spi/DagRunListener.java
├── error/DagNodeException.java
└── DagEngineAutoConfiguration.java
```

二方包纪律（评审时逐条看）：

- 不出现 `@RestController` / `@Mapper` / `BaseEntity` / `R<T>` / `ServiceException` / `@EncryptField`
- 不读业务配置（不引 `platform.*`），需要参数就从构造器/方法传进来
- 失败一律抛自己的异常（`DagExecutionException` / `ScriptException` / `RenderException` / `HttpExecuteException`），**不返回 `null` 表示失败**
- 可以引 `spring-context`（只为 `@ConfigurationProperties` 绑定与可选 `@AutoConfiguration`），**核心能力类必须能在不启动 Spring 的单测里跑通**

### 9.5 编码约定（照搬 RuoYi）

| 约定 | 落地方式 |
|---|---|
| 管理端统一响应 | `R<T>`：`code`(200 成功 / 500 失败)、`msg`、`data`；`R.ok()` / `R.fail()` |
| **网关统一响应（对外契约）** | `GwResult<T>`：`code`(语义码)、`message`、`data`、`traceId`、`providerStatus`、`costMs`。<br>**不复用 `R<T>`**——外部契约不能被内部结构调整带崩 |
| 分页 | 入参 `PageQuery`(pageNum/pageSize/orderByColumn/isAsc)，出参 `TableDataInfo<T>`(total/rows) |
| 实体基类 | `extends BaseEntity`（createBy/createTime/updateBy/updateTime/params），无审计需求用 `BaseEntityNoAudit` |
| 主键 | 雪花 ID：`@TableId(type = IdType.ASSIGN_ID) private Long id;`，并加 `@JsonSerialize(using = ToStringSerializer.class)` 防前端精度丢失 |
| 逻辑删除 | `@TableLogic private String delFlag;`，DB 默认 `'0'`，`'1'` 已删除 |
| 实体转换 | MapStruct-Plus：Bo/Vo 上 `@AutoMapper(target = Connector.class)`，用 `MapstructUtils.convert(bo, Connector.class)` |
| Mapper | `extends BaseMapperPlus<Connector, ConnectorVo>`，直接获得 `selectVoList` / `selectVoPage` |
| 校验 | `@Validated(AddGroup.class)` / `@Validated(EditGroup.class)` 分组 |
| Service | 接口 `IXxxService` + `impl/XxxServiceImpl extends ServiceImpl<XxxMapper, Xxx>` |
| Controller | `extends BaseController`（提供 `toAjax(boolean)`）；路径 `/{module}/{subdomain}`，如 `/connector/env` |
| 操作日志 | `@Log(title = "连接器", businessType = BusinessType.INSERT)` |
| 幂等 | `@RepeatSubmit(interval = 3000)` 用于保存/发布/重置密钥 |
| 限流 | `@RateLimiter` 用于试调接口 |
| 字段加密 | `@EncryptField` 打在 `ConnectorAuth.config` 密文槽位与 `App.appSecret` |
| 响应脱敏 | `@Sensitive(strategy = SensitiveStrategy.PASSWORD)` |
| 接口文档 | **只写 JavaDoc**；`carbon-common-doc` + therapi 自动生成，禁止堆 `@Tag`/`@Operation` 注解 |
| 异常 | 抛 `ServiceException("连接器编码已存在")`，由 `GlobalExceptionHandler` 统一转 `R.fail` |
| 配置读取 | `@ConfigurationProperties` + `spring-boot-configuration-processor` 生成 IDE 元数据 |

### 9.6 与 RuoYi 的差异清单

| RuoYi 5.X 模块 | 本平台 | 原因 / 迁移路径 |
|---|---|---|
| `ruoyi-common-satoken`、`-security` | **不建** | 平台不做登录。若后续要补：新建 `carbon-common-security`（引 Sa-Token）+ 在 `carbon-admin` 注册 `SaInterceptor`，两处改动即可 |
| `ruoyi-common-tenant` | **不建** | 无多租户。若后续要补：引 Sa-Token + MP 租户插件 + 全域 `tenant_id`，属重大改造 |
| `ruoyi-modules/ruoyi-system`（用户/角色/菜单/部门/岗位） | 仅保留 `carbon-system` 的**平台设置 + 字典 + 操作日志** | 无登录即无 RBAC |
| `ruoyi-common-social` / `-sms` / `-mail` / `-oss` / `-websocket` / `-translation` | **不建** | 本期无对应场景 |
| `ruoyi-common-excel` | **不建**（P3 要导出日志时再加） | 避免空壳模块 |
| `ruoyi-modules/ruoyi-generator` | **不建** | 无代码生成需求 |
| `ruoyi-modules/ruoyi-workflow`（warm-flow） | 用 `carbon-framework-dag`（引擎）+ `carbon-flow`（业务）替代 | 我们的编排是**数据流**而非审批流 |
| SnailJob（`ruoyi-extend/ruoyi-snailjob-server`）+ `ruoyi-common-job` | `carbon-modules/carbon-job`：Quartz JDBC 集群 + `JobHandler` 适配层 | 少一个部署单元；换 SnailJob 时只需新增 `SnailJobHandler implements JobHandler`，业务无感 |
| `ruoyi-extend/ruoyi-monitor-admin` | **照搬**为 `carbon-extend/carbon-monitor-admin` | 运维监控有用 |
| 雪花 ID / `R<T>` / Bo-Vo / `BaseMapperPlus` / `MapStruct-Plus` / 注解式能力 | **照搬** | 生态熟悉，可直接复用 RuoYi 代码片段 |

> 说明：曾经考虑“预留 `carbon-common-satoken` 空模块以便日后开登录”，最终**放弃**——死模块会误导后续维护者，且开关打开后没有用户/角色表仍不可用。改为在本文档写清迁移路径。

**`carbon-gateway` 的降级合并条件**（不算默认方案，只为小团队留口子）：若对外暴露接口 ≤ 3 个且团队 ≤ 2 人，允许把 `GwController` / `SignatureVerifier` / `NonceReplayGuard` 并入 `carbon-connector` 的 `gw/` 包，依赖方向不变（controller 层直接注入 `InvokeService`，connector 主体仍不依赖 engine）。**一旦出现第二个需要签名的外部入口、或引入 IP 白名单/限流策略，必须重新拆出独立模块。**

### 9.7 前端 `carbon-ui` 目录规范

对齐 `JavaLionLi/plus-ui` 的目录约定（Vue 3.5 + TS + Vite + Element Plus + Pinia + UnoCSS）：

```text
carbon-ui/
├── package.json
├── vite.config.ts
├── vite/plugins/                      ← auto-import / svg-icon / compression / setup-extend
├── uno.config.ts
├── tsconfig.json
├── .env.development / .env.production
└── src/
    ├── api/                           ← 与后端 carbon-modules 一一对应
    │   ├── connector/{connector,operation,env,auth,app}.ts
    │   ├── gateway/gateway.ts
    │   ├── flow/{flow,flowRun}.ts
    │   ├── job/job.ts
    │   ├── invokelog/{log,dashboard}.ts
    │   └── system/{config,dict}.ts
    ├── assets/{icons,images,logo,styles}/
    ├── components/                    ← 通用组件
    │   ├── Pagination/  DictTag/  Editor/  JsonViewer/  RightToolbar/  FileUpload/
    │   └── biz/                       ← 业务组件
    │       ├── RequestBuilder/        ← 入参表单（按 {{input.x}} 自动生成）
    │       ├── ResponsePanel/         ← Pretty/Raw/Headers/Timing 四视图
    │       ├── ScriptEditor/          ← Monaco Groovy
    │       └── ExtractTable/          ← JSONPath 抽取表格 + 实时预览
    ├── directive/                     ← v-copy 等
    ├── hooks/                         ← useTable / useDialog / useSse / useCurl
    ├── lang/{zh_CN,en_US}/
    ├── layout/                        ← 侧边栏 / 顶栏
    ├── plugins/{download,modal,tab,cache}/
    ├── router/                        ← index.ts + 按模块拆分的路由文件
    ├── store/modules/{app,connector,env,settings}.ts   ← Pinia
    ├── types/{api,global}.d.ts
    ├── utils/{request.ts,sign.ts,mask.ts,dict.ts}
    └── views/
        ├── dashboard/index.vue
        ├── connector/{index.vue,edit.vue,operation.vue}
        ├── env/index.vue
        ├── app/index.vue
        ├── log/{index.vue,detail.vue}
        ├── docs/index.vue
        ├── flow/{index.vue,design.vue,runs.vue}
        ├── job/index.vue
        └── settings/index.vue
```

前端技术补充：Monaco Editor 替代 plus-ui 的 highlight.js（脚本与报文编辑需要）；ECharts 6 做看板；LogicFlow 放在 `components/biz/FlowCanvas/`。

### 9.8 构建与部署

```bash
# 后端（跳过前端）
mvn -pl carbon-admin -am clean package -Pprod -DskipTests

# 前端 → 复制到 carbon-admin 静态目录（package 阶段由 maven-resources-plugin 完成）
cd carbon-ui && pnpm install && pnpm build:prod

# 一键环境
docker compose -f script/docker/docker-compose.yml up -d
```

**部署单元**

| 单元 | 说明 |
|---|---|
| `carbon-admin.jar` | 主服务，内含 `carbon-ui` 构建产物（`src/main/resources/static`） |
| `carbon-monitor-admin.jar` | 可选，Spring Boot Admin 监控台 |
| MySQL 8 | 元数据 + 调用日志（日志表按月分区） |
| Redis 7 | Token 缓存 / 防重放 nonce / 限流 / 并发锁 / SSE 会话 |

**启动命令**

```bash
java -jar carbon-admin.jar \
  --spring.profiles.active=prod \
  --CARBON_MASTER_KEY=<base64-32bytes>
```

### 9.9 关键技术选型细节

**运行时与基础设施**

- Spring Boot 3.5.x / **Java 21（LTS）**。与 RuoYi 5.6.2 的 `common-*` 直接搬运不冲突（那些代码无 17-only 依赖）。选 21 的实际收益只有一项，但很实在：`sealed interface` + `record` 让 §4.7 的 `NodeDef` 变成密封类型族，`switch` 穷尽检查把“漏写一个节点类型”从运行时报错提前到编译期
- **JDK 21 的三个连带约束**：① `groovy.version ≥ 4.0.21`（Groovy 3.x 在 21 上有已知问题）；② `lombok ≥ 1.18.30`；③ **不开启虚拟线程**（`spring.threads.virtual.enabled` 保持默认 `false`）——瓶颈是 `maxTotal=500` 的连接池而非线程数，且 JDK 21 上 `synchronized` 会 pin 载体线程（JEP 491 到 JDK 24 才修），HttpClient5 内部有 `synchronized`，开了可能更慢。真要开，先拿 P1 的压测对比，再单独用专用 executor 试
- **回退成本**：若 P0 发现 Groovy/某依赖在 21 上翻车，回 17 的动作 = 改根 pom 的 `<java.version>` + 去掉 `sealed`/`record` 语法，不涉及设计层
- **自研二方包与模块的对应关系**：模板/表达式 → `carbon-framework-expression`；HTTP 传输 → `carbon-framework-http`；Groovy 沙箱 → `carbon-framework-script`；编排编译与适配 → `carbon-framework-dag`（内核为 `liteflow-core 2.16.x`，需锁定版本）。`carbon-engine` 只做“拼装”：取配置 → 渲染 → 鉴权 → 发请求 → 抽取 → 写日志
- JSONPath：Jayway 2.9，禁用 `$..` 深扫（配置时校验拦截，防大报文 OOM）
- 数据库迁移：Flyway（`carbon-admin/src/main/resources/db/migration`），同时保留 `script/sql/carbon.sql` 供 DBA 手工初始化
- 缓存：Caffeine（本地，L1）+ Redisson（分布式，L2）
- 分布式锁：Lock4j（底层 Redisson），用于 Token single-flight、flow 并发策略、调度 Disallow
- 编排步进推送：`carbon-common-sse`
- 调度：Quartz JDBC 集群 + `JobHandler` 抽象（当前 `FlowJobHandler`；换 SnailJob 时新增一个实现即可）

**HTTP 客户端：Apache HttpClient 5（定稿）**

- 选它的三条理由：
  1. `PoolingHttpClientConnectionManager` 的 `maxTotal` + `setDefaultMaxPerRoute` + `connectionRequestTimeout` 能在池满时**快速失败**（500 QPS 基线下行为可预期）。OkHttp 的 `maxRequestsPerHost` 是**并发数**而非池大小，突发流量下只能硬砍或放任
  2. 纯 Java，不带入 kotlin-stdlib + okio（OkHttp 4/5 的额外运行时约 2MB）
  3. 与 Spring Boot 3.5 同族，版本由 BOM 管理；API 的冗长被 `carbon-framework-http` 完全封装，业务侧只见 `HttpRequestSpec` / `HttpResult`
- **两个必须显式关闭的默认值**（都与本平台策略相反）：`disableAutomaticRetries()`（它默认会对 GET 自动重试，违反「重试默认 0 次」）、`disableRedirectHandling()`（重定向可绕过 §7 的 SSRF 校验）
- 已知缺口：HC5 **没有「整次调用总时长」**（`responseTimeout` 不含 body 流式读取阶段）。单次调用由 `InvokeService` 的调用总时长 watchdog 兜底；编排场景由 LiteFlow 的 `.maxWaitMilliseconds` 中断节点，不另建 watchdog
- **改用 OkHttp 的判据**：若 P1 阶段团队反馈 HC5 的 API 拖慢开发，只替换 `carbon-framework-http` 内部实现，`HttpRequestSpec`/`HttpResult` 契约不变、业务零改动。届时必须配 `retryOnConnectionFailure(false)` + `followRedirects(false)` + `ConnectionPool` 上限

**表达式：Aviator 作为求值核（带准入门禁）**

- 壳自研、核可插拔（详见 §4.2）。引入 Aviator 的原因：手写布尔表达式求值器（优先级、类型转换、短路、括号、嵌套）约 400–600 行且边界 bug 密集，是全部自研项里性价比最差的一块
- **不选** SpEL / OGNL（`T(java.lang.Runtime)` 等可触达系统能力，历史多次 RCE）
- **不用 Aviator 替换 Groovy**：AviatorScript 生态与示例稀少、实施人员学习成本高，且脚本在整条链路中不是性能瓶颈（HTTP 往返才是）
- 准入：P0 安全 spike 必须通过 §4.2 列出的 6 条逃逸用例，否则回退 `SimpleExpressionEvaluator`
- **代价（必须向使用者说明）**：平台内存在两套表达式语法——`{{}}` 与 `CONDITION` 用 Aviator 语法，前后置脚本用 Groovy 语法。页面提示与用户文档需明确分割

**编排引擎：封装 LiteFlow core（v1.6 定案，v1.5 的判断已推翻）**

**先记录被推翻的四条错判**（保留原文，避免后续重复讨论）：

| v1.5 的判断 | 实测事实（liteflow.cc，v2.16.x） |
|---|---|
| ①「节点 = Spring Bean，编译期注册」 | 判错。2.11.1+ 起 `LiteFlowNodeBuilder.createCommonNode()/createScriptNode()` 可运行期建节点，`LiteFlowChainELBuilder.createChain().setChainName(id).setEL(el).build()` 可运行期建/改建链，`ELBus.then/when/switchOpt/ifOpt/forOpt/whileOpt/iteratorOpt/catchException` 可在代码里拼 EL，`LiteflowMetaOperator.reloadOneChain(chainId, el)` 可单链热替换；节点级配置用 `.data(json)` 挂载，组件里 `getCmpData(Xxx.class)` 反序列化 |
| ②「任意有向图无法表达」 | 夸大。EL 是**树**，但**菱形汇聚可表达**（汇聚点作父级的后继即可，官方「复杂编排例子」即如此）。真正表达不了的是**回边（GOTO 回跳）与跨子树连边** |
| ③「没有版本/回滚」 | 夸大。无内置版本，但 `chainId = flow:{id}:v{n}` + `reloadOneChain` 即得不可变版本；发布/回滚本来就在 `carbon-flow` 里。真正的成本是**chain 存在 JVM 内存，多实例必须逐节点刷新** |
| ④「节点级语义仍需自建」 | 大部分不成立。开箱即有 `CmpStep`（nodeId/耗时/isSuccess/异常/start-end/threadName/`setStepData` 自定义步信息）、`onSuccess`/`onError`/`afterProcess` 回调、`.retry(n)` / `.retry(n, 异常类)`、`node.maxWaitSeconds(n)` / `.maxWaitMilliseconds(n)` 节点级超时、`WHEN(...).ignoreError(true)` |

**为什么仍然选它**：自研执行器约 1500–2000 行，且是全篇最容易出隐性 bug 的部分（并发、超时中断、状态机、循环内的变量隔离）；用 LiteFlow 只需：

| 要做的事 | 行数 |
|---|---|
| `ElCompiler`：结构化 DSL → EL（递归生成，非法结构直接抛错，**不做反解**） | ~250 |
| `StepMapper`：`CmpStep` 队列 → `flow_step_run`（含 `SKIPPED` 推断、循环内 `execIndex`） | ~200 |
| `ChainRegistry`：chain 注册/替换 + Redis pub/sub 广播 + 启动时预热已发布版本 | ~200 |
| 控制流组件（`Start/End/Condition/Loop/LoopGuard/Foreach/Delay`）+ `AbstractDagNodeCmp` | ~300（自研也要写类似量） |
| **合计增量** | **约 650–800 行** |

**代价（必须记账，不是免费的）**

1. **表达力收敛**：画布必须是**结构化树形图**——禁回边、禁交叉边、分支必须汇聚。`onError` 从三档降为两档（`STOP` / `CONTINUE`），`GOTO` 由 `LOOP` 节点替代（`LOOP` 对运营更可读、LoopGuard 天然防死循环）。
2. **状态在内存**：chain 在 JVM 内存，多实例发布必须广播 reload（Redis pub/sub），多一条「某节点没刷到」的故障路径 → 用「启动时全量预热 + 发布后立刻回读校验 + 暴露 `/actuator/carbon-chains` 查看各实例已注册 chain」兜底。
3. **跟版本**：锁定 `liteflow-core 2.16.x`，升级需跑全量回归；不引入其 rule-db / 配置源插件（规则唯一真源仍是我们的 `flow_version` 表）。
4. **两套语法的心理负担**：EL 是框架内部产物，**不暴露给用户**，用户只见画布与 `{{}}`/`CONDITION` 语法。

**不采用的部分**：不用它的规则文件/配置源/Nacos/ZK 插件、不用决策路由、不用 `execute2RespWithEL`（每版本一个具名 chain，才能在多实例上预热与核对）。

**反悔条件**：若后续确实需要「失败跳转到任意远处节点」这类非结构化控制流，则改用自研 `DagExecutor`——届时要重写的只有 `ChainRegistry` 与组件骨架，`ElCompiler` 之外的 DSL/校验/步骤映射可复用。


## 10. 测试与验收

### 10.1 单元测试

`carbon-framework-*` 的单测**不启动 Spring 上下文**（这是二方包合格线），其余模块用 `@SpringBootTest` 分片。

| 目标 | 用例 |
|---|---|
| `ExpressionEvaluator` | 嵌套路径、缺失返回 null、`??` 默认值、函数白名单、非法语法报错（默认实现与兜底实现跑同一套用例） |
| `SignatureVerifier` | 正确签名通过；篡改 body/path/timestamp 失败；时间窗外失败；nonce 重放失败 |
| `GroovySandbox` | 逃逸用例全部拦截：`new File('/etc/passwd')`、`Runtime.getRuntime().exec`、`System.exit(0)`、`Class.forName`、死循环超时、`Thread.sleep(99999)` |
| `GraphValidator` | 无 START / 多 START / 孤立节点 / 引用不存在 operation / CONDITION 分支缺失 / 节点超限 / **回边 / 交叉边 / 分支未汇聚**（后三条必须报出具体节点对） |
| `ElCompiler` | 逐节点类型断言 DSL→EL 产物：`CONDITION→IF`、`LOOP→WHILE(LoopGuard)`、`FOREACH→ITERATOR`、嵌套分支括号正确；非法结构抛带节点 id 的错误 |
| `StepMapper` | 未命中分支推导为 `SKIPPED`；循环内多次执行按 `execIndex` 展开；失败步带异常 |
| `ResponseExtractor` | 命中、未命中带 default、类型转换、数组路径 |
| `RetryPolicy` | 仅白名单状态码重试、退避序列、非幂等 POST 默认不重试 |
| `MaskingUtils` | 正则命中、Header 前缀保留、超长截断 |
| `AviatorEvaluatorAdapter` | 逃逸用例全部拦截（与 `GroovySandbox` 同六条）+ 算术/三元/`??` 默认值/白名单函数调用结果正确 |
| `TemplateRenderer` | §4.2.1 八条规则逐条覆盖：类型透传（数字不变成字符串）、嵌入字符串、含 `"` 与 `\` 的转义、path/query 自动 encode、缺失变量报错、`}}` 在表达式内、变量引用枚举 |

### 10.2 集成测试
- Testcontainers：MySQL 8 + Redis 7
- WireMock 模拟三方：200 正常、401（验证 token 刷新）、429（验证重试）、5xx、超时、超大响应体、非 JSON 响应
- Quartz：双实例模拟（两个 `SchedulerFactoryBean` 指向同一 DB）验证同一时刻只触发一次
- chain 广播：双实例模拟发布 → 两边 `ChainRegistry` 均注册新 `chainId`；只广播不预热时旧实例跑旧版本（验证平滑刷新语义）
- **运行环境基线**：CI 与容器镜像统一 `eclipse-temurin:21-jre`；`mvn -v` / `java -version` 断言为 21；`groovy-all` 版本断言 ≥ 4.0.21（建一个 P0 的 `BuildBaselineTest` 直接反射读取 `GroovySystem.getVersion()`）
- **Groovy 在 JDK 21 下的沙箱回归**：§10.1 的 `GroovySandbox` 六条逃逸用例必须在 21 上全绿（这是升级 JDK 的最大不确定项，放 P0 提前探）

### 10.3 端到端验收场景

1. **连接器 + 网关闭环**：建 ApiKey 连接器 → 建接口 → 试调成功 → 创建应用 → 用 AppKey 签名调 `/gw/{c}/{o}` 成功；篡改 sign → `SIGN_INVALID`；重放 nonce → `SIGN_INVALID`；超 5 分钟时间戳 → `SIGN_INVALID`；慢接口 → `PROVIDER_TIMEOUT`
2. **OAuth2 token 生命周期**：首次调用取 token → 第二次命中 Redis（三方 token 接口只被调用 1 次）→ 三方返回 401 → 自动刷新并重试一次后成功
3. **脚本加工**：前置脚本改 header → 三方收到；后置脚本改写 body → 抽取结果变化；脚本抛异常 → `SCRIPT_ERROR` 且日志含脱敏堆栈
4. **编排**：「列表 → FOREACH → CONDITION 分支 → 汇总」全链路；画布 SSE 步进可见；某节点失败配 `CONTINUE` → run 状态 `PARTIAL`；配 `STOP` → `FAILED` 且后续节点 SKIPPED
5. **编排版本**：发布 v1 → 改草稿 → 调度仍跑 v1 → 发布 v2 → 调度跑 v2 → 回滚到 v1 生效；双实例下两边都能跑 v2
6. **结构化校验**：画布上拉一条回边 → 保存被拒并指出具体节点对；画交叉边 → 同样被拒；合法菱形汇聚 → 保存成功且执行走对分支
7. **循环**：`LOOP(maxTimes=3)` 内节点失败 → 重试至成功或达上限；达上限后整体 `FAILED` 且 `flow_step_run` 有 3 条 `execIndex` 递升记录
8. **调度集群**：cron 每 10s，双实例部署下同一时刻仅执行一次；「立即执行」与 cron 不并发冲突（`DISALLOW`）
9. **日志与脱敏**：>64KB 响应体截断并标记；`Authorization` 显示为 `Bearer ****`；traceId 可串起编排 + 单次调用
10. **SSRF**：开启 `deny-cidr` 后配置 `http://127.0.0.1:8080` 被拒绝；未开启时允许（内网接入场景）

### 10.4 性能基线
- 网关单实例 ≥ 500 QPS（简单透传，P99 < 100ms，不含三方耗时）
- 编排 10 节点串行 + 100 次循环 < 5s（本地三方 mock）
- 日志写入不阻塞主链路（异步队列，队列满时丢弃 WARN 并计数）

---

## 11. 默认参数与配置项

```yaml
# profile：--spring.profiles.active=@profiles.active@（local/dev/prod）
# 配置分模块存放：carbon-admin/src/main/resources/application.yml（公共）
#             + application-{profile}.yml（环境差异）
server:
  port: 8080
  servlet:
    context-path: /

platform:
  master-key: ${CARBON_MASTER_KEY}        # 必填
  auth:
    enabled: false                        # 平台不做登录；开启前需先补 carbon-common-security + 用户/角色表
  script:
    enabled: true
    default-timeout-ms: 2000
    max-script-bytes: 65536
  egress:
    deny-cidr-enabled: false
    deny-cidrs: [127.0.0.0/8, 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, 169.254.0.0/16]
  log:
    retention-days: 30
    body-max-bytes: 65536
    async-queue-capacity: 10000
  gateway:
    sign-time-window-sec: 300
    nonce-ttl-sec: 600
    default-qps: 50
  http:
    connect-timeout-ms: 3000
    read-timeout-ms: 10000
    max-read-timeout-ms: 120000
    max-response-bytes: 10485760
    pool-max-total: 500
    pool-per-route: 100
  render:
    evaluator: aviator                     # aviator | simple（simple = 退路：自研路径取值 + ?? + 白名单函数）
    placeholder-cache-size: 2000           # {{}} 扫描结果缓存条目数（key: operationId + hash(template)）
  flow:
    default-timeout-ms: 60000
    max-nodes: 500
    max-foreach-concurrency: 10
    chain-reload-topic: carbon:flow:chain:reload   # Redis pub/sub，多实例 chain 刷新广播
    chain-preheat-on-startup: true                # 启动时注册所有已发布版本，避免首次调用抖动
  token:
    refresh-ahead-sec: 60
    lock-ttl-ms: 10000
```

---

## 12. 里程碑

| 阶段 | 周期 | 交付内容 | 验收标准 |
|---|---|---|---|
| **P0 工程骨架** | 3–5 天 | 根 `pom.xml`（`${revision}` + flatten + bom + 三套 profile）、12 个 `carbon-common-*` 骨架、**4 个 `carbon-framework-*` 骨架（含能跑通的空实现 + 单测）**、7 个 `carbon-modules` 骨架、`carbon-admin` 启动类 + 全局配置、`script/sql/carbon.sql`（同时作为 Flyway `V1__init.sql`）、`script/docker/docker-compose.yml`、`carbon-ui` Vite 脚手架、**Aviator 安全 spike（4h）**、**JDK 21 + Groovy 4.0.x 兼容验证（含 §10.1 沙箱六条用例）** | `mvn -pl carbon-admin -am package` 通过；`docker compose up` 后 `/actuator/health` 返回 200；`mvn -pl carbon-framework/carbon-framework-dag test` 不启 Spring 即可通过；**Aviator spike 通过 §10.1 的 6 条逃逸用例**（不过则 `platform.render.evaluator=simple` 走兜底核，不影响 P1）；**`java -version` 为 21 且 Groovy 沙箱六条用例全绿** |
| **P1 连接器 + 网关** | 2–3 周 | 环境/连接器/接口/应用 CRUD、5+1 种鉴权、模板渲染、JSONPath 抽取、Groovy 沙箱、平台试调、网关签名与限流、调用日志与脱敏、OpenAPI 输出 | §10.3 场景 1/2/3/7 通过，可独立上线供业务方接入 |
| **P2 编排** | 3–4 周 | `carbon-framework-dag`（DSL + GraphValidator 含结构化校验 + ElCompiler + ChainRegistry + StepMapper + 7 个控制流组件，内核为 `liteflow-core 2.16.x`）+ `carbon-flow`（定义/版本/发布 + `CONNECTOR_CALL`/`SCRIPT` 两个组件 + LogicFlow 画布 + SSE 试跑 + 运行历史）+ **多实例 chain 刷新广播** | §10.3 场景 4/5/6/7 通过 |
| **P3 调度与观测** | 2 周 | Quartz 集群 + `JobHandler` 适配层 + 任务管理页 + 立即执行 + 统计看板（在 `carbon-invokelog` 内实现）+ 失败告警 Webhook + 日志分区归档 + 平台设置页 | §10.3 场景 8/9 通过 |

---

## 13. 明确不做（Out of Scope）

- 平台用户登录 / 多租户 / RBAC（由反向代理兜底；开启路径见 §9.6）
- Postman 式断言、Collection 批量运行、数据驱动测试（用编排的 `CONDITION` 节点替代）
- 文件上传接口类型（multipart）
- WebSocket / GraphQL 接入
- MQ（Kafka/RocketMQ）触发与消费
- 接收三方回调（预留 `POST /gw/hooks/{code}` 扩展点，不做实现）
- 编排里的非结构化控制流：`GOTO` 任意跳转、回边、跨子树连线（需要时用 `LOOP` + `CONDITION`；真的绕不过再走 §9.9 的「反悔条件」换自研执行器）
- 熔断降级（Resilience4j 待 P3 后按实际故障率评估）
- 密钥轮换（`v2:` 前缀已预留）

---

## 14. 后续可扩展方向

1. **登录与权限**：按 §9.6 添回 `carbon-common-security`（Sa-Token）+ `sys_user/sys_role/sys_menu` 三表，是当前架构唯一预留的“开关”
2. **Mock 服务**：为未就绪的三方提供 Mock 响应，接口级一键切换
3. **连接器市场**：连接器配置导出/导入（YAML 包），跨环境迁移
4. **并行编排**：`PARALLEL` 节点 + join 语义（底层 `WHEN` 已具备，缺画布与汇聚校验）
5. **数据同步**：编排后接「写入本地库 / 推送 MQ」节点类型
6. **配额与计费**：应用维度日调用量配额、账单导出
7. **调度中心替换**：实现新的 `JobHandler` 适配层，把 Quartz 换成 SnailJob/XXL-Job
