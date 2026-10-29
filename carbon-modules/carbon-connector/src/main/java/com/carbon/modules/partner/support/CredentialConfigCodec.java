package com.carbon.modules.partner.support;

import com.carbon.common.encrypt.CryptoService;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 凭证配置的加密与脱敏。
 *
 * <p>三个职责放一起，是因为它们必须用**同一份敏感字段清单**：
 * {@link #encryptForSave} 入库前把敏感字段转成 {@code ENC:v1:} 密文；
 * {@link #mask} 出参时换成 {@code ******}；{@link #decrypt} 调用三方前还原明文。
 * 清单一旦分裂成两份，「前端看到的脱敏字段」和「库里加密的字段」就不一致，
 * 表现为「保存后调用失败」这种最难查的 bug。
 *
 * <p><b>为什么不加密整个 config 列</b>：tokenUrl、clientId、scope 这些要能查、能看、能排错。
 * 整列加密后连「这套凭证配的是哪个 token 地址」都要解密才知道，运维成本不值。
 *
 * @author carbon
 */
public final class CredentialConfigCodec {

    /** 敏感字段在各类凭证下的名字（不同厂商叫法不同，这里取平台契约里的名字） */
    private static final Map<String, Set<String>> SECRET_KEYS = Map.of(
            "none", Set.of(),
            "api_key", Set.of("value"),
            "basic", Set.of("password"),
            "bearer", Set.of("token"),
            "oauth2_cc", Set.of("clientSecret"),
            "login_token", Set.of("password"));

    /** 前端拿到的掩码；提交时原样回传视为「不修改」 */
    public static final String MASK = "******";

    private CredentialConfigCodec() {
    }

    public static Set<String> secretKeys(String type) {
        return SECRET_KEYS.getOrDefault(type, Set.of());
    }

    /**
     * 入库前的 config：敏感字段加密，非敏感字段原样保留。
     *
     * <p>{@code existingPlain} 是库里的**明文**旧值：入参为空或等于掩码就沿用它
     * （编辑凭证时不该因为没重填密钥就把密钥清空）。
     */
    public static Map<String, Object> encryptForSave(String type,
                                                     Map<String, Object> incoming,
                                                     Map<String, Object> existingPlain,
                                                     CryptoService crypto) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (incoming != null) {
            result.putAll(incoming);
        }
        for (String key : secretKeys(type)) {
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
                result.put(key, text);
            } else {
                result.put(key, crypto.encrypt(text));
            }
        }
        return result;
    }

    /** 出参用的 config：敏感字段一律换成掩码 */
    public static Map<String, Object> mask(String type, Map<String, Object> stored) {
        Map<String, Object> masked = new HashMap<>();
        if (stored == null) {
            return masked;
        }
        Set<String> secrets = secretKeys(type);
        stored.forEach((key, value) -> {
            if (secrets.contains(key)) {
                masked.put(key, value == null ? null : MASK);
            } else {
                masked.put(key, value);
            }
        });
        return masked;
    }

    /** 调用三方前用的 config：所有 {@code ENC:v1:} 值解成明文；历史明文原样返回 */
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
