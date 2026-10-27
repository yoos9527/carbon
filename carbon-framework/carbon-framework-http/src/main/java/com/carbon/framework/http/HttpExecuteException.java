package com.carbon.framework.http;

import java.io.Serial;

/**
 * HTTP 执行异常：只表达「没拿到响应」这一类失败。
 *
 * <p>拿到响应（哪怕是 500）<b>不</b>抛异常——那是 {@link HttpResult} 的正常取值域，
 * 由上层按 {@code PROVIDER_HTTP_4XX/5XX} 判定。这样「传输层失败」与「三方业务失败」两条路径不会混。
 *
 * @author carbon
 */
public class HttpExecuteException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 连接超时（建连阶段）。 */
    public static final String CONNECT_TIMEOUT = "CONNECT_TIMEOUT";

    /** 读超时（已建连、等响应体阶段）。 */
    public static final String READ_TIMEOUT = "READ_TIMEOUT";

    /** 连接池耗尽 / 路由不可达。 */
    public static final String CONNECT_ERROR = "CONNECT_ERROR";

    /** 其它 IO 失败（对端断连、TLS 握手失败等）。 */
    public static final String IO_ERROR = "IO_ERROR";

    private final String reason;

    /**
     * 构造。
     *
     * @param reason  原因码
     * @param message 消息
     * @param cause   根因
     */
    public HttpExecuteException(String reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public String getReason() {
        return reason;
    }

    /**
     * 是否属于超时类（上层映射为 {@code PROVIDER_TIMEOUT} / 504）。
     *
     * @return 是否超时
     */
    public boolean isTimeout() {
        return CONNECT_TIMEOUT.equals(reason) || READ_TIMEOUT.equals(reason);
    }

    /**
     * 连接超时。
     *
     * @param url   URL（已脱敏由调用方负责）
     * @param cause 根因
     * @return 异常
     */
    public static HttpExecuteException connectTimeout(String url, Throwable cause) {
        return new HttpExecuteException(CONNECT_TIMEOUT, "连接超时: " + url, cause);
    }

    /**
     * 读超时。
     *
     * @param url   URL
     * @param cause 根因
     * @return 异常
     */
    public static HttpExecuteException readTimeout(String url, Throwable cause) {
        return new HttpExecuteException(READ_TIMEOUT, "读超时: " + url, cause);
    }

    /**
     * 建连失败。
     *
     * @param url   URL
     * @param cause 根因
     * @return 异常
     */
    public static HttpExecuteException connectError(String url, Throwable cause) {
        return new HttpExecuteException(CONNECT_ERROR, "连接失败: " + url + " (" + rootMessage(cause) + ")", cause);
    }

    /**
     * 其它 IO 失败。
     *
     * @param url   URL
     * @param cause 根因
     * @return 异常
     */
    public static HttpExecuteException ioError(String url, Throwable cause) {
        return new HttpExecuteException(IO_ERROR, "IO 异常: " + url + " (" + rootMessage(cause) + ")", cause);
    }

    private static String rootMessage(Throwable cause) {
        if (cause == null) {
            return "unknown";
        }
        Throwable root = cause;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }
}
