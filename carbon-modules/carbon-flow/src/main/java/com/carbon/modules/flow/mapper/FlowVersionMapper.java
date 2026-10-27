package com.carbon.modules.flow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.carbon.modules.flow.domain.entity.FlowVersion;
import org.apache.ibatis.annotations.Mapper;

/**
 * 流程版本 Mapper。
 *
 * @author carbon
 */
@Mapper
public interface FlowVersionMapper extends BaseMapper<FlowVersion> {
}
