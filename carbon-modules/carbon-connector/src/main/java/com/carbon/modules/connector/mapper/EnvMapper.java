package com.carbon.modules.connector.mapper;

import com.carbon.common.mybatis.core.mapper.BaseMapperPlus;
import com.carbon.modules.connector.domain.entity.Env;
import org.apache.ibatis.annotations.Mapper;

/**
 * 环境 Mapper。
 *
 * <p>只放「一个 Wrapper 写不出来」的 SQL；简单查询一律在 Service 里用 LambdaQueryWrapper，
 * 别把 CRUD 摊成 XML——改字段时两边不同步的坑，比省下的代码贵。
 *
 * @author carbon
 */
@Mapper
public interface EnvMapper extends BaseMapperPlus<Env> {
}
