package ac.no.screenshot;

import android.view.View;
import android.view.WindowManager;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import io.github.libxposed.api.XposedModule;

public class MainHook extends XposedModule {

    private final Set<View> protectedViews =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private final Set<Object> dimHandled =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private final Set<Object> secureApplied =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    private Field fView;
    private Field fWindowAttributes;
    private Field fSurfaceControl;
    private Field fLeash;
    private Field fSurfaceControlLocked;
    private Field fSurface;

    private volatile Field validSurfaceField;

    private Method mScIsValid;
    private Method mTransactionApply;
    private Method mSetSkipScreenshot;
    private Method mSetSkipScreenshotLegacy;
    private Method mSetSecure;
    private Method mSetAllowScreenshots;
    private Constructor<?> consTransaction;

    private volatile boolean cacheReady;
    private final Object cacheLock = new Object();

    public MainHook() {
        super();

        final ClassLoader cl = MainHook.class.getClassLoader();

        // 1. 提前反射缓存
        try {
            Class<?> vri = Class.forName("android.view.ViewRootImpl", false, cl);
            ensureCache(vri, cl);
        } catch (Throwable ignored) {}

        hookWindowManagerGlobal(cl);
        hookViewRootImpl(cl);
    }

    private void hookWindowManagerGlobal(ClassLoader cl) {
        try {
            Class<?> wmg = Class.forName("android.view.WindowManagerGlobal", false, cl);
            for (Method m : wmg.getDeclaredMethods()) {
                if (m.getName().equals("addView") && m.getParameterTypes().length > 0
                        && View.class.isAssignableFrom(m.getParameterTypes()[0])) {
                    hook(m).intercept(chain -> {
                        View view = (View) chain.getArgs().get(0);
                        protectedViews.add(view);
                        return chain.proceed();
                    });
                }
            }
        } catch (Throwable ignored) {}
    }

    private void hookViewRootImpl(ClassLoader cl) {
        try {
            Class<?> vri = Class.forName("android.view.ViewRootImpl", false, cl);
            ensureCache(vri, vri.getClassLoader());

            for (Method m : vri.getDeclaredMethods()) {
                if (m.getName().equals("setView")) {
                    hook(m).intercept(chain -> {
                        Object result = chain.proceed();
                        removeDim(chain.getThisObject());
                        applySecure(chain.getThisObject());
                        return result;
                    });
                }
            }

            for (Method m : vri.getDeclaredMethods()) {
                if (m.getName().equals("relayoutWindow")) {
                    hook(m).intercept(chain -> {
                        removeDim(chain.getThisObject());
                        Object result = chain.proceed();
                        secureApplied.remove(chain.getThisObject());
                        applySecure(chain.getThisObject());
                        return result;
                    });
                }
            }

            for (Method m : vri.getDeclaredMethods()) {
                if (m.getName().equals("performTraversals") && m.getParameterCount() == 0) {
                    hook(m).intercept(chain -> {
                        applySecure(chain.getThisObject());
                        return chain.proceed();
                    });
                }
            }
        } catch (Throwable ignored) {}
    }

    private void ensureCache(Class<?> vriClass, ClassLoader systemCl) {
        if (cacheReady) return;
        synchronized (cacheLock) {
            if (cacheReady) return;

            try { fView = vriClass.getDeclaredField("mView"); fView.setAccessible(true); } catch (Throwable ignored) {}
            try { fWindowAttributes = vriClass.getDeclaredField("mWindowAttributes"); fWindowAttributes.setAccessible(true); } catch (Throwable ignored) {}
            try { fSurfaceControl = vriClass.getDeclaredField("mSurfaceControl"); fSurfaceControl.setAccessible(true); } catch (Throwable ignored) {}
            try { fLeash = vriClass.getDeclaredField("mLeash"); fLeash.setAccessible(true); } catch (Throwable ignored) {}
            try { fSurfaceControlLocked = vriClass.getDeclaredField("mSurfaceControlLocked"); fSurfaceControlLocked.setAccessible(true); } catch (Throwable ignored) {}
            try { fSurface = vriClass.getDeclaredField("mSurface"); fSurface.setAccessible(true); } catch (Throwable ignored) {}

            try {
                Class<?> scClass = Class.forName("android.view.SurfaceControl", false, systemCl);
                Class<?> txnClass = Class.forName("android.view.SurfaceControl$Transaction", false, systemCl);

                mScIsValid = scClass.getDeclaredMethod("isValid");
                mScIsValid.setAccessible(true);
                consTransaction = txnClass.getDeclaredConstructor();
                consTransaction.setAccessible(true);
                mTransactionApply = txnClass.getDeclaredMethod("apply");
                mTransactionApply.setAccessible(true);

                try { mSetSkipScreenshot = txnClass.getDeclaredMethod("setSkipScreenshot", scClass, boolean.class); mSetSkipScreenshot.setAccessible(true); } catch (Throwable ignored) {}
                try { mSetSkipScreenshotLegacy = txnClass.getDeclaredMethod("setSkipScreenshot", boolean.class); mSetSkipScreenshotLegacy.setAccessible(true); } catch (Throwable ignored) {}
                try { mSetSecure = txnClass.getDeclaredMethod("setSecure", scClass, boolean.class); mSetSecure.setAccessible(true); } catch (Throwable ignored) {}
                try { mSetAllowScreenshots = txnClass.getDeclaredMethod("setAllowScreenshots", scClass, boolean.class); mSetAllowScreenshots.setAccessible(true); } catch (Throwable ignored) {}
            } catch (Throwable ignored) {}

            cacheReady = true;
        }
    }

    private void removeDim(Object vri) {
        if (fWindowAttributes == null || dimHandled.contains(vri)) return;
        try {
            WindowManager.LayoutParams lp = (WindowManager.LayoutParams) fWindowAttributes.get(vri);
            if (lp != null && (lp.flags & WindowManager.LayoutParams.FLAG_DIM_BEHIND) != 0) {
                lp.flags &= ~WindowManager.LayoutParams.FLAG_DIM_BEHIND;
                lp.dimAmount = 0f;
                dimHandled.add(vri);
            }
        } catch (Throwable ignored) {}
    }

    private void applySecure(Object vri) {
        if (!cacheReady) return;
        if (secureApplied.contains(vri)) return;

        View view = null;
        try { if (fView != null) view = (View) fView.get(vri); } catch (Throwable ignored) {}
        if (view == null || !protectedViews.contains(view)) return;

        Object sc = findValidSurface(vri);
        if (sc == null) return;

        Object txn = null;
        try {
            txn = consTransaction.newInstance();
            boolean applied = false;

            if (mSetSkipScreenshot != null) {
                try { mSetSkipScreenshot.invoke(txn, sc, true); applied = true; } catch (Throwable ignored) {}
            }
            if (!applied && mSetSkipScreenshotLegacy != null) {
                try { mSetSkipScreenshotLegacy.invoke(txn, true); applied = true; } catch (Throwable ignored) {}
            }
            if (!applied && mSetSecure != null) {
                try { mSetSecure.invoke(txn, sc, true); applied = true; } catch (Throwable ignored) {}
            }
            if (!applied && mSetAllowScreenshots != null) {
                try { mSetAllowScreenshots.invoke(txn, sc, false); applied = true; } catch (Throwable ignored) {}
            }

            if (applied) {
                mTransactionApply.invoke(txn);
                secureApplied.add(vri);
            }
        } catch (Throwable ignored) {
        } finally {
            if (txn != null) {
                try {
                    txn.getClass().getMethod("close").invoke(txn);
                } catch (Throwable ignored) {}
            }
        }
    }

    private Object findValidSurface(Object vri) {
        if (validSurfaceField != null) {
            try {
                Object sc = validSurfaceField.get(vri);
                if (sc != null && Boolean.TRUE.equals(mScIsValid.invoke(sc))) {
                    return sc;
                }
            } catch (Throwable ignored) {}
            validSurfaceField = null;
        }

        Field[] candidates = {fSurfaceControl, fLeash, fSurfaceControlLocked, fSurface};
        for (Field f : candidates) {
            if (f == null) continue;
            try {
                Object sc = f.get(vri);
                if (sc != null && Boolean.TRUE.equals(mScIsValid.invoke(sc))) {
                    validSurfaceField = f;
                    return sc;
                }
            } catch (Throwable ignored) {}
        }
        return null;
    }
}
