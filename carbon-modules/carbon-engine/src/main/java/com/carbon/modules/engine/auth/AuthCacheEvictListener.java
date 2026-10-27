package com.carbon.modules.engine.auth;

import com.carbon.common.core.event.ConnectorAuthChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 连接器鉴权变更 → 丢掉该连接器的 token 缓存。
 *
 * <p>为什么监听 {@code AFTER_COMMIT}：配置写库回滚了就不该清缓存——清了也不生效，
 * 反而让一次失败的编辑把线上正在用的 token 打掉，下一次调用要重新换 token（多一次三方往返）。
 *
 * <p>{@code fallbackExecution = true}：调用方可能不在事务里（例如脚本、将来的批量导入），
 * 那种情况下没有事务可等，直接执行即可。
 *
 * @author carbon
 */
@Component
public class AuthCacheEvictListener {

    private static final Logger log = LoggerFactory.getLogger(AuthCacheEvictListener.class);

    private final AuthResolver authResolver;

    public AuthCacheEvictListener(AuthResolver authResolver) {
        this.authResolver = authResolver;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onAuthChanged(ConnectorAuthChangedEvent event) {
        if (event.connectorId() == null) {
            return;
        }
        authResolver.invalidate(event.connectorId());
        log.debug("[auth] 连接器 {} 鉴权变更，token 缓存已清", event.connectorId());
    }
}
