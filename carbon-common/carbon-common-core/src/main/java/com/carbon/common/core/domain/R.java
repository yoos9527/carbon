package com.carbon.common.core.domain;

import java.io.Serial;
import java.io.Serializable;

/**
 * 统一响应体：{@code code} / {@code msg} / {@code data}。
 *
 * <p>对齐 RuoYi-Vue-Plus 的 {@code R<T>}，前端拦截器只认这三个字段。
 * 成功恒为 {@link #SUCCESS}，失败用 {@link com.carbon.common.core.exception.BizError} 的码。
 *
 * <p><b>注意</b>：对外网关不允许直接返回本类，见 {@code GwResult}——
 * 网关响应体是给第三方业务系统看的，契约里不能出现平台内部的 {@code msg} 文案。
 *
 * @param <T> 业务数据类型
 * @author carbon
 */
public class R<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 成功码 */
    public static final int SUCCESS = 200;

    /** 通用失败码（未归类的系统异常） */
    public static final int FAIL = 500;

    private int code;
    private String msg;
    private T data;

    public R() {
    }

    public R(int code, String msg, T data) {
        this.code = code;
        this.msg = msg;
        this.data = data;
    }

    public static <T> R<T> ok() {
        return new R<>(SUCCESS, "操作成功", null);
    }

    public static <T> R<T> ok(T data) {
        return new R<>(SUCCESS, "操作成功", data);
    }

    public static <T> R<T> ok(T data, String msg) {
        return new R<>(SUCCESS, msg, data);
    }

    public static <T> R<T> fail() {
        return new R<>(FAIL, "操作失败", null);
    }

    public static <T> R<T> fail(String msg) {
        return new R<>(FAIL, msg, null);
    }

    public static <T> R<T> fail(int code, String msg) {
        return new R<>(code, msg, null);
    }

    public boolean isSuccess() {
        return this.code == SUCCESS;
    }

    public int getCode() {
        return code;
    }

    public void setCode(int code) {
        this.code = code;
    }

    public String getMsg() {
        return msg;
    }

    public void setMsg(String msg) {
        this.msg = msg;
    }

    public T getData() {
        return data;
    }

    public void setData(T data) {
        this.data = data;
    }
}
