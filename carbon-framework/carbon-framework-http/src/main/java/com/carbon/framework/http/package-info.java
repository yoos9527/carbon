/**
 * HTTP 执行器（业务无关、不依赖 Spring）：HttpExecutor、RetryPolicy/Backoff、ResponseTruncator。
 * 注意：必须显式关闭 HttpClient5 的自动重试与自动重定向。
 */
package com.carbon.framework.http;
