# 企查查 OpenAPI Mock

给平台开发用的企查查替身：**路径、Query 名、Header 名、响应包结构都与官方文档一致**，所以平台侧
的接口配置、前置加签脚本、后置清洗脚本可以照真接口写，等 AppKey 到位只改 `baseUrl` 与鉴权配置。

- 官方接口事实见 [`docs/08-数据接入平台.md`](../../docs/08-数据接入平台.md) §8
- 纯 Python 标准库，无需 `pip install`

## 启动

```bash
python3 script/mock/qcc-mock/server.py --port 9100
# 可选：--host 0.0.0.0 --key qcc_mock_key --secret qcc_mock_secret --clock-skew 300
```

启动时会打印一条可直接粘贴的 curl（含按当前时间算好的 `Token`）。

## 鉴权（与官方一致）

```
Header  Token:    MD5(key + Timespan + secretKey).toUpperCase()
Header  Timespan: 秒级 Unix 时间戳，允许 ±300 秒偏差
Query   key:      AppKey（默认 qcc_mock_key，secretKey 默认 qcc_mock_secret）
```

对应平台侧的前置 ETL：

```groovy
vars.timespan = utils.timestamp()
vars.token    = utils.md5(auth.appKey + vars.timespan + auth.secretKey).toUpperCase()
```

## 端点

| 用途 | 路径 | 额外 Query |
|---|---|---|
| 企业工商信息 | `GET /ECIInfoVerify/GetInfo` | `searchKey`（名称/信用代码/注册号/KeyNo/QccCode） |
| 企业风险扫描 | `GET /ECIInfoOverview/GetInfo` | `searchKey` |
| 裁判文书 | `GET /JudgmentDocCheck/GetList` | `pubYear`、`caseIdentity`(1 被告/2 原告)、`caseStatus`(1 待结案/2 已结案)、`keyWord`、`pageIndex`、`pageSize`(≤20) |

响应统一为：

```json
{ "Status": "200", "Message": "查询成功", "Result": { }, "OrderNumber": "mock-8f3a..." }
```

`Status` 是**字符串**：`200` 成功、`101` 鉴权失败、`102` 余额/权限不足、`201` 参数错误、`429` 限流。
后置 ETL 必须判 `Status`，不能只看 HTTP 200。

## 故障注入（仅 mock 支持）

| 参数 | 行为 |
|---|---|
| `mock=slow&ms=3000` | 睡 3 秒后正常返回（测慢调用/读超时） |
| `mock=timeout&ms=30000` | 睡很久（测连接/读超时与重试） |
| `mock=429` | HTTP 429（测限流与退避） |
| `mock=error&status=102` | 外层 `Status=102`（测业务错误分支） |
| `mock=empty` | `Result={}`（测空数据/抽取缺失） |

## 数据集

| 企业 | 覆盖场景 |
|---|---|
| 企查查科技股份有限公司（`91320594MA1MOCK001`） | 正常：股东 3 / 高管 3 / 分支 2 / 变更 2；风险：处罚 2、经营异常 1、失信 1、被执行 3、股权出质 1、抽查 1、纳税 A；文书 3 |
| 苏州某某注销贸易有限公司（`...002`） | 已注销 + 清算信息 + 无风险无文书 |
| 某某吊销建材有限公司（`...003`） | 吊销未注销 + 失信 2 + 经营异常 + 文书 1 |
| QCC MOCK HONG KONG LIMITED（`71234567`） | 香港企业：`CreditCode` 是商业登记号、无股东/无文书 |
| 苏州仅名称匹配测试有限公司（`...005`） | 字段稀疏（测可选字段缺失时的清洗逻辑） |

搜不到时回 `Status=200` + `Result={}`（与官方一致：不报错，返回空）。

## 平台侧怎么配

1. 连接器 `baseUrl` = `http://127.0.0.1:9100`，鉴权类型先选 `api_key`
   （`config` 里 `appKey` 放明文 AppKey、`value` 放 SecretKey —— `value` 会被平台加密存储）
2. 接口 `path` = `/ECIInfoVerify/GetInfo`，`queryParams` = `{"key":"{{auth.appKey}}","searchKey":"{{vars.searchKey}}"}`
3. 接口 `headers` = `{"Token":"{{vars.token}}","Timespan":"{{vars.timespan}}"}`
4. 前置脚本算 `vars.token/vars.timespan`（见上），后置脚本按 §7.1 契约产出 `output`

## 已知限制

- 只实现 GET（官方这三个接口也都是 GET）
- 不校验 IP 白名单、不做真实计费
- 只在内存里查表，改数据请编辑 `data/companies.json` 后重启
