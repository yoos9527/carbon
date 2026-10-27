package com.carbon.modules.connector.controller;

import com.carbon.common.core.domain.R;
import com.carbon.common.json.JsonUtils;
import com.carbon.modules.connector.domain.entity.Env;
import com.carbon.modules.connector.service.IEnvService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 环境查询接口。
 *
 * <p>第一阶段只读：环境变量属于部署配置，改它需要重启或热加载策略，
 * 用 SQL/配置文件维护比做个半吊子的 CRUD 页面更清楚。
 *
 * @author carbon
 */
@RestController
@RequestMapping("/api/envs")
public class EnvController {

    private final IEnvService envService;

    public EnvController(IEnvService envService) {
        this.envService = envService;
    }

    @GetMapping
    public R<List<Map<String, Object>>> list() {
        return R.ok(envService.list().stream().map(this::toView).toList());
    }

    /** {@code code} 省略时返回默认环境的变量 */
    @GetMapping("/vars")
    public R<Map<String, Object>> vars(@RequestParam(required = false) String code) {
        return R.ok(envService.varsOf(code));
    }

    private Map<String, Object> toView(Env env) {
        return Map.of(
                "id", env.getId(),
                "code", env.getCode(),
                "name", env.getName(),
                "baseUrlPrefix", env.getBaseUrlPrefix() == null ? "" : env.getBaseUrlPrefix(),
                "vars", env.getVars() == null ? Map.of() : JsonUtils.parseMap(env.getVars()),
                "isDefault", env.getIsDefault());
    }
}
