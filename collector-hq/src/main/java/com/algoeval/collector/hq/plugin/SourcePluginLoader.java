package com.algoeval.collector.hq.plugin;

import com.algoeval.source.sdk.HqSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import java.io.File;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.HashMap;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 行情源插件加载器（简历亮点的可运行证据）：
 * <ul>
 *   <li>classpath 模式：直接实例化类路径上的内置实现（默认）</li>
 *   <li>file 模式：自定义 URLClassLoader 加载外部 jar，扫描 HqSource SPI 实现，
 *       实例化后注册进 Spring 上下文——换 jar 不换代码即切换行情源</li>
 * </ul>
 */
@Component
public class SourcePluginLoader {

    private static final Logger log = LoggerFactory.getLogger(SourcePluginLoader.class);

    private final ConfigurableApplicationContext context;

    @Value("${algo.hq.source.plugin:classpath}")
    private String pluginSpec;

    @Value("${algo.hq.source.impl:com.algoeval.source.mock.MockExchangeSource}")
    private String classpathImpl;

    public SourcePluginLoader(ConfigurableApplicationContext context) {
        this.context = context;
    }

    /**
     * 加载行情源并注册为 Spring 单例 bean（bean 名 hqSource）。
     */
    public HqSource load(Map<String, String> props) {
        HqSource source = pluginSpec.startsWith("file:") ? loadFromJar(pluginSpec.substring(5)) : loadFromClasspath();
        source.configure(props);
        ConfigurableListableBeanFactory beanFactory = context.getBeanFactory();
        beanFactory.registerSingleton("hqSource", source);
        if (source instanceof com.algoeval.source.sdk.QuoteCurveProvider provider) {
            beanFactory.registerSingleton("quoteCurveProvider", provider);
        }
        log.info("[collector-hq] 行情源插件加载完成: {} ({})", source.name(), source.getClass().getName());
        return source;
    }

    private HqSource loadFromClasspath() {
        try {
            Class<?> clazz = Class.forName(classpathImpl);
            return (HqSource) clazz.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new IllegalStateException("classpath 模式加载行情源失败: " + classpathImpl, e);
        }
    }

    /**
     * 外部 jar 动态加载：URLClassLoader + ServiceLoader(SPI) 优先，
     * 无 SPI 声明则扫描 jar 内 class 找 HqSource 实现。
     */
    private HqSource loadFromJar(String jarPath) {
        try {
            File jar = new File(jarPath);
            if (!jar.exists()) {
                throw new IllegalStateException("插件 jar 不存在: " + jarPath);
            }
            URLClassLoader classLoader = new URLClassLoader(new URL[]{jar.toURI().toURL()},
                    getClass().getClassLoader());
            // 途径一：标准 SPI（META-INF/services/com.algoeval.source.sdk.HqSource）
            for (HqSource source : ServiceLoader.load(HqSource.class, classLoader)) {
                log.info("[collector-hq] 通过 SPI 从 {} 加载行情源 {}", jarPath, source.getClass().getName());
                return source;
            }
            // 途径二：扫描 jar 内所有 class，找可实例化的 HqSource 实现
            try (JarFile jarFile = new JarFile(jar)) {
                var entries = jarFile.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (entry.getName().endsWith(".class") && !entry.getName().contains("$")) {
                        String className = entry.getName().replace('/', '.').replace(".class", "");
                        try {
                            Class<?> clazz = classLoader.loadClass(className);
                            if (HqSource.class.isAssignableFrom(clazz) && !clazz.isInterface()
                                    && !java.lang.reflect.Modifier.isAbstract(clazz.getModifiers())) {
                                log.info("[collector-hq] 扫描 jar 发现行情源实现 {}", className);
                                return (HqSource) clazz.getDeclaredConstructor().newInstance();
                            }
                        } catch (NoClassDefFoundError | ClassNotFoundException ignored) {
                            // 依赖缺失的 class 跳过
                        }
                    }
                }
            }
            throw new IllegalStateException("jar 中未找到 HqSource 实现: " + jarPath);
        } catch (Exception e) {
            throw new IllegalStateException("加载行情源插件失败: " + jarPath, e);
        }
    }

    public static Map<String, String> props(int symbols, long seed, long millisPerMinute) {
        Map<String, String> props = new HashMap<>();
        props.put("symbols", String.valueOf(symbols));
        props.put("seed", String.valueOf(seed));
        props.put("millisPerMinute", String.valueOf(millisPerMinute));
        return props;
    }
}
