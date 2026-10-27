package com.carbon.modules.connector.support;

import com.carbon.common.encrypt.CryptoService;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 鉴权配置的加密与脱敏。
 *
 * <p>三个职责，放在一起是因为它们必须用**同一份敏感字段清单**：
 * <ul>
 *   <li>{@link #encryptForSave} 入库前把敏感字段转成 {@code ENC:v1:} 密文；</li>
 *   <li>{@link #mask} 出参时把敏感字段换成 {@code ******}；</li>
 *   <li>{@link #decrypt} 调用三方前还原明文。</li>
 * </ul>
 * 一旦清单分裂成两份，「前端看到的脱敏字段」和「库里加密的字段」就会不一致，
 * 表现为「保存后调用失败」这种最难查的 bug。
 *
 * <p><b>为什么不加密整个 config 列</b>：tokenUrl、clientId、scope 这些需要能查、能看、能排错；
 * 整列加密后连「这个连接器配的是哪个 token 地址」都要解密才能知道，运维成本不值。
 *
 * @author carbon
 */
public final class AuthConfigCodec {

    /** 敏感字段在各鉴权类型下的名字（不同厂商叫法不同，这里取平台契约里的名字） */
    private static final Map<String, Set<String>> SECRET_KEYS = Map.of(
            "none", Set.of(),
            "api_key", Set.of("value"),
            "basic", Set.of("password"),
            "bearer", Set.of("token"),
            "oauth2_cc", Set.of("clientSecret"),
            "login_token", Set.of("password"));

    /** 前端拿到的掩码；提交时原样回传视为「不修改」 */
    public static final String MASK = "******";

    private AuthConfigCodec() {
    }

    public static Set<String> secretKeys(String authType) {
        return SECRET_KEYS.getOrDefault(authType, Set.of());
    }

    /**
     * 入库前的 config：敏感字段加密，非敏感字段原样保留。
     *
     * <p>{@code existingPlain} 是库里的**明文**旧值。规则：
     * <ul>
     *   <li>入参为空或等于掩码 → 沿用旧值（编辑连接器时不该因为没重填密钥就把它清空）；</li>
     *   <li>入参是明文 → 加密后写入。</li>
     * </ul>
     */
    public static Map<String, Object> encryptForSave(String authType,
                                                     Map<String, Object> incoming,
                                                     Map<String, Object> existingPlain,
                                                     CryptoService crypto) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (incoming != null) {
            result.putAll(incoming);
        }
        for (String key : secretKeys(authType)) {
            Object raw = result.get(key);
            String text = raw == null ? null : String.valueOf(raw);
            if (text == null || text.isBlank() || MASK.equals(text)) {
                Object old = existingPlain == null ? null : existingPlain.get(key);
                if (old == null) {
                    result.remove(key);
                } else {
                    result.put(key, old);
                }
            } else if (CryptoService.isEncrypted(text)) {
                // 已经是密文（理论上不该发生，但允许内部调用直接传密文）
                result.put(key, text);
            } else {
                result.put(key, crypto.encrypt(text));
            }
        }
        return result;
    }

    /** 出参用的 config：敏感字段一律换成掩码 */
    public static Map<String, Object> mask(String authType, Map<String, Object> stored) {
        Map<String, Object> masked = new HashMap<>();
        if (stored == null) {
            return masked;
        }
        Set<String> secrets = secretKeys(authType);
        stored.forEach((key, value) -> {
            if (secrets.contains(key)) {
                masked.put(key, value == null ? null : MASK);
            } else {
                masked.put(key, value);
            }
        });
        return masked;
    }

    /**
     * 调用三方前用的 config：所有 {@code ENC:v1:} 值解成明文。
     *
     * <p>非密文原样返回，便于灰度期历史明文数据继续可用。
     */
    public static Map<String, Object> decrypt(Map<String, Object> stored, CryptoService crypto) {
        Map<String, Object> plain = new LinkedHashMap<>();
        if (stored == null) {
            return plain;
        }
        stored.forEach((key, value) -> {
            if (value instanceof String text && CryptoService.isEncrypted(text)) {
                plain.put(key, crypto.decrypt(text));
            } else {
                plain.put(key, value);
            }
        });
        return plain;
    }
}
