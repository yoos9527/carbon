package com.carbon.framework.script;

import com.fasterxml.jackson.databind.ObjectMapper;
import groovy.lang.Binding;
import groovy.lang.Closure;
import groovy.lang.GroovyClassLoader;
import groovy.lang.Script;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.customizers.ImportCustomizer;
import org.codehaus.groovy.ast.stmt.DoWhileStatement;
import org.codehaus.groovy.ast.stmt.WhileStatement;
import org.codehaus.groovy.control.customizers.SecureASTCustomizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Groovy 沙箱。
 *
 * <p><b>三层防线</b>：
 * <ol>
 *   <li>编译期：{@code ImportCustomizer} 只放行 5 个白名单包；{@code SecureASTCustomizer} 禁反射/系统/线程类型；
 *       {@link ForbiddenConstructCustomizer} 禁 while/do-while 与反射方法名；</li>
 *   <li>运行期：每次执行独立守护线程 + 墙钟超时，超时即中断并<b>丢弃该线程</b>；</li>
 *   <li>能力期：脚本只拿到 {@code ctx} 与 {@link ScriptUtils}（纯计算，无 IO、无反射）。</li>
 * </ol>
 *
 * <p>不满足条件的脚本在编译期就失败，不会进入执行——这比「运行时拦」可解释得多：
 * 用户拿到的是「第 3 行禁止 while 循环」，而不是某个看不懂的 NPE。
 *
 * @author carbon
 */
public class GroovySandbox implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(GroovySandbox.class);

    /** 白名单包（全限定 import）。 */
    private static final List<String> IMPORT_WHITELIST = List.of(
            "java.math.BigDecimal", "java.math.RoundingMode", "java.math.BigInteger");

    /** 白名单包（星号 import）。{@code java.time.format} / {@code java.time.temporal} 必须单列——星号白名单是精确前缀匹配，不递归。 */
    private static final List<String> STAR_IMPORT_WHITELIST = List.of(
            "java.util", "java.time", "java.time.format", "java.time.temporal", "java.time.chrono", "groovy.json");

    private final GroovySandboxOptions options;
    private final ObjectMapper objectMapper;
    private final CompilerConfiguration compilerConfiguration;
    private final Map<String, CacheEntry> classCache = new LinkedHashMap<>(64, 0.75f, true);

    /**
     * 构造。
     *
     * @param options      参数
     * @param objectMapper JSON 映射器（可为 null）
     */
    public GroovySandbox(GroovySandboxOptions options, ObjectMapper objectMapper) {
        this.options = options == null ? GroovySandboxOptions.defaults() : options;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
        this.compilerConfiguration = buildConfiguration();
    }

    /**
     * 执行脚本。
     *
     * @param script 脚本原文
     * @param ctx    上下文（{@code request}/{@code response}/{@code vars}/{@code input}/{@code env}），会被原地改写
     * @param traceId 当前调用 traceId（可为 null）
     * @return 执行结果
     */
    public ScriptResult execute(String script, Map<String, Object> ctx, String traceId) {
        Map<String, Object> context = ctx == null ? new LinkedHashMap<>() : ctx;
        String hash = hashOf(script);
        if (!options.enabled()) {
            log.warn("[script] 沙箱已关闭，跳过执行 script={}", hash);
            return new ScriptResult(context, hash, 0L, true);
        }
        if (script == null || script.isBlank()) {
            return new ScriptResult(context, hash, 0L, false);
        }

        ScriptUtils utils = new ScriptUtils(objectMapper, traceId);
        context.put("utils", utils);
        context.put("log", new Closure<Object>(this) {
            private static final long serialVersionUID = 1L;

            @SuppressWarnings("unused")
            public Object doCall(Object message) {
                utils.log(String.valueOf(message));
                return null;
            }
        });

        long start = System.nanoTime();
        ScriptTask task = new ScriptTask(script, hash, context, utils);
        Thread thread = Thread.ofPlatform()
                .daemon(true)
                .name("carbon-script-" + hash)
                .unstarted(task);
        thread.start();
        try {
            thread.join(options.timeoutMs());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            thread.interrupt();
            throw new ScriptError(ScriptError.Phase.TIMEOUT, hash, "脚本执行被外部中断", e);
        }
        if (thread.isAlive()) {
            thread.interrupt();
            throw new ScriptError(ScriptError.Phase.TIMEOUT, hash,
                    "脚本执行超时（上限 " + options.timeoutMs() + "ms），已强制丢弃", null);
        }
        if (task.error != null) {
            throw task.error;
        }
        long costMs = (System.nanoTime() - start) / 1_000_000L;
        return new ScriptResult(context, hash, costMs, false);
    }

    /**
     * 当前编译缓存条数（便于断言缓存生效）。
     *
     * @return 条数
     */
    public synchronized int cacheSize() {
        return classCache.size();
    }

    private Class<?> classOf(String script, String hash) {
        long now = System.currentTimeMillis();
        synchronized (this) {
            CacheEntry entry = classCache.get(hash);
            if (entry != null && now - entry.lastAccess <= options.cacheTtlMs()) {
                entry.lastAccess = now;
                return entry.clazz;
            }
            if (entry != null) {
                classCache.remove(hash);
            }
        }
        Class<?> compiled = compile(script, hash);
        synchronized (this) {
            classCache.put(hash, new CacheEntry(compiled, System.currentTimeMillis()));
            while (classCache.size() > options.cacheMax()) {
                var eldest = classCache.entrySet().iterator().next();
                classCache.remove(eldest.getKey());
            }
        }
        return compiled;
    }

    private Class<?> compile(String script, String hash) {
        try (GroovyClassLoader loader = new GroovyClassLoader(getClass().getClassLoader(), compilerConfiguration)) {
            return loader.parseClass(script, "CarbonScript_" + hash + ".groovy");
        } catch (SecurityException e) {
            throw new ScriptError(ScriptError.Phase.COMPILE, hash, "脚本被沙箱拒绝：" + e.getMessage(), e);
        } catch (Exception e) {
            throw new ScriptError(ScriptError.Phase.COMPILE, hash, "脚本编译失败：" + firstLine(e.getMessage()), e);
        }
    }

    private CompilerConfiguration buildConfiguration() {
        CompilerConfiguration configuration = new CompilerConfiguration();

        ImportCustomizer imports = new ImportCustomizer();
        IMPORT_WHITELIST.forEach(imports::addImports);
        STAR_IMPORT_WHITELIST.forEach(imports::addStarImports);

        SecureASTCustomizer secure = new SecureASTCustomizer();
        secure.setImportsWhitelist(IMPORT_WHITELIST);
        secure.setStarImportsWhitelist(STAR_IMPORT_WHITELIST);
        // 不开 indirect import check：它会把「类型不在白名单里的任何方法调用」都判违规，
        // 于是 ctx.vars.xxx = 1 这种唯一正确的写法都会被拒。默认导入的 java.io / java.net
        // 由 ForbiddenConstructCustomizer 的类型前缀黑名单兜住。
        secure.setIndirectImportCheckEnabled(false);
        secure.setPackageAllowed(false);
        secure.setClosuresAllowed(true);
        secure.setMethodDefinitionAllowed(true);
        // 语句级黑名单：while / do-while 直接禁掉（纯 CPU 循环不响应中断，超时救不了）
        secure.setDisallowedStatements(List.of(WhileStatement.class, DoWhileStatement.class));
        // 静态 import 一律不放行
        secure.setStaticImportsWhitelist(List.of());
        secure.setStaticStarImportsWhitelist(List.of());
        // 类型级黑名单：系统、进程、线程、反射
        secure.setDisallowedReceiversClasses(List.of(
                System.class, Runtime.class, Class.class, Thread.class, ThreadGroup.class,
                ProcessBuilder.class, ClassLoader.class, java.lang.reflect.Method.class));

        List<org.codehaus.groovy.control.customizers.CompilationCustomizer> customizers = new ArrayList<>(3);
        customizers.add(imports);
        customizers.add(secure);
        customizers.add(new ForbiddenConstructCustomizer());
        configuration.addCompilationCustomizers(customizers.toArray(
                new org.codehaus.groovy.control.customizers.CompilationCustomizer[0]));
        return configuration;
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "未知错误";
        }
        int idx = message.indexOf('\n');
        return idx < 0 ? message : message.substring(0, idx);
    }

    /**
     * 计算脚本指纹：sha256 前 12 位十六进制。
     *
     * @param script 脚本原文
     * @return 指纹
     */
    public static String hashOf(String script) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((script == null ? "" : script).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM 缺少 SHA-256", e);
        }
    }

    @Override
    public void close() {
        synchronized (this) {
            classCache.clear();
        }
    }

    /** 编译缓存条目。 */
    private static final class CacheEntry {
        private final Class<?> clazz;
        private long lastAccess;

        private CacheEntry(Class<?> clazz, long lastAccess) {
            this.clazz = clazz;
            this.lastAccess = lastAccess;
        }
    }

    /** 单次执行任务（跑在独立守护线程里，超时即被丢弃）。 */
    private final class ScriptTask implements Runnable {
        private final String script;
        private final String hash;
        private final Map<String, Object> context;
        private final ScriptUtils utils;
        private volatile ScriptError error;

        private ScriptTask(String script, String hash, Map<String, Object> context, ScriptUtils utils) {
            this.script = script;
            this.hash = hash;
            this.context = context;
            this.utils = utils;
        }

        @Override
        public void run() {
            try {
                Class<?> clazz = classOf(script, hash);
                Script instance = (Script) clazz.getDeclaredConstructor().newInstance();
                Binding binding = new Binding();
                binding.setVariable("ctx", context);
                binding.setVariable("utils", utils);
                instance.setBinding(binding);
                instance.run();
            } catch (ScriptError e) {
                this.error = e;
            } catch (SecurityException e) {
                this.error = new ScriptError(ScriptError.Phase.COMPILE, hash, "脚本被沙箱拒绝：" + e.getMessage(), e);
            } catch (Throwable e) {
                this.error = new ScriptError(ScriptError.Phase.RUNTIME, hash,
                        "脚本执行失败：" + firstLine(e.getMessage()), e);
            }
        }
    }
}
