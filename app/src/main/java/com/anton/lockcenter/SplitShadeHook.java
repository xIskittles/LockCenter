package com.anton.lockcenter;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LockCenter — принудительное включение раздельной шторки (dual shade / split shade).
 *
 * Включается наличием файла-маркера:
 *   su -c 'for d in /data/user_de/0/com.android.systemui /data/user/0/com.android.systemui /data/system; do [ -d "$d" ] && touch "$d/lockcenter_split_shade"; done'
 * затем перезагрузка (или pkill -f "com.androi[d].systemui").
 * Выключить: удалить эти файлы и перезагрузиться.
 *
 * Что делает хук, если маркер есть:
 *   1) хукает известные методы-геттеры флагов (dualShade / sceneContainer / splitShade ...),
 *      возвращая true;
 *   2) форсирует старый split shade (shouldUseSplitNotificationShade);
 *   3) читает dex-ы SystemUI и печатает в лог найденные классы с Flag/Dual/Scene/Split,
 *      а также хукает целевые методы в них — чтобы работало даже если имена классов
 *      в сборке отличаются от ожидаемых.
 */
public class SplitShadeHook implements IXposedHookLoadPackage {

    private static final String TAG = "[LockCenter/Shade] ";

    /** Файл с диагностикой (читается из Termux без логов). */
    private static File logFile;
    /** Контекст SystemUI (появляется уже после загрузки модуля). */
    private static volatile Context appContext;
    /** Уже включённые методы (общий набор для всех путей). */
    private static final Set<String> HOOKED = new LinkedHashSet<>();
    /** Строки до открытия файла. */
    private static final java.util.List<String> BUFFERED = new java.util.ArrayList<>();
    private static final String[] MARKER_PATHS = {
            "/data/user_de/0/com.android.systemui/lockcenter_split_shade",
            "/data/data/com.android.systemui/lockcenter_split_shade",
            "/data/user/0/com.android.systemui/lockcenter_split_shade",
            "/data/system/lockcenter_split_shade"
    };

    /** Методы-геттеры, которые нужно вернуть как true (все без аргументов и boolean). */
    private static final String[] TARGET_METHODS = {
            "dualShade",
            "sceneContainer",
            "splitShade",
            "isDualShadeEnabled",
            "isDualShadeSettingEnabled",
            "isSplitShadeEnabled",
            "shouldUseSplitNotificationShade",
            "isEnabled",
            "getEnabled"
    };

    /** Классы-кандидаты: хукаем сразу, не дожидаясь разбора dex. */
    private static final String[] CANDIDATE_CLASSES = {
            "com.android.systemui.Flags",
            "com.android.systemui.FeatureFlagsImpl",
            "com.android.systemui.FeatureFlags",
            "com.android.systemui.flags.Flags",
            "com.android.systemui.flags.FeatureFlagsImpl",
            "com.android.systemui.flags.FeatureFlags",
            "com.android.systemui.shade.shared.flag.DualShadeFlag",
            "com.android.systemui.shade.shared.flag.SplitShadeFlag",
            "com.android.systemui.scene.shared.flag.SceneContainerFlag",
            "com.android.systemui.shade.data.repository.ShadeConfigRepository",
            "com.android.systemui.statusbar.policy.SplitShadeStateControllerImpl"
    };

    private static final XC_MethodHook RETURN_TRUE = new XC_MethodHook() {
        @Override
        protected void beforeHookedMethod(MethodHookParam param) {
            param.setResult(Boolean.TRUE);
        }
    };

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!"com.android.systemui".equals(lpparam.packageName)) return;
        final ClassLoader cl = lpparam.classLoader;

        appContext = currentApplication(cl);
        initLogFile(appContext);
        final boolean marker = markerExists();
        log("хук запущен в SystemUI: маркер=" + marker + ", файл=" + logFile);
        if (!marker) {
            log("маркера нет — раздельную шторку не трогаю");
            return;
        }
        log("маркер найден — форсирую раздельную шторку");

        // 1) быстрые хуки по известным именам (успевают до инициализации SystemUI)
        for (int i = 0; i < CANDIDATE_CLASSES.length; i++) {
            forceTrue(cl, CANDIDATE_CLASSES[i], HOOKED);
        }

        // 2) разбор dex-ов SystemUI и хуки по фактическим именам классов —
        //    в фоне, чтобы не тормозить старт SystemUI
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < 60 && appContext == null; i++) {
                    try {
                        Thread.sleep(500L);
                    } catch (Throwable ignored) {
                    }
                    appContext = currentApplication(cl);
                }
                initLogFile(appContext);
                if (appContext == null) {
                    log("контекст SystemUI не появился — файл диагностики не создан");
                }
                try {
                    discoverAndHook(cl, appContext, HOOKED);
                } catch (Throwable e) {
                    log("разбор dex: " + e);
                }
            }
        }, "LockCenter-ShadeScan");
        t.setPriority(Thread.MIN_PRIORITY);
        t.start();

        // Контекст на этапе загрузки модуля обычно ещё не создан, поэтому ловим
        // создание SystemUIService: оттуда получаем контекст и открываем файл.
        try {
            Class<?> svc = XposedHelpers.findClassIfExists("com.android.systemui.SystemUIService", cl);
            if (svc != null) {
                XposedBridge.hookAllMethods(svc, "onCreate", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object self = param.thisObject;
                            if (self instanceof Context) {
                                appContext = (Context) self;
                                initLogFile(appContext);
                                log("контекст получен (SystemUIService), файл=" + logFile);
                            }
                        } catch (Throwable e) {
                            try {
                                XposedBridge.log(TAG + "SystemUIService hook: " + e);
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                });
                log("жду создания SystemUIService для получения контекста");
            } else {
                log("класс SystemUIService не найден");
            }
        } catch (Throwable e) {
            log("хук SystemUIService: " + e);
        }    }

    // ------------------------------------------------------------------ хуки по имени

    private static void forceTrue(ClassLoader cl, String className, Set<String> hooked) {
        Class<?> cls;
        try {
            cls = Class.forName(className, false, cl);
        } catch (Throwable t) {
            log("класс не найден: " + className);
            return;
        }
        if (cls == null) return;

        for (int i = 0; i < TARGET_METHODS.length; i++) {
            final String name = TARGET_METHODS[i];
            Method m;
            try {
                m = XposedHelpers.findMethodExactIfExists(cls, name);
            } catch (Throwable t) {
                continue;
            }
            if (m == null) continue;

            Class<?> rt = m.getReturnType();
            if (rt != boolean.class && rt != Boolean.class) continue;
            if (!hooked.add(className + "#" + name)) continue;

            if (Modifier.isStatic(m.getModifiers())) {
                try {
                    log("было " + className + "#" + name + " = " + m.invoke(null));
                } catch (Throwable ignored) {
                }
            }
            try {
                XposedBridge.hookMethod(m, RETURN_TRUE);
                log("включил: " + className + "#" + name);
            } catch (Throwable t) {
                log("не удалось включить " + className + "#" + name + ": " + t);
            }
        }
    }

    // ------------------------------------------------------------------ разбор dex-ов SystemUI

    private static void discoverAndHook(ClassLoader cl, Context app, Set<String> hooked) {
        String apk = null;
        try {
            if (app != null) apk = app.getPackageCodePath();
        } catch (Throwable ignored) {
        }
        if (apk == null) {
            log("путь к APK SystemUI неизвестен — разбор dex пропущен");
            return;
        }
        log("APK SystemUI: " + apk);

        List<String> classes = new ArrayList<>();
        ZipFile zip = null;
        int budget = 48 * 1024 * 1024;
        try {
            zip = new ZipFile(apk);
            Enumeration<? extends ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (!e.getName().endsWith(".dex")) continue;
                if (budget <= 0) break;
                byte[] data = readAll(zip.getInputStream(e), Math.min(budget, 24 * 1024 * 1024));
                if (data == null) continue;
                budget -= data.length;
                scanDex(data, classes);
            }
        } catch (Throwable t) {
            log("чтение dex не удалось: " + t);
        } finally {
            try {
                if (zip != null) zip.close();
            } catch (Throwable ignored) {
            }
        }

        log("найдено классов с Flag/Dual/Scene/Split: " + classes.size());
        int shown = 0;
        for (int i = 0; i < classes.size(); i++) {
            if (shown >= 120) {
                log("... список классов обрезан (всего " + classes.size() + ")");
                break;
            }
            log("  класс: " + classes.get(i));
            shown++;
        }

        for (int i = 0; i < classes.size(); i++) {
            String cn = classes.get(i);
            forceTrue(cl, cn, hooked);
            // у классов-обёрток флагов печатаем имена методов — по ним видно,
            // какие флаги реально есть в сборке
            String simple = cn.substring(cn.lastIndexOf('.') + 1);
            if ("Flags".equals(simple) || simple.startsWith("FeatureFlags")) {
                listShadeMethods(cl, cn);
            }
        }
        log("итого включено методов: " + hooked.size());
    }

    /** Печатает методы класса флагов, относящиеся к шторке. */
    private static void listShadeMethods(ClassLoader cl, String className) {
        try {
            Class<?> cls = Class.forName(className, false, cl);
            Method[] ms = cls.getDeclaredMethods();
            int shown = 0;
            for (int i = 0; i < ms.length; i++) {
                Method m = ms[i];
                String n = m.getName().toLowerCase(Locale.US);
                if (n.contains("shade") || n.contains("scene") || n.contains("split") || n.contains("dual")) {
                    if (shown++ < 60) {
                        log("  метод: " + className + "#" + m.getName()
                                + " -> " + m.getReturnType().getSimpleName());
                    }
                }
            }
            log("  всего методов в " + className + ": " + ms.length + ", из них по шторке: " + shown);
        } catch (Throwable t) {
            log("  методы " + className + " не прочитать: " + t);
        }
    }
    /** Ищем в байтах dex строки-дескрипторы классов SystemUI с нужными словами. */
    private static void scanDex(byte[] data, List<String> out) {
        final byte[] marker = "Lcom/android/systemui/".getBytes();
        int i = 0;
        while (i + marker.length < data.length) {
            if (data[i] != marker[0]) {
                i++;
                continue;
            }
            boolean ok = true;
            for (int j = 1; j < marker.length; j++) {
                if (data[i + j] != marker[j]) {
                    ok = false;
                    break;
                }
            }
            if (!ok) {
                i++;
                continue;
            }
            int end = i;
            int limit = Math.min(data.length, i + 200);
            while (end < limit) {
                byte b = data[end];
                if (b == ';') break;
                boolean valid = (b >= 'a' && b <= 'z') || (b >= 'A' && b <= 'Z')
                        || (b >= '0' && b <= '9') || b == '/' || b == '_' || b == '$';
                if (!valid) {
                    end = -1;
                    break;
                }
                end++;
            }
            if (end <= i || end >= limit) {
                i++;
                continue;
            }
            String cls = new String(data, i + 1, end - i - 1).replace('/', '.');
            String lower = cls.toLowerCase(Locale.US);
            if (lower.contains("flag") || lower.contains("dualshade") || lower.contains("scenecontainer")
                    || lower.contains("splitshade") || lower.contains("shademode")
                    || lower.contains("shadeconfig")) {
                if (!out.contains(cls) && out.size() < 4000) out.add(cls);
            }
            i = end;
        }
    }

    private static byte[] readAll(InputStream in, int max) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(1 << 20);
            byte[] buf = new byte[64 * 1024];
            int total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
                total += n;
                if (total >= max) break;
            }
            return bos.toByteArray();
        } catch (Throwable t) {
            return null;
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
            }
        }
    }

    // ------------------------------------------------------------------ окружение

    private static Context currentApplication(ClassLoader cl) {
        try {
            Class<?> at = XposedHelpers.findClassIfExists("android.app.ActivityThread", cl);
            if (at != null) {
                Method m = at.getDeclaredMethod("currentApplication");
                m.setAccessible(true);
                Object app = m.invoke(null);
                if (app instanceof Context) return (Context) app;
            }
        } catch (Throwable ignored) {
        }
        try {
            Class<?> helper = XposedHelpers.findClassIfExists("de.robv.android.xposed.AndroidAppHelper", cl);
            if (helper != null) {
                Method m = helper.getDeclaredMethod("currentApplication");
                m.setAccessible(true);
                Object app = m.invoke(null);
                if (app instanceof Context) return (Context) app;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean markerExists() {
        for (int i = 0; i < MARKER_PATHS.length; i++) {
            try {
                if (new File(MARKER_PATHS[i]).exists()) return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    /**
     * Пишем диагностику и в лог Xposed, и в файл — файл легко прочитать из Termux:
     *   su -c 'cat /data/user/0/com.android.systemui/files/lockcenter_shade.txt'
     */
    private static void log(String msg) {
        try {
            XposedBridge.log(TAG + msg);
        } catch (Throwable ignored) {
        }
        if (logFile == null) {
            synchronized (BUFFERED) {
                if (BUFFERED.size() < 400) BUFFERED.add(msg);
            }
            return;
        }
        writeLine(msg);
    }

    private static void writeLine(String msg) {
        try {
            java.io.FileWriter w = new java.io.FileWriter(logFile, true);
            w.write(msg + "\n");
            w.close();
        } catch (Throwable ignored) {
        }
    }

    private static void flushBuffered() {
        java.util.List<String> copy;
        synchronized (BUFFERED) {
            copy = new java.util.ArrayList<>(BUFFERED);
            BUFFERED.clear();
        }
        for (int i = 0; i < copy.size(); i++) {
            writeLine(copy.get(i));
        }
    }

    /** Файл диагностики; перезаписывается при каждом старте SystemUI. */
    private static void initLogFile(Context app) {
        if (logFile != null || app == null) return;
        java.util.List<File> candidates = new java.util.ArrayList<>();
        try {
            if (app != null) candidates.add(new File(app.getFilesDir(), "lockcenter_shade.txt"));
        } catch (Throwable ignored) {
        }
        try {
            if (app != null && app.getExternalFilesDir(null) != null) {
                candidates.add(new File(app.getExternalFilesDir(null), "lockcenter_shade.txt"));
            }
        } catch (Throwable ignored) {
        }
        candidates.add(new File("/data/system/lockcenter_shade.txt"));
        candidates.add(new File("/data/local/tmp/lockcenter_shade.txt"));
        for (int i = 0; i < candidates.size(); i++) {
            try {
                File f = candidates.get(i);
                java.io.FileWriter w = new java.io.FileWriter(f, false);
                w.write("=== LockCenter: раздельная шторка, " + new java.util.Date() + " ===\n");
                w.close();
                logFile = f;
                flushBuffered();
                try {
                    XposedBridge.log(TAG + "диагностика пишется в " + f);
                } catch (Throwable ignored) {
                }
                return;
            } catch (Throwable ignored) {
            }
        }
        try {
            XposedBridge.log(TAG + "файл диагностики не создался ни в одном месте");
        } catch (Throwable ignored) {
        }
    }}
