package com.carbon.common.json;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 非 Web 场景的 JSON 工具（Worker 线程、脚本桥接、日志落库）。
 *
 * <p>刻意**不复用** Spring 容器里的 {@code ObjectMapper}：容器那个受
 * {@code spring.jackson.*} 与业务自定义 {@code Module} 影响，配置一变，
 * 落库的 JSON 形态就跟着变——已入库的历史数据会解析不出来。
 * 这里用冻结配置，只认平台自己的约定。
 *
 * @author carbon
 */
public final class JsonUtils {

    private static final ObjectMapper MAPPER = build();

    private JsonUtils() {
    }

    private static ObjectMapper build() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        // 时间写成 ISO-8601 字符串而不是时间戳：落库的 JSON 要能被人直接读
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        // 三方接口经常偷偷加字段，解析不能因为多一个 key 就炸
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        return mapper;
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("序列化 JSON 失败: " + e.getMessage(), e);
        }
    }

    public static <T> T parse(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalArgumentException("解析 JSON 失败: " + e.getMessage(), e);
        }
    }

    public static <T> T parse(String json, TypeReference<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalArgumentException("解析 JSON 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 解析成有序 {@code Map}。用 {@link LinkedHashMap} 是为了让模板渲染后
     * 生成的 JSON **字段顺序稳定**——三方接口的签名经常要求原文顺序。
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseMap(String json) {
        if (json == null || json.isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<LinkedHashMap<String, Object>>() {
            });
        } catch (Exception e) {
            throw new IllegalArgumentException("解析 JSON 失败: " + e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    public static List<Object> parseList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<List<Object>>() {
            });
        } catch (Exception e) {
            throw new IllegalArgumentException("解析 JSON 数组失败: " + e.getMessage(), e);
        }
    }
}
