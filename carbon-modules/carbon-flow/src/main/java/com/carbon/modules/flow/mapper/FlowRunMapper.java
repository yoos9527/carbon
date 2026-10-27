package com.carbon.modules.flow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.carbon.modules.flow.domain.entity.FlowRun;
import org.apache.ibatis.annotations.Mapper;

/**
 * 编排执行 Mapper。
 *
 * @author carbon
 */
@Mapper
public interface FlowRunMapper extends BaseMapper<FlowRun> {
}
