package com.carbon.common.mybatis.interceptor;

import com.baomidou.mybatisplus.core.toolkit.Assert;
import com.baomidou.mybatisplus.extension.plugins.inner.BlockAttackInnerInterceptor;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.schema.Table;

/**
 * 只拦「完全没有 WHERE」的全表 update/delete。
 *
 * <p>不能直接用 {@link BlockAttackInnerInterceptor} 的原版判定：MyBatis-Plus 3.5.18 会把
 * 逻辑删除生成的 SQL 判成全表操作。它的 {@code fullMatch} 走 {@code AndExpression} 时只认
 * **右支**，而逻辑删除的 where 形如 {@code id IN (?) AND deleted=0}——右支正好是
 * {@code deleted=0}（逻辑字段常量比较）→ 判定为「全表」→ 抛
 * {@code Prohibition of table update operation}。结果是**所有软删除全挂**
 * （连接器、接口、流程、任务、环境都删不掉），而 {@code updateById} 因为逻辑字段在左支反而正常，
 * 所以这个坑只在删除路径上暴露。
 *
 * <p>取舍：原版还会拦 {@code update ... where deleted=0} 这类「条件退化成逻辑字段」的语句。
 * 那个能力我们放弃——它与软删除的 SQL 形状无法区分，误伤代价（所有删除不可用）远大于收益。
 * 真正要防的 {@code update t set x=1}（无 WHERE）仍然拦得住。
 *
 * @author carbon
 */
public class LogicDeleteSafeBlockAttackInterceptor extends BlockAttackInnerInterceptor {

    @Override
    protected void checkWhere(Table table, Expression where, String message) {
        Assert.isFalse(where == null, message);
    }
}
