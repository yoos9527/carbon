package com.carbon.modules.gateway.gw;

/**
 * 网关鉴权/限流拒绝。
 *
 * <p>为什么用异常而不是「返回一个失败结果」：校验有七步，每一步的返回形态都是
 * 「HTTP 状态码 + GwResult」，如果每步都 return，调用方得写七次样板；
 * 而且一旦漏 return 就会带着未校验的请求继续往下走——这类 bug 只有在被攻击时才会暴露。
 * 抛出去交给 {@link GwExceptionHandler} 统一收口，漏了就是 500，不可能静默放行。
 *
 * @author carbon
 */
public class GwRejectedException extends RuntimeException {

    private final GwErrorCode errorCode;

    public GwRejectedException(GwErrorCode errorCode, String message) {
        super(message == null ? errorCode.defaultMessage() : message);
        this.errorCode = errorCode;
    }

    public GwErrorCode errorCode() {
        return errorCode;
    }
}
