package com.carbon.common.idempotent;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 提交去重：同一调用方在 {@link #interval()} 毫秒内，用<b>同一方法 + 同一参数</b>再打一次，
 * 直接拒绝并提示，不再执行。
 *
 * <p>解决的是<b>重复创建</b>这一类问题：鼠标双击「新建连接器」、表单提交后网络超时用户手动重试、
 * 前端按钮没禁用又点了一次——这几种情况都会留下两条一模一样的配置，
 * 而事后清理比当时拦住贵得多。
 *
 * <p>它<b>不是</b>严格的分布式幂等：不保证「只执行一次」，只保证「短时间内的重复请求不会都执行」。
 * 真正的幂等要靠业务键（如支付单号）做唯一约束，那属于业务语义，平台不该假装能替业务决定。
 *
 * <p>去重键 = 方法签名 + 客户端 IP + 参数的 JSON 摘要，所以：
 * <ul>
 *   <li>两个人同时建同一个编码的连接器<b>不会</b>被拦（IP 不同），他们会被唯一键拦住——各司其职；</li>
 *   <li>同一个人的两次不同提交<b>不会</b>被拦（参数不同），只有参数完全一致才算重复。</li>
 * </ul>
 *
 * @author carbon
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RepeatSubmit {

    /** 去重窗口，毫秒。默认 3 秒：够覆盖双击与手快重试，又不至于让人正常操作也被拦 */
    int interval() default 3000;

    /** 命中重复时的提示语，直接给到用户 */
    String message() default "请勿重复提交，稍后再试";
}
