package com.carbon.framework.dag.dsl.node;

/**
 * 节点构造参数的公共校验（包内工具，不对外）。
 *
 * @author carbon
 */
final class Nodes {

    private Nodes() {
    }

    static void requireId(String id, String type) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException(type + " 节点缺少 id");
        }
    }

    static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " 不能为空");
        }
    }
}
