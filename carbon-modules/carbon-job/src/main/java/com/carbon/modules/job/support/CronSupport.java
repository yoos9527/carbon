package com.carbon.modules.job.support;

import com.carbon.common.core.exception.ServiceException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;
import org.quartz.CronExpression;
import org.quartz.CronScheduleBuilder;

/**
 * cron / 时区 / misfire 的全部换算都收在这里。
 *
 * <p>为什么不让调用方各自 new {@link CronExpression}：Quartz 的 cron 解析有两个容易踩的点——
 * ① 表达式合法但要配合时区才有意义（同一串 cron 在 UTC 和 Asia/Shanghai 触发时间差 8 小时）；
 * ② 秒级表达式（6 段）与经典 5 段的字段含义不同。集中一处，好统一报错文案。
 *
 * @author carbon
 */
public final class CronSupport {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private CronSupport() {
    }

    /** 校验表达式与时区，不合法直接抛 {@code PARAM_INVALID}，避免脏数据进 Quartz。 */
    public static void validate(String cron, String timezone) {
        if (cron == null || cron.isBlank()) {
            throw ServiceException.param("cron 不能为空");
        }
        if (!CronExpression.isValidExpression(cron)) {
            throw ServiceException.param("cron 表达式不合法：" + cron + "（Quartz 支持 6 段或 7 段，秒在最前）");
        }
        zoneOf(timezone);
    }

    public static ZoneId zoneOf(String timezone) {
        String id = (timezone == null || timezone.isBlank()) ? "Asia/Shanghai" : timezone;
        try {
            return ZoneId.of(id);
        } catch (RuntimeException e) {
            throw ServiceException.param("时区不合法：" + id + "，应使用 IANA 名称，例如 Asia/Shanghai");
        }
    }

    /** 按给定次数往后推触发时间，用于前端「下次触发」预览。 */
    public static List<String> nextFireTimes(String cron, String timezone, int count) {
        ZoneId zone = zoneOf(timezone);
        CronExpression expression;
        try {
            expression = new CronExpression(cron);
        } catch (java.text.ParseException | RuntimeException e) {
            return List.of();
        }
        expression.setTimeZone(TimeZone.getTimeZone(zone));
        List<String> result = new ArrayList<>(count);
        Date cursor = new Date();
        for (int i = 0; i < count; i++) {
            Date next = expression.getNextValidTimeAfter(cursor);
            if (next == null) {
                break;
            }
            result.add(Instant.ofEpochMilli(next.getTime()).atZone(zone).format(FMT));
            cursor = next;
        }
        return result;
    }

    /**
     * 组装 CronScheduleBuilder，把 misfire 策略翻成 Quartz 的指令。
     *
     * <p>{@code DO_NOTHING} = 错过的这次直接跳过（生产上多数任务该这样：补跑一批已过时的任务
     * 只会制造雪崩）；{@code FIRE_ONCE} = 只补最近的一次，不补全部。
     */
    public static CronScheduleBuilder scheduleBuilder(String cron, String timezone, String misfirePolicy) {
        CronScheduleBuilder builder = CronScheduleBuilder
                .cronSchedule(cron)
                .inTimeZone(TimeZone.getTimeZone(zoneOf(timezone)));
        if ("FIRE_ONCE".equalsIgnoreCase(misfirePolicy)) {
            return builder.withMisfireHandlingInstructionFireAndProceed();
        }
        return builder.withMisfireHandlingInstructionDoNothing();
    }
}
