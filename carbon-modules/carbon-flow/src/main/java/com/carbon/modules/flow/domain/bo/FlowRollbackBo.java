package com.carbon.modules.flow.domain.bo;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * 回滚请求。
 *
 * @author carbon
 */
public class FlowRollbackBo {

    /** 目标版本号（已存在的历史版本）。 */
    @NotNull(message = "请指定要回滚到的版本号")
    @Min(value = 1, message = "版本号从 1 开始")
    private Integer version;

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }
}
