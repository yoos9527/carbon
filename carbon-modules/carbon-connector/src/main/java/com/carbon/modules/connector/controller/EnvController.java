package com.carbon.modules.connector.controller;

import com.carbon.common.core.domain.R;
import com.carbon.modules.connector.domain.bo.EnvBo;
import com.carbon.modules.connector.domain.vo.EnvVo;
import com.carbon.modules.connector.service.IEnvService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 环境管理接口。
 *
 * <p>{@code {{env.xxx}}} 是模板里唯一的部署期变量出口，所以这里给了完整 CRUD：
 * 换一套测试域名应该是页面上点两下，而不是给 DBA 提工单。
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
    public R<List<EnvVo>> list() {
        return R.ok(envService.list());
    }

    @GetMapping("/{id}")
    public R<EnvVo> detail(@PathVariable Long id) {
        return R.ok(envService.detail(id));
    }

    /** {@code code} 省略时返回默认环境的变量（渲染前的上下文预览用） */
    @GetMapping("/vars")
    public R<Map<String, Object>> vars(@RequestParam(required = false) String code) {
        return R.ok(envService.varsOf(code));
    }

    @PostMapping
    public R<Long> create(@Valid @RequestBody EnvBo bo) {
        return R.ok(envService.create(bo));
    }

    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @Valid @RequestBody EnvBo bo) {
        envService.update(id, bo);
        return R.ok();
    }

    @DeleteMapping
    public R<Void> delete(@RequestBody List<Long> ids) {
        envService.delete(ids);
        return R.ok();
    }

    /** 设为唯一默认（无 body，页面上一个按钮的事） */
    @PutMapping("/{id}/default")
    public R<Void> setDefault(@PathVariable Long id) {
        envService.setDefault(id);
        return R.ok();
    }
}
