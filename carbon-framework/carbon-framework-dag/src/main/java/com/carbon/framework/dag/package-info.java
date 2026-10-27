/**
 * 编排编译与执行适配（业务无关、不依赖 Spring）：DagDsl 模型、GraphValidator 结构化校验、
 * ElCompiler（DSL → LiteFlow EL）、ChainRegistry（注册与广播）、StepMapper（执行轨迹回填）。
 * 本模块只认“节点与边”，不知道“流程”这一业务概念。
 */
package com.carbon.framework.dag;
