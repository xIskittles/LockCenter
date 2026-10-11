package com.anton.lockcenter;

import java.io.File;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LockCenter — принудительное включение раздельной шторки (dual shade / split shade).
 * Экспериментально: сработает только если код шторки есть в сборке SystemUI.
 *
 * Включить (из Termux):
 *   su -c "touch /data/data/com.android.systemui/lockcenter_split_shade"
 *   su -c "pkill -f com.android.systemui"
 * Выключить:
 *   su -c "rm -f /data/data/com.android.systemui/lockcenter_split_shade"
 *   su -c "pkill -f com.android.systemui"
 */
public class SplitShadeHook implements IXposedHookLoadPackage {

    private static final String TAG = "[LockCenter/Shade] ";

    private static final String[] MARKER_PATHS = {
            "/data/user_de/0/com.android.systemui/lockcenter_split_shade",
            "/data/data/com.android.systemui/lockcenter_split_shade",
            "/data/system/lockcenter_split_shade"
    };

    /** aconfig-геттеры: com.android.systemui.Flags.<имя>() */
    private static final String[] ACONFIG_METHODS = {
            "dualShade", "sceneContainer", "splitShade"
    };

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!"com.android.systemui".equals(lpparam.packageName)) return;
        final ClassLoader cl = lpparam.classLoader;

        if (!markerExists()) {
            log("маркера нет — раздельную шторку не трогаю");
            return;
        }
        log("маркер найден — форсирую раздельную шторку");

        // 1) Новый dual shade: aconfig-флаги (Kotlin inline-свойства всё равно
        //    вызывают эти геттеры, поэтому хук по ним работает).
        forceTrue(cl, "com.android.systemui.Flags", ACONFIG_METHODS);
        forceTrue(cl, "com.android.systemui.FeatureFlagsImpl", ACONFIG_METHODS);
        forceTrue(cl, "com.android.systemui.FeatureFlags", ACONFIG_METHODS);

        // 2) Флаги-объекты самой фичи (на случай, если они не инлайнятся).
        forceTrue(cl, "com.android.systemui.shade.shared.flag.DualShadeFlag",
                "isEnabled", "getEnabled");
        forceTrue(cl, "com.android.systemui.scene.shared.flag.SceneContainerFlag",
                "isEnabled", "getEnabled");

        // 3) Настройка «двух шторок» включена.
        forceTrue(cl, "com.android.systemui.shade.data.repository.ShadeConfigRepository",
                "isDualShadeSettingEnabled");

        // 4) Старый split shade (шторка QS слева, уведомления справа).
        forceTrue(cl, "com.android.systemui.statusbar.policy.SplitShadeStateControllerImpl",
                "shouldUseSplitNotificationShade");
    }

    private static void forceTrue(ClassLoader cl, String className, String... methodNames) {
        Class<?> cls;
        try {
            cls = XposedHelpers.findClassIfExists(className, cl);
        } catch (Throwable t) {
            log("ошибка поиска класса " + className + ": " + t);
            return;
        }
        if (cls == null) {
            log("класс не найден: " + className);
            return;
        }
        for (int i = 0; i < methodNames.length; i++) {
            final String name = methodNames[i];
            try {
                java.lang.reflect.Method m = XposedHelpers.findMethodExactIfExists(cls, name);
                if (m == null) {
                    log("нет метода: " + className + "#" + name);
                    continue;
                }
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        param.setResult(Boolean.TRUE);
                    }
                });
                log("включил: " + className + "#" + name);
            } catch (Throwable t) {
                log("не удалось включить " + className + "#" + name + ": " + t);
            }
        }
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

    private static void log(String msg) {
        try {
            XposedBridge.log(TAG + msg);
        } catch (Throwable ignored) {
        }
    }
}
