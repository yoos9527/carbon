package com.carbon.common.log;

/**
 * 操作类型。刻意只有五种：日志的价值在于「筛得出来」，类型越多越筛不动。
 *
 * @author carbon
 */
public enum BusinessType {

    /** 新建 */
    INSERT,

    /** 修改（含启停、重置密钥这类「改状态」） */
    UPDATE,

    /** 删除 */
    DELETE,

    /** 立即执行/手动触发这类「不改配置但产生副作用」的动作 */
    EXECUTE,

    /** 其余（发布、回滚、校验等） */
    OTHER
}
