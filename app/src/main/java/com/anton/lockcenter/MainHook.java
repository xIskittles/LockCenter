package com.anton.lockcenter;

import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * LockCenter v5 (Pixel 7 Pro, Android 16).
 *
 * 1) Small clock + date: before EVERY measure pass of KeyguardRootView the module
 *    re-applies its own ConstraintLayout rules (clock centered with zero margins,
 *    date centered UNDER the clock, "smart_space_barrier_bottom" extended to the
 *    date so notifications start below it). Because it is re-applied each time,
 *    the system cannot silently undo it.
 * 2) Small clock size: scale factors below (width a bit more than height).
 * 3) Compact notification pill on the lock screen (NotificationShelf): centered.
 * 4) Notification icons on AOD (aod_notification_icon_container): centered.
 */
public class MainHook implements IXposedHookLoadPackage {

    // ======== TWEAK HERE ========
    /** Clock size multipliers (1.0 = stock). Width is larger than height on purpose. */
    private static final float CLOCK_SCALE_X = 1.20f;
    private static final float CLOCK_SCALE_Y = 1.12f;
    // ============================

    private static final int UNSET = -1;
    private static final int PARENT = 0;

    private static final Set<View> HOOKED =
            Collections.newSetFromMap(new WeakHashMap<View, Boolean>());

    private static Class<?> clockClass;
    private static Class<?> dateClass;
    private static Class<?> keyguardRootClass;
    private static int barrierId = 0;

    private static int logCount = 0;
    private static int shelfLogs = 0;
    private static int aodLogs = 0;
    private static int fixLogs = 0;

    private static void log(String m) {
        if (logCount++ < 150) XposedBridge.log("[LockCenter] " + m);
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!"com.android.systemui".equals(lpparam.packageName)) return;
        ClassLoader cl = lpparam.classLoader;
        log("v5 started");
        try {
            hookKeyguardMeasure(cl);
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

    // ---------------------------------------------------------------- clock + date

    private static void hookKeyguardMeasure(ClassLoader cl) {
        clockClass = XposedHelpers.findClassIfExists(
                "com.android.systemui.shared.clocks.view.FlexClockTextView", cl);
        dateClass = XposedHelpers.findClassIfExists(
                "com.google.android.systemui.smartspace.DateSmartspaceView", cl);
        keyguardRootClass = XposedHelpers.findClassIfExists(
                "com.android.systemui.keyguard.ui.view.KeyguardRootView", cl);
        Class<?> constraintLayout = XposedHelpers.findClassIfExists(
                "androidx.constraintlayout.widget.ConstraintLayout", cl);
        log("classes: clock=" + (clockClass != null) + " date=" + (dateClass != null)
                + " root=" + (keyguardRootClass != null)
                + " constraintLayout=" + (constraintLayout != null));
        if (clockClass == null || keyguardRootClass == null || constraintLayout == null) return;

        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    if (param.thisObject != null
                            && param.thisObject.getClass() == keyguardRootClass) {
                        fixKeyguard((ViewGroup) param.thisObject);
                    }
                } catch (Throwable t) {
                    XposedBridge.log(t);
                }
            }
        };
        int n = XposedBridge.hookAllMethods(constraintLayout, "onMeasure", hook).size();
        n += XposedBridge.hookAllMethods(keyguardRootClass, "onMeasure", hook).size();
        log("onMeasure hooks: " + n);
    }

    private static void fixKeyguard(ViewGroup root) {
        View clock = null;
        View date = null;
        for (int i = 0; i < root.getChildCount(); i++) {
            View c = root.getChildAt(i);
            if (clockClass.isInstance(c)) clock = c;                       // small clock
            else if (dateClass != null && dateClass.isInstance(c)) date = c; // date (+ alarm)
        }
        if (clock == null) return;

        boolean a = enforceCentered(clock, root);
        attachClockScaler(clock);
        if (a && fixLogs++ < 10) log("small clock centered");

        if (date == null) return;
        ViewGroup.LayoutParams lp = date.getLayoutParams();
        int cid = clock.getId();
        if (lp == null || cid == View.NO_ID) return;

        boolean tiedToClock = getInt(lp, "startToEnd") == cid
                || getInt(lp, "topToBottom") == cid
                || getInt(lp, "topToTop") == cid;
        if (!tiedToClock) return;   // large clock mode: leave the date alone

        boolean v = false;
        v |= setInt(lp, "topToTop", UNSET);
        v |= setInt(lp, "bottomToTop", UNSET);
        v |= setInt(lp, "bottomToBottom", UNSET);
        v |= setInt(lp, "topToBottom", cid);
        boolean h = enforceCentered(date, root);
        if (v && !h) resolve(date, lp, root);
        if ((v || h) && fixLogs++ < 10) log("date placed under clock and centered");

        extendBarrier(root, date.getId());
    }

    /** Make the barrier under the clock block include the date, so notifications start below it. */
    private static void extendBarrier(ViewGroup root, int dateId) {
        if (dateId == View.NO_ID) return;
        if (barrierId == 0) {
            barrierId = root.getResources().getIdentifier(
                    "smart_space_barrier_bottom", "id", "com.android.systemui");
        }
        if (barrierId == 0) return;
        View b = root.findViewById(barrierId);
        if (b == null) return;
        try {
            int[] ids = (int[]) XposedHelpers.callMethod(b, "getReferencedIds");
            for (int id : ids) if (id == dateId) return;
            int[] n = Arrays.copyOf(ids, ids.length + 1);
            n[ids.length] = dateId;
            XposedHelpers.callMethod(b, "setReferencedIds", (Object) n);
            if (fixLogs++ < 10) log("barrier extended with date (was " + ids.length + " ids)");
        } catch (Throwable t) {
            if (fixLogs++ < 10) log("barrier fix failed: " + t);
        }
    }

    private static boolean enforceCentered(View v, ViewGroup parent) {
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
        if (ch) resolve(v, lp, parent);
        return ch;
    }

    /** We are inside onMeasure: re-resolve the params directly instead of calling requestLayout. */
    private static void resolve(final View v, final ViewGroup.LayoutParams lp, ViewGroup parent) {
        try {
            lp.getClass().getMethod("resolveLayoutDirection", int.class)
                    .invoke(lp, parent.getLayoutDirection());
        } catch (Throwable t) {
            v.post(new Runnable() {
                @Override
                public void run() {
                    v.setLayoutParams(lp);
                }
            });
        }
    }

    private static int getInt(Object lp, String name) {
        try {
            return lp.getClass().getField(name).getInt(lp);
        } catch (Throwable t) {
            return -2;
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

    // ------------------------------------------------------ clock size

    /**
     * Multiplies whatever scale the system sets (burn-in, transitions) by our factor.
     * Only touches the value when the system changed it, so it never compounds.
     */
    private static void attachClockScaler(final View clock) {
        if (!HOOKED.add(clock)) return;
        clock.getViewTreeObserver().addOnPreDrawListener(
                new ViewTreeObserver.OnPreDrawListener() {
                    private float lastX = Float.NaN;
                    private float lastY = Float.NaN;

                    @Override
                    public boolean onPreDraw() {
                        try {
                            if (clock.getVisibility() != View.VISIBLE) return true;
                            clock.setPivotX(clock.getWidth() / 2f);
                            clock.setPivotY(clock.getHeight() / 2f);
                            float cx = clock.getScaleX();
                            if (cx != lastX) {
                                lastX = cx * CLOCK_SCALE_X;
                                clock.setScaleX(lastX);
                            }
                            float cy = clock.getScaleY();
                            if (cy != lastY) {
                                lastY = cy * CLOCK_SCALE_Y;
                                clock.setScaleY(lastY);
                            }
                        } catch (Throwable t) {
                            XposedBridge.log(t);
                        }
                        return true;
                    }
                });
    }

    // ------------------------------------------------ shelf pill + AOD icons

    private static void hookPreDraw(ClassLoader cl, String className, final boolean isShelf) {
        Class<?> c = XposedHelpers.findClassIfExists(className, cl);
        if (c == null) {
            log("class not found: " + className);
            return;
        }
        int n = XposedBridge.hookAllConstructors(c, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                final View v = (View) param.thisObject;
                if (!HOOKED.add(v)) return;
                v.getViewTreeObserver().addOnPreDrawListener(
                        new ViewTreeObserver.OnPreDrawListener() {
                            @Override
                            public boolean onPreDraw() {
                                try {
                                    if (isShelf) {
                                        centerShelf((ViewGroup) v);
                                    } else if (isAodContainer(v)) {
                                        centerAodIcons((ViewGroup) v);
                                    }
                                } catch (Throwable t) {
                                    XposedBridge.log(t);
                                }
                                return true;
                            }
                        });
            }
        }).size();
        log("constructor hooks for " + className + ": " + n);
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

    private static void centerAodIcons(ViewGroup c) {
        if (c.getVisibility() != View.VISIBLE || c.getWidth() == 0) return;
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
        float shift = (c.getWidth() - (maxR - minL)) / 2f - minL;
        if (Math.abs(shift) < 1f) return;
        for (int i = 0; i < c.getChildCount(); i++) {
            View ch = c.getChildAt(i);
            ch.setTranslationX(ch.getTranslationX() + shift);
        }
        if (aodLogs++ < 6) log("AOD icons shifted by " + shift + " (icons=" + n + ")");
    }

    private static void centerShelf(ViewGroup shelf) {
        if (shelf.getVisibility() != View.VISIBLE || shelf.getWidth() == 0
                || shelf.getAlpha() < 0.01f) return;
        View parent = (View) shelf.getParent();
        if (parent == null) return;

        int full = shelf.getWidth();
        int pill = pillWidth(shelf);
        float target = 0f;
        if (pill > 0 && full - pill > 100) {
            target = (parent.getWidth() - pill) / 2f - shelf.getLeft();
        }
        if (Math.abs(shelf.getTranslationX() - target) > 1f) {
            shelf.setTranslationX(target);
            if (shelfLogs++ < 8) {
                log("shelf: full=" + full + " pill=" + pill + " shift=" + target);
            }
        }
    }

    /** Width of the drawn pill: from the background view if possible, else from the icons. */
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
                return w > 0 ? w : 0;       // <=0 means "full width"
            } catch (Throwable ignored) {
                // field is named differently on this build: fall back to icons
            }
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
            if (n > 0) return (int) (maxR + minL);   // symmetric padding on both sides
        }
        return 0;
    }
}
