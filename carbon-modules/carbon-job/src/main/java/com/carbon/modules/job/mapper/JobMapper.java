package com.carbon.modules.job.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.carbon.modules.job.domain.entity.Job;
import org.apache.ibatis.annotations.Mapper;

/**
 * @author carbon
 */
@Mapper
public interface JobMapper extends BaseMapper<Job> {
}
