package com.carbon.framework.script;

import java.util.Map;

/**
 * 脚本执行结果。
 *
 * @param ctx        执行后的上下文（与入参是同一个可变 Map，脚本原地改写）
 * @param scriptHash 脚本指纹（sha256 前 12 位），日志与排障用
 * @param costMs     耗时（毫秒）
 * @param skipped    是否因总开关关闭而跳过
 * @author carbon
 */
public record ScriptResult(Map<String, Object> ctx, String scriptHash, long costMs, boolean skipped) {
}
