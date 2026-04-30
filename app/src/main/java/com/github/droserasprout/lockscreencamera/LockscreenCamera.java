package com.github.droserasprout.lockscreencamera;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.content.ContentResolver;
import android.content.ContentValues;

import androidx.annotation.NonNull;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam;

public class LockscreenCamera extends XposedModule {

    private static final String TAG = "LockscreenCamera";

    public LockscreenCamera() {
        super();
    }

    // =========================================================================
    // SessionManager
    // =========================================================================

    public static final class SessionManager {

        static final String[] TARGET_BOOLEAN_FIELDS = {
            "mIsSecure", "mIsSecureCamera", "mKeyguardLocked",
            "mInLockScreen", "mIgnoreKeyguard", "mIsScreenOn",
            "mSecureCamera", "mIsKeyguardLocked", "mIsHideForeground",
            "mIsGalleryLock", "mIsCaptureIntent", "mIsPortraitIntent", "mIsVideoIntent",
            "mUserAuthenticationFlag", "mIgnoreKeyguardCheck",
            "mIsCameraApp", "mPrivacyAuthorized", "mIsForeground"
        };

        private static final Map<String, Field> fieldCache = new ConcurrentHashMap<>();

        public static final AtomicBoolean isActive = new AtomicBoolean(false);
        public static final Set<Uri> SESSION_URIS =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

        private static final Field FIELD_NOT_FOUND_SENTINEL;
        static {
            Field sentinel = null;
            try {
                sentinel = SessionManager.class.getDeclaredField("isActive");
            } catch (Throwable ignored) {}
            FIELD_NOT_FOUND_SENTINEL = sentinel;
        }

        public static void start() {
            isActive.set(true);
            SESSION_URIS.clear();
            Log.i(TAG, "Session Started");
        }

        public static void end() {
            isActive.set(false);
            SESSION_URIS.clear();
            Log.i(TAG, "Session Cleared");
        }

        public static void add(Uri uri) {
            if (isActive.get() && uri != null) {
                SESSION_URIS.add(uri);
            }
        }

        static void setFieldFast(Object obj, String fieldName, Object value) {
            try {
                Class<?> current = obj.getClass();
                String cacheKey = current.getName() + ":" + fieldName;
                Field f = fieldCache.get(cacheKey);

                if (f == null) {
                    Field found = null;
                    Class<?> search = current;
                    while (search != null && !search.getName().equals("android.app.Activity")) {
                        try {
                            found = search.getDeclaredField(fieldName);
                            found.setAccessible(true);
                            break;
                        } catch (NoSuchFieldException e) {
                            search = search.getSuperclass();
                        }
                    }
                    fieldCache.put(cacheKey, found != null ? found : FIELD_NOT_FOUND_SENTINEL);
                    f = fieldCache.get(cacheKey);
                }

                if (f != null && f != FIELD_NOT_FOUND_SENTINEL) {
                    f.set(obj, value);
                }
            } catch (Throwable ignored) {}
        }
    }

    // =========================================================================
    // XposedModule Lifecycle
    // =========================================================================

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        String pkg = param.getPackageName();
        if (!isCameraPackage(pkg)) return;

        // 1. Viewの可視性制御
        // DecorView および カメラプレビュー用 View (SurfaceView/TextureView) のみを保護対象とし、
        // ローディング表示・ダイアログ・アニメーション等への干渉を避ける
        try {
            hook(View.class.getDeclaredMethod("setVisibility", int.class))
                .intercept(chain -> {
                    View view = (View) chain.getThisObject();
                    if (isCameraContext(view.getContext())) {
                        if (isDecorView(view)
                                || view instanceof SurfaceView
                                || view instanceof TextureView) {
                            int vis = (int) chain.getArg(0);
                            if (vis != View.VISIBLE) {
                                return chain.proceed(new Object[]{View.VISIBLE});
                            }
                        }
                    }
                    return chain.proceed();
                });

            hook(View.class.getDeclaredMethod("setAlpha", float.class))
                .intercept(chain -> {
                    View view = (View) chain.getThisObject();
                    if (isCameraContext(view.getContext())) {
                        if (isDecorView(view)
                                || view instanceof SurfaceView
                                || view instanceof TextureView) {
                            float alpha = (float) chain.getArg(0);
                            if (alpha < 1.0f) {
                                return chain.proceed(new Object[]{1.0f});
                            }
                        }
                    }
                    return chain.proceed();
                });
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "View visibility control failed", t);
        }

        // 2. Keyguard解除要求の制御
        try {
            Method dismissMethod = KeyguardManager.class.getDeclaredMethod(
                "requestDismissKeyguard",
                Activity.class,
                KeyguardManager.KeyguardDismissCallback.class);
            hook(dismissMethod).intercept(chain -> null);
        } catch (Throwable t) {
            log(Log.WARN, TAG, "requestDismissKeyguard control failed", t);
        }

        // 3. Activityのフォーカス状態制御
        try {
            hook(Activity.class.getDeclaredMethod("hasWindowFocus"))
                .intercept(chain -> {
                    if (isCameraActivity((Activity) chain.getThisObject())) return true;
                    return chain.proceed();
                });
            hook(Activity.class.getDeclaredMethod("isResumed"))
                .intercept(chain -> {
                    if (isCameraActivity((Activity) chain.getThisObject())) return true;
                    return chain.proceed();
                });
        } catch (Throwable t) {
            log(Log.WARN, TAG, "Activity visibility control failed", t);
        }

        // 4. Intentのパラメータ調整
        try {
            hook(Activity.class.getDeclaredMethod("getIntent"))
                .intercept(chain -> {
                    Intent intent = (Intent) chain.proceed();
                    if (isCameraActivity((Activity) chain.getThisObject()) && intent != null) {
                        if (intent.getBooleanExtra(
                                "com.miui.camera.extra.START_BY_KEYGUARD", false)) {
                            intent.putExtra("is_secure_camera", true);
                            intent.putExtra("ShowCameraWhenLocked", true);
                        }
                    }
                    return intent;
                });
        } catch (Throwable t) {
            log(Log.WARN, TAG, "getIntent control failed", t);
        }

        // 5. ギャラリー呼び出しのインテント制御
        try {
            hook(Activity.class.getDeclaredMethod("startActivity", Intent.class))
                .intercept(chain -> {
                    handleGalleryIntent(
                        (Context) chain.getThisObject(),
                        (Intent) chain.getArg(0));
                    return chain.proceed();
                });

            hook(Activity.class.getDeclaredMethod(
                    "startActivityForResult", Intent.class, int.class))
                .intercept(chain -> {
                    handleGalleryIntent(
                        (Context) chain.getThisObject(),
                        (Intent) chain.getArg(0));
                    return chain.proceed();
                });

            hook(ContextWrapper.class.getDeclaredMethod("startActivity", Intent.class))
                .intercept(chain -> {
                    handleGalleryIntent(
                        (Context) chain.getThisObject(),
                        (Intent) chain.getArg(0));
                    return chain.proceed();
                });
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "startActivity control failed", t);
        }

        // 6. ライフサイクルイベントの監視
        // screenOffReceiver を onDestroy で解除できるよう Activity をキーにした Map で管理する
        // attachBaseContext は this が ContextWrapper であり Activity にならないため除外
        final Map<Activity, BroadcastReceiver> receiverMap = new ConcurrentHashMap<>();

        String[] criticalMethods = {
            "onCreate", "onStart", "onResume",
            "onWindowFocusChanged", "onDestroy"
        };

        for (String mname : criticalMethods) {
            try {
                final Method m;
                if ("onCreate".equals(mname)) {
                    m = Activity.class.getDeclaredMethod("onCreate", Bundle.class);
                } else if ("onWindowFocusChanged".equals(mname)) {
                    m = Activity.class.getDeclaredMethod("onWindowFocusChanged", boolean.class);
                } else if ("onDestroy".equals(mname)) {
                    m = Activity.class.getDeclaredMethod("onDestroy");
                } else {
                    m = Activity.class.getDeclaredMethod(mname);
                }

                final String methodName = mname;
                hook(m).intercept(chain -> {
                    Object thisObj = chain.getThisObject();
                    if (!(thisObj instanceof Activity)) return chain.proceed();

                    Activity act = (Activity) thisObj;
                    if (!isCameraActivity(act)) return chain.proceed();

                    if ("onDestroy".equals(methodName)) {
                        if (SessionManager.isActive.get()) {
                            SessionManager.end();
                        }
                        // 登録済みレシーバーを解除してリークを防ぐ
                        BroadcastReceiver receiver = receiverMap.remove(act);
                        if (receiver != null) {
                            try {
                                act.unregisterReceiver(receiver);
                            } catch (Exception ignored) {}
                        }
                        return chain.proceed();
                    }

                    if ("onWindowFocusChanged".equals(methodName)) {
                        boolean hasFocus = (boolean) chain.getArg(0);
                        if (!hasFocus) return chain.proceed();
                        // フォーカス取得時は軽量更新のみ。Window属性の再適用によるバトルを防ぐ
                        applyWindowAttributes(act, true);
                        return chain.proceed();
                    }

                    if ("onCreate".equals(methodName)) {
                        Object res = chain.proceed();
                        Intent intent = act.getIntent();
                        boolean isLockscreenLaunch = intent != null &&
                            intent.getBooleanExtra(
                                "com.miui.camera.extra.START_BY_KEYGUARD", false);

                        if (isLockscreenLaunch) {
                            SessionManager.start();

                            IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
                            BroadcastReceiver screenOffReceiver = new BroadcastReceiver() {
                                @Override
                                public void onReceive(Context context, Intent i) {
                                    SessionManager.end();
                                    act.finish();
                                }
                            };

                            try {
                                if (Build.VERSION.SDK_INT >= 33) {
                                    act.registerReceiver(screenOffReceiver, filter,
                                        Context.RECEIVER_NOT_EXPORTED);
                                } else {
                                    act.registerReceiver(screenOffReceiver, filter);
                                }
                                // onDestroy で解除できるよう Map に保持
                                receiverMap.put(act, screenOffReceiver);
                            } catch (Exception e) {
                                log(Log.WARN, TAG, "Failed to register screen off receiver", e);
                            }
                        }

                        // onCreate のみフル適用
                        applyWindowAttributes(act, false);
                        return res;
                    }

                    // onStart / onResume は軽量更新のみ。フル適用はバトルの原因になる
                    applyWindowAttributes(act, true);
                    return chain.proceed();
                });
            } catch (Throwable t) {
                log(Log.WARN, TAG, "Lifecycle method control failed: " + mname, t);
            }
        }

        // 7. 保存されたメディアのトラッキング（書き込み完了時のみ）
        // IS_PENDING=0 への update をもって保存完了とみなす。
        // insert フックは「仮URI」を拾ってしまうため使用しない。
        try {
            hook(ContentResolver.class.getDeclaredMethod(
                    "update", Uri.class, ContentValues.class, String.class, String[].class))
                .intercept(chain -> {
                    Object result = chain.proceed();
                    if (SessionManager.isActive.get()) {
                        Uri uri = (Uri) chain.getArg(0);
                        ContentValues values = (ContentValues) chain.getArg(1);

                        if (uri != null && values != null) {
                            if (Build.VERSION.SDK_INT >= 29
                                    && values.containsKey(MediaStore.MediaColumns.IS_PENDING)) {
                                Integer pending =
                                    values.getAsInteger(MediaStore.MediaColumns.IS_PENDING);
                                if (pending != null && pending == 0) {
                                    SessionManager.add(uri);
                                }
                            } else if (values.containsKey(MediaStore.Images.Media.DATA)) {
                                SessionManager.add(uri);
                            }
                        }
                    }
                    return result;
                });
        } catch (Throwable t) {
            log(Log.WARN, TAG, "ContentResolver.update control failed", t);
        }

        // 8. システムサービスの制御
        try {
            Class<?> callbackClass = Class.forName(
                "android.hardware.camera2.CameraManager$AvailabilityCallback",
                true, param.getClassLoader());
            hook(callbackClass.getDeclaredMethod("onCameraUnavailable", String.class))
                .intercept(chain -> null);
        } catch (Throwable t) {
            log(Log.WARN, TAG, "CameraManager.AvailabilityCallback control failed", t);
        }

        try {
            Class<?> biometricClass = Class.forName(
                "android.hardware.biometrics.BiometricManager", true, param.getClassLoader());
            hook(biometricClass.getDeclaredMethod("canAuthenticate", int.class))
                .intercept(chain -> 0);
        } catch (Throwable t) {
            log(Log.WARN, TAG, "BiometricManager.canAuthenticate control failed", t);
        }
    }

    @Override
    public void onSystemServerStarting(@NonNull SystemServerStartingParam param) {
        try {
            Class<?> gestureClass = Class.forName(
                "com.android.server.GestureLauncherService", true, param.getClassLoader());
            Method handleCameraGesture = gestureClass.getDeclaredMethod(
                "handleCameraGesture", boolean.class, int.class);

            hook(handleCameraGesture).intercept(chain -> {
                try {
                    Method getContextMethod =
                        chain.getThisObject().getClass().getMethod("getContext");
                    Context context = (Context) getContextMethod.invoke(chain.getThisObject());

                    KeyguardManager km =
                        (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
                    boolean isLocked = (km != null && km.isKeyguardLocked());

                    PackageManager pm = context.getPackageManager();
                    Intent resolveIntent =
                        new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE);
                    ResolveInfo info =
                        pm.resolveActivity(resolveIntent, PackageManager.MATCH_DEFAULT_ONLY);

                    if (info == null || info.activityInfo.name.contains("Resolver")) {
                        resolveIntent = new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA);
                        info = pm.resolveActivity(resolveIntent, PackageManager.MATCH_DEFAULT_ONLY);
                    }

                    String targetPkg = "com.android.camera";
                    String targetCls = "com.android.camera.Camera";

                    if (info != null && !info.activityInfo.name.contains("Resolver")) {
                        targetPkg = info.activityInfo.packageName;
                        targetCls = info.activityInfo.name;
                    }

                    Intent intent =
                        new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE);
                    if (targetPkg != null && targetCls != null) {
                        intent.setComponent(new ComponentName(targetPkg, targetCls));
                    }

                    final int FLAG_ACTIVITY_RESET_TASK_IF_NEEDED = 0x00040000;
                    final int FLAG_SHOW_WHEN_LOCKED_COMPAT        = 0x00080000;
                    final int FLAG_DISMISS_KEYGUARD_COMPAT         = 0x00400000;
                    final int FLAG_TURN_SCREEN_ON_COMPAT           = 0x00200000;

                    int flags = FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                        | Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP;

                    if (isLocked) {
                        flags |= Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS;
                        intent.putExtra("com.miui.camera.extra.START_BY_KEYGUARD", true);
                        intent.putExtra("StartActivityWhenLocked", true);
                        intent.putExtra("is_secure_camera", true);
                    } else {
                        intent.putExtra("com.miui.camera.extra.START_BY_KEYGUARD", false);
                        intent.putExtra("StartActivityWhenLocked", false);
                    }

                    intent.addFlags(flags);
                    intent.addFlags(FLAG_SHOW_WHEN_LOCKED_COMPAT
                        | FLAG_DISMISS_KEYGUARD_COMPAT
                        | FLAG_TURN_SCREEN_ON_COMPAT);
                    intent.putExtra("android.intent.extra.CAMERA_OPEN_ONLY", true);
                    intent.putExtra("com.android.systemui.camera_launch_source",
                        "lockscreen_affordance");

                    ActivityOptions options = ActivityOptions.makeBasic();
                    if (Build.VERSION.SDK_INT >= 34) {
                        options.setPendingIntentBackgroundActivityStartMode(2);
                    }

                    context.startActivity(intent, options.toBundle());
                    return true;
                } catch (Throwable t) {
                    log(Log.ERROR, TAG, "Gesture camera launch control failed", t);
                }
                return chain.proceed();
            });
        } catch (Throwable t) {
            log(Log.WARN, TAG, "GestureLauncherService control failed", t);
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private boolean isCameraPackage(String pkg) {
        if (pkg == null) return false;
        if (pkg.equals("com.android.camera") || pkg.equals("org.codeaurora.snapcam")) return true;
        if (pkg.contains("GoogleCamera")) return true;
        if (pkg.contains("cam")
            && !pkg.startsWith("com.android.")
            && !pkg.startsWith("com.google.")) {
            return true;
        }
        return false;
    }

    private boolean isCameraActivity(Activity act) {
        try {
            return act != null && isCameraPackage(act.getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isCameraContext(Context ctx) {
        if (ctx == null) return false;
        try {
            return isCameraPackage(ctx.getPackageName());
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isDecorView(View v) {
        return v != null && v.getClass().getName().endsWith("DecorView");
    }

    private void handleGalleryIntent(Context ctx, Intent intent) {
        if (ctx == null || intent == null || intent.getAction() == null) return;
        if (!SessionManager.isActive.get()) return;

        try {
            if (!isCameraPackage(ctx.getPackageName())) return;
        } catch (Exception e) {
            return;
        }

        if (intent.getData() != null) {
            try {
                if (intent.hasExtra("is_secure_camera")
                    && !"com.android.camera".equals(ctx.getPackageName())) {
                    Log.d(TAG, "Smart Bypass: Intent is already secure in non-MIUI app.");
                    return;
                }
            } catch (Exception ignored) {}
        }

        String action = intent.getAction();
        boolean isGallery = Intent.ACTION_VIEW.equals(action)
            || Intent.ACTION_PICK.equals(action)
            || action.contains("REVIEW")
            || action.contains("STILL_IMAGE_CAMERA");

        if (!isGallery) return;

        ArrayList<Uri> uriList = new ArrayList<>(SessionManager.SESSION_URIS);
        if (uriList.isEmpty() && intent.getData() != null) {
            uriList.add(intent.getData());
        }

        intent.setComponent(new ComponentName(
            "com.github.droserasprout.lockscreencamera",
            "com.github.droserasprout.lockscreencamera.SecureViewerActivity"
        ));
        intent.setPackage(null);
        intent.setSelector(null);

        if (!uriList.isEmpty()) {
            ClipData clipData = new ClipData(
                "session_photos",
                new String[]{"image/*"},
                new ClipData.Item(uriList.get(0))
            );
            for (int i = 1; i < uriList.size(); i++) {
                clipData.addItem(new ClipData.Item(uriList.get(i)));
            }
            intent.setClipData(clipData);
        }

        intent.putParcelableArrayListExtra("session_photos_list", uriList);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
            | Intent.FLAG_ACTIVITY_NEW_TASK
            | Intent.FLAG_ACTIVITY_CLEAR_TOP
            | Intent.FLAG_ACTIVITY_NO_ANIMATION);

        final int FLAG_SHOW_WHEN_LOCKED_COMPAT = 0x00080000;
        final int FLAG_DISMISS_KEYGUARD_COMPAT  = 0x00400000;
        final int FLAG_TURN_SCREEN_ON_COMPAT    = 0x00200000;
        intent.addFlags(FLAG_SHOW_WHEN_LOCKED_COMPAT
            | FLAG_DISMISS_KEYGUARD_COMPAT
            | FLAG_TURN_SCREEN_ON_COMPAT);
    }

    /**
     * @param lightUpdate true のとき Window 属性の再適用を省略する。
     *                    onWindowFocusChanged 等の高頻度コールバックから呼ぶ際に使用し、
     *                    setAttributes/addFlags の連続呼び出しによるフォーカス再計算ループを防ぐ。
     */
    private void applyWindowAttributes(Activity activity, boolean lightUpdate) {
        try {
            activity.setShowWhenLocked(true);
            activity.setTurnScreenOn(true);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                activity.setInheritShowWhenLocked(true);
            }

            Window window = activity.getWindow();
            if (window != null && !lightUpdate) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
                window.setFormat(PixelFormat.TRANSLUCENT);

                // setAttributes + addFlags を連続して呼ぶとフレームワークがフォーカス再計算を
                // 繰り返しバトル状態に陥るため、window.setFlags で一括指定する
                final int addFlags = WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_FULLSCREEN
                    | WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON
                    | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED;

                // FLAG_DISMISS_KEYGUARD を除外マスクに含めてクリアしつつ、他フラグを一括セット
                window.setFlags(addFlags,
                    addFlags | WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    WindowManager.LayoutParams lp = window.getAttributes();
                    lp.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                    window.setAttributes(lp);
                }

                View decorView = window.getDecorView();
                if (decorView != null) {
                    decorView.setAlpha(1.0f);
                    decorView.setVisibility(View.VISIBLE);
                    decorView.requestFocus();
                }
            }

            for (String fieldName : SessionManager.TARGET_BOOLEAN_FIELDS) {
                SessionManager.setFieldFast(activity, fieldName, true);
            }
            SessionManager.setFieldFast(activity, "mIsNormalIntent", false);
            SessionManager.setFieldFast(activity, "mShowEnteringAnimation", false);
            SessionManager.setFieldFast(activity, "mKeyguardStatus", 1);
            SessionManager.setFieldFast(activity, "mIsSecureCameraId", 0);

        } catch (Throwable t) {
            log(Log.DEBUG, TAG, "Window attribute application failed: " + t.toString());
        }
    }
}
