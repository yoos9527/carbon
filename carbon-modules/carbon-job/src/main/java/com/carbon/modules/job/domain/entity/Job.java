package com.carbon.modules.job.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import java.time.LocalDateTime;

/**
 * 一个定时任务：把某个已发布的流程按 cron 触发。
 *
 * <p>这张表是**真身**，Quartz 里的 JobDetail/CronTrigger 是它的投影：
 * 应用启动时按表重建 Quartz 侧定义（{@code JobSyncService}），所以集群里加节点、
 * 或者有人手滑删了 QRTZ_* 表，重启就能自愈。
 *
 * @author carbon
 */
@TableName("job")
public class Job {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long flowId;

    private String name;

    private String cron;

    private String timezone;

    /** DO_NOTHING（错过就跳过）| FIRE_ONCE（补一次）。 */
    private String misfirePolicy;

    /** ALLOW（允许重叠）| DISALLOW（同一任务串行）。 */
    private String concurrency;

    /** 作为流程的 input，JSON 文本。 */
    private String params;

    /** 1 = 已排进 Quartz；0 = 从 Quartz 摘掉。 */
    private Integer enabled;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createdAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

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

    public String getParams() {
        return params;
    }

    public void setParams(String params) {
        this.params = params;
    }

    public Integer getEnabled() {
        return enabled;
    }

    public void setEnabled(Integer enabled) {
        this.enabled = enabled;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
