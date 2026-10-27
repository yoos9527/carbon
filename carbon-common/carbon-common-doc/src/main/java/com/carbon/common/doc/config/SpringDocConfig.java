package com.carbon.common.doc.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 管理端 OpenAPI 装配。
 *
 * <p><b>只暴露一个 {@code admin} 分组</b>（{@code /v3/api-docs/admin}，UI 在 {@code /doc}）：
 * 管理端接口需要「按模块看、能试调」，而网关的对外契约是<b>另一套东西</b>——
 * 它按应用的实际授权实时生成，见 {@code carbon-gateway} 的
 * {@code GatewayOpenApiController}（{@code /v3/api-docs/gateway}）。
 * 两者刻意不合并：把内部管理接口混进给三方看的文档里，等于把攻击面清单发出去。
 *
 * <p>没有声明 {@code gateway} 分组，是为了不和上面那个手写 Controller 抢同一个路径——
 * SpringDoc 的 {@code GroupedOpenApi} 与手写映射都注册 {@code /v3/api-docs/gateway} 时，
 * 谁先注册谁生效，行为随启动顺序变化，属于必须避免的不确定性。
 *
 * @author carbon
 */
@Configuration
public class SpringDocConfig {

    @Bean
    public OpenAPI carbonOpenApi(@Value("${carbon.version:dev}") String version) {
        return new OpenAPI()
                .info(new Info()
                        .title("Carbon 连接器平台 · 管理端 API")
                        .version(version)
                        .description("""
                                内部管理接口。响应统一为 `R{code,msg,data}`，`code=200` 表示成功；
                                分页响应为 `R{code,msg,data:{total,rows}}`。
                                雪花 ID 在 JSON 里是**字符串**（19 位数字超出 JS 安全整数范围）。
                                时间是 `Asia/Shanghai`，格式 `yyyy-MM-dd HH:mm:ss`。

                                对外网关（AppKey + HMAC 签名）不在此文档内，见 `/v3/api-docs/gateway`。
                                """)
                        .contact(new Contact().name("Carbon"))
                        .license(new License().name("Apache-2.0")))
                // 管理端暂无鉴权（登录在路线图上），这里只声明公共 Header 语义，避免 UI 里无从下手
                .components(new Components().addSecuritySchemes("traceId",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-Trace-Id")
                                .description("可选。透传后可与后端日志对齐，排障时把它给运维")));
    }

    @Bean
    public GroupedOpenApi adminApi() {
        return GroupedOpenApi.builder()
                .group("admin")
                .displayName("管理端")
                .pathsToMatch("/api/**")
                // 网关、actuator 都不属于管理端契约
                .pathsToExclude("/gw/**", "/actuator/**")
                .build();
    }
}
