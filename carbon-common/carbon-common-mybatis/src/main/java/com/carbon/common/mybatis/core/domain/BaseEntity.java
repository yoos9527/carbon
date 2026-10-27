package com.carbon.common.mybatis.core.domain;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 实体基类：审计字段 + 逻辑删除。
 *
 * <p>与 DDL 对齐（见 {@code docs/02-数据模型.md} 与 {@code script/sql/carbon.sql}）：
 * {@code created_at} / {@code updated_at} / {@code deleted}。
 * 这两列由 {@code AuditMetaObjectHandler} 自动填充，业务代码不许手写。
 *
 * <p><b>为什么不用 RuoYi 的 {@code create_by / update_by}</b>：平台第一阶段不做登录（决策基线），
 * 拿不到操作人。等 §7.4 的鉴权打开后，再加两列 + 往 handler 里塞 {@code LoginHelper} 的值即可，
 * 不影响现有表结构。
 *
 * @author carbon
 */
public class BaseEntity implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;

    /** 逻辑删除标记：0 存在 / 1 已删；不出现在任何响应体里 */
    @JsonIgnore
    @TableLogic
    @TableField("deleted")
    private Integer deleted;

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

    public Integer getDeleted() {
        return deleted;
    }

    public void setDeleted(Integer deleted) {
        this.deleted = deleted;
    }
}
