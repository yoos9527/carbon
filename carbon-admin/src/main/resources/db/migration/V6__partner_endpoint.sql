-- 四层模型：合作方 → 凭证 / 接入点 → 接口
--
-- 原 connector / connector_auth / connector_operation 在 V8 中删除，
-- 中间由 V7 把存量数据搬进新表，所以这里只建表、不改旧表。
--
-- 为什么凭证单独成表而不是继续塞在接入点里：一套密钥属于「合作方」这个商业主体，
-- 同一家公司的查询接口与回传接口经常是两套 key，按接入点存就会把一家公司拆成多条记录。

CREATE TABLE partner (
    id             BIGINT       NOT NULL COMMENT '主键',
    code           VARCHAR(64)  NOT NULL COMMENT '合作方编码，业务引用键',
    name           VARCHAR(128) NOT NULL COMMENT '合作方名称',
    status         TINYINT      NOT NULL DEFAULT 0 COMMENT '0 草稿 / 1 正常 / 2 停用 / 3 禁用',
    contact_name   VARCHAR(64)  NULL COMMENT '联系人',
    contact_phone  VARCHAR(32)  NULL COMMENT '联系电话',
    contract_no    VARCHAR(128) NULL COMMENT '合同编号',
    priority       INT          NOT NULL DEFAULT 100 COMMENT '数值越小越优先，供异常切换排序用',
    remark         VARCHAR(512) NULL,
    created_at     DATETIME     NULL,
    updated_at     DATETIME     NULL,
    deleted        TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_partner_code (code)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='合作方（三方数据供应商）';

CREATE TABLE partner_credential (
    id             BIGINT       NOT NULL COMMENT '主键',
    partner_id     BIGINT       NOT NULL COMMENT '所属合作方',
    code           VARCHAR(64)  NOT NULL COMMENT '合作方内唯一',
    name           VARCHAR(128) NOT NULL COMMENT '凭证名称，如「查询 key」「回传 key」',
    type           VARCHAR(32)  NOT NULL COMMENT 'none/api_key/basic/bearer/oauth2_cc/login_token',
    config         JSON         NOT NULL COMMENT '敏感字段为 ENC:v1: 密文，非敏感字段明文便于排障',
    is_default     TINYINT      NOT NULL DEFAULT 0 COMMENT '1 = 该合作方的默认凭证',
    status         TINYINT      NOT NULL DEFAULT 1 COMMENT '0 停用 / 1 启用',
    effective_from DATETIME     NULL COMMENT '生效起，空表示立即生效',
    effective_to   DATETIME     NULL COMMENT '生效止，空表示长期有效',
    remark         VARCHAR(512) NULL,
    created_at     DATETIME     NULL,
    updated_at     DATETIME     NULL,
    deleted        TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_cred_partner_code (partner_id, code),
    KEY idx_cred_partner (partner_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='合作方凭证';

-- 接入点：原 connector 表的内容，多了一个「所属合作方」与「默认凭证」
-- 表名改了，但列名 connector_id（在 endpoint_operation 里）保持不动：
-- 运行记录、调用日志、编排 DSL 全都在用 connectorId，改名只会把风险扩散出去。
CREATE TABLE endpoint (
    id              BIGINT       NOT NULL COMMENT '主键',
    partner_id      BIGINT       NOT NULL COMMENT '所属合作方',
    code            VARCHAR(64)  NOT NULL COMMENT '全局唯一，网关路径与流程 DSL 引用它',
    name            VARCHAR(128) NOT NULL,
    category        VARCHAR(64)  NULL COMMENT '分组，如 支付/物流/CRM',
    base_url        VARCHAR(512) NOT NULL COMMENT '支持 {{env.xxx}} 模板',
    default_headers JSON         NULL COMMENT '所有接口共享的 Header',
    timeout_ms      INT          NULL DEFAULT 10000,
    retry           JSON         NULL,
    credential_id   BIGINT       NULL COMMENT '默认凭证；空表示用合作方的默认凭证',
    status          TINYINT      NOT NULL DEFAULT 0 COMMENT '0 草稿 / 1 启用',
    remark          VARCHAR(512) NULL,
    created_at      DATETIME     NULL,
    updated_at      DATETIME     NULL,
    deleted         TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_endpoint_code (code),
    KEY idx_endpoint_partner (partner_id),
    KEY idx_endpoint_category (category)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='接入点（一个合作方下的一组接口的公共配置）';

CREATE TABLE endpoint_operation (
    id                BIGINT       NOT NULL COMMENT '主键',
    connector_id      BIGINT       NOT NULL COMMENT '所属接入点（列名沿用旧名，见上）',
    code              VARCHAR(64)  NOT NULL,
    name              VARCHAR(128) NOT NULL,
    method            VARCHAR(8)   NOT NULL,
    path              VARCHAR(512) NOT NULL,
    query_params      JSON         NULL,
    headers           JSON         NULL,
    body_type         VARCHAR(16)  NOT NULL DEFAULT 'none',
    body_template     MEDIUMTEXT   NULL,
    response_extract  JSON         NULL,
    pre_script        TEXT         NULL,
    post_script       TEXT         NULL,
    script_timeout_ms INT          NOT NULL DEFAULT 2000,
    timeout_ms        INT          NULL COMMENT '空表示继承接入点',
    retry             JSON         NULL COMMENT '空表示继承接入点',
    credential_mode   VARCHAR(16)  NOT NULL DEFAULT 'INHERIT' COMMENT 'INHERIT / OVERRIDE / NONE',
    credential_id     BIGINT       NULL COMMENT '仅 OVERRIDE 时有值，且必须属于同一合作方',
    status            TINYINT      NOT NULL DEFAULT 1 COMMENT '0 停用 / 1 启用',
    created_at        DATETIME     NULL,
    updated_at        DATETIME     NULL,
    deleted           TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_op_endpoint_code (connector_id, code),
    KEY idx_op_endpoint (connector_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='接入点下的接口';

-- 调用日志补三列：出问题时要能回答「这次失败用的是哪一版 key」
ALTER TABLE invoke_log
    ADD COLUMN partner_id           BIGINT      NULL COMMENT '合作方 id（V6 之前的历史行为空）',
    ADD COLUMN partner_credential_id BIGINT     NULL COMMENT '实际使用的凭证 id（无鉴权时为空）',
    ADD COLUMN credential_type      VARCHAR(32) NULL COMMENT '实际使用的凭证类型';
