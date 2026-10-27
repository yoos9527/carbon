package com.carbon.framework.script;

/**
 * 脚本失败（对应平台错误码 {@code SCRIPT_ERROR}）。
 *
 * <p>消息刻意做成「人话 + 脚本行号」：脚本是用户写的，堆栈里出现 {@code org.codehaus.groovy...}
 * 只会让人更困惑。
 *
 * @author carbon
 */
public class ScriptError extends RuntimeException {

    /** 失败阶段。 */
    public enum Phase {
        /** 编译期（沙箱拒绝、语法错误）。 */
        COMPILE,
        /** 运行期异常。 */
        RUNTIME,
        /** 超时被强行终止。 */
        TIMEOUT
    }

    private static final long serialVersionUID = 1L;

    private final Phase phase;
    private final String scriptHash;

    /**
     * 构造。
     *
     * @param phase      阶段
     * @param scriptHash 脚本指纹（sha256 前 12 位）
     * @param message    简明消息
     * @param cause      根因
     */
    public ScriptError(Phase phase, String scriptHash, String message, Throwable cause) {
        super(message, cause);
        this.phase = phase;
        this.scriptHash = scriptHash;
    }

    public Phase getPhase() {
        return phase;
    }

    public String getScriptHash() {
        return scriptHash;
    }

    @Override
    public String toString() {
        return "ScriptError(" + phase + ", script=" + scriptHash + "): " + getMessage();
    }
}
