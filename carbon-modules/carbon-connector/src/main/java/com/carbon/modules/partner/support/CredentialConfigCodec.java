package com.carbon.modules.partner.support;

import com.carbon.common.core.exception.ServiceException;
import com.carbon.common.encrypt.CryptoService;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 凭证配置的加密、脱敏与结构校验。
 *
 * <p><b>只有两种凭证</b>（2026-10-29 调整，替代原来的 api_key/basic/bearer/oauth2_cc/login_token 六型）：
 * <ul>
 *   <li>{@link #TYPE_HEADER}：配置若干条 header（也可放到 query），值是密钥，逐条加密；</li>
 *   <li>{@link #TYPE_SCRIPT}：一段 Groovy 脚本，运行时算出这次要加的 header / query，
 *       脚本里用到的密钥放在 {@code secrets} 里单独加密，脚本本身是明文（它是逻辑，不是密钥）。</li>
 * </ul>
 *
 * <p>三个职责放一起，是因为它们必须用**同一份结构约定**：
 * {@link #encryptForSave} 入库前把敏感位置转成 {@code ENC:v1:} 密文；
 * {@link #mask} 出参时换成 {@code ******}；{@link #decrypt} 调用前还原明文。
 * 约定一旦分裂成两份，「前端看到的脱敏字段」和「库里加密的字段」就不一致，
 * 表现为「保存后调用失败」这种最难查的 bug。
 *
 * <p><b>为什么不加密整个 config 列</b>：脚本正文、header 名、ttl 这些要能看能排错。
 * 整列加密后连「这条凭证到底往哪个头里塞东西」都要解密才知道，运维成本不值。
 *
 * @author carbon
 */
public final class CredentialConfigCodec {

    /** 静态请求头凭证 */
    public static final String TYPE_HEADER = "header";

    /** Groovy 脚本凭证 */
    public static final String TYPE_SCRIPT = "script";

    /** 支持的凭证类型 */
    public static final Set<String> TYPES = Set.of(TYPE_HEADER, TYPE_SCRIPT);

    /** 前端拿到的掩码；提交时原样回传视为「不修改」 */
    public static final String MASK = "******";

    private static final String KEY_HEADERS = "headers";
    private static final String KEY_NAME = "name";
    private static final String KEY_VALUE = "value";
    private static final String KEY_IN = "in";
    private static final String KEY_SCRIPT = "script";
    private static final String KEY_SECRETS = "secrets";
    private static final String KEY_SENSITIVE = "sensitiveKeys";

    private CredentialConfigCodec() {
    }

    /**
     * 结构校验：类型和内容必须自洽。
     *
     * <p>校验放在保存时而不是调用时，因为「保存成功但调用必然失败」是最差的组合——
     * 配置者以为配好了，直到真实业务调过来才发现。
     */
    public static void validate(String type, Map<String, Object> config) {
        if (type == null || !TYPES.contains(type)) {
            throw ServiceException.param("凭证类型只支持 header / script：" + type);
        }
        if (config == null || config.isEmpty()) {
            throw ServiceException.param("凭证配置不能为空");
        }
        if (TYPE_HEADER.equals(type)) {
            validateHeaders(config);
        } else {
            validateScript(config);
        }
    }

    private static void validateHeaders(Map<String, Object> config) {
        List<Map<String, Object>> rows = rows(config.get(KEY_HEADERS));
        if (rows.isEmpty()) {
            throw ServiceException.param("header 凭证至少需要一条请求头");
        }
        Set<String> seen = new LinkedHashSet<>();
        for (Map<String, Object> row : rows) {
            String name = text(row.get(KEY_NAME));
            if (name == null || name.isBlank()) {
                throw ServiceException.param("第 " + (rows.indexOf(row) + 1) + " 条请求头缺少名称");
            }
            String value = text(row.get(KEY_VALUE));
            // 掩码是合法的「不修改」信号，只有彻底为空才算错
            if (value == null || value.isBlank()) {
                throw ServiceException.param("请求头 " + name + " 缺少值");
            }
            String in = inOf(row);
            if (!"header".equals(in) && !"query".equals(in)) {
                throw ServiceException.param("请求头 " + name + " 的位置只支持 header / query");
            }
            if (!seen.add(in + ":" + name.toLowerCase())) {
                throw ServiceException.param("请求头 " + name + " 重复了（同一位置只能配一条）");
            }
        }
    }

    private static void validateScript(Map<String, Object> config) {
        String script = text(config.get(KEY_SCRIPT));
        if (script == null || script.isBlank()) {
            throw ServiceException.param("script 凭证需要填写脚本内容");
        }
        Object secrets = config.get(KEY_SECRETS);
        if (secrets != null && !(secrets instanceof Map)) {
            throw ServiceException.param("secrets 必须是「键 → 值」的对象");
        }
    }

    /**
     * 入库前的 config：敏感位置加密，其余原样保留。
     *
     * <p>{@code existingPlain} 是库里的**明文**旧值：入参为空或等于掩码就沿用它
     * （编辑凭证时不该因为没重填密钥就把密钥清空）。
     */
    public static Map<String, Object> encryptForSave(String type,
                                                     Map<String, Object> incoming,
                                                     Map<String, Object> existingPlain,
                                                     CryptoService crypto) {
        if (TYPE_HEADER.equals(type)) {
            return encryptHeaders(incoming, existingPlain, crypto);
        }
        if (TYPE_SCRIPT.equals(type)) {
            return encryptScript(incoming, existingPlain, crypto);
        }
        Map<String, Object> passthrough = new LinkedHashMap<>();
        if (incoming != null) {
            passthrough.putAll(incoming);
        }
        return passthrough;
    }

    private static Map<String, Object> encryptHeaders(Map<String, Object> incoming,
                                                      Map<String, Object> existingPlain,
                                                      CryptoService crypto) {
        // 旧值按「位置:名称」索引，因为用户可能调换行的顺序
        Map<String, String> old = new LinkedHashMap<>();
        rows(existingPlain == null ? null : existingPlain.get(KEY_HEADERS)).forEach(row -> {
            String name = text(row.get(KEY_NAME));
            if (name != null) {
                old.put(inOf(row) + ":" + name.toLowerCase(), text(row.get(KEY_VALUE)));
            }
        });

        List<Object> saved = new ArrayList<>();
        for (Map<String, Object> row : rows(incoming == null ? null : incoming.get(KEY_HEADERS))) {
            String name = text(row.get(KEY_NAME));
            String in = inOf(row);
            String value = text(row.get(KEY_VALUE));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put(KEY_NAME, name);
            item.put(KEY_IN, in);
            item.put(KEY_VALUE, keepOrEncrypt(value, old.get(in + ":" + String.valueOf(name).toLowerCase()), crypto));
            saved.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(KEY_HEADERS, saved);
        return result;
    }

    private static Map<String, Object> encryptScript(Map<String, Object> incoming,
                                                     Map<String, Object> existingPlain,
                                                     CryptoService crypto) {
        Map<String, Object> source = incoming == null ? Map.of() : incoming;
        Map<String, Object> oldSecrets = asMap(existingPlain == null ? null : existingPlain.get(KEY_SECRETS));

        Map<String, Object> secrets = new LinkedHashMap<>();
        asMap(source.get(KEY_SECRETS)).forEach((key, raw) ->
                secrets.put(key, keepOrEncrypt(text(raw), text(oldSecrets.get(key)), crypto)));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put(KEY_SCRIPT, text(source.get(KEY_SCRIPT)));
        result.put(KEY_SECRETS, secrets);
        copyIfPresent(source, result, "ttlSeconds");
        copyIfPresent(source, result, KEY_SENSITIVE);
        return result;
    }

    /** 出参用的 config：敏感位置一律换成掩码 */
    public static Map<String, Object> mask(String type, Map<String, Object> stored) {
        Map<String, Object> masked = new LinkedHashMap<>();
        if (stored == null) {
            return masked;
        }
        if (TYPE_HEADER.equals(type)) {
            List<Object> rows = new ArrayList<>();
            rows(stored.get(KEY_HEADERS)).forEach(row -> {
                Map<String, Object> item = new LinkedHashMap<>(row);
                item.put(KEY_VALUE, MASK);
                rows.add(item);
            });
            masked.put(KEY_HEADERS, rows);
            return masked;
        }
        if (TYPE_SCRIPT.equals(type)) {
            masked.put(KEY_SCRIPT, stored.get(KEY_SCRIPT));
            Map<String, Object> secrets = new LinkedHashMap<>();
            asMap(stored.get(KEY_SECRETS)).forEach((key, value) -> secrets.put(key, MASK));
            masked.put(KEY_SECRETS, secrets);
            copyIfPresent(stored, masked, "ttlSeconds");
            copyIfPresent(stored, masked, KEY_SENSITIVE);
            return masked;
        }
        return new LinkedHashMap<>(stored);
    }

    /** 调用三方前用的 config：所有 {@code ENC:v1:} 值解成明文（递归，兼容嵌套结构） */
    public static Map<String, Object> decrypt(Map<String, Object> stored, CryptoService crypto) {
        Map<String, Object> plain = new LinkedHashMap<>();
        if (stored == null) {
            return plain;
        }
        stored.forEach((key, value) -> plain.put(key, deepDecrypt(value, crypto)));
        return plain;
    }

    private static Object deepDecrypt(Object value, CryptoService crypto) {
        if (value instanceof String text) {
            return CryptoService.isEncrypted(text) ? crypto.decrypt(text) : text;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> out.put(String.valueOf(k), deepDecrypt(v, crypto)));
            return out;
        }
        if (value instanceof Collection<?> list) {
            List<Object> out = new ArrayList<>();
            list.forEach(item -> out.add(deepDecrypt(item, crypto)));
            return out;
        }
        return value;
    }

    /** 空或掩码 → 沿用旧值；已是密文 → 保持；其余加密 */
    private static String keepOrEncrypt(String incoming, String existingPlain, CryptoService crypto) {
        if (incoming == null || incoming.isBlank() || MASK.equals(incoming)) {
            return existingPlain;
        }
        if (CryptoService.isEncrypted(incoming)) {
            return incoming;
        }
        return crypto.encrypt(incoming);
    }

    private static void copyIfPresent(Map<String, Object> from, Map<String, Object> to, String key) {
        if (from.containsKey(key)) {
            to.put(key, from.get(key));
        }
    }

    private static String inOf(Map<String, Object> row) {
        String in = text(row.get(KEY_IN));
        return in == null || in.isBlank() ? "header" : in.toLowerCase();
    }

    private static List<Map<String, Object>> rows(Object value) {
        List<Map<String, Object>> result = new ArrayList<>();
        if (value instanceof Collection<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> map) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    map.forEach((k, v) -> row.put(String.valueOf(k), v));
                    result.add(row);
                }
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((k, v) -> result.put(String.valueOf(k), v));
            return result;
        }
        return new LinkedHashMap<>();
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
