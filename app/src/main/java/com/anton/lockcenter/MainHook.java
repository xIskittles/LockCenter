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
    private static final float CLOCK_SCALE_X = 1.50f; // растянуты в ширину на 1.5
    private static final float CLOCK_SCALE_Y = 1.15f; // высота
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
        String n = v.getClass().getName();
        return n.contains("DateSmartspaceView") || n.contains("BcSmartspaceView") || n.contains("Smartspace");
    }

    private static boolean isSmallClock(View v) {
        if (v == null) return false;
        String n = v.getClass().getName();
        return n.endsWith(CLOCK_SUFFIX) && v.getHeight() < 300;
    }

    private static void hookKeyguard(ClassLoader cl) {
        Class<?> constraintSet = XposedHelpers.findClassIfExists(
                "androidx.constraintlayout.widget.ConstraintSet", cl);
        Class<?> constraintLayout = XposedHelpers.findClassIfExists(
                "androidx.constraintlayout.widget.ConstraintLayout", cl);
        Class<?> rootClass = XposedHelpers.findClassIfExists(
                "com.android.systemui.keyguard.ui.view.KeyguardRootView", cl);

        // 1. Устраняем 1-секундный улет даты: меняем правила ДО применения анимации
        if (constraintSet != null) {
            XC_MethodHook applyHook = new XC_MethodHook() {
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
            XposedBridge.hookAllMethods(constraintSet, "applyTo", applyHook);
            XposedBridge.hookAllMethods(constraintSet, "applyToWithoutCheck", applyHook);
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

        // 3. Контроль отрисовки: гарантирует центр на обычном экране и AOD без наложений
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
                                        centerNet(root);
                                    } catch (Throwable ignored) {}
                                    return true;
                                }
                            });
                }
            });
        }
    }

    private static void patchConstraintSet(Object cs, ViewGroup root) {
        View clock = null;
        View date = null;
        for (int i = 0; i < root.getChildCount(); i++) {
            View c = root.getChildAt(i);
            if (isSmallClock(c)) clock = c;
            else if (isDateView(c)) date = c;
        }

        int clockId = clock != null ? clock.getId() : resId(root, "small_clock_view");
        if (clockId == 0 || clockId == View.NO_ID) clockId = resId(root, "lockscreen_clock_view");

        int dateId = date != null ? date.getId() : resId(root, "date_smartspace_view");
        if (dateId == 0 || dateId == View.NO_ID) dateId = resId(root, "bc_smartspace_view");

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
                    XposedHelpers.callMethod(cs, "connect", dateId, CS_TOP, clockId, CS_BOTTOM, 20);
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

    private static boolean fixKeyguard(ViewGroup root, boolean inMeasure) {
        View clock = null;
        View date = null;
        for (int i = 0; i < root.getChildCount(); i++) {
            View c = root.getChildAt(i);
            String n = c.getClass().getName();
            if (n.endsWith(CLOCK_SUFFIX)) clock = c;
            else if (isDateView(c)) date = c;
        }
        if (clock == null) return false;

        // Если у часов нет ID, создаем его, иначе ConstraintLayout не сможет привязать дату под часы
        if (clock.getId() == View.NO_ID) {
            clock.setId(View.generateViewId());
        }
        int cid = clock.getId();

        boolean changed = enforceCentered(clock, root, inMeasure);
        scaleClock(clock);

        if (date == null) return changed;
        if (date.getId() == View.NO_ID) {
            date.setId(View.generateViewId());
        }

        ViewGroup.LayoutParams lp = date.getLayoutParams();
        if (lp == null) return changed;

        boolean v = false;
        v |= setInt(lp, "topToTop", UNSET);
        v |= setInt(lp, "bottomToTop", UNSET);
        v |= setInt(lp, "bottomToBottom", UNSET);
        v |= setInt(lp, "startToEnd", UNSET);
        v |= setInt(lp, "leftToRight", UNSET);
        v |= setInt(lp, "topToBottom", cid);

        boolean h = enforceCentered(date, root, inMeasure);

        // Отступ 20px, чтобы увеличенные часы не налезали на дату
        if (lp instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) lp;
            if (m.topMargin < 20) {
                m.topMargin = 20;
                v = true;
            }
        }

        if (v || h) apply(date, lp, root, inMeasure);
        changed |= v || h;

        changed |= pointPlaceholderAtDate(root, date.getId(), inMeasure);
        return changed;
    }

    /**
     * Предотвращает наложение даты на часы и центрирует всё по физическому центру дисплея (включая AOD)
     */
    private static void centerNet(ViewGroup root) {
        View clock = null;
        View date = null;
        for (int i = 0; i < root.getChildCount(); i++) {
            View c = root.getChildAt(i);
            String n = c.getClass().getName();
            if (n.endsWith(CLOCK_SUFFIX)) clock = c;
            else if (isDateView(c)) date = c;
        }

        if (clock == null || root.getWidth() == 0) return;
        if (clock.getVisibility() != View.VISIBLE || clock.getWidth() == 0 || clock.getAlpha() < 0.05f || clock.getHeight() > 300) return;

        int screenWidth = root.getResources().getDisplayMetrics().widthPixels;
        float rootShift = root.getLeft() + root.getTranslationX();
        float targetCenterInRoot = (screenWidth / 2f) - rootShift;

        // Центрируем часы
        netCenter(clock, targetCenterInRoot);

        // Центрируем дату и исключаем наложение по вертикали
        if (date != null && date.getVisibility() == View.VISIBLE && date.getWidth() > 0 && date.getAlpha() > 0.05f) {
            netCenter(date, targetCenterInRoot);

            // Физическая защита от наложения: дата гарантированно начинается НИЖЕ часов
            float visualClockBottom = clock.getTop() + clock.getTranslationY() + clock.getHeight() * (1f + (CLOCK_SCALE_Y - 1f) / 2f);
            float desiredDateTop = visualClockBottom + 16f;
            float currentDateTop = date.getTop() + date.getTranslationY();
            if (currentDateTop < desiredDateTop) {
                date.setTranslationY(date.getTranslationY() + (desiredDateTop - currentDateTop));
            }
        }
    }

    private static void netCenter(View v, float targetCenterInRoot) {
        float center = v.getLeft() + v.getTranslationX() + v.getWidth() / 2f;
        float err = targetCenterInRoot - center;
        if (Math.abs(err) > 0.5f) {
            v.setTranslationX(v.getTranslationX() + err);
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

    private static boolean pointPlaceholderAtDate(ViewGroup root, int dateId, boolean inMeasure) {
        if (dateId == View.NO_ID) return false;
        if (placeholderId == 0) placeholderId = resId(root, "nssl_placeholder");
        if (placeholderId == 0) return false;
        View ph = root.findViewById(placeholderId);
        if (ph == null || ph.getLayoutParams() == null) return false;
        ViewGroup.LayoutParams lp = ph.getLayoutParams();
        if (!setInt(lp, "topToBottom", dateId)) return false;
        apply(ph, lp, root, inMeasure);
        return true;
    }

    private static int resId(ViewGroup root, String name) {
        try {
            return root.getResources().getIdentifier(name, "id", "com.android.systemui");
        } catch (Throwable t) {
            return 0;
        }
    }

    private static boolean enforceCentered(View v, ViewGroup parent, boolean inMeasure) {
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp == null || !lp.getClass().getName().contains("ConstraintLayout")) return false;
        boolean ch = false;
        String[] clear = {"leftToLeft", "leftToRight", "rightToLeft", "rightToRight",
                "startToEnd", "endToStart"};
        for (String f : clear) ch |= setInt(lp, f, UNSET);
        ch |= setInt(lp, "startToStart", PARENT);
        ch |= setInt(lp, "endToEnd", PARENT);
        ch |= setFloat(lp, "horizontalBias", 0.5f);
        if (lp instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) lp;
            if (m.getMarginStart() != 0 || m.getMarginEnd() != 0
                    || m.leftMargin != 0 || m.rightMargin != 0) {
                m.setMarginStart(0);
                m.setMarginEnd(0);
                m.leftMargin = 0;
                m.rightMargin = 0;
                ch = true;
            }
        }
        if (ch) apply(v, lp, parent, inMeasure);
        return ch;
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

    // ------------------------------------------------ Shelf pill + AOD icons

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

    /** Выравнивает иконки на AOD строго по физическому центру дисплея */
    private static void centerAodIcons(ViewGroup c, int screenWidth) {
        if (c.getVisibility() != View.VISIBLE || c.getWidth() == 0) return;

        int[] cLoc = new int[2];
        c.getLocationOnScreen(cLoc);

        float minL = Float.MAX_VALUE;
        float maxR = -Float.MAX_VALUE;
        int n = 0;
        for (int i = 0; i < c.getChildCount(); i++) {
            View ch = c.getChildAt(i);
            if (ch.getVisibility() != View.VISIBLE || ch.getWidth() == 0
                    || ch.getAlpha() < 0.05f) continue;
            float l = ch.getLeft() + ch.getTranslationX();
            minL = Math.min(minL, l);
            maxR = Math.max(maxR, l + ch.getWidth());
            n++;
        }
        if (n == 0) return;

        float groupCenterOnScreen = cLoc[0] + minL + (maxR - minL) / 2f;
        float targetCenter = screenWidth / 2f;
        float shift = targetCenter - groupCenterOnScreen;

        if (Math.abs(shift) > 0.5f) {
            for (int i = 0; i < c.getChildCount(); i++) {
                View ch = c.getChildAt(i);
                ch.setTranslationX(ch.getTranslationX() + shift);
            }
        }
    }

    /** Центрирует круглый кружок уведомления (Shelf) на экране блокировки */
    private static void centerShelf(ViewGroup shelf, int screenWidth) {
        if (shelf.getVisibility() != View.VISIBLE || shelf.getWidth() == 0
                || shelf.getAlpha() < 0.01f) return;

        int full = shelf.getWidth();
        int pill = pillWidth(shelf);
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
