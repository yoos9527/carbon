package com.carbon.modules.invokelog.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.carbon.modules.invokelog.domain.entity.InvokeLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 调用日志 Mapper。
 *
 * <p>只用 insert 与 select：这张表不允许 update（审计数据被改写就失去意义）。
 *
 * @author carbon
 */
@Mapper
public interface InvokeLogMapper extends BaseMapper<InvokeLog> {
}
