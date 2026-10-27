package com.carbon.framework.dag.dsl.node;


/**
 * 脚本节点：在 Groovy 沙箱里执行，读写 {@code vars}。
 *
 * @param id        节点 id
 * @param lang      脚本语言，目前只支持 {@code groovy}
 * @param timeoutMs 节点级超时（空则用全局脚本超时）
 * @param script    脚本原文
 * @author carbon
 */
public record ScriptNode(String id, String lang, Long timeoutMs, String script) implements NodeDef {

    /** 默认语言。 */
    public static final String GROOVY = "groovy";

    public ScriptNode {
        Nodes.requireId(id, "SCRIPT");
        Nodes.requireText(script, "SCRIPT.script");
        lang = lang == null || lang.isBlank() ? GROOVY : lang;
        if (!GROOVY.equals(lang)) {
            throw new IllegalArgumentException("SCRIPT.lang 目前只支持 groovy（node=" + id + "）");
        }
    }
}
