-- 凭证类型收敛为两种：header（静态请求头）/ script（Groovy 脚本）
--
-- 背景：原来的 api_key / basic / bearer / oauth2_cc / login_token 六型是「按常见供应商枚举」，
-- 遇到签名类、双头类、多步登录类的供应商就必须加新类型。收敛成
--   header  ——「要加什么头」直接列出来（一条或多条，header 或 query 都行）
--   script  ——「怎么算出这些头」用代码写
-- 之后新增供应商鉴权方式不再需要改平台代码。
--
-- 迁移策略：
--   1) api_key 是无损可转的（name/value/in 三个字段正好对上 header 行），自动转；
--   2) basic / bearer / oauth2_cc / login_token 需要在库里做 base64 或换 token，
--      SQL 层面转不了（密码是 ENC:v1: 密文），一律置为停用并在备注里写清楚，
--      由人工按新模型重写——比起「悄悄转换出一个错的凭证」，停用更难被忽略；
--   3) type='none' 保持原样（调用链对 none 直接返回空产物，本来就是「不鉴权」）。

-- 1) api_key → header
UPDATE partner_credential
SET config = JSON_OBJECT(
        'headers', JSON_ARRAY(JSON_OBJECT(
                'name', COALESCE(JSON_UNQUOTE(JSON_EXTRACT(config, '$.name')), 'X-Api-Key'),
                'in', COALESCE(JSON_UNQUOTE(JSON_EXTRACT(config, '$.in')), 'header'),
                'value', JSON_UNQUOTE(JSON_EXTRACT(config, '$.value'))
                          ))
             ),
    type = 'header',
    remark = CONCAT(COALESCE(remark, ''), CASE WHEN COALESCE(remark, '') = '' THEN '' ELSE '；' END,
                    '已由 api_key 自动迁移为 header 型')
WHERE type = 'api_key';

-- 2) 无法自动转换的旧类型：停用 + 备注
UPDATE partner_credential
SET status = 0,
    remark = CONCAT(COALESCE(remark, ''), CASE WHEN COALESCE(remark, '') = '' THEN '' ELSE '；' END,
                    '旧鉴权类型 ', type, ' 已不再支持，请改写为 header 或 script 型')
WHERE type IN ('basic', 'bearer', 'oauth2_cc', 'login_token');
