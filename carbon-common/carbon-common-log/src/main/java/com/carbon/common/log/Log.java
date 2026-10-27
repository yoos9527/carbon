package com.carbon.common.log;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 操作日志：把「谁在什么时候改了哪个模块的什么东西」记进 {@code sys_oper_log}。
 *
 * <p>平台目前<b>没有登录</b>，所以这里记不下「谁」——只有 IP、UA 与时间。
 * 即便如此它仍然必要：配置域每一条变更都会影响线上调用，
 * 出现「昨天还能调，今天 401」时，第一件事就是查「昨天谁改了鉴权」。
 * 等接入登录后，只需在切面里补一个 {@code user_id} 列，调用点不用动。
 *
 * <p>只标在<b>写接口</b>上。读接口（列表、详情、试调、SSE）不记：
 * 它们高频、无副作用，记了只会让真正重要的变更被淹没。
 *
 * @author carbon
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Log {

    /** 模块名，用于列表筛选与阅读，如「连接器」「环境」「应用」 */
    String title();

    /** 动作类型 */
    com.carbon.common.log.BusinessType businessType() default com.carbon.common.log.BusinessType.OTHER;
}
