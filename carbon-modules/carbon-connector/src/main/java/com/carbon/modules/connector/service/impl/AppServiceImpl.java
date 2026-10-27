package com.carbon.modules.connector.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.carbon.common.core.exception.ServiceException;
import com.carbon.common.encrypt.CryptoService;
import com.carbon.common.json.JsonUtils;
import com.carbon.modules.connector.domain.entity.App;
import com.carbon.modules.connector.mapper.AppMapper;
import com.carbon.modules.connector.service.IAppService;
import com.fasterxml.jackson.core.type.TypeReference;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 调用方应用实现。
 *
 * <p>密钥用 32 字节随机数（base64 后 44 个字符），不是 UUID：HMAC-SHA256 的强度上限就是密钥熵，
 * 而 UUID v4 只有 122 位且格式化固定，一旦被拿到样例就能判断生成方式。
 *
 * @author carbon
 */
@Service
public class AppServiceImpl implements IAppService {

    private static final SecureRandom RANDOM = new SecureRandom();

    /** 单应用 QPS 上限：再高就该走多应用拆流，而不是一个 key 打满 */
    private static final int MAX_QPS = 10_000;

    private final AppMapper appMapper;
    private final CryptoService cryptoService;

    public AppServiceImpl(AppMapper appMapper, CryptoService cryptoService) {
        this.appMapper = appMapper;
        this.cryptoService = cryptoService;
    }

    @Override
    public List<Map<String, Object>> list() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (App app : appMapper.selectList(Wrappers.<App>lambdaQuery().orderByDesc(App::getCreatedAt))) {
            rows.add(toView(app));
        }
        return rows;
    }

    @Override
    public Map<String, Object> create(String name, List<Long> allowedConnectors, Integer rateLimitQps,
                                      List<String> ipWhitelist) {
        if (name == null || name.isBlank()) {
            throw ServiceException.param("应用名不能为空");
        }
        String secret = newSecret();
        App app = new App();
        app.setName(name);
        app.setAppKey("ck_" + randomHex(12));
        app.setAppSecretEnc(cryptoService.encrypt(secret));
        app.setAllowedConnectors(allowedConnectors == null || allowedConnectors.isEmpty()
                ? null : JsonUtils.toJson(allowedConnectors));
        app.setRateLimitQps(rateLimitQps == null || rateLimitQps <= 0 ? 50 : rateLimitQps);
        app.setIpWhitelist(ipWhitelist == null || ipWhitelist.isEmpty() ? null : JsonUtils.toJson(ipWhitelist));
        app.setStatus(1);
        app.setCreatedAt(LocalDateTime.now());
        app.setUpdatedAt(app.getCreatedAt());
        appMapper.insert(app);

        Map<String, Object> view = toView(app);
        // 整个平台唯一一次出现明文的地方，之后任何接口都只会返回「已设置」
        view.put("appSecret", secret);
        view.put("notice", "密钥只显示这一次，请立刻保存；丢失只能重新生成");
        return view;
    }

    @Override
    public Map<String, Object> rotateSecret(Long id) {
        App app = requireApp(id);
        String secret = newSecret();
        app.setAppSecretEnc(cryptoService.encrypt(secret));
        app.setUpdatedAt(LocalDateTime.now());
        appMapper.updateById(app);

        Map<String, Object> view = toView(app);
        view.put("appSecret", secret);
        view.put("notice", "旧密钥已立即失效，请同步更新调用方");
        return view;
    }

    @Override
    public void update(Long id, String name, List<Long> allowedConnectors, Integer rateLimitQps,
                       List<String> ipWhitelist) {
        requireApp(id);
        if (name == null || name.isBlank()) {
            throw ServiceException.param("应用名不能为空");
        }
        if (rateLimitQps != null && (rateLimitQps <= 0 || rateLimitQps > MAX_QPS)) {
            throw ServiceException.param("QPS 必须在 1~" + MAX_QPS + " 之间：" + rateLimitQps);
        }
        if (ipWhitelist != null) {
            ipWhitelist.forEach(AppServiceImpl::assertIpOrCidr);
        }
        // 用 update wrapper 而不是 updateById：MyBatis-Plus 的 updateById 默认跳过 null 字段，
        // 而「清空白名单」正是把列写成 null，走实体更新会静默不生效（前端点了保存、库里还是老值）。
        LambdaUpdateWrapper<App> update = Wrappers.<App>lambdaUpdate()
                .eq(App::getId, id)
                .set(App::getName, name.trim())
                .set(App::getUpdatedAt, LocalDateTime.now());
        if (allowedConnectors != null) {
            // 空集合写成 null：库里 null 的表达是「不限制」，写 "[]" 会让网关侧多一种要判断的形态
            update.set(App::getAllowedConnectors,
                    allowedConnectors.isEmpty() ? null : JsonUtils.toJson(allowedConnectors));
        }
        if (rateLimitQps != null) {
            update.set(App::getRateLimitQps, rateLimitQps);
        }
        if (ipWhitelist != null) {
            update.set(App::getIpWhitelist, ipWhitelist.isEmpty() ? null : JsonUtils.toJson(ipWhitelist));
        }
        appMapper.update(null, update);
    }

    @Override
    public void updateStatus(Long id, Integer status) {
        App app = requireApp(id);
        if (status == null || (status != 0 && status != 1)) {
            throw ServiceException.param("status 只能是 0 或 1");
        }
        app.setStatus(status);
        app.setUpdatedAt(LocalDateTime.now());
        appMapper.updateById(app);
    }

    @Override
    public void delete(Long id) {
        requireApp(id);
        appMapper.deleteById(id);
    }

    @Override
    public AppCredential findByAppKey(String appKey) {
        if (appKey == null || appKey.isBlank()) {
            return null;
        }
        App app = appMapper.selectOne(Wrappers.<App>lambdaQuery()
                .eq(App::getAppKey, appKey)
                .eq(App::getStatus, 1)
                .last("limit 1"));
        if (app == null) {
            return null;
        }
        return new AppCredential(app, cryptoService.decrypt(app.getAppSecretEnc()));
    }

    private App requireApp(Long id) {
        App app = id == null ? null : appMapper.selectById(id);
        if (app == null) {
            throw ServiceException.param("应用不存在：" + id);
        }
        return app;
    }

    private Map<String, Object> toView(App app) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("id", app.getId());
        view.put("name", app.getName());
        view.put("appKey", app.getAppKey());
        view.put("secretSet", app.getAppSecretEnc() != null);
        view.put("allowedConnectors", parseLongs(app.getAllowedConnectors()));
        view.put("rateLimitQps", app.getRateLimitQps());
        view.put("ipWhitelist", parseStrings(app.getIpWhitelist()));
        view.put("status", app.getStatus());
        // 字段名与平台其它实体一致：createdAt/updatedAt（早先写成 createTime/updateTime，前端每个模块都要记两套）
        view.put("createdAt", app.getCreatedAt());
        view.put("updatedAt", app.getUpdatedAt());
        return view;
    }

    private List<Long> parseLongs(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return JsonUtils.mapper().readValue(json, new TypeReference<List<Long>>() { });
        } catch (Exception e) {
            return List.of();
        }
    }

    private List<String> parseStrings(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return JsonUtils.mapper().readValue(json, new TypeReference<List<String>>() { });
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * IP 白名单条目校验：接受 {@code 10.0.0.1} 与 {@code 10.0.0.0/8}（IPv6 同理）。
     *
     * <p>为什么在保存时校验而不是等网关运行时：写错的条目在运行时表现为「调用方被 403」，
     * 而错误信息里不会说「是你白名单写错了」。保存时拦下来，问题就落在写下它的那一刻。
     */
    private static void assertIpOrCidr(String entry) {
        if (entry == null || entry.isBlank()) {
            throw ServiceException.param("IP 白名单里不能有空条目");
        }
        String text = entry.trim();
        String address = text;
        String prefix = null;
        int slash = text.indexOf('/');
        if (slash >= 0) {
            address = text.substring(0, slash);
            prefix = text.substring(slash + 1);
        }
        java.net.InetAddress parsed;
        try {
            parsed = java.net.InetAddress.getByName(address);
        } catch (Exception e) {
            throw ServiceException.param("不是合法的 IP 或 CIDR：" + text);
        }
        if (prefix != null) {
            int max = parsed instanceof java.net.Inet4Address ? 32 : 128;
            int bits;
            try {
                bits = Integer.parseInt(prefix);
            } catch (NumberFormatException e) {
                throw ServiceException.param("CIDR 前缀不是数字：" + text);
            }
            if (bits < 0 || bits > max) {
                throw ServiceException.param("CIDR 前缀超出范围（0~" + max + "）：" + text);
            }
        }
    }

    private static String newSecret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static String randomHex(int bytes) {
        byte[] buf = new byte[bytes];
        RANDOM.nextBytes(buf);
        StringBuilder sb = new StringBuilder(bytes * 2);
        for (byte b : buf) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }
}
