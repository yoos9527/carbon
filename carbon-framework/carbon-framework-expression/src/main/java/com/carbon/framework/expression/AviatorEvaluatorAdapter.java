package com.carbon.framework.expression;

import com.googlecode.aviator.AviatorEvaluator;
import com.googlecode.aviator.AviatorEvaluatorInstance;
import com.googlecode.aviator.Expression;
import com.googlecode.aviator.Feature;
import com.googlecode.aviator.Options;
import com.googlecode.aviator.runtime.function.AbstractVariadicFunction;
import com.googlecode.aviator.runtime.type.AviatorObject;
import com.googlecode.aviator.runtime.type.AviatorRuntimeJavaType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 默认求值核：Aviator，按 §4.2「Aviator 准入条件」加固。
 *
 * <p>加固四件套（缺一不可）：
 * <ol>
 *   <li>{@code setFunctionMissing(null)}——切断「未注册函数 → 反射调用」这条后门</li>
 *   <li>{@code Options.FEATURE_SET} = 空集——关掉赋值/循环/lambda/静态方法/静态字段/new 实例等全部可选特性</li>
 *   <li>{@code Options.ALLOWED_CLASS_SET} = 空集——不允许表达式里出现任何类</li>
 *   <li>{@code Options.EVAL_TIMEOUT_MS} + {@code MAX_LOOP_COUNT}——为求值设时间与迭代上限</li>
 * </ol>
 *
 * <p>函数只在 {@link BuiltinFunctions} 白名单内注册，白名单外调用直接失败。
 *
 * @author carbon
 */
public class AviatorEvaluatorAdapter implements ExpressionEvaluator {

    /** 单次求值时限（毫秒），超时抛 EXPRESSION_ERROR。 */
    private static final long EVAL_TIMEOUT_MS = 1000L;

    private final AviatorEvaluatorInstance engine;

    /**
     * 默认构造：使用加固后的独立实例（不污染 Aviator 全局实例）。
     */
    public AviatorEvaluatorAdapter() {
        this(hardenedInstance());
    }

    /**
     * 构造。
     *
     * @param engine 已加固的 Aviator 实例
     */
    public AviatorEvaluatorAdapter(AviatorEvaluatorInstance engine) {
        this.engine = engine;
    }

    /**
     * 构造一个加固后的 Aviator 实例并注册白名单函数。
     *
     * @return 实例
     */
    public static AviatorEvaluatorInstance hardenedInstance() {
        AviatorEvaluatorInstance instance = AviatorEvaluator.newInstance();
        instance.setFunctionMissing(null);
        instance.setOption(Options.FEATURE_SET, Feature.asSet());
        instance.setOption(Options.ALLOWED_CLASS_SET, Collections.emptySet());
        instance.setOption(Options.MAX_LOOP_COUNT, 1);
        instance.setOption(Options.EVAL_TIMEOUT_MS, EVAL_TIMEOUT_MS);
        instance.setOption(Options.NIL_WHEN_PROPERTY_NOT_FOUND, Boolean.TRUE);
        BuiltinFunctions.names().forEach(name -> instance.addFunction(name, new WhitelistFunction(name)));
        return instance;
    }

    @Override
    public Object eval(String expr, Map<String, Object> vars) {
        if (expr == null || expr.isBlank()) {
            throw RenderException.paramInvalid("表达式为空");
        }
        try {
            Expression compiled = engine.compile(expr.trim(), true);
            return compiled.execute(vars);
        } catch (RenderException e) {
            throw e;
        } catch (com.googlecode.aviator.exception.ExpressionSyntaxErrorException e) {
            throw new RenderException(RenderException.PARAM_INVALID,
                    "表达式语法错误: " + e.getMessage(), null, null, e);
        } catch (Exception e) {
            throw new RenderException(RenderException.EXPRESSION_ERROR,
                    "表达式求值失败: " + e.getMessage(), null, null, e);
        }
    }

    @Override
    public boolean test(String expr, Map<String, Object> vars) {
        Object value = eval(expr, vars);
        if (value instanceof Boolean b) {
            return b;
        }
        throw RenderException.paramInvalid("条件表达式必须返回 boolean，实际为 "
                + (value == null ? "null" : value.getClass().getSimpleName()));
    }

    @Override
    public String name() {
        return "aviator";
    }

    /**
     * 白名单函数的 Aviator 适配：把 Aviator 参数还原成 Java 值，再交给 {@link BuiltinFunctions}。
     *
     * <p>这样「函数语义」只有一份实现，Aviator 与 {@link SimpleExpressionEvaluator} 完全一致。
     */
    private static final class WhitelistFunction extends AbstractVariadicFunction {

        private final String functionName;

        private WhitelistFunction(String functionName) {
            this.functionName = functionName;
        }

        @Override
        public String getName() {
            return functionName;
        }

        @Override
        public AviatorObject variadicCall(Map<String, Object> env, AviatorObject... args) {
            List<Object> values = new ArrayList<>(args.length);
            for (AviatorObject arg : args) {
                values.add(arg.getValue(env));
            }
            return AviatorRuntimeJavaType.valueOf(BuiltinFunctions.call(functionName, values));
        }
    }
}
