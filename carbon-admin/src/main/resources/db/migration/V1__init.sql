-- =====================================================================
-- carbon 连接器平台 · 初始化脚本
-- 与 docs/02-数据模型.md 保持一致；本文件同时作为 Flyway V1 迁移的母本
-- 主键：应用侧雪花 ID（MyBatis-Plus assign_id），DDL 不用 AUTO_INCREMENT
-- 逻辑删除：deleted（0 存在 / 1 已删）
-- =====================================================================

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- 主键统一由应用侧雪花 ID 生成（MyBatis-Plus assign_id），DDL 里不用 AUTO_INCREMENT
-- 逻辑删除统一用 deleted（0 存在 / 1 已删），审计字段 created_at / updated_at

-- 环境：提供 {{env.xxx}} 变量，连接器 base_url 与 path 中使用
CREATE TABLE env (
  id              BIGINT PRIMARY KEY,
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

-- 连接器：一个三方系统 = 一个连接器
CREATE TABLE connector (
  id              BIGINT PRIMARY KEY,
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

-- 连接器鉴权：与连接器 1:1，独立表便于整列加密与按类型扩展
CREATE TABLE connector_auth (
  id          BIGINT PRIMARY KEY,
  connector_id BIGINT      NOT NULL,
  auth_type   VARCHAR(32)  NOT NULL COMMENT 'none|api_key|basic|bearer|oauth2_cc|login_token',
  config      JSON         NOT NULL COMMENT '结构随 auth_type 变化；敏感字段单独加密，见 §7',
  status      TINYINT      NOT NULL DEFAULT 1,
  created_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at  DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_auth_connector (connector_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 接口（Operation）：连接器下的一个可调用能力
CREATE TABLE connector_operation (
  id                BIGINT PRIMARY KEY,
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

-- 调用方应用：业务系统接入网关的凭证
CREATE TABLE app (
  id                 BIGINT PRIMARY KEY,
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

CREATE TABLE flow (
  id                BIGINT PRIMARY KEY,
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
  id           BIGINT PRIMARY KEY,
  flow_id      BIGINT      NOT NULL,
  version      INT         NOT NULL,
  dsl          JSON        NOT NULL,
  published_by VARCHAR(64) NULL,
  published_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  UNIQUE KEY uk_flow_version (flow_id, version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE flow_run (
  id           BIGINT PRIMARY KEY,
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
  id               BIGINT PRIMARY KEY,
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

CREATE TABLE job (
  id              BIGINT PRIMARY KEY,
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
  PARTITION p_init VALUES LESS THAN ('2025-01-01'),
  -- p_max 是兜底分区：没有它，超出最后一个边界的写入会直接报
  -- "Table has no partition for value"（2026 年建表、当天写入就踩）
  -- 定时任务每月 ADD PARTITION 后 REORGANIZE p_max，永远保留一个 MAXVALUE 兜底
  PARTITION p_max VALUES LESS THAN (MAXVALUE)
);


SET FOREIGN_KEY_CHECKS = 1;

-- 内置三个环境：{{env.host}} 等变量由这里提供；真实凭证请改 vars 或外部覆盖
INSERT INTO env (id, code, name, base_url_prefix, vars, is_default, remark, deleted) VALUES
  (1000000000000000001, 'dev',  '开发', NULL, '{"host":"localhost:9000"}', 1, '默认环境；host 不带协议，模板里自己写 https://', 0),
  (1000000000000000002, 'test', '测试', NULL, '{"host":"test.internal"}', 0, NULL, 0),
  (1000000000000000003, 'prod', '生产', NULL, '{"host":"api.example.com"}', 0, '需替换为真实域名', 0);
