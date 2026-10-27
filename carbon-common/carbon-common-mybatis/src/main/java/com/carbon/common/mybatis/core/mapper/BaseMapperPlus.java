package com.carbon.common.mybatis.core.mapper;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

/**
 * Mapper 基类：在 {@link BaseMapper} 之上只补一个「按条件分页」。
 *
 * <p>为什么只补这一个：项目里 90% 的列表查询都是「按条件分页」，其余用 {@code Wrapper} 直接写更清楚；
 * 再往上堆通用方法，只会让人猜不出最终 SQL 长什么样。
 *
 * <p>批量取 id 直接用 MyBatis-Plus 3.5.16 自带的 {@code BaseMapper#selectByIds(Collection)}，
 * 不再包一层同名方法（包了反而在子接口里产生歧义）。
 *
 * <p>逻辑删除由 {@code @TableLogic} 自动拼 {@code deleted = 0}，业务代码不许手写该条件。
 *
 * @param <T> 实体类型
 * @author carbon
 */
public interface BaseMapperPlus<T> extends BaseMapper<T> {

    /**
     * 按条件分页。{@code current} / {@code size} 必须由调用方用
     * {@link com.carbon.common.core.domain.PageQuery#safePageNum()} 归一后再传，
     * 防止一次请求拖出全表。
     */
    default IPage<T> selectPageBy(long current, long size, Wrapper<T> wrapper) {
        return selectPage(new Page<>(current, size), wrapper);
    }
}
