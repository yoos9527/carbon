package com.carbon.modules.flow.domain.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 流程新建/修改入参。
 *
 * <p>{@code dsl} 是**字符串**而不是结构化对象：结构合法性由 {@code GraphValidator} 判定，
 * 走 Bean Validation 只能给出「缺左括号」这种噪音；而且草稿允许暂时不合法（用户改到一半要保存）。
 *
 * @author carbon
 */
public class FlowBo {

    @NotBlank(message = "流程 code 不能为空")
    @Pattern(regexp = "^[A-Za-z][A-Za-z0-9_]{1,63}$",
            message = "流程 code 只能由字母、数字、下划线组成，且以字母开头")
    private String code;

    @NotBlank(message = "流程名称不能为空")
    @Size(max = 128, message = "流程名称不能超过 128 字符")
    private String name;

    /** 画布 DSL（JSON 串）。允许不合法——保存草稿不该要求流程已能跑通。 */
    private String draftDsl;

    /** 0=停用 1=启用。 */
    private Integer status;

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDraftDsl() {
        return draftDsl;
    }

    public void setDraftDsl(String draftDsl) {
        this.draftDsl = draftDsl;
    }

    public Integer getStatus() {
        return status;
    }

    public void setStatus(Integer status) {
        this.status = status;
    }
}
