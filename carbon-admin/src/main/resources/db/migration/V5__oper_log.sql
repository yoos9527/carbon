-- 操作日志：管理端写操作的审计轨迹。
--
-- 为什么单独一张表而不是复用 invoke_log：
--   * invoke_log 是「数据面」——每秒几十条、按月分区、30 天清理；
--   * sys_oper_log 是「管理面」——一天几条、按时间倒序翻、要长期留。
-- 两者的读写形态、保留策略完全不同，硬塞进一张表只会让分区和索引互相打架。
--
-- 没有 deleted 列：日志不软删，没人会「恢复一条日志」。
-- 没有 user_id：平台暂不登录；接入登录后加一列即可，切面调用点不用动。
CREATE TABLE IF NOT EXISTS `sys_oper_log` (
    `id`             BIGINT       NOT NULL PRIMARY KEY COMMENT '雪花 ID',
    `title`          VARCHAR(64)  NOT NULL COMMENT '模块名，如「连接器」',
    `business_type`  VARCHAR(16)  NOT NULL COMMENT 'INSERT/UPDATE/DELETE/EXECUTE/OTHER',
    `method`         VARCHAR(128) NOT NULL COMMENT 'Class.method，便于定位代码',
    `request_method` VARCHAR(8)   DEFAULT NULL COMMENT 'HTTP 方法',
    `request_uri`    VARCHAR(255) DEFAULT NULL COMMENT '请求路径（不含 query，避免密钥落表）',
    `client_ip`      VARCHAR(64)  DEFAULT NULL COMMENT '调用方 IP（无登录时唯一线索）',
    `user_agent`     VARCHAR(255) DEFAULT NULL,
    `params`         VARCHAR(2048) DEFAULT NULL COMMENT '脱敏后的参数 JSON，截断 2000',
    `status`         TINYINT      NOT NULL DEFAULT 1 COMMENT '1 成功 / 0 失败',
    `error_msg`      VARCHAR(512) DEFAULT NULL COMMENT '失败原因，截断 500',
    `duration_ms`    BIGINT       DEFAULT NULL,
    `created_at`     DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    KEY `idx_oper_log_created` (`created_at`),
    KEY `idx_oper_log_title` (`title`, `created_at`),
    KEY `idx_oper_log_status` (`status`, `created_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '管理端操作日志';
