package com.carbon.framework.dag.compiler;

/**
 * 编译期失败：画布结构能过校验、但拼不成 LiteFlow EL 时抛出。
 *
 * <p>正常路径下不应出现——{@code GraphValidator} 已经把非结构化画布挡住了。真出现就是
 * 校验器的漏洞，属于程序错误而不是用户错误，所以是 {@link IllegalStateException} 的子类。
 *
 * @author carbon
 */
public class DagCompileException extends IllegalStateException {

    private static final long serialVersionUID = 1L;

    /**
     * 构造。
     *
     * @param message 说明
     */
    public DagCompileException(String message) {
        super(message);
    }
}
