package com.carbon.modules.engine.extract;

import com.carbon.common.core.enums.BizError;
import com.carbon.common.core.exception.ServiceException;
import com.carbon.common.json.JsonUtils;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import com.jayway.jsonpath.spi.json.JacksonJsonProvider;
import com.jayway.jsonpath.spi.mapper.JacksonMappingProvider;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 响应抽取：把三方的原始报文压成本系统约定的字段集合。
 *
 * <p><b>为什么要有这一层</b>：三方返回的结构千奇百怪，业务侧不应该知道
 * 「微信的金额在 {@code $.data.amount}、支付宝的在 {@code $.response.total_amount}」。
 * 连接器的价值有一半在这里。
 *
 * <p>两个刻意的行为：
 * <ul>
 *   <li>JSONPath 取不到时**不抛异常**，走 {@code defaultValue}；
 *       只有 {@code required=true} 才报 {@code EXTRACT_FAILED}。
 *       三方接口「可选字段时有时无」太常见，配置成必填应该是显式选择。</li>
 *   <li>JSON 解析失败不是抽取错误，是 {@code PROVIDER_HTTP_2XX} 但内容非 JSON，
 *       报 {@code EXTRACT_FAILED} 并带上原文片段——否则用户只会看到「抽取为空」。</li>
 * </ul>
 *
 * @author carbon
 */
public final class ResponseExtractor {

    private static final Configuration LENIENT = Configuration.builder()
            .jsonProvider(new JacksonJsonProvider(JsonUtils.mapper()))
            .mappingProvider(new JacksonMappingProvider(JsonUtils.mapper()))
            .options(Option.SUPPRESS_EXCEPTIONS)
            .build();

    private ResponseExtractor() {
    }

    /**
     * 单条抽取规则。
     *
     * @param name         输出字段名
     * @param jsonpath     JSONPath 表达式
     * @param defaultValue 取不到时的兜底值
     * @param required     {@code true} 表示取不到就失败
     * @author carbon
     */
    public record Rule(String name, String jsonpath, Object defaultValue, boolean required) {
    }

    /**
     * 从实体上那份 JSON 规则串解析出规则列表。
     *
     * <p>容忍三种写法：字段名 {@code jsonpath} / {@code path} / {@code expr}，
     * 因为这份配置在不同版本的导入导出里叫过不同名字，没必要让用户为此重配。
     */
    public static List<Rule> parseRules(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        List<Object> raw = JsonUtils.parseList(json);
        List<Rule> rules = new ArrayList<>(raw.size());
        for (Object item : raw) {
            if (!(item instanceof Map<?, ?> map)) {
                continue;
            }
            String name = text(map.get("name"));
            String path = text(map.get("jsonpath"));
            if (path == null) {
                path = text(map.get("path"));
            }
            if (path == null) {
                path = text(map.get("expr"));
            }
            if (name == null || path == null) {
                continue;
            }
            rules.add(new Rule(name, path, map.get("defaultValue"), Boolean.TRUE.equals(map.get("required"))));
        }
        return rules;
    }

    /**
     * 执行抽取。
     *
     * @param body  三方响应原文
     * @param rules 规则列表；为空时返回空 Map（表示「不透传字段」，不是失败）
     * @throws ServiceException {@code EXTRACT_FAILED}
     */
    public static Map<String, Object> extract(String body, List<Rule> rules) {
        if (rules == null || rules.isEmpty()) {
            return Map.of();
        }
        Object document;
        try {
            document = LENIENT.jsonProvider().parse(body == null ? "" : body);
        } catch (Exception e) {
            throw ServiceException.of(BizError.EXTRACT_FAILED,
                    "响应不是合法 JSON，无法抽取：" + abbreviate(body));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        for (Rule rule : rules) {
            Object value;
            try {
                value = JsonPath.using(LENIENT).parse(document).read(rule.jsonpath());
            } catch (Exception e) {
                value = null;
            }
            if (value == null) {
                if (rule.required()) {
                    throw ServiceException.of(BizError.EXTRACT_FAILED,
                            "必填字段抽取失败：" + rule.name() + "（" + rule.jsonpath() + "）");
                }
                if (rule.defaultValue() != null) {
                    result.put(rule.name(), rule.defaultValue());
                } else {
                    result.put(rule.name(), null);
                }
            } else {
                result.put(rule.name(), value);
            }
        }
        return result;
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private static String abbreviate(String body) {
        if (body == null) {
            return "";
        }
        return body.length() <= 200 ? body : body.substring(0, 200) + "...";
    }
}
