package com.carbon.common.web.config;

import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalTimeSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;

/**
 * HTTP 出参的 JSON 约定。
 *
 * <p>两件事，都是**契约**级别，不是风格问题：
 *
 * <ol>
 *   <li><b>雪花 id 序列化成字符串</b>。JS 的 number 只有 53 位精度，19 位的雪花 id
 *       （如 {@code 2115009578035945473}）经 {@code JSON.parse} 后被四舍五入成
 *       {@code 2115009578035945500}，页面表现是「列表能看、点详情 404」——因为回传的
 *       已经是另一个 id 了。只序列化装箱 {@code Long}：{@code TableDataInfo.total}
 *       这类 {@code long} 基本类型仍是数字，前端分页不用改。
 *   <li><b>时间统一 {@code yyyy-MM-dd HH:mm:ss}</b>。{@code spring.jackson.date-format}
 *       <b>不作用于</b> {@code LocalDateTime}（那是 {@code java.util.Date} 的开关），
 *       不在这里显式注册就退化成 ISO-8601（{@code 2026-10-27T09:59:31.756}），
 *       前端得自己截字符串。反序列化同步注册，否则 {@code PUT} 回传的时间解析不了。
 * </ol>
 *
 * <p>用 {@link Jackson2ObjectMapperBuilderCustomizer} 而不是自己 new 一个
 * {@code ObjectMapper}：容器里的 mapper 还要承担 {@code spring.jackson.*} 的其他配置
 * （{@code non_null}、未知字段忽略），替换整个实例会把那些一并弄丢。
 *
 * <p>注意：{@code carbon-common-json} 的 {@code JsonUtils} 用的是**冻结配置**，
 * 不受本类影响——刻意如此，见那里的注释。
 *
 * @author carbon
 */
@Configuration
public class JacksonConfig {

    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer carbonJacksonCustomizer() {
        return builder -> builder
                .serializerByType(Long.class, ToStringSerializer.instance)
                .serializerByType(LocalDateTime.class, new LocalDateTimeSerializer(DATE_TIME))
                .serializerByType(LocalDate.class, new LocalDateSerializer(DATE))
                .serializerByType(LocalTime.class, new LocalTimeSerializer(TIME))
                .deserializerByType(LocalDateTime.class, new LocalDateTimeDeserializer(DATE_TIME))
                .deserializerByType(LocalDate.class, new LocalDateDeserializer(DATE))
                .deserializerByType(LocalTime.class, new LocalTimeDeserializer(TIME));
    }
}
