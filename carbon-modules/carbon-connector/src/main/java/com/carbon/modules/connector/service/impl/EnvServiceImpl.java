package com.carbon.modules.connector.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.carbon.common.core.enums.BizError;
import com.carbon.common.core.exception.ServiceException;
import com.carbon.common.json.JsonUtils;
import com.carbon.modules.connector.domain.entity.Env;
import com.carbon.modules.connector.mapper.EnvMapper;
import com.carbon.modules.connector.service.IEnvService;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 环境实现。
 *
 * <p>默认环境的判定顺序：{@code is_default=1} → {@code code=dev} → 表里第一条。
 * 三级兜底是为了让「只建了一个环境但忘了勾默认」的安装也能直接跑起来——
 * 这类「配了却跑不动」的首次体验问题，比多写三行判定更贵。
 *
 * @author carbon
 */
@Service
public class EnvServiceImpl implements IEnvService {

    private final EnvMapper envMapper;

    public EnvServiceImpl(EnvMapper envMapper) {
        this.envMapper = envMapper;
    }

    @Override
    public List<Env> list() {
        return envMapper.selectList(new LambdaQueryWrapper<Env>().orderByDesc(Env::getIsDefault).orderByAsc(Env::getCode));
    }

    @Override
    public Env requireByCode(String envCode) {
        if (envCode != null && !envCode.isBlank()) {
            Env env = envMapper.selectOne(new LambdaQueryWrapper<Env>().eq(Env::getCode, envCode));
            if (env == null) {
                throw ServiceException.of(BizError.PARAM_INVALID, "环境不存在：" + envCode);
            }
            return env;
        }
        Env env = envMapper.selectOne(new LambdaQueryWrapper<Env>()
                .eq(Env::getIsDefault, 1).last("limit 1"));
        if (env == null) {
            env = envMapper.selectOne(new LambdaQueryWrapper<Env>().eq(Env::getCode, "dev").last("limit 1"));
        }
        if (env == null) {
            List<Env> all = envMapper.selectList(new LambdaQueryWrapper<Env>().last("limit 1"));
            env = all.isEmpty() ? null : all.get(0);
        }
        if (env == null) {
            throw ServiceException.of(BizError.PARAM_INVALID, "尚未配置任何环境，请先在「环境管理」里新建");
        }
        return env;
    }

    @Override
    public Map<String, Object> varsOf(String envCode) {
        Env env = requireByCode(envCode);
        Map<String, Object> vars = new LinkedHashMap<>();
        if (env.getVars() != null && !env.getVars().isBlank()) {
            vars.putAll(JsonUtils.parseMap(env.getVars()));
        }
        vars.put("code", env.getCode());
        vars.put("baseUrlPrefix", env.getBaseUrlPrefix() == null ? "" : env.getBaseUrlPrefix());
        return vars;
    }
}
