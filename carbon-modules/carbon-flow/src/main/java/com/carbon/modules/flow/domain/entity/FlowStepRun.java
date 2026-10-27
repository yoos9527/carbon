package com.carbon.modules.flow.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 节点执行明细。LOOP/FOREACH 会把同一节点展开成多行，靠 exec_index 排序。
 *
 * @author carbon
 */
@TableName("flow_step_run")
public class FlowStepRun {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long flowRunId;

    private String nodeId;

    private String nodeType;

    private Long connectorId;

    private Long operationId;

    private Integer attempt;

    private Integer execIndex;

    private String requestSummary;

    private String responseSummary;

    private String status;

    private Integer durationMs;

    private String errorMsg;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getFlowRunId() {
        return flowRunId;
    }

    public void setFlowRunId(Long flowRunId) {
        this.flowRunId = flowRunId;
    }

    public String getNodeId() {
        return nodeId;
    }

    public void setNodeId(String nodeId) {
        this.nodeId = nodeId;
    }

    public String getNodeType() {
        return nodeType;
    }

    public void setNodeType(String nodeType) {
        this.nodeType = nodeType;
    }

    public Long getConnectorId() {
        return connectorId;
    }

    public void setConnectorId(Long connectorId) {
        this.connectorId = connectorId;
    }

    public Long getOperationId() {
        return operationId;
    }

    public void setOperationId(Long operationId) {
        this.operationId = operationId;
    }

    public Integer getAttempt() {
        return attempt;
    }

    public void setAttempt(Integer attempt) {
        this.attempt = attempt;
    }

    public Integer getExecIndex() {
        return execIndex;
    }

    public void setExecIndex(Integer execIndex) {
        this.execIndex = execIndex;
    }

    public String getRequestSummary() {
        return requestSummary;
    }

    public void setRequestSummary(String requestSummary) {
        this.requestSummary = requestSummary;
    }

    public String getResponseSummary() {
        return responseSummary;
    }

    public void setResponseSummary(String responseSummary) {
        this.responseSummary = responseSummary;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Integer durationMs) {
        this.durationMs = durationMs;
    }

    public String getErrorMsg() {
        return errorMsg;
    }

    public void setErrorMsg(String error) {
        this.errorMsg = error;
    }

}
