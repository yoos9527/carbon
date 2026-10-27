package com.carbon.common.core.domain;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 分页响应体：{@code total} + {@code rows}。
 *
 * <p>与 {@link R} 组合使用（{@code R<TableDataInfo<T>>}），前端表格组件直接吃这两个字段。
 *
 * @param <T> 行数据类型
 * @author carbon
 */
public class TableDataInfo<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private long total;
    private List<T> rows;

    public TableDataInfo() {
    }

    public TableDataInfo(long total, List<T> rows) {
        this.total = total;
        this.rows = rows;
    }

    public static <T> TableDataInfo<T> of(long total, List<T> rows) {
        return new TableDataInfo<>(total, rows);
    }

    public static <T> TableDataInfo<T> empty() {
        return new TableDataInfo<>(0L, List.of());
    }

    public long getTotal() {
        return total;
    }

    public void setTotal(long total) {
        this.total = total;
    }

    public List<T> getRows() {
        return rows;
    }

    public void setRows(List<T> rows) {
        this.rows = rows;
    }
}
