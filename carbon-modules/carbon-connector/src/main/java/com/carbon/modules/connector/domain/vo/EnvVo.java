package com.carbon.modules.connector.domain.vo;

import com.carbon.common.json.JsonUtils;
import com.carbon.modules.connector.domain.entity.Env;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 环境展示对象。
 *
 * <p>实体上的 {@code vars} 是 JSON 字符串（列类型就是 JSON），前端要的是对象；
 * 在这里解析一次，避免每个调用方都自己 {@code parseMap} 一遍、解析失败各写一套兜底。
 *
 * @author carbon
 */
public class EnvVo {

    private Long id;
    private String code;
    private String name;
    private String baseUrlPrefix;
    private Map<String, Object> vars;
    private Integer isDefault;
    private String remark;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    public static EnvVo of(Env entity) {
        EnvVo vo = new EnvVo();
        vo.id = entity.getId();
        vo.code = entity.getCode();
        vo.name = entity.getName();
        vo.baseUrlPrefix = entity.getBaseUrlPrefix() == null ? "" : entity.getBaseUrlPrefix();
        vo.vars = parseVars(entity.getVars());
        vo.isDefault = entity.getIsDefault();
        vo.remark = entity.getRemark();
        vo.createdAt = entity.getCreatedAt();
        vo.updatedAt = entity.getUpdatedAt();
        return vo;
    }

    private static Map<String, Object> parseVars(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> parsed = JsonUtils.parseMap(raw);
            return parsed == null ? Map.of() : new LinkedHashMap<>(parsed);
        } catch (RuntimeException e) {
            // 历史脏数据（手写 SQL 塞进非法 JSON）不该让整个列表 500
            return Map.of();
        }
    }

    public Long getId() {
        return id;
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }

    public String getBaseUrlPrefix() {
        return baseUrlPrefix;
    }

    public Map<String, Object> getVars() {
        return vars;
    }

    public Integer getIsDefault() {
        return isDefault;
    }

    public String getRemark() {
        return remark;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
