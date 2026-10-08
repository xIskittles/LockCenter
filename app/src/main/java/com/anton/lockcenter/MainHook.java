package com.anton.lockcenter;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    // ================= НАСТРОЙКА РАЗМЕРА ЧАСОВ =================
    // 1.0 = стандарт. Сделали еще шире в ширину по твоему запросу:
    private static final float CLOCK_SCALE_X = 1.5f; // ширина
    private static final float CLOCK_SCALE_Y = 1.14f; // высота
    // ==========================================================

    private static final int UNSET = -1;
    private static final int PARENT = 0;

    private static final int CS_PARENT = 0;
    private static final int CS_TOP = 3;
    private static final int CS_BOTTOM = 4;
    private static final int CS_START = 6;
    private static final int CS_END = 7;

    private static final Set<View> HOOKED =
            Collections.newSetFromMap(new WeakHashMap<View, Boolean>());

    private static final String CLOCK_SUFFIX = ".FlexClockTextView";
    private static final String ROOT_SUFFIX = ".KeyguardRootView";

    private static int placeholderId = 0;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!"com.android.systemui".equals(lpparam.packageName)) return;
        ClassLoader cl = lpparam.classLoader;

        try {
            hookKeyguard(cl);
        } catch (Throwable t) {
            XposedBridge.log(t);
        }

        try {
            hookPreDraw(cl, "com.android.systemui.statusbar.NotificationShelf", true);
            hookPreDraw(cl, "com.android.systemui.statusbar.phone.NotificationIconContainer", false);
        } catch (Throwable t) {
            XposedBridge.log(t);
        }
    }

    private static boolean isRoot(Object o) {
        return o != null && o.getClass().getName().endsWith(ROOT_SUFFIX);
    }

    private static boolean isDateView(View v) {
        if (v == null) return false;
        String name = v.getClass().getName();
        return name.contains("Smartspace") || name.contains("VariableDate");
    }

    private static boolean isSmallClock(View v) {
        if (v == null) return false;
        String name = v.getClass().getName();
        // Маленькие часы имеют высоту < 300px (большие часы занимают > 800px)
        return name.endsWith(CLOCK_SUFFIX) && v.getHeight() < 300;
    }

    private static void hookKeyguard(ClassLoader cl) {
        Class<?> constraintSet = XposedHelpers.findClassIfExists(
                "androidx.constraintlayout.widget.ConstraintSet", cl);
        Class<?> constraintLayout = XposedHelpers.findClassIfExists(
                "androidx.constraintlayout.widget.ConstraintLayout", cl);
        Class<?> rootClass = XposedHelpers.findClassIfExists(
                "com.android.systemui.keyguard.ui.view.KeyguardRootView", cl);

        // 1. ПЕРЕХВАТ ДО НАЧАЛА СИСТЕМНОЙ АНИМАЦИИ (убирает 1-секундный улет даты вправо)
        if (constraintSet != null) {
            XC_MethodHook beforeApply = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args.length >= 1 && isRoot(param.args[0])) {
                            patchConstraintSet(param.thisObject, (ViewGroup) param.args[0]);
                        }
                    } catch (Throwable ignored) {}
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        if (param.args.length >= 1 && isRoot(param.args[0])) {
                            fixKeyguard((ViewGroup) param.args[0], false);
                        }
                    } catch (Throwable ignored) {}
                }
            };
            XposedBridge.hookAllMethods(constraintSet, "applyTo", beforeApply);
            XposedBridge.hookAllMethods(constraintSet, "applyToWithoutCheck", beforeApply);
        }

        // 2. Хук на onMeasure
        if (constraintLayout != null) {
            XC_MethodHook beforeMeasure = new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        if (isRoot(param.thisObject)) {
                            fixKeyguard((ViewGroup) param.thisObject, true);
                        }
                    } catch (Throwable ignored) {}
                }
            };
            XposedBridge.hookAllMethods(constraintLayout, "onMeasure", beforeMeasure);
            if (rootClass != null) {
                XposedBridge.hookAllMethods(rootClass, "onMeasure", beforeMeasure);
            }
        }

        // 3. Непрерывный контроль позиции на каждом кадре (исправляет сдвиг AOD)
        if (rootClass != null) {
            XposedBridge.hookAllConstructors(rootClass, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    final ViewGroup root = (ViewGroup) param.thisObject;
                    if (!HOOKED.add(root)) return;
                    root.getViewTreeObserver().addOnPreDrawListener(
                            new ViewTreeObserver.OnPreDrawListener() {
                                @Override
                                public boolean onPreDraw() {
                                    try {
                                        fixKeyguard(root, false);
                                        absoluteCenterKeyguard(root);
                                    } catch (Throwable ignored) {}
                                    return true;
                                }
                            });
                }
            });
        }
    }

    /** Модифицирует системный ConstraintSet ДО того, как система начнет раскладку */
    private static void patchConstraintSet(Object cs, ViewGroup root) {
        int smallClockId = resId(root, "small_clock_view");
        if (smallClockId == 0) smallClockId = resId(root, "lockscreen_clock_view");

        View smallClock = null;
        View date = null;
        for (int i = 0; i < root.getChildCount(); i++) {
            View c = root.getChildAt(i);
            if (isSmallClock(c)) smallClock = c;
            else if (isDateView(c)) date = c;
        }

        int clockId = smallClock != null ? smallClock.getId() : smallClockId;
        int dateId = date != null ? date.getId() : resId(root, "date_smartspace_view");
        if (dateId == 0) dateId = resId(root, "bc_smartspace_view");

        if (clockId > 0 && clockId != View.NO_ID) {
            try {
                XposedHelpers.callMethod(cs, "clear", clockId, CS_START);
                XposedHelpers.callMethod(cs, "clear", clockId, CS_END);
                XposedHelpers.callMethod(cs, "connect", clockId, CS_START, CS_PARENT, CS_START, 0);
                XposedHelpers.callMethod(cs, "connect", clockId, CS_END, CS_PARENT, CS_END, 0);
                XposedHelpers.callMethod(cs, "setHorizontalBias", clockId, 0.5f);
            } catch (Throwable ignored) {}
        }

        if (dateId > 0 && dateId != View.NO_ID) {
            try {
                XposedHelpers.callMethod(cs, "clear", dateId, CS_START);
                XposedHelpers.callMethod(cs, "clear", dateId, CS_END);
                XposedHelpers.callMethod(cs, "connect", dateId, CS_START, CS_PARENT, CS_START, 0);
                XposedHelpers.callMethod(cs, "connect", dateId, CS_END, CS_PARENT, CS_END, 0);
                XposedHelpers.callMethod(cs, "setHorizontalBias", dateId, 0.5f);
                if (clockId > 0 && clockId != View.NO_ID) {
                    XposedHelpers.callMethod(cs, "clear", dateId, CS_TOP);
                    XposedHelpers.callMethod(cs, "connect", dateId, CS_TOP, clockId, CS_BOTTOM, 0);
                }
            } catch (Throwable ignored) {}
        }

        int phId = resId(root, "nssl_placeholder");
        if (phId > 0 && dateId > 0 && dateId != View.NO_ID) {
            try {
                XposedHelpers.callMethod(cs, "connect", phId, CS_TOP, dateId, CS_BOTTOM, 0);
            } catch (Throwable ignored) {}
        }
    }

    private static void fixKeyguard(ViewGroup root, boolean inMeasure) {
        View clock = null;
        View date = null;
        for (int i = 0; i < root.getChildCount(); i++) {
            View c = root.getChildAt(i);
            if (isSmallClock(c)) clock = c;
            else if (isDateView(c)) date = c;
        }

        if (clock != null) {
            enforceCentered(clock, root, inMeasure);
            scaleClock(clock);
        }

        if (date != null && clock != null) {
            ViewGroup.LayoutParams lp = date.getLayoutParams();
            if (lp != null) {
                int cid = clock.getId();
                setInt(lp, "startToEnd", UNSET);
                setInt(lp, "leftToRight", UNSET);
                setInt(lp, "topToTop", UNSET);

                if (cid != View.NO_ID) {
                    setInt(lp, "topToBottom", cid);
                }

                setInt(lp, "startToStart", PARENT);
                setInt(lp, "endToEnd", PARENT);
                setFloat(lp, "horizontalBias", 0.5f);

                if (lp instanceof ViewGroup.MarginLayoutParams) {
                    ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) lp;
                    m.setMarginStart(0);
                    m.setMarginEnd(0);
                    m.leftMargin = 0;
                    m.rightMargin = 0;
                }

                apply(date, lp, root, inMeasure);
            }

            pointPlaceholderAtDate(root, date.getId(), inMeasure);
        }
    }

    /**
     * Центрирование по абсолютному центру физического экрана.
     * Нивелирует системный сдвиг AOD (burn-in offset).
     */
    private static void absoluteCenterKeyguard(ViewGroup root) {
        int screenWidth = root.getResources().getDisplayMetrics().widthPixels;
        if (screenWidth <= 0) return;

        int[] rootLoc = new int[2];
        root.getLocationOnScreen(rootLoc);

        View clock = null;
        View date = null;
        for (int i = 0; i < root.getChildCount(); i++) {
            View c = root.getChildAt(i);
            if (isSmallClock(c)) clock = c;
            else if (isDateView(c)) date = c;
        }

        if (clock != null && clock.getVisibility() == View.VISIBLE && clock.getWidth() > 0) {
            alignToScreenCenter(clock, rootLoc[0], screenWidth);
        }

        if (date != null && date.getVisibility() == View.VISIBLE && date.getWidth() > 0) {
            alignToScreenCenter(date, rootLoc[0], screenWidth);
        }
    }

    private static void alignToScreenCenter(View v, int rootScreenLeft, int screenWidth) {
        float currentCenterOnScreen = rootScreenLeft + v.getLeft() + v.getTranslationX() + (v.getWidth() / 2f);
        float targetCenterOnScreen = screenWidth / 2f;
        float diff = targetCenterOnScreen - currentCenterOnScreen;

        if (Math.abs(diff) > 0.5f) {
            v.setTranslationX(v.getTranslationX() + diff);
        }
    }

    private static void scaleClock(View clock) {
        if (clock.getVisibility() != View.VISIBLE || clock.getWidth() == 0) return;
        clock.setPivotX(clock.getWidth() / 2f);
        clock.setPivotY(clock.getHeight() / 2f);
        if (Math.abs(clock.getScaleX() - CLOCK_SCALE_X) > 0.01f) {
            clock.setScaleX(CLOCK_SCALE_X);
            clock.setScaleY(CLOCK_SCALE_Y);
        }
    }

    private static void pointPlaceholderAtDate(ViewGroup root, int dateId, boolean inMeasure) {
        if (dateId == View.NO_ID) return;
        if (placeholderId == 0) placeholderId = resId(root, "nssl_placeholder");
        if (placeholderId == 0) return;
        View ph = root.findViewById(placeholderId);
        if (ph == null || ph.getLayoutParams() == null) return;
        ViewGroup.LayoutParams lp = ph.getLayoutParams();
        if (setInt(lp, "topToBottom", dateId)) {
            apply(ph, lp, root, inMeasure);
        }
    }

    private static int resId(ViewGroup root, String name) {
        try {
            return root.getResources().getIdentifier(name, "id", "com.android.systemui");
        } catch (Throwable t) {
            return 0;
        }
    }

    private static void enforceCentered(View v, ViewGroup parent, boolean inMeasure) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp == null || !lp.getClass().getName().contains("ConstraintLayout")) return;
        String[] clear = {"leftToLeft", "leftToRight", "rightToLeft", "rightToRight",
                "startToEnd", "endToStart"};
        for (String f : clear) setInt(lp, f, UNSET);
        setInt(lp, "startToStart", PARENT);
        setInt(lp, "endToEnd", PARENT);
        setFloat(lp, "horizontalBias", 0.5f);
        if (lp instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) lp;
            m.setMarginStart(0);
            m.setMarginEnd(0);
            m.leftMargin = 0;
            m.rightMargin = 0;
        }
        apply(v, lp, parent, inMeasure);
    }

    private static void apply(View v, ViewGroup.LayoutParams lp, ViewGroup parent, boolean inMeasure) {
        if (inMeasure) {
            v.forceLayout();
        } else {
            v.setLayoutParams(lp);
        }
    }

    private static boolean setInt(Object lp, String name, int value) {
        try {
            Field f = lp.getClass().getField(name);
            if (f.getInt(lp) == value) return false;
            f.setInt(lp, value);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean setFloat(Object lp, String name, float value) {
        try {
            Field f = lp.getClass().getField(name);
            if (f.getFloat(lp) == value) return false;
            f.setFloat(lp, value);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------ Иконки на Lockscreen и AOD

    private static void hookPreDraw(ClassLoader cl, String className, final boolean isShelf) {
        Class<?> c = XposedHelpers.findClassIfExists(className, cl);
        if (c == null) return;
        XposedBridge.hookAllConstructors(c, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                final View v = (View) param.thisObject;
                if (!HOOKED.add(v)) return;
                v.getViewTreeObserver().addOnPreDrawListener(
                        new ViewTreeObserver.OnPreDrawListener() {
                            @Override
                            public boolean onPreDraw() {
                                try {
                                    int screenWidth = v.getResources().getDisplayMetrics().widthPixels;
                                    if (isShelf) {
                                        centerShelf((ViewGroup) v, screenWidth);
                                    } else if (isAodContainer(v)) {
                                        centerAodIcons((ViewGroup) v, screenWidth);
                                    }
                                } catch (Throwable ignored) {}
                                return true;
                            }
                        });
            }
        });
    }

    private static boolean isAodContainer(View v) {
        int id = v.getId();
        if (id == View.NO_ID) return false;
        try {
            return "aod_notification_icon_container".equals(
                    v.getResources().getResourceEntryName(id));
        } catch (Throwable t) {
            return false;
        }
    }

    /** Выравнивает группу иконок на AOD строго по центру экрана */
    private static void centerAodIcons(ViewGroup c, int screenWidth) {
        if (c.getVisibility() != View.VISIBLE || c.getWidth() == 0) return;

        int[] cLoc = new int[2];
        c.getLocationOnScreen(cLoc);

        float minL = Float.MAX_VALUE;
        float maxR = -Float.MAX_VALUE;
        int count = 0;
        for (int i = 0; i < c.getChildCount(); i++) {
            View ch = c.getChildAt(i);
            if (ch.getVisibility() != View.VISIBLE || ch.getWidth() == 0 || ch.getAlpha() < 0.05f) continue;
            float l = ch.getLeft() + ch.getTranslationX();
            minL = Math.min(minL, l);
            maxR = Math.max(maxR, l + ch.getWidth());
            count++;
        }
        if (count == 0) return;

        float groupCenterOnScreen = cLoc[0] + minL + (maxR - minL) / 2f;
        float targetCenter = screenWidth / 2f;
        float delta = targetCenter - groupCenterOnScreen;

        if (Math.abs(delta) > 0.5f) {
            for (int i = 0; i < c.getChildCount(); i++) {
                View ch = c.getChildAt(i);
                ch.setTranslationX(ch.getTranslationX() + delta);
            }
        }
    }

    /** Центрирует круглый кружок уведомления (Shelf) на экране блокировки */
    private static void centerShelf(ViewGroup shelf, int screenWidth) {
        if (shelf.getVisibility() != View.VISIBLE || shelf.getWidth() == 0 || shelf.getAlpha() < 0.01f) return;

        int full = shelf.getWidth();
        int pill = pillWidth(shelf);

        // Если уведомления раскрыты на всю ширину — не трогаем
        if (pill <= 0 || full - pill <= 100) {
            if (Math.abs(shelf.getTranslationX()) > 1f) {
                shelf.setTranslationX(0f);
            }
            return;
        }

        int[] sLoc = new int[2];
        shelf.getLocationOnScreen(sLoc);
        float baseScreenLeft = sLoc[0] - shelf.getTranslationX();
        float targetScreenLeft = (screenWidth - pill) / 2f;
        float targetTx = targetScreenLeft - baseScreenLeft;

        if (Math.abs(shelf.getTranslationX() - targetTx) > 0.5f) {
            shelf.setTranslationX(targetTx);
        }
    }

    private static int pillWidth(ViewGroup shelf) {
        View bg = null;
        ViewGroup icons = null;
        for (int i = 0; i < shelf.getChildCount(); i++) {
            View c = shelf.getChildAt(i);
            String n = c.getClass().getName();
            if (n.endsWith("NotificationShelfBackgroundView")) bg = c;
            else if (n.endsWith("NotificationShelfIconContainer") && c instanceof ViewGroup) {
                icons = (ViewGroup) c;
            }
        }
        if (bg != null) {
            try {
                int w = XposedHelpers.getIntField(bg, "mActualWidth");
                if (w > 0) return w;
            } catch (Throwable ignored) {}
        }
        if (icons != null) {
            float minL = Float.MAX_VALUE;
            float maxR = -Float.MAX_VALUE;
            int n = 0;
            for (int i = 0; i < icons.getChildCount(); i++) {
                View ch = icons.getChildAt(i);
                if (ch.getVisibility() != View.VISIBLE || ch.getWidth() == 0) continue;
                float l = ch.getLeft() + ch.getTranslationX();
                minL = Math.min(minL, l);
                maxR = Math.max(maxR, l + ch.getWidth());
                n++;
            }
            if (n > 0) return (int) (maxR + minL);
        }
        return 0;
    }
}
