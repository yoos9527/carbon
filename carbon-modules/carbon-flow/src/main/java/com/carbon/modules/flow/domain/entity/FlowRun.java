package com.carbon.modules.flow.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/**
 * 一次编排执行。status 只有 RUNNING/SUCCESS/PARTIAL/FAILED 四种，无 FAILED 之外的细分。
 *
 * @author carbon
 */
@TableName("flow_run")
public class FlowRun {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String traceId;

    private Long flowId;

    private Integer version;

    private String triggerType;

    private String triggerBy;

    private String status;

    private String inputJson;

    private String outputJson;

    private String errorMsg;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getTraceId() {
        return traceId;
    }

    public void setTraceId(String traceId) {
        this.traceId = traceId;
    }

    public Long getFlowId() {
        return flowId;
    }

    public void setFlowId(Long flowId) {
        this.flowId = flowId;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public String getTriggerType() {
        return triggerType;
    }

    public void setTriggerType(String triggerType) {
        this.triggerType = triggerType;
    }

    public String getTriggerBy() {
        return triggerBy;
    }

    public void setTriggerBy(String triggerBy) {
        this.triggerBy = triggerBy;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getInputJson() {
        return inputJson;
    }

    public void setInputJson(String input) {
        this.inputJson = input;
    }

    public String getOutputJson() {
        return outputJson;
    }

    public void setOutputJson(String output) {
        this.outputJson = output;
    }

    public String getErrorMsg() {
        return errorMsg;
    }

    public void setErrorMsg(String error) {
        this.errorMsg = error;
    }

    public LocalDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(LocalDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public LocalDateTime getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(LocalDateTime finishedAt) {
        this.finishedAt = finishedAt;
    }

}
