package com.carbon.modules.engine.credential;

import com.carbon.common.core.event.PartnerCredentialChangedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 凭证变更 → 清掉运行时缓存的 token。
 *
 * <p>用事件而不是直接调用，是为了不让 {@code carbon-connector} 反向依赖 {@code carbon-engine}
 * （见 {@code docs/01-总体架构.md} 的叶子模块约束）。事件在事务提交后才处理，
 * 否则「先删缓存、事务回滚」会留下一个短暂窗口：缓存没了但库里还是旧密钥。
 *
 * <p>{@code fallbackExecution = true} 让没有事务的调用路径（例如脚本里直接改库）也能生效。
 *
 * @author carbon
 */
@Component
public class CredentialCacheEvictListener {

    private final CredentialResolver credentialResolver;

    public CredentialCacheEvictListener(CredentialResolver credentialResolver) {
        this.credentialResolver = credentialResolver;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onCredentialChanged(PartnerCredentialChangedEvent event) {
        credentialResolver.invalidate(event.credentialId());
    }
}
