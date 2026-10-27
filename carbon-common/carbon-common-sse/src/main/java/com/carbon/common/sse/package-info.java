/**
 * SSE 会话管理：{@link com.carbon.common.sse.SseStreamRegistry}——
 * 「先缓冲再订阅」的进程内总线，保证客户端连上来时不会漏掉开头几步。
 *
 * <p>只做「同一进程内的实时播报」。多实例部署时订阅者可能连到没执行任务的实例，
 * 那时实时事件看不到，需要调用方用数据库轮询兜底（编排试运行就是这么做的）。
 * 真要跨实例实时推送得换 Redis Pub/Sub——那是另一种复杂度的方案，当前不值得。
 */
package com.carbon.common.sse;
