package com.carbon.modules.connector.service.impl;

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
            throw new ServiceException("应用名不能为空");
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
    public void updateStatus(Long id, Integer status) {
        App app = requireApp(id);
        if (status == null || (status != 0 && status != 1)) {
            throw new ServiceException("status 只能是 0 或 1");
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
            throw new ServiceException("应用不存在：" + id);
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
        view.put("createTime", app.getCreatedAt());
        view.put("updateTime", app.getUpdatedAt());
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
