package com.carbon.modules.connector.domain.bo;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 连接器复制的入参。
 *
 * <p>全部字段可空：不填就由服务端推导（`code` 取 `<原编码>-copy`，`name` 取「<原名> 副本」）。
 * 复制是「照着已有的改一个」这个动作的快捷方式——从零填一个 15 字段的连接器表单
 * 才是真正浪费时间的部分，而编码往往就是原编码加个后缀。
 *
 * @author carbon
 */
public class ConnectorCopyBo {

    /** 新编码；留空则自动生成，格式与原编码规则一致 */
    @Pattern(regexp = "^[a-z0-9_-]{2,64}$", message = "编码只能是小写字母、数字、下划线或中划线，2~64 位")
    private String code;

    /** 新名称；留空则「原名 + 副本」 */
    @Size(max = 64, message = "名称最长 64 字")
    private String name;

    /**
     * 是否连接口（operation）一起复制，默认 true。
     *
     * <p>复制连接器却留下空接口列表的场景很少见；只想复用基址与鉴权的人
     * 传 false 即可，不必事后一条条删。
     */
    private Boolean withOperations;

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Boolean getWithOperations() {
        return withOperations;
    }

    public void setWithOperations(Boolean withOperations) {
        this.withOperations = withOperations;
    }

    public boolean copyOperations() {
        return withOperations == null || withOperations;
    }
}
