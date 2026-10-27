package com.carbon.common.core.enums;

/**
 * 平台错误码基线：**网关对外契约的唯一来源**。
 *
 * <p>管理端（{@code /api/**}）只用 {@link #PARAM_INVALID} 之类的粗粒度码，
 * 网关（{@code /gw/**}）把 {@link #name()} 原样作为 {@code code} 返回给第三方业务系统，
 * 所以<b>枚举名即契约</b>：改了名字就是破坏性变更，必须走版本。
 *
 * <p>HTTP 状态码与语义码同时返回：业务方看不懂平台内部逻辑，但看得懂 4xx/5xx。
 *
 * @author carbon
 */
public enum BizError {

    /** 成功：网关侧 code 为字符串 {@code "0"}，此处给 200 便于管理端复用 */
    OK(200),

    CONNECTOR_NOT_FOUND(404),
    OPERATION_NOT_FOUND(404),

    PARAM_INVALID(400),

    /** 三方鉴权获取 Token 失败（网络/凭证错误） */
    AUTH_FAILED(502),
    /** 三方返回 4xx（401 自愈一次后仍失败） */
    PROVIDER_HTTP_4XX(502),
    /** 三方返回 5xx */
    PROVIDER_HTTP_5XX(502),
    /** 响应抽取全部落空且未设默认值 */
    EXTRACT_FAILED(502),

    PROVIDER_TIMEOUT(504),
    FLOW_TIMEOUT(504),

    /** Groovy 脚本编译失败、执行抛异常或超时 */
    SCRIPT_ERROR(500),

    /** 签名不匹配 / 时间窗外 / nonce 重放 / appKey 无效 */
    SIGN_INVALID(401),
    /** 应用级 QPS 超限 */
    RATE_LIMITED(429),

    INTERNAL_ERROR(500);

    private final int httpStatus;

    BizError(int httpStatus) {
        this.httpStatus = httpStatus;
    }

    public int httpStatus() {
        return httpStatus;
    }

    /**
     * 网关响应体里的 {@code code} 字段：成功是 {@code "0"}，失败就是枚举名。
     */
    public String gwCode() {
        return this == OK ? "0" : name();
    }
}
