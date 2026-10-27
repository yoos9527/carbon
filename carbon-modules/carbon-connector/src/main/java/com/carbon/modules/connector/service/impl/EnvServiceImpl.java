package com.carbon.modules.connector.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.carbon.common.core.enums.BizError;
import com.carbon.common.core.exception.ServiceException;
import com.carbon.common.json.JsonUtils;
import com.carbon.modules.connector.domain.bo.EnvBo;
import com.carbon.modules.connector.domain.entity.Env;
import com.carbon.modules.connector.domain.vo.EnvVo;
import com.carbon.modules.connector.mapper.EnvMapper;
import com.carbon.modules.connector.service.IEnvService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
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
 * <p>写接口维护一条不变式：**只要有环境，就恰好有一个默认环境**。理由同上：
 * 允许「一个默认都没有」等于把上面那三级兜底当正常路径用，用户看到的默认环境
 * 会随插入顺序漂移，很难解释。
 *
 * @author carbon
 */
@Service
public class EnvServiceImpl implements IEnvService {

    /** 环境变量表的大小上限：它会被拼进每一次渲染的上下文，不该成为大字段仓库 */
    private static final int VARS_MAX_BYTES = 64 * 1024;

    /** 这两个 key 由 {@link #varsOf(String)} 派生注入，同名变量会被静默覆盖，所以直接禁写 */
    private static final List<String> RESERVED_KEYS = List.of("code", "baseUrlPrefix");

    private static final List<String> PREFIX_SCHEMES = List.of("http://", "https://");

    private final EnvMapper envMapper;

    public EnvServiceImpl(EnvMapper envMapper) {
        this.envMapper = envMapper;
    }

    @Override
    public List<EnvVo> list() {
        return envMapper.selectList(new LambdaQueryWrapper<Env>()
                        .orderByDesc(Env::getIsDefault)
                        .orderByAsc(Env::getCode))
                .stream().map(EnvVo::of).toList();
    }

    @Override
    public EnvVo detail(Long id) {
        return EnvVo.of(require(id));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long create(EnvBo bo) {
        String code = normalizeCode(bo.getCode());
        requireCodeAvailable(code, null);

        Env env = new Env();
        env.setCode(code);
        env.setName(bo.getName().trim());
        env.setBaseUrlPrefix(normalizePrefix(bo.getBaseUrlPrefix()));
        env.setVars(toVarsJson(bo.getVars()));
        // 第一个环境必须是默认的，否则 requireByCode 的三级兜底会立刻被用上
        boolean first = envMapper.selectCount(null) == 0;
        env.setIsDefault(first || Boolean.TRUE.equals(bo.getIsDefault()) ? 1 : 0);
        env.setRemark(bo.getRemark());
        envMapper.insert(env);

        if (env.getIsDefault() == 1) {
            clearOtherDefaults(env.getId());
        }
        return env.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, EnvBo bo) {
        Env exist = require(id);
        String code = normalizeCode(bo.getCode());
        requireCodeAvailable(code, id);

        Env update = new Env();
        update.setId(id);
        update.setCode(code);
        update.setName(bo.getName().trim());
        // 前缀传空串表示清空；传 null 表示不动（MyBatis-Plus 的 updateById 会跳过 null）
        if (bo.getBaseUrlPrefix() != null) {
            update.setBaseUrlPrefix(normalizePrefix(bo.getBaseUrlPrefix()));
        }
        if (bo.getVars() != null) {
            update.setVars(toVarsJson(bo.getVars()));
        }
        if (bo.getRemark() != null) {
            update.setRemark(bo.getRemark());
        }
        envMapper.updateById(update);

        if (Boolean.TRUE.equals(bo.getIsDefault())) {
            setDefaultFlag(id);
        } else if (Boolean.FALSE.equals(bo.getIsDefault()) && Integer.valueOf(1).equals(exist.getIsDefault())) {
            // 取消默认：先置 0，再把剩下最早的环境提上来，保证「有环境就有默认」
            demote(id);
            promoteEarliest();
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        List<Env> targets = envMapper.selectBatchIds(ids);
        if (targets.isEmpty()) {
            return;
        }
        boolean hadDefault = targets.stream().anyMatch(e -> Integer.valueOf(1).equals(e.getIsDefault()));
        if (hadDefault) {
            // 先摘默认标记再软删：逻辑删除后 WHERE 会自动带 deleted=0，删完就再也更新不到这些行了
            // （否则表里会同时存在两条 is_default=1，其中一条已删，用 SQL 直接查库的人会被误导）
            envMapper.update(new Env(), new LambdaUpdateWrapper<Env>()
                    .in(Env::getId, ids).set(Env::getIsDefault, 0));
        }
        envMapper.deleteByIds(ids);
        if (hadDefault) {
            promoteEarliest();
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setDefault(Long id) {
        require(id);
        setDefaultFlag(id);
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
            try {
                vars.putAll(JsonUtils.parseMap(env.getVars()));
            } catch (RuntimeException e) {
                throw ServiceException.of(BizError.PARAM_INVALID,
                        "环境 " + env.getCode() + " 的变量不是合法 JSON，请重新保存该环境");
            }
        }
        vars.put("code", env.getCode());
        vars.put("baseUrlPrefix", env.getBaseUrlPrefix() == null ? "" : env.getBaseUrlPrefix());
        return vars;
    }

    /* ------------------------------------------------------------------ 内部 */

    private Env require(Long id) {
        Env env = envMapper.selectById(id);
        if (env == null) {
            throw ServiceException.of(BizError.PARAM_INVALID, "环境不存在：id=" + id);
        }
        return env;
    }

    private void requireCodeAvailable(String code, Long excludeId) {
        LambdaQueryWrapper<Env> wrapper = new LambdaQueryWrapper<Env>().eq(Env::getCode, code);
        if (excludeId != null) {
            wrapper.ne(Env::getId, excludeId);
        }
        if (envMapper.selectCount(wrapper) > 0) {
            throw ServiceException.of(BizError.PARAM_INVALID, "环境编码已存在：" + code);
        }
    }

    private static String normalizeCode(String raw) {
        return raw == null ? null : raw.trim().toLowerCase();
    }

    /**
     * 前缀归一到「scheme://host[:port][/path]」：补协议头、去尾部斜杠。
     *
     * <p>补协议不是好心：{@code api-test.example.com} 拼出来的 URL 会被 HttpClient 当相对路径
     * 直接报 {@code URI with undefined scheme}，而且报错位置离「环境配错了」很远
     * （落到试调的 {@code PARAM_INVALID} 上）。宁可在这里猜一下它想表达什么。
     */
    private static String normalizePrefix(String raw) {
        if (raw == null) {
            return null;
        }
        String prefix = raw.trim();
        if (prefix.isEmpty()) {
            return "";
        }
        String lower = prefix.toLowerCase();
        if (PREFIX_SCHEMES.stream().noneMatch(lower::startsWith)) {
            prefix = "http://" + prefix;
        } else if (lower.startsWith("https://")) {
            prefix = "https://" + prefix.substring("https://".length());
        } else {
            prefix = "http://" + prefix.substring("http://".length());
        }
        while (prefix.endsWith("/")) {
            prefix = prefix.substring(0, prefix.length() - 1);
        }
        return prefix;
    }

    private static String toVarsJson(Map<String, Object> vars) {
        if (vars == null) {
            return null;
        }
        Map<String, Object> cleaned = new LinkedHashMap<>();
        vars.forEach((key, value) -> {
            if (key == null || key.isBlank()) {
                throw ServiceException.of(BizError.PARAM_INVALID, "环境变量名不能为空");
            }
            String name = key.trim();
            if (RESERVED_KEYS.contains(name)) {
                throw ServiceException.of(BizError.PARAM_INVALID,
                        "环境变量名 " + name + " 是保留字段（由平台自动注入）");
            }
            if (value != null && !(value instanceof CharSequence) && !(value instanceof Number)
                    && !(value instanceof Boolean)) {
                throw ServiceException.of(BizError.PARAM_INVALID,
                        "环境变量 " + name + " 只能是字符串/数字/布尔：模板里要的是可直接拼进 URL/报文的值");
            }
            cleaned.put(name, value == null ? null : value);
        });
        String json = cleaned.isEmpty() ? "{}" : JsonUtils.toJson(cleaned);
        if (json.length() > VARS_MAX_BYTES) {
            throw ServiceException.of(BizError.PARAM_INVALID,
                    "环境变量总大小超过 " + (VARS_MAX_BYTES / 1024) + "KB");
        }
        return json;
    }

    /**
     * 把某个环境提为默认。
     *
     * <p>注意这里用 {@code updateById} 而不是 {@code update(null, wrapper)}：
     * MyBatis-Plus 的 {@code BlockAttackInnerInterceptor} 会把「entity 为 null 的 update」
     * 判定为全表更新直接拦掉（报 {@code Prohibition of table update operation}）。
     * 批量改的标志位只能走「带 entity 的 wrapper 更新」或 {@code updateById}。
     */
    private void setDefaultFlag(Long id) {
        clearOtherDefaults(id);
        Env patch = new Env();
        patch.setId(id);
        patch.setIsDefault(1);
        envMapper.updateById(patch);
    }

    private void demote(Long id) {
        Env patch = new Env();
        patch.setId(id);
        patch.setIsDefault(0);
        envMapper.updateById(patch);
    }

    private void clearOtherDefaults(Long keepId) {
        Env patch = new Env();
        patch.setIsDefault(0);
        envMapper.update(patch, new LambdaQueryWrapper<Env>()
                .ne(Env::getId, keepId)
                .eq(Env::getIsDefault, 1));
    }

    /** 有环境但没默认时，把创建时间最早的那个提上来 */
    private void promoteEarliest() {
        Long hasDefault = envMapper.selectCount(new LambdaQueryWrapper<Env>().eq(Env::getIsDefault, 1));
        if (hasDefault != null && hasDefault > 0) {
            return;
        }
        List<Env> all = new ArrayList<>(envMapper.selectList(new LambdaQueryWrapper<Env>()
                .orderByAsc(Env::getId).last("limit 1")));
        if (!all.isEmpty()) {
            setDefaultFlag(all.get(0).getId());
        }
    }
}
