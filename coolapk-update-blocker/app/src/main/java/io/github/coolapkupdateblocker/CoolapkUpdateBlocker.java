package io.github.coolapkupdateblocker;

import android.app.Application;
import android.content.Context;
import java.io.*;
import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import de.robv.android.xposed.*;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public final class CoolapkUpdateBlocker implements IXposedHookLoadPackage {
    private static final String PKG = "com.coolapk.market";
    private static final String TAG = "[CoolapkUpdateBlocker] ";
    private static volatile boolean started, rulesLoaded;
    private static File logFile;
    private static final List<Rule> RULES = new ArrayList<>();
    private static final Set<Object> BUILDERS = Collections.newSetFromMap(new WeakHashMap<Object, Boolean>());
    private static final Set<String> SEEN = Collections.synchronizedSet(new HashSet<String>());

    @Override public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam p) {
        if (!PKG.equals(p.packageName) || started) return;
        started = true;
        try {
            XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Context c = (Context) param.args[0];
                        File d = c.getExternalFilesDir(null);
                        if (d == null) d = c.getFilesDir();
                        if (!d.exists()) d.mkdirs();
                        logFile = new File(d, "CoolapkUpdateBlocker.log");
                        File rf = new File(d, "CoolapkUpdateBlocker.rules");
                        if (!rf.exists()) write(rf, "# exact-host|path-regex\n# no active rules until self-update endpoint is confirmed\n");
                        loadRules(rf);
                        log("rules=" + RULES.size());
                    } catch (Throwable t) { log("context init failed: " + t.getClass().getSimpleName()); }
                }
            });
            Class<?> b = XposedHelpers.findClass("okhttp3.OkHttpClient$Builder", p.classLoader);
            XposedBridge.hookAllMethods(b, "build", new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try { inject(param.thisObject, p.classLoader); }
                    catch (Throwable t) { log("inject failed: " + t.getClass().getSimpleName()); }
                }
            });
            log("hook active for " + p.packageName);
        } catch (Throwable t) { log("startup failed: " + t.getClass().getSimpleName()); }
    }

    private static void inject(Object builder, ClassLoader loader) throws Exception {
        synchronized (BUILDERS) { if (BUILDERS.contains(builder)) return; }
        Class<?> i = Class.forName("okhttp3.Interceptor", false, loader);
        Method add = null;
        for (Method m : builder.getClass().getMethods())
            if (m.getName().equals("addInterceptor") && m.getParameterTypes().length == 1
                    && m.getParameterTypes()[0].equals(i)) { add = m; break; }
        if (add == null) return;
        Object proxy = Proxy.newProxyInstance(loader, new Class<?>[]{i}, new Handler());
        add.invoke(builder, proxy);
        synchronized (BUILDERS) { BUILDERS.add(builder); }
    }

    private static final class Handler implements InvocationHandler {
        public Object invoke(Object proxy, Method m, Object[] a) throws Throwable {
            if (m.getDeclaringClass() == Object.class) {
                if ("toString".equals(m.getName())) return "CoolapkUpdateBlocker";
                if ("hashCode".equals(m.getName())) return System.identityHashCode(proxy);
                if ("equals".equals(m.getName())) return proxy == a[0];
            }
            if (!"intercept".equals(m.getName()) || a == null || a.length != 1) return null;
            Object chain = a[0];
            Object req = chain.getClass().getMethod("request").invoke(chain);
            Object url = req.getClass().getMethod("url").invoke(req);
            String host = String.valueOf(url.getClass().getMethod("host").invoke(url));
            String path = String.valueOf(url.getClass().getMethod("encodedPath").invoke(url));
            Rule hit = match(host, path);
            if (hit != null) {
                log("BLOCK " + host + path + " rule=" + hit.source);
                throw new IOException("Blocked by CoolapkUpdateBlocker");
            }
            if ((host.endsWith("coolapk.com") || host.endsWith("coolapkmarket.com"))
                    && SEEN.size() < 300 && SEEN.add(host + path)) log("REQ " + host + path);
            for (Method x : chain.getClass().getMethods())
                if (x.getName().equals("proceed") && x.getParameterTypes().length == 1
                        && x.getParameterTypes()[0].isAssignableFrom(req.getClass()))
                    return x.invoke(chain, req);
            throw new IOException("OkHttp proceed method not found");
        }
    }

    private static Rule match(String host, String path) {
        synchronized (RULES) {
            for (Rule r : RULES)
                if (r.host.equalsIgnoreCase(host) && r.pattern.matcher(path).matches()) return r;
        }
        return null;
    }
    private static void loadRules(File f) {
        if (rulesLoaded) return;
        rulesLoaded = true;
        synchronized (RULES) {
            try (BufferedReader br = new BufferedReader(new FileReader(f))) {
                String s;
                while ((s = br.readLine()) != null) {
                    s = s.trim();
                    if (s.isEmpty() || s.startsWith("#")) continue;
                    int n = s.indexOf('|');
                    if (n < 1 || n == s.length()-1) continue;
                    try { RULES.add(new Rule(s.substring(0,n).trim(), Pattern.compile(s.substring(n+1).trim()), s)); }
                    catch (Throwable t) { log("ignored invalid rule"); }
                }
            } catch (Throwable t) { log("rule read failed"); }
        }
    }
    private static void write(File f, String s) throws IOException {
        try (FileOutputStream o = new FileOutputStream(f)) { o.write(s.getBytes(StandardCharsets.UTF_8)); }
    }
    private static void log(String s) {
        String line = TAG + s;
        XposedBridge.log(line);
        try { if (logFile != null) try (FileOutputStream o = new FileOutputStream(logFile, true)) {
            o.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        }} catch (Throwable ignored) {}
    }
    private static final class Rule {
        final String host, source; final Pattern pattern;
        Rule(String h, Pattern p, String s) { host=h; pattern=p; source=s; }
    }
}