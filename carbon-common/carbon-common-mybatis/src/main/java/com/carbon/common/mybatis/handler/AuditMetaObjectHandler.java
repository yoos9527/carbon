package com.carbon.common.mybatis.handler;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 审计字段自动填充：{@code created_at} / {@code updated_at}。
 *
 * <p>用 {@code LocalDateTime} 而不是 {@code Date}：MySQL 侧是 {@code DATETIME(3)}，
 * JSR-310 直连不需要时区推断；容器统一 {@code Asia/Shanghai}（见 Dockerfile 与 compose）。
 *
 * <p>不允许业务代码自己 set 这两列——出现过「手工 set createdAt 但忘了 updatedAt」
 * 导致列表排序错乱的事故，这里用 {@code strictInsertFill / strictUpdateFill} 拦在框架层。
 *
 * @author carbon
 */
@Component
public class AuditMetaObjectHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        LocalDateTime now = LocalDateTime.now();
        strictInsertFill(metaObject, "createdAt", LocalDateTime.class, now);
        strictInsertFill(metaObject, "updatedAt", LocalDateTime.class, now);
        strictInsertFill(metaObject, "deleted", Integer.class, 0);
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        strictUpdateFill(metaObject, "updatedAt", LocalDateTime.class, LocalDateTime.now());
    }
}
