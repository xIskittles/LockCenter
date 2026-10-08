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

/**
 * LockCenter v4 (Pixel 7 Pro, Android 16).
 * 1) small clock + date: ConstraintLayout rules of KeyguardRootView are patched
 *    right after ConstraintSet.applyTo (clock centered, date centered UNDER the clock).
 * 2) compact notification pill on the lock screen (NotificationShelf): shifted to the center.
 * 3) notification icons on AOD (aod_notification_icon_container): shifted to the center.
 * Nothing uses a permanent hack on translationX of clock/date, so AOD burn-in shifts keep working.
 */
public class MainHook implements IXposedHookLoadPackage {

    private static final int UNSET = -1;
    private static final int PARENT = 0;

    private static final Set<View> HOOKED =
            Collections.newSetFromMap(new WeakHashMap<View, Boolean>());
    private static int logCount = 0;
    private static int shelfLogs = 0;
    private static int aodLogs = 0;

    private static void log(String m) {
        if (logCount++ < 120) XposedBridge.log("[LockCenter] " + m);
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (!"com.android.systemui".equals(lpparam.packageName)) return;
        ClassLoader cl = lpparam.classLoader;
        log("v4 started");
        try {
            hookConstraintSet(cl);
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

    private static void hookConstraintSet(ClassLoader cl) {
        Class<?> cs = XposedHelpers.findClassIfExists(
                "androidx.constraintlayout.widget.ConstraintSet", cl);
        if (cs == null) {
            log("ConstraintSet not found");
            return;
        }
        XC_MethodHook hook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    if (param.args.length >= 1 && param.args[0] instanceof ViewGroup) {
                        ViewGroup root = (ViewGroup) param.args[0];
                        if (root.getClass().getName().endsWith("KeyguardRootView")) {
                            fixKeyguard(root);
                        }
                    }
                } catch (Throwable t) {
                    XposedBridge.log(t);
                }
            }
        };
        int n = XposedBridge.hookAllMethods(cs, "applyTo", hook).size();
        n += XposedBridge.hookAllMethods(cs, "applyToWithoutCheck", hook).size();
        log("ConstraintSet hooks: " + n);
    }

    private static void fixKeyguard(ViewGroup root) {
        View clock = null;
        View date = null;
        for (int i = 0; i < root.getChildCount(); i++) {
            View c = root.getChildAt(i);
            String n = c.getClass().getName();
            if (n.endsWith(".FlexClockTextView")) clock = c;       // small clock (direct child)
            else if (n.endsWith(".DateSmartspaceView")) date = c;  // date (+ alarm)
        }
        if (clock == null) return;

        if (centerHorizontally(clock)) log("small clock centered");

        if (date != null) {
            ViewGroup.LayoutParams lp = date.getLayoutParams();
            int cid = clock.getId();
            if (lp != null && cid != View.NO_ID
                    && (getInt(lp, "startToEnd") == cid
                    || getInt(lp, "topToBottom") == cid
                    || getInt(lp, "topToTop") == cid)) {
                boolean v = false;
                v |= setInt(lp, "topToTop", UNSET);
                v |= setInt(lp, "bottomToBottom", UNSET);
                v |= setInt(lp, "topToBottom", cid);
                boolean h = centerHorizontally(date);
                if (v && !h) date.setLayoutParams(lp);
                if (v || h) log("date moved under clock and centered");
            }
        }
    }

    private static boolean centerHorizontally(View v) {
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
        if (ch) v.setLayoutParams(lp);
        return ch;
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
