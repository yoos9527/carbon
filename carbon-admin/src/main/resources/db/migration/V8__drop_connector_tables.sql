-- 旧三表下线（数据已在 V7 全部搬进 partner / partner_credential / endpoint / endpoint_operation）
--
-- 顺序：先删子表再删主表，和 V1 的建表顺序相反。
-- 这一步不可逆——回滚只能靠数据库备份。

DROP TABLE IF EXISTS connector_auth;
DROP TABLE IF EXISTS connector_operation;
DROP TABLE IF EXISTS connector;
