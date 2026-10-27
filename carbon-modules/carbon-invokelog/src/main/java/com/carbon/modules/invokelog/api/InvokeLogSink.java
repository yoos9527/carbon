package com.carbon.modules.invokelog.api;

/**
 * 调用日志落库入口（引擎侧定义，日志模块实现）。
 *
 * <p>接口放在 invokelog（叶子模块）而不是 engine：依赖方向是 {@code engine → invokelog}。
 * 日志表结构、批次大小、丢弃策略都属于「记录日志」这件事，改它们不应该让引擎重新编译发布；
 *
 * <p>实现方**必须**保证不抛异常、不阻塞调用线程：这是审计旁路，
 * 它挂掉不能把业务请求一起带下去。用 {@code List<InvokeLogSink>} 注入即可实现
 * 「有实现就记、没有实现就跳过」。
 *
 * @author carbon
 */
@FunctionalInterface
public interface InvokeLogSink {

    /**
     * 记录一条调用日志。
     *
     * @param event 已脱敏、已截断的事件
     */
    void record(InvokeLogEvent event);
}
