package com.carbon.modules.job.domain.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;

/**
 * 任务的写入口。
 *
 * <p>{@code params} 用 Map 而不是 String：前端传对象，后端转 JSON 文本落库；
 * 用字符串的话前端要自己转义一次 JSON，多一次出错机会。
 *
 * @author carbon
 */
public class JobBo {

    @NotNull
    private Long flowId;

    @NotBlank
    @Size(max = 128)
    private String name;

    /** Quartz 6/7 段表达式，合法性由服务层用 CronExpression 校验。 */
    @NotBlank
    @Size(max = 64)
    private String cron;

    @Size(max = 64)
    private String timezone;

    @Pattern(regexp = "^(DO_NOTHING|FIRE_ONCE)$")
    private String misfirePolicy;

    @Pattern(regexp = "^(ALLOW|DISALLOW)$")
    private String concurrency;

    /** 作为流程 input；null 或 {} 表示无参数。 */
    private Map<String, Object> params;

    private Integer enabled;

    public Long getFlowId() {
        return flowId;
    }

    public void setFlowId(Long flowId) {
        this.flowId = flowId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCron() {
        return cron;
    }

    public void setCron(String cron) {
        this.cron = cron;
    }

    public String getTimezone() {
        return timezone;
    }

    public void setTimezone(String timezone) {
        this.timezone = timezone;
    }

    public String getMisfirePolicy() {
        return misfirePolicy;
    }

    public void setMisfirePolicy(String misfirePolicy) {
        this.misfirePolicy = misfirePolicy;
    }

    public String getConcurrency() {
        return concurrency;
    }

    public void setConcurrency(String concurrency) {
        this.concurrency = concurrency;
    }

    public Map<String, Object> getParams() {
        return params;
    }

    public void setParams(Map<String, Object> params) {
        this.params = params;
    }

    public Integer getEnabled() {
        return enabled;
    }

    public void setEnabled(Integer enabled) {
        this.enabled = enabled;
    }
}
