package com.carbon.common.mybatis.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.OptimisticLockerInnerInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.carbon.common.mybatis.interceptor.LogicDeleteSafeBlockAttackInterceptor;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 插件装配。
 *
 * <p>顺序有讲究：分页插件必须在最后（MP 官方要求），否则分页 SQL 会被后面的拦截器改写。
 *
 * <p>{@link LogicDeleteSafeBlockAttackInterceptor} 拦「无 WHERE 的全表 update/delete」——
 * 本项目没有登录态兜底，一次 {@code update ... where} 拼错就是全表覆盖，这个插件是最后一道闸。
 * 它继承 MP 的原版但改掉了判定条件（原版会把逻辑删除 SQL 误判成全表操作，见该类注释）。
 *
 * <p>{@code @MapperScan} 放在这里而不是启动类：业务模块只依赖本模块就能被扫到，
 * 不给 {@code carbon-admin} 增加「必须记住加扫描路径」的隐式契约。
 *
 * @author carbon
 */
@Configuration
@MapperScan("com.carbon.**.mapper")
public class MybatisPlusConfig {

    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new OptimisticLockerInnerInterceptor());
        interceptor.addInnerInterceptor(new LogicDeleteSafeBlockAttackInterceptor());
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        // 单页上限与 PageQuery.MAX_PAGE_SIZE 对齐：即使调用方漏了归一，也不至于拖出全表
        pagination.setMaxLimit(200L);
        pagination.setOverflow(false);
        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }
}
