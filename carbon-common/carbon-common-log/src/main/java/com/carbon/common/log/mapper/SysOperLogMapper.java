package com.carbon.common.log.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.carbon.common.log.domain.SysOperLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 操作日志 Mapper。只做「插入 + 按条件分页」，没有自定义 SQL。
 *
 * @author carbon
 */
@Mapper
public interface SysOperLogMapper extends BaseMapper<SysOperLog> {
}
