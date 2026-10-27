-- 环境变量 host 不带协议：模板里是 `https://{{env.host}}` 而不是 `{{env.host}}`，
-- 否则渲染出 `https://http://...` 这种双协议 URL（V1 的种子数据就是这么写错的）。
UPDATE env SET vars = JSON_OBJECT('host', 'localhost:9000') WHERE code = 'dev';
UPDATE env SET vars = JSON_OBJECT('host', 'test.internal') WHERE code = 'test';
UPDATE env SET vars = JSON_OBJECT('host', 'api.example.com') WHERE code = 'prod';
