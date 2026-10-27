#!/usr/bin/env python3
"""企查查 OpenAPI 的本地 mock（形状与官方文档逐字对齐，便于平台侧真跑）。

真实接口（2026-10 抓自 openapi.qcc.com）：

    GET https://api.qichacha.com/ECIInfoVerify/GetInfo?key=&searchKey=          企业工商信息
    GET https://api.qichacha.com/ECIInfoOverview/GetInfo?key=&searchKey=        企业风险扫描
    GET https://api.qichacha.com/JudgmentDocCheck/GetList?key=&searchKey=...    裁判文书

鉴权：Header `Token = MD5(key + Timespan + secretKey).upper()`、`Timespan` = 秒级时间戳。
响应：`{"Status":"200","Message":"","Result":{...},"OrderNumber":"..."}`，Status 是**字符串**。

故障注入（Query 参数，仅 mock 有）：
    mock=slow&ms=3000     睡 ms 毫秒后正常返回（测超时/慢调用）
    mock=timeout&ms=30000 睡很久（测读超时）
    mock=429              回 HTTP 429（测限流）
    mock=error&status=102 回 Status=102（测业务错误分支）
    mock=empty            回空 Result（测抽取缺失/空数据）

跑法：
    python3 server.py --port 9100 --key qcc_mock_key --secret qcc_mock_secret
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

DATA_FILE = os.path.join(os.path.dirname(os.path.abspath(__file__)), "data", "companies.json")

STATUS_OK = "200"
STATUS_AUTH = "101"      # 鉴权失败（官方：签名/时间窗错误）
STATUS_BALANCE = "102"   # 余额/权限不足
STATUS_PARAM = "201"     # 参数错误

COMPANY_DETAIL_FIELDS = [
    "KeyNo", "QccCode", "Name", "No", "CreditCode", "Status", "StartDate", "EndDate",
    "OperName", "OperId", "BelongOrg", "Province", "RegistCapi", "RegisteredCapital",
    "RegisteredCapitalUnit", "RegisteredCapitalCCY", "RecCap", "EconKind", "Address",
    "Scope", "TermStart", "TermEnd", "CheckDate", "OrgNo", "IsOnStock", "StockNumber",
    "StockType", "InsuredCount", "PersonScope", "EnglishName", "EntType", "UpdatedDate",
    "Industry", "Area", "TagList", "Partners", "Employees", "Branches", "ChangeRecords",
    "ContactInfo", "OriginalName", "ImageUrl",
]

RISK_LIST_FIELDS = [
    "Penalty", "Exceptions", "ShiXinItems", "ZhiXingItems", "MPledge", "Pledge",
    "SpotCheck", "CompanyTaxCreditItems", "CompanyProducts", "PermissionEciInfo",
]

CASE_TYPE_MAP = {
    "zx": "zx", "zscq": "zscq", "xz": "xz", "ms": "ms", "pc": "pc", "xs": "xs",
    "gx": "gx", "qsx": "qsx", "gsx": "gsx", "zc": "zc", "bq": "bq",
}


def load_companies() -> list[dict]:
    with open(DATA_FILE, encoding="utf-8") as fh:
        return json.load(fh)


def envelope(status: str, message: str, result, order_no: str) -> dict:
    return {"Status": status, "Message": message, "Result": result, "OrderNumber": order_no}


def sha16(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()[:16]


class QccMockHandler(BaseHTTPRequestHandler):
    server_version = "QccMock/1.0"
    companies: list[dict] = []
    app_key = "qcc_mock_key"
    secret_key = "qcc_mock_secret"
    clock_skew_sec = 300

    # ---------- 基础设施 ----------

    def log_message(self, fmt: str, *args) -> None:  # 覆盖默认实现，输出更有用的信息
        sys.stdout.write("[%s] %s %s\n" % (time.strftime("%H:%M:%S"), self.address_string(), fmt % args))

    def _send(self, payload: dict, http_status: int = 200) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(http_status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _query(self) -> dict[str, str]:
        parsed = urlparse(self.path)
        return {k: v[0] for k, v in parse_qs(parsed.query).items()}

    # ---------- 鉴权 ----------

    def _check_auth(self, q: dict[str, str]) -> tuple[bool, str]:
        key = q.get("key", "")
        token = self.headers.get("Token", "")
        timespan = self.headers.get("Timespan", "")
        if not key or not token or not timespan:
            return False, "缺少 Token / Timespan / key"
        if key != self.app_key:
            return False, "AppKey 不存在或未开通"
        try:
            ts = int(timespan)
        except ValueError:
            return False, "Timespan 不是秒级时间戳"
        if abs(int(time.time()) - ts) > self.clock_skew_sec:
            return False, "Timespan 超出允许的时间窗（±300 秒）"
        expect = hashlib.md5((key + timespan + self.secret_key).encode("utf-8")).hexdigest().upper()
        if token != expect:
            return False, "Token 校验失败：应为 MD5(key + Timespan + secretKey) 的 32 位大写"
        return True, ""

    # ---------- 故障注入 ----------

    def _inject_fault(self, q: dict[str, str]) -> bool:
        """返回 True 表示已经自行响应，调用方应直接 return。"""
        mode = q.get("mock", "")
        if not mode:
            return False
        if mode == "slow":
            time.sleep(int(q.get("ms", "3000")) / 1000.0)
            return False
        if mode == "timeout":
            time.sleep(int(q.get("ms", "30000")) / 1000.0)
            return False
        if mode == "429":
            self._send(envelope("429", "调用过于频繁，请稍后再试", None, "mock-429"), http_status=429)
            return True
        if mode == "error":
            status = q.get("status", STATUS_BALANCE)
            self._send(envelope(status, "mock 注入的业务错误：%s" % status, None, "mock-error"))
            return True
        if mode == "empty":
            self._send(envelope(STATUS_OK, "查询成功（mock 空结果）", {}, "mock-empty"))
            return True
        return False

    # ---------- 路由 ----------

    def do_GET(self) -> None:  # noqa: N802 - BaseHTTPRequestHandler 的约定
        path = urlparse(self.path).path.rstrip("/")
        q = self._query()

        ok, reason = self._check_auth(q)
        if not ok:
            self._send(envelope(STATUS_AUTH, reason, None, "mock-auth"))
            return
        if self._inject_fault(q):
            return

        handler = {
            "/ECIInfoVerify/GetInfo": self._company_detail,
            "/ECIInfoOverview/GetInfo": self._company_risk,
            "/JudgmentDocCheck/GetList": self._judgment_docs,
        }.get(path)
        if handler is None:
            self._send(envelope(STATUS_PARAM, "接口不存在：%s" % path, None, "mock-404"), http_status=404)
            return
        handler(q)

    # ---------- 业务 ----------

    def _find(self, q: dict[str, str]) -> tuple[dict | None, str]:
        search = (q.get("searchKey") or "").strip()
        if not search:
            return None, "searchKey 不能为空"
        for c in self.companies:
            keys = {c["Name"], c["CreditCode"], c["No"], c["KeyNo"], c.get("QccCode", "")}
            if search in keys:
                return c, ""
        # 模糊：名称包含即命中（真实接口对名称也是模糊优先，但结果集里会给出多条）
        for c in self.companies:
            if search in c["Name"]:
                return c, ""
        return None, ""

    def _company_detail(self, q: dict[str, str]) -> None:
        company, err = self._find(q)
        if err:
            self._send(envelope(STATUS_PARAM, err, None, "mock-param"))
            return
        if company is None:
            self._send(envelope(STATUS_OK, "未查询到相关企业", {}, "mock-" + sha16(q.get("searchKey", ""))))
            return
        result = {k: company.get(k) for k in COMPANY_DETAIL_FIELDS if k in company}
        self._send(envelope(STATUS_OK, "查询成功", result, "mock-" + sha16(company["KeyNo"])))

    def _company_risk(self, q: dict[str, str]) -> None:
        company, err = self._find(q)
        if err:
            self._send(envelope(STATUS_PARAM, err, None, "mock-param"))
            return
        if company is None:
            self._send(envelope(STATUS_OK, "未查询到相关企业", {}, "mock-" + sha16(q.get("searchKey", ""))))
            return
        # 风险扫描接口 = 工商照面 + 风险列表（与官方一致：两套字段合并在一个 Result 里）
        result = {k: company.get(k) for k in COMPANY_DETAIL_FIELDS if k in company}
        risk = company.get("risk") or {}
        for field in RISK_LIST_FIELDS:
            result[field] = risk.get(field, [])
        result["Liquidation"] = risk.get("Liquidation")
        self._send(envelope(STATUS_OK, "查询成功", result, "mock-" + sha16("risk" + company["KeyNo"])))

    def _judgment_docs(self, q: dict[str, str]) -> None:
        company, err = self._find(q)
        if err:
            self._send(envelope(STATUS_PARAM, err, None, "mock-param"))
            return
        docs = list((company or {}).get("docs") or [])

        pub_year = (q.get("pubYear") or "").strip()
        if pub_year:
            docs = [d for d in docs if str(d.get("PublishDate", ""))[:4] == pub_year]
        identity = (q.get("caseIdentity") or "").strip()
        if identity == "1":
            docs = [d for d in docs if d.get("IsDefendant") == "true"]
        elif identity == "2":
            docs = [d for d in docs if d.get("IsProsecutor") == "true"]
        status = (q.get("caseStatus") or "").strip()
        if status == "1":
            docs = [d for d in docs if not d.get("JudgeResult")]
        elif status == "2":
            docs = [d for d in docs if d.get("JudgeResult")]
        keyword = (q.get("keyWord") or "").strip()
        if keyword:
            docs = [d for d in docs
                    if keyword in d.get("CaseNo", "") or keyword in d.get("CaseReason", "")
                    or keyword in d.get("CaseName", "") or keyword in d.get("JudgeResult", "")]

        try:
            page_index = max(1, int(q.get("pageIndex") or 1))
            page_size = int(q.get("pageSize") or 10)
        except ValueError:
            self._send(envelope(STATUS_PARAM, "pageIndex/pageSize 必须是整数", None, "mock-param"))
            return
        if page_size > 20:
            self._send(envelope(STATUS_PARAM, "pageSize 最大 20", None, "mock-param"))
            return

        start = (page_index - 1) * page_size
        page = docs[start:start + page_size]
        result = {
            "VerifyResult": 1 if docs else 0,
            "Data": page,
            "PageIndex": page_index,
            "PageSize": page_size,
            "Total": len(docs),
        }
        self._send(envelope(STATUS_OK, "查询成功", result, "mock-" + sha16("doc" + company["KeyNo"])))


def main() -> None:
    parser = argparse.ArgumentParser(description="企查查 OpenAPI mock")
    parser.add_argument("--port", type=int, default=9100)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--key", default="qcc_mock_key")
    parser.add_argument("--secret", default="qcc_mock_secret")
    parser.add_argument("--clock-skew", type=int, default=300)
    args = parser.parse_args()

    QccMockHandler.companies = load_companies()
    QccMockHandler.app_key = args.key
    QccMockHandler.secret_key = args.secret
    QccMockHandler.clock_skew_sec = args.clock_skew

    base = "http://%s:%d" % (args.host, args.port)
    ts = str(int(time.time()))
    token = hashlib.md5((args.key + ts + args.secret).encode("utf-8")).hexdigest().upper()

    print("企查查 mock 已启动：%s" % base)
    print("  AppKey   : %s" % args.key)
    print("  SecretKey: %s" % args.secret)
    print("  示例（工商信息）：")
    print("    curl -s '%s/ECIInfoVerify/GetInfo?key=%s&searchKey=%s' \\" % (base, args.key, QccMockHandler.companies[0]["Name"]))
    print("         -H 'Token: %s' -H 'Timespan: %s'" % (token, ts))
    print("  故障注入：?mock=slow&ms=3000 / ?mock=timeout&ms=30000 / ?mock=429 / ?mock=error&status=102 / ?mock=empty")
    ThreadingHTTPServer((args.host, args.port), QccMockHandler).serve_forever()


if __name__ == "__main__":
    main()
