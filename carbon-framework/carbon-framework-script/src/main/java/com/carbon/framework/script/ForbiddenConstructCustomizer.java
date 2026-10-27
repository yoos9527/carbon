package com.carbon.framework.script;

import org.codehaus.groovy.ast.ClassCodeVisitorSupport;
import org.codehaus.groovy.ast.ClassNode;
import org.codehaus.groovy.ast.expr.ConstructorCallExpression;
import org.codehaus.groovy.ast.expr.MethodCallExpression;
import org.codehaus.groovy.ast.expr.StaticMethodCallExpression;
import org.codehaus.groovy.classgen.GeneratorContext;
import org.codehaus.groovy.control.CompilePhase;
import org.codehaus.groovy.control.SourceUnit;
import org.codehaus.groovy.control.customizers.CompilationCustomizer;

import java.util.List;
import java.util.Set;

/**
 * 语法结构层面的加固（{@code SecureASTCustomizer} 管不到或管不合适的那部分）。
 *
 * <p>为什么不打开 {@code SecureASTCustomizer.setIndirectImportCheckEnabled(true)}：
 * 它会把<b>任何</b>「类型不在白名单里的方法调用」都判违规，于是 {@code ctx.vars} 这种
 * 「脚本只该看到 Map」的正常写法也会被拒。那不是一个能用的沙箱，而是个不能用的沙箱。
 *
 * <p>但关掉它以后有个必须自己补的洞：Groovy 默认 import 了 {@code java.io.*} / {@code java.net.*}，
 * 所以 {@code new File('/etc/passwd')} 不写 import 也能解析出来。这里用<b>类型前缀黑名单</b>
 * 在构造与静态调用两处补上。
 *
 * @author carbon
 */
final class ForbiddenConstructCustomizer extends CompilationCustomizer {

    /** 明确禁止的方法名（反射逃逸、进程退出、动态派发）。 */
    private static final Set<String> FORBIDDEN_METHODS = Set.of(
            "exit", "halt", "getClass", "newInstance", "invokeMethod", "forName", "setAccessible",
            "loadClass", "defineClass", "getDeclaredMethod", "getDeclaredField");

    /**
     * 类型级禁用前缀：文件、网络、反射、并发、容器内核。
     *
     * <p>注意不能把 {@code org.codehaus.groovy.} 整个拉黑：编译器会为动态派发生成
     * {@code InvokerHelper.xxx(...)} 静态调用，拦了它连 {@code ctx.vars.x = 1} 都编不过。
     * 具体危险类（{@code GroovyShell} / {@code GroovyClassLoader}）走精确名单。
     */
    private static final List<String> FORBIDDEN_TYPE_PREFIXES = List.of(
            "java.io.", "java.net.", "java.nio.", "java.lang.reflect.", "java.lang.invoke.",
            "java.util.concurrent.", "java.lang.management.", "javax.", "jakarta.");

    /** 精确禁用的类型。 */
    private static final Set<String> FORBIDDEN_TYPES = Set.of(
            "java.lang.System", "java.lang.Runtime", "java.lang.ProcessBuilder", "java.lang.Process",
            "java.lang.Thread", "java.lang.ThreadGroup", "java.lang.ClassLoader", "java.lang.Class",
            "groovy.lang.GroovyShell", "groovy.lang.GroovyClassLoader", "groovy.lang.GroovySystem",
            "java.util.Timer");

    /**
     * 简单名禁用名单：斜了绕过的口子——Groovy 默认 import {@code java.io.*} / {@code java.net.*}，
     * 用户不写 import 就能拿到这些类；而类型没解析时只能看到简单名。
     */
    private static final Set<String> FORBIDDEN_SIMPLE_NAMES = Set.of(
            "File", "FileInputStream", "FileOutputStream", "FileReader", "FileWriter", "RandomAccessFile",
            "Files", "Paths", "Path", "URL", "URI", "Socket", "ServerSocket", "DatagramSocket",
            "InetAddress", "HttpClient", "HttpURLConnection", "ProcessBuilder", "Runtime", "Thread",
            "ClassLoader", "Timer", "GroovyShell", "GroovyClassLoader");

    ForbiddenConstructCustomizer() {
        // 必须是 SEMANTIC_ANALYSIS：CONVERSION 阶段类型还没解析，`new File(...)` 的 getType()
        // 只会给出未解析的简单名 "File"，前缀黑名单能生生效。
        super(CompilePhase.SEMANTIC_ANALYSIS);
    }

    @Override
    public void call(SourceUnit source, GeneratorContext context, ClassNode classNode) {
        if (classNode == null) {
            return;
        }
        SourceUnit unit = source;
        classNode.visitContents(new ClassCodeVisitorSupport() {

            @Override
            protected SourceUnit getSourceUnit() {
                return unit;
            }

            @Override
            public void visitConstructorCallExpression(ConstructorCallExpression call) {
                assertTypeAllowed(call.getType().getName(), "实例化");
                super.visitConstructorCallExpression(call);
            }

            @Override
            public void visitStaticMethodCallExpression(StaticMethodCallExpression call) {
                assertTypeAllowed(call.getOwnerType().getName(), "静态调用");
                assertMethodAllowed(call.getMethod());
                super.visitStaticMethodCallExpression(call);
            }

            @Override
            public void visitMethodCallExpression(MethodCallExpression call) {
                assertMethodAllowed(call.getMethodAsString());
                super.visitMethodCallExpression(call);
            }
        });
    }

    private static void assertMethodAllowed(String name) {
        if (name != null && FORBIDDEN_METHODS.contains(name)) {
            throw new SecurityException("禁止调用方法 " + name + "()");
        }
    }

    private static void assertTypeAllowed(String typeName, String action) {
        if (typeName == null) {
            return;
        }
        String simpleName = typeName.substring(typeName.lastIndexOf('.') + 1);
        if (FORBIDDEN_TYPES.contains(typeName) || FORBIDDEN_SIMPLE_NAMES.contains(simpleName)) {
            throw new SecurityException("禁止" + action + " " + typeName);
        }
        for (String prefix : FORBIDDEN_TYPE_PREFIXES) {
            if (typeName.startsWith(prefix)) {
                throw new SecurityException("禁止" + action + " " + typeName);
            }
        }
    }
}
