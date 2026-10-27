package com.carbon.modules.connector.controller;

import com.carbon.common.core.domain.R;
import com.carbon.modules.connector.service.IAppService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import com.carbon.common.log.BusinessType;
import com.carbon.common.log.Log;
import com.carbon.common.idempotent.RepeatSubmit;

/**
 * 调用方应用管理。
 *
 * <p>没有 GET /{id} 这个接口：密钥只在创建/重置时返回一次，之后单查一个应用能看到的
 * 与列表里那一行完全相同，多一个接口只会多一处泄漏面。**但 PUT /{id} 是必须的**——
 * 白名单与 QPS 是运营期会变的东西（新接一个连接器、限流调大一倍），
 * 没有它就只能「删了重建」，而重建会换掉 appKey，接入方全得改配置。
 *
 * @author carbon
 */
@RestController
@RequestMapping("/api/apps")
public class AppController {

    private final IAppService appService;

    public AppController(IAppService appService) {
        this.appService = appService;
    }

    @GetMapping
    public R<List<Map<String, Object>>> list() {
        return R.ok(appService.list());
    }

    /**
     * 创建应用。响应里的 {@code appSecret} 是明文，只此一次。
     */
    @RepeatSubmit
    @Log(title = "应用", businessType = BusinessType.INSERT)
    @PostMapping
    public R<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        return R.ok(appService.create(
                asString(body.get("name")),
                asLongList(body.get("allowedConnectors")),
                body.get("rateLimitQps") == null ? null : Integer.valueOf(String.valueOf(body.get("rateLimitQps"))),
                asStringList(body.get("ipWhitelist"))));
    }

    /**
     * 更新名称 / 连接器白名单 / QPS / IP 白名单。
     *
     * <p>字段语义：{@code allowedConnectors}、{@code ipWhitelist} 传 {@code null} 表示不动，
     * 传空数组表示「清空 = 不限制」；{@code rateLimitQps} 传 {@code null} 表示不动。
     */
    @Log(title = "应用", businessType = BusinessType.UPDATE)
    @PutMapping("/{id}")
    public R<Void> update(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        appService.update(id,
                asString(body.get("name")),
                body.containsKey("allowedConnectors") ? asLongList(body.get("allowedConnectors")) : null,
                body.get("rateLimitQps") == null
                        ? null : Integer.valueOf(String.valueOf(body.get("rateLimitQps"))),
                body.containsKey("ipWhitelist") ? asStringList(body.get("ipWhitelist")) : null);
        return R.ok();
    }

    @RepeatSubmit
    @Log(title = "应用", businessType = BusinessType.UPDATE)
    @PutMapping("/{id}/secret")
    public R<Map<String, Object>> rotateSecret(@PathVariable Long id) {
        return R.ok(appService.rotateSecret(id));
    }

    @Log(title = "应用", businessType = BusinessType.UPDATE)
    @PutMapping("/{id}/status")
    public R<Void> updateStatus(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        appService.updateStatus(id, Integer.valueOf(String.valueOf(body.get("status"))));
        return R.ok();
    }

    @Log(title = "应用", businessType = BusinessType.DELETE)
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        appService.delete(id);
        return R.ok();
    }

    private String asString(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private List<Long> asLongList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                    .filter(java.util.Objects::nonNull)
                    .map(item -> Long.valueOf(String.valueOf(item)))
                    .toList();
        }
        return List.of();
    }

    private List<String> asStringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                    .filter(java.util.Objects::nonNull)
                    .map(String::valueOf)
                    .toList();
        }
        return List.of();
    }
}
