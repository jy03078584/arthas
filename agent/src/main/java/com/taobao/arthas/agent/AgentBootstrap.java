package com.taobao.arthas.agent;

import java.arthas.Spy;
import java.io.*;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.net.URL;
import java.security.CodeSource;
import java.net.URLDecoder;
import java.util.jar.JarFile;

/**
 * 代理启动类
 *
 * @author vlinux on 15/5/19.
 */
public class AgentBootstrap {

    private static final String ADVICEWEAVER = "com.taobao.arthas.core.advisor.AdviceWeaver";
    private static final String ON_BEFORE = "methodOnBegin";
    private static final String ON_RETURN = "methodOnReturnEnd";
    private static final String ON_THROWS = "methodOnThrowingEnd";
    private static final String BEFORE_INVOKE = "methodOnInvokeBeforeTracing";
    private static final String AFTER_INVOKE = "methodOnInvokeAfterTracing";
    private static final String THROW_INVOKE = "methodOnInvokeThrowTracing";
    private static final String RESET = "resetArthasClassLoader";
    private static final String ARTHAS_SPY_JAR = "arthas-spy.jar";
    private static final String ARTHAS_CONFIGURE = "com.taobao.arthas.core.config.Configure";
    private static final String ARTHAS_BOOTSTRAP = "com.taobao.arthas.core.server.ArthasBootstrap";
    private static final String TO_CONFIGURE = "toConfigure";
    private static final String GET_JAVA_PID = "getJavaPid";
    private static final String GET_INSTANCE = "getInstance";
    private static final String IS_BIND = "isBind";
    private static final String BIND = "bind";

    private static PrintStream ps = System.err;
    static {
        try {
            File arthasLogDir = new File(System.getProperty("user.home") + File.separator + "logs" + File.separator
                    + "arthas" + File.separator);
            if (!arthasLogDir.exists()) {
                arthasLogDir.mkdirs();
            }
            if (!arthasLogDir.exists()) {
                // #572
                arthasLogDir = new File(System.getProperty("java.io.tmpdir") + File.separator + "logs" + File.separator
                        + "arthas" + File.separator);
                if (!arthasLogDir.exists()) {
                    arthasLogDir.mkdirs();
                }
            }

            File log = new File(arthasLogDir, "arthas.log");

            if (!log.exists()) {
                log.createNewFile();
            }
            ps = new PrintStream(new FileOutputStream(log, true));
        } catch (Throwable t) {
            t.printStackTrace(ps);
        }
    }

    // 全局持有classloader用于隔离 Arthas 实现
    private static volatile ClassLoader arthasClassLoader;

    public static void premain(String args, Instrumentation inst) {
        main(true, args, inst);
    }

    public static void agentmain(String args, Instrumentation inst) {
        main(args, inst);
    }

    /**
     * 让下次再次启动时有机会重新加载
     */
    public synchronized static void resetArthasClassLoader() {
        arthasClassLoader = null;
    }

    private static ClassLoader getClassLoader(Instrumentation inst, File spyJarFile, File agentJarFile) throws Throwable {
        // 将Spy添加到BootstrapClassLoader
        inst.appendToBootstrapClassLoaderSearch(new JarFile(spyJarFile));

        // 构造自定义的类加载器，尽量减少Arthas对现有工程的侵蚀
        return loadOrDefineClassLoader(agentJarFile);
    }

    private static ClassLoader loadOrDefineClassLoader(File agentJar) throws Throwable {
        if (arthasClassLoader == null) {
            arthasClassLoader = new ArthasClassloader(new URL[]{agentJar.toURI().toURL()});
        }
        return arthasClassLoader;
    }

    private static void initSpy(ClassLoader classLoader) throws ClassNotFoundException, NoSuchMethodException {
        Class<?> adviceWeaverClass = classLoader.loadClass(ADVICEWEAVER);
        Method onBefore = adviceWeaverClass.getMethod(ON_BEFORE, int.class, ClassLoader.class, String.class,
                String.class, String.class, Object.class, Object[].class);
        Method onReturn = adviceWeaverClass.getMethod(ON_RETURN, Object.class);
        Method onThrows = adviceWeaverClass.getMethod(ON_THROWS, Throwable.class);
        Method beforeInvoke = adviceWeaverClass.getMethod(BEFORE_INVOKE, int.class, String.class, String.class, String.class, int.class);
        Method afterInvoke = adviceWeaverClass.getMethod(AFTER_INVOKE, int.class, String.class, String.class, String.class, int.class);
        Method throwInvoke = adviceWeaverClass.getMethod(THROW_INVOKE, int.class, String.class, String.class, String.class, int.class);
        Method reset = AgentBootstrap.class.getMethod(RESET);
        Spy.initForAgentLauncher(classLoader, onBefore, onReturn, onThrows, beforeInvoke, afterInvoke, throwInvoke, reset);
    }

    private static synchronized void main(final String args, final Instrumentation inst) {
        main(false, args, inst);
    }
    private static synchronized void main(boolean premain, final String args, final Instrumentation inst) {
        try {
            ps.println("Arthas server agent start...");


            // 传递的args参数分两个部分:agentJar路径和agentArgs, 分别是Agent的JAR包路径和期望传递到服务端的参数
            String agentJar = null;
            File agentJarFile = null;
            String agentArgs = null;
            if (premain && (args == null || args.trim().isEmpty())) {
                // 当时premain启动时，并且没有配置参数时，尝试从arthas-agent.jar所在的目录查找 arthas-core.jar
                CodeSource codeSource = AgentBootstrap.class.getProtectionDomain().getCodeSource();
                URL agentJarLocation = codeSource.getLocation();
                agentJarFile = new File(new File(agentJarLocation.getFile()).getParentFile(), "arthas-core.jar");

                /**
                 * 当用户没有配置参数时，默认的配置字符串 TODO 默认值是否要设置到 com.taobao.arthas.core.config.Configure
                 */
                agentArgs = "telnetPort=3658;httpPort=8563;ip=127.0.0.1;sessionTimeout=1800";
            } else {
                int index = args.indexOf(';');
                agentJar = args.substring(0, index);
                agentArgs = args.substring(index, args.length());

                agentJarFile = new File(agentJar);
                if (!agentJarFile.exists()) {
                    ps.println("Agent jar file does not exist: " + agentJarFile);
                    return;
                }
            }
            if(agentArgs == null) {
                agentArgs = "";
            }
            final String finalAgentArgs = agentArgs;

            File spyJarFile = new File(agentJarFile.getParentFile(), ARTHAS_SPY_JAR);
            if (!spyJarFile.exists()) {
                ps.println("Spy jar file does not exist: " + spyJarFile);
                return;
            }

            /**
             * Use a dedicated thread to run the binding logic to prevent possible memory leak. #195
             */
            final ClassLoader agentLoader = getClassLoader(inst, spyJarFile, agentJarFile);
            initSpy(agentLoader);

            Thread bindingThread = new Thread() {
                @Override
                public void run() {
                    try {
                        bind(inst, agentLoader, finalAgentArgs);
                    } catch (Throwable throwable) {
                        throwable.printStackTrace(ps);
                    }
                }
            };

            bindingThread.setName("arthas-binding-thread");
            bindingThread.start();
            bindingThread.join();
        } catch (Throwable t) {
            t.printStackTrace(ps);
            try {
                if (ps != System.err) {
                    ps.close();
                }
            } catch (Throwable tt) {
                // ignore
            }
            throw new RuntimeException(t);
        }
    }

    private static void bind(Instrumentation inst, ClassLoader agentLoader, String args) throws Throwable {
        /**
         * <pre>
         * Configure configure = Configure.toConfigure(args);
         * int javaPid = configure.getJavaPid();
         * ArthasBootstrap bootstrap = ArthasBootstrap.getInstance(javaPid, inst);
         * </pre>
         */
        Class<?> classOfConfigure = agentLoader.loadClass(ARTHAS_CONFIGURE);
        Object configure = classOfConfigure.getMethod(TO_CONFIGURE, String.class).invoke(null, args);
        int javaPid = (Integer) classOfConfigure.getMethod(GET_JAVA_PID).invoke(configure);
        Class<?> bootstrapClass = agentLoader.loadClass(ARTHAS_BOOTSTRAP);
        Object bootstrap = bootstrapClass.getMethod(GET_INSTANCE, int.class, Instrumentation.class).invoke(null, javaPid, inst);
        boolean isBind = (Boolean) bootstrapClass.getMethod(IS_BIND).invoke(bootstrap);
        if (!isBind) {
            try {
                ps.println("Arthas start to bind...");
                bootstrapClass.getMethod(BIND, classOfConfigure).invoke(bootstrap, configure);
                ps.println("Arthas server bind success.");
                return;
            } catch (Exception e) {
                ps.println("Arthas server port binding failed! Please check $HOME/logs/arthas/arthas.log for more details.");
                throw e;
            }
        }
        ps.println("Arthas server already bind.");
    }

    private static String decodeArg(String arg) {
        try {
            return URLDecoder.decode(arg, "utf-8");
        } catch (UnsupportedEncodingException e) {
            return arg;
        }
    }
}
