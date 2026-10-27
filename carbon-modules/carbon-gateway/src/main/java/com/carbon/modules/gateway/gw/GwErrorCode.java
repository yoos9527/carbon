package com.carbon.modules.gateway.gw;

import com.carbon.common.core.enums.BizError;

/**
 * 网关错误码。
 *
 * <p>与内部 {@link BizError} 刻意分开维护：网关是**对外契约**，
 * 内部枚举改名、拆分、合并都不应该让调用方跟着改。两边靠 {@link #from(BizError)} 映射，
 * 映射不到的一律落到 {@code INTERNAL_ERROR}——宁可让调用方看到一个笼统的错，
 * 也不要把还没设计过的内部码泄漏出去。
 *
 * @author carbon
 */
public enum GwErrorCode {

    /** 成功。注意不是 "200"：HTTP 状态码与语义码是两套东西，混用迟早出事 */
    OK("0", 200, "成功"),

    PARAM_INVALID("400", 400, "参数不合法"),
    SIGN_INVALID("401", 401, "签名校验失败"),
    APP_FORBIDDEN("403", 403, "应用无权调用该连接器"),
    CONNECTOR_NOT_FOUND("4404", 404, "连接器不存在或已停用"),
    OPERATION_NOT_FOUND("4405", 404, "接口不存在或已停用"),
    FLOW_NOT_FOUND("4406", 404, "流程不存在或未发布"),
    RATE_LIMITED("4029", 429, "调用频率超限"),
    PROVIDER_TIMEOUT("5504", 504, "三方服务超时"),
    PROVIDER_HTTP_4XX("5402", 502, "三方服务返回 4xx"),
    PROVIDER_HTTP_5XX("5502", 502, "三方服务返回 5xx"),
    AUTH_FAILED("5401", 502, "三方鉴权失败"),
    EXTRACT_FAILED("5410", 502, "响应抽取失败"),
    SCRIPT_ERROR("5001", 500, "脚本执行失败"),
    INTERNAL_ERROR("5000", 500, "平台内部错误");

    private final String code;
    private final int httpStatus;
    private final String defaultMessage;

    GwErrorCode(String code, int httpStatus, String defaultMessage) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public String code() {
        return code;
    }

    public int httpStatus() {
        return httpStatus;
    }

    public String defaultMessage() {
        return defaultMessage;
    }

    /**
     * 内部错误码 → 对外语义码。
     */
    public static GwErrorCode from(BizError error) {
        if (error == null) {
            return INTERNAL_ERROR;
        }
        return switch (error) {
            case OK -> OK;
            case PARAM_INVALID -> PARAM_INVALID;
            case SIGN_INVALID -> SIGN_INVALID;
            case RATE_LIMITED -> RATE_LIMITED;
            case CONNECTOR_NOT_FOUND -> CONNECTOR_NOT_FOUND;
            case OPERATION_NOT_FOUND -> OPERATION_NOT_FOUND;
            case FLOW_NOT_FOUND -> FLOW_NOT_FOUND;
            case AUTH_FAILED -> AUTH_FAILED;
            case PROVIDER_TIMEOUT -> PROVIDER_TIMEOUT;
            case PROVIDER_HTTP_4XX -> PROVIDER_HTTP_4XX;
            case PROVIDER_HTTP_5XX -> PROVIDER_HTTP_5XX;
            case EXTRACT_FAILED -> EXTRACT_FAILED;
            case SCRIPT_ERROR -> SCRIPT_ERROR;
            case FLOW_TIMEOUT, INTERNAL_ERROR -> INTERNAL_ERROR;
        };
    }
}
