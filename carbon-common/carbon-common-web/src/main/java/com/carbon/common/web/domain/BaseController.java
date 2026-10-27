package com.carbon.common.web.domain;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.carbon.common.core.domain.PageQuery;
import com.carbon.common.core.domain.TableDataInfo;
import com.carbon.common.core.exception.ServiceException;

import java.util.List;
import java.util.Set;

/**
 * Controller 基类：只管一件事——把 {@link PageQuery} 变成能直接扔给 Mapper 的 {@link Page}，
 * 并把排序列**关进白名单**。
 *
 * <p>为什么排序白名单必须在基类做：{@code orderByColumn} 会被拼进 SQL 的 {@code ORDER BY}，
 * 这是本项目唯一一处「前端字符串直接进 SQL」的入口。基类强制要求调用方传入允许的列集合，
 * 想绕过就必须显式写 {@code new Page<>(...)}，代码评审一眼能看见。
 *
 * @author carbon
 */
public abstract class BaseController {

    private static final Set<String> RESERVED = Set.of("deleted", "created_at", "updated_at");

    /**
     * 构造分页对象；{@code allowedColumns} 是**驼峰形式的实体属性名**，由 MyBatis-Plus 转下划线。
     */
    protected <T> Page<T> toPage(PageQuery query, Set<String> allowedColumns) {
        Page<T> page = new Page<>(query.safePageNum(), query.safePageSize());
        String column = query.getOrderByColumn();
        if (column == null || column.isBlank()) {
            return page;
        }
        if (!allowedColumns.contains(column) || RESERVED.contains(column)) {
            throw ServiceException.param("不支持的排序列：" + column + "，可选：" + allowedColumns);
        }
        page.addOrder(query.ascending()
                ? com.baomidou.mybatisplus.core.metadata.OrderItem.asc(column)
                : com.baomidou.mybatisplus.core.metadata.OrderItem.desc(column));
        return page;
    }

    /**
     * 分页结果 → 前端表格要的 {@code total + rows}。
     */
    protected <T> TableDataInfo<T> toTable(IPage<T> page) {
        return TableDataInfo.of(page.getTotal(), page.getRecords());
    }

    protected <T> TableDataInfo<T> toTable(List<T> rows) {
        return TableDataInfo.of(rows == null ? 0L : rows.size(), rows == null ? List.of() : rows);
    }
}
