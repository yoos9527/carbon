-- `input` / `output` / `error` 在 MySQL 里不是保留字，但会被 JSqlParser 当关键字解析。
-- 后果不是「偶尔报错」，而是 MyBatis-Plus 对 flow_run 的**每一次 UPDATE 都失败**
-- （逻辑删除重写要解析 SQL，解析在 `error=?` 这类片段上崩），执行记录永远卡在 RUNNING。
-- 改成带后缀的名字：既避开词法坑，也把「这一列是 JSON 文本」写在名字里。
ALTER TABLE flow_run
    CHANGE COLUMN `input`  input_json  JSON NULL,
    CHANGE COLUMN `output` output_json JSON NULL,
    CHANGE COLUMN `error`  error_msg   TEXT NULL;

ALTER TABLE flow_step_run
    CHANGE COLUMN `error` error_msg TEXT NULL;
