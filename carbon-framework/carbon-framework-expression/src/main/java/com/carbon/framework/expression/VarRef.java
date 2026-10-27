package com.carbon.framework.expression;

/**
 * 模板中的一条变量引用（表单元数据）。
 *
 * <p>同一份扫描结果同时供三处使用：① 前端按 {@code {{input.x}}} 自动生成入参表单；
 * ② 调用前必填/类型校验；③ 试调页预填。
 *
 * @param scope      作用域：input / vars / auth / env / function / unknown
 * @param path       路径（如 {@code input.order.id}），函数调用时为函数名
 * @param hasDefault 是否带 {@code ??} 默认值
 * @param raw        占位符原文（含花括号）
 * @author carbon
 */
public record VarRef(String scope, String path, boolean hasDefault, String raw) {
}
