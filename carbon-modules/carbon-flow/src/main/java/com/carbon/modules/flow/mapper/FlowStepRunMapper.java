package com.carbon.modules.flow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.carbon.modules.flow.domain.entity.FlowStepRun;
import org.apache.ibatis.annotations.Mapper;

/**
 * 节点执行明细 Mapper。
 *
 * @author carbon
 */
@Mapper
public interface FlowStepRunMapper extends BaseMapper<FlowStepRun> {
}
