package com.carbon.framework.expression;

/**
 * 渲染/求值异常。
 *
 * <p>错误码与平台错误码表一致（§7）：模板问题一律 {@link #PARAM_INVALID}，表达式求值失败为
 * {@link #EXPRESSION_ERROR}。{@code varPath} 与 {@code position} 用于把错误定位到模板里的具体占位符。
 *
 * @author carbon
 */
public class RenderException extends RuntimeException {

    /** 参数非法（模板语法、缺失变量、默认值缺失等）。 */
    public static final String PARAM_INVALID = "PARAM_INVALID";

    /** 表达式求值失败。 */
    public static final String EXPRESSION_ERROR = "EXPRESSION_ERROR";

    private final String code;

    private final String varPath;

    private final Integer position;

    /**
     * 构造。
     *
     * @param code    错误码
     * @param message 消息
     */
    public RenderException(String code, String message) {
        this(code, message, null, null, null);
    }

    /**
     * 构造。
     *
     * @param code    错误码
     * @param message 消息
     * @param cause   根因
     */
    public RenderException(String code, String message, Throwable cause) {
        this(code, message, null, null, cause);
    }

    /**
     * 构造。
     *
     * @param code     错误码
     * @param message  消息
     * @param varPath  变量路径（可为空）
     * @param position 在模板中的位置（可为空）
     * @param cause    根因（可为空）
     */
    public RenderException(String code, String message, String varPath, Integer position, Throwable cause) {
        super(buildMessage(message, varPath, position), cause);
        this.code = code;
        this.varPath = varPath;
        this.position = position;
    }

    /**
     * 参数非法快捷构造。
     *
     * @param message 消息
     * @return 异常
     */
    public static RenderException paramInvalid(String message) {
        return new RenderException(PARAM_INVALID, message);
    }

    /**
     * 参数非法快捷构造（带变量定位）。
     *
     * @param message  消息
     * @param varPath  变量路径
     * @param position 位置
     * @return 异常
     */
    public static RenderException paramInvalid(String message, String varPath, Integer position) {
        return new RenderException(PARAM_INVALID, message, varPath, position, null);
    }

    public String getCode() {
        return code;
    }

    public String getVarPath() {
        return varPath;
    }

    public Integer getPosition() {
        return position;
    }

    private static String buildMessage(String message, String varPath, Integer position) {
        if (varPath == null && position == null) {
            return message;
        }
        StringBuilder sb = new StringBuilder(message);
        if (varPath != null) {
            sb.append(" [变量: ").append(varPath).append(']');
        }
        if (position != null) {
            sb.append(" [位置: ").append(position).append(']');
        }
        return sb.toString();
    }
}
