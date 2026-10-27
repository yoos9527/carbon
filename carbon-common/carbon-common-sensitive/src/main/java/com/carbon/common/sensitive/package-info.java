/**
 * 脱敏：把「能证明你身份的字符串」换成星号。
 *
 * <p>只有<b>工具类</b>（{@code MaskingUtils}），<b>刻意不做</b>注解式自动脱敏
 * （{@code @Sensitive} + 自定义 {@code JsonSerializer}）。原因很实在：
 * 平台里没有任何一个 VO 字段是「需要序列化时自动打码」的——
 * <ul>
 *   <li>{@code app_secret} 从来不回传（只在创建/重置那一次返回明文，且是显式构造的 Map）；</li>
 *   <li>{@code connector_auth.config} 从不整体回传，只回 {@code authConfigMasked}（服务层决定形状）；</li>
 *   <li>真正需要打码的是<b>自由形态</b>的东西：HTTP 报文头、URL query、JSON body、日志参数快照——
 *       它们没有对应的 Java 字段，注解无用武之地。</li>
 * </ul>
 * 所以这里只提供「按需调用」的函数。若将来真出现带敏感字段的 VO，再加注解式实现也不迟——
 * 到那时它会有一个真实的调用点，而不是一个为了「架构完整」而存在的切面。
 */
package com.carbon.common.sensitive;
