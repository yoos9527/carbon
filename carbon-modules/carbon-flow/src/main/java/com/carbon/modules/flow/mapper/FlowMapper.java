package com.carbon.modules.flow.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.carbon.modules.flow.domain.entity.Flow;
import org.apache.ibatis.annotations.Mapper;

/**
 * 流程 Mapper。
 *
 * @author carbon
 */
@Mapper
public interface FlowMapper extends BaseMapper<Flow> {
}
