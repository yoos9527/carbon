package com.carbon.common.core.domain;

import java.io.Serial;
import java.io.Serializable;

/**
 * 分页查询入参：{@code pageNum} / {@code pageSize} / {@code orderByColumn} / {@code isAsc}。
 *
 * <p>字段名对齐 RuoYi-Vue-Plus，前端表格组件的分页参数可以直接透传。
 * <b>排序必须走白名单</b>：{@code orderByColumn} 会被拼进 SQL，只允许出现字母、数字、下划线，
 * 且最终列名由 Service 侧的枚举映射，不接受前端任意列名。
 *
 * @author carbon
 */
public class PageQuery implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 默认页码 */
    public static final int DEFAULT_PAGE_NUM = 1;

    /** 默认每页条数 */
    public static final int DEFAULT_PAGE_SIZE = 10;

    /** 每页条数上限，防止一次拖垮库 */
    public static final int MAX_PAGE_SIZE = 200;

    private Integer pageNum = DEFAULT_PAGE_NUM;
    private Integer pageSize = DEFAULT_PAGE_SIZE;
    private String orderByColumn;
    private String isAsc = "desc";

    public int safePageNum() {
        return pageNum == null || pageNum < 1 ? DEFAULT_PAGE_NUM : pageNum;
    }

    public int safePageSize() {
        if (pageSize == null || pageSize < 1) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(pageSize, MAX_PAGE_SIZE);
    }

    /**
     * 排序方向：只接受 {@code asc} / {@code desc}，其余一律降级为 {@code desc}。
     */
    public boolean ascending() {
        return "asc".equalsIgnoreCase(isAsc);
    }

    public Integer getPageNum() {
        return pageNum;
    }

    public void setPageNum(Integer pageNum) {
        this.pageNum = pageNum;
    }

    public Integer getPageSize() {
        return pageSize;
    }

    public void setPageSize(Integer pageSize) {
        this.pageSize = pageSize;
    }

    public String getOrderByColumn() {
        return orderByColumn;
    }

    public void setOrderByColumn(String orderByColumn) {
        this.orderByColumn = orderByColumn;
    }

    public String getIsAsc() {
        return isAsc;
    }

    public void setIsAsc(String isAsc) {
        this.isAsc = isAsc;
    }
}
