package com.carbon.framework.dag.validator;

import java.util.Arrays;
import java.util.List;

/**
 * 一条校验错误。
 *
 * <p>{@code nodeIds} 是画布上要标红的那几个节点，{@code message} 必须给出**怎么改**，
 * 而不只是「不合法」——保存失败时用户看到的第一句话决定了他能不能自己修好。
 *
 * @param code    错误码
 * @param message 面向用户的中文说明
 * @param nodeIds 相关节点 id
 * @author carbon
 */
public record ValidationError(ValidationCode code, String message, List<String> nodeIds) {

    public ValidationError {
        nodeIds = nodeIds == null ? List.of() : List.copyOf(nodeIds);
    }

    /**
     * 构造。
     *
     * @param code    错误码
     * @param message 说明
     * @param nodeIds 相关节点 id
     * @return 错误对象
     */
    public static ValidationError of(ValidationCode code, String message, String... nodeIds) {
        return new ValidationError(code, message, Arrays.asList(nodeIds));
    }

    /**
     * 构造（节点 id 以列表给出）。
     *
     * @param code    错误码
     * @param message 说明
     * @param nodeIds 相关节点 id
     * @return 错误对象
     */
    public static ValidationError of(ValidationCode code, String message, List<String> nodeIds) {
        return new ValidationError(code, message, nodeIds);
    }

    @Override
    public String toString() {
        return code + "[" + String.join(",", nodeIds) + "] " + message;
    }
}
