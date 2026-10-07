package com.anton.lockcenter;

import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {
    private static final String TAG = "[LockCenter] ";

    private static void log(String msg) {
        XposedBridge.log(TAG + msg);
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!"com.android.systemui".equals(lpparam.packageName)) {
            return;
        }

        log("Загружен в SystemUI Android 16!");
        ClassLoader cl = lpparam.classLoader;

        // 1. Хук на FlexClockViewGroup (часы экрана блокировки Pixel)
        try {
            Class<?> flexClockClass = XposedHelpers.findClassIfExists(
                "com.android.systemui.shared.clocks.view.FlexClockViewGroup", cl);
            if (flexClockClass != null) {
                // Хукаем layout часов
                XposedBridge.hookAllMethods(flexClockClass, "layout", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        View clockView = (View) param.thisObject;
                        centerViewHorizontally(clockView);
                    }
                });
            }
        } catch (Throwable t) {
            log("Ошибка FlexClockViewGroup: " + t);
        }

        // 2. Хук на Smartspace (виджет даты/погоды "Самое главное")
        try {
            Class<?> smartspaceClass = XposedHelpers.findClassIfExists(
                "com.google.android.systemui.smartspace.BcSmartspaceView", cl);
            if (smartspaceClass == null) {
                smartspaceClass = XposedHelpers.findClassIfExists(
                    "com.android.systemui.statusbar.policy.VariableDateView", cl);
            }
            if (smartspaceClass != null) {
                XposedBridge.hookAllMethods(smartspaceClass, "layout", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        View dateView = (View) param.thisObject;
                        centerViewHorizontally(dateView);
                    }
                });
            }
        } catch (Throwable t) {
            log("Ошибка Smartspace/Date: " + t);
        }

        // 3. Хук на NotificationIconContainer (компактная иконка Telegram / уведомлений)
        try {
            Class<?> iconContainerClass = XposedHelpers.findClassIfExists(
                "com.android.systemui.statusbar.phone.NotificationIconContainer", cl);
            if (iconContainerClass != null) {
                XposedBridge.hookAllMethods(iconContainerClass, "layout", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        View iconView = (View) param.thisObject;
                        // Центрируем только если это иконки на Keyguard / AOD, а не в шторке
                        if (isKeyguardElement(iconView)) {
                            centerViewHorizontally(iconView);
                        }
                    }
                });
            }
        } catch (Throwable t) {
            log("Ошибка NotificationIconContainer: " + t);
        }
    }

    /**
     * Центрирует любой View относительно экрана, корректируя translationX
     */
    private static void centerViewHorizontally(View view) {
        if (view == null || view.getVisibility() != View.VISIBLE) return;

        View parent = (View) view.getParent();
        if (parent == null) return;

        int parentWidth = parent.getWidth();
        if (parentWidth <= 0) {
            parentWidth = view.getResources().getDisplayMetrics().widthPixels;
        }

        int viewWidth = view.getWidth();
        if (viewWidth <= 0 || viewWidth >= parentWidth - 50) {
            // Если вьюшка во всю ширину экрана — центрировать не нужно (например, развернутое уведомление)
            return;
        }

        int currentLeft = view.getLeft();
        int targetLeft = (parentWidth - viewWidth) / 2;
        int deltaX = targetLeft - currentLeft;

        // Применяем сдвиг, чтобы элемент встал строго по центру
        if (view.getTranslationX() != deltaX) {
            view.setTranslationX(deltaX);
        }
    }

    private static boolean isKeyguardElement(View view) {
        View current = view;
        while (current != null) {
            String name = current.getClass().getName();
            if (name.contains("KeyguardRootView") || name.contains("Aod") || name.contains("Keyguard")) {
                return true;
            }
            if (current.getParent() instanceof View) {
                current = (View) current.getParent();
            } else {
                break;
            }
        }
        return false;
    }
}
