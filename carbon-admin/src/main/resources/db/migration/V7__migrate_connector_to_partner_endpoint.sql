-- 存量数据搬迁：一个旧「连接器」= 一个合作方 + 一个接入点
--
-- 这样搬的好处是零风险：接入点 code 与原连接器 code 完全一致，
-- 所有已发布的编排 DSL（节点里的 connector 字段）、网关路径、调用日志的 connector_id 全部继续有效，
-- 不需要改一行 DSL、也不需要重新授权任何调用方。
--
-- 合作方状态一律写 1（正常）：旧的 connector.status 只管「这个入口能不能调」，
-- 搬过来由 endpoint.status 继续承担，合作方层不该凭空多出一道会导致停机的新闸门。

INSERT INTO partner (id, code, name, status, priority, remark, created_at, updated_at, deleted)
SELECT c.id, c.code, c.name, 1, 100, c.remark, NOW(), NOW(), c.deleted
FROM connector c
WHERE NOT EXISTS (SELECT 1 FROM partner p WHERE p.id = c.id);

INSERT INTO endpoint (id, partner_id, code, name, category, base_url, default_headers,
                      timeout_ms, retry, credential_id, status, remark, created_at, updated_at, deleted)
SELECT c.id, c.id, c.code, c.name, c.category, c.base_url, c.default_headers,
       c.timeout_ms, c.retry, NULL, c.status, c.remark, c.created_at, c.updated_at, c.deleted
FROM connector c
WHERE NOT EXISTS (SELECT 1 FROM endpoint e WHERE e.id = c.id);

INSERT INTO endpoint_operation (id, connector_id, code, name, method, path, query_params, headers,
                               body_type, body_template, response_extract, pre_script, post_script,
                               script_timeout_ms, timeout_ms, retry, credential_mode, credential_id,
                               status, created_at, updated_at, deleted)
SELECT o.id, o.connector_id, o.code, o.name, o.method, o.path, o.query_params, o.headers,
       o.body_type, o.body_template, o.response_extract, o.pre_script, o.post_script,
       o.script_timeout_ms, o.timeout_ms, o.retry, 'INHERIT', NULL,
       o.status, o.created_at, o.updated_at, o.deleted
FROM connector_operation o
WHERE NOT EXISTS (SELECT 1 FROM endpoint_operation eo WHERE eo.id = o.id);

-- 旧的「一连接器一份鉴权」直接升成合作方的默认凭证；
-- config 里的 ENC:v1: 密文原样搬过去（同一把 CARBON_MASTER_KEY），不解密也不重加密。
INSERT INTO partner_credential (id, partner_id, code, name, type, config, is_default, status,
                                remark, created_at, updated_at, deleted)
SELECT a.id, a.connector_id, 'default', '默认凭证', a.auth_type, a.config, 1, a.status,
       '由连接器鉴权自动迁移', a.created_at, a.updated_at, 0
FROM connector_auth a
WHERE a.auth_type IS NOT NULL
  AND a.auth_type <> 'none'
  AND NOT EXISTS (SELECT 1 FROM partner_credential pc WHERE pc.id = a.id);

-- 调用日志的历史行补合作方：connector_id 与合作方一一对应，能补就直接补上
UPDATE invoke_log SET partner_id = connector_id WHERE partner_id IS NULL AND connector_id IS NOT NULL;
