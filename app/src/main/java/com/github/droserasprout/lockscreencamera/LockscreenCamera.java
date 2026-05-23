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
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.content.ContentResolver;
import android.content.ContentValues;

import androidx.annotation.NonNull;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;
import io.github.libxposed.api.XposedModuleInterface.PackageReadyParam;
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam;

public class LockscreenCamera extends XposedModule {

    private static final String TAG = "LockscreenCamera";
    // フィールド検索用キャッシュ
    private static final Map<String, Field> fieldCache = new ConcurrentHashMap<>();

    // 書き換え対象の内部フィールド名リスト
    private static final String[] TARGET_BOOLEAN_FIELDS = {
        "mIsSecure", "mIsSecureCamera", "mKeyguardLocked",
        "mInLockScreen", "mIgnoreKeyguard", "mIsScreenOn",
        "mSecureCamera", "mIsKeyguardLocked", "mIsHideForeground",
        "mIsGalleryLock", "mIsCaptureIntent", "mIsPortraitIntent", "mIsVideoIntent",
        "mUserAuthenticationFlag", "mIgnoreKeyguardCheck",
        "mIsCameraApp", "mPrivacyAuthorized", "mIsForeground"
    };

    public LockscreenCamera() {
        super();
    }

    // === SessionManager (カメラアプリプロセス内で動作) ===
    public static class SessionManager {
        public static volatile boolean isActive = false;
        public static final List<Uri> SESSION_URIS = new CopyOnWriteArrayList<>();

        public static void start() {
            isActive = true;
            SESSION_URIS.clear();
            Log.i(TAG, "Secure Camera Session Started");
        }

        public static void end() {
            isActive = false;
            SESSION_URIS.clear();
            Log.i(TAG, "Secure Camera Session Cleared");
        }

        public static void add(Uri uri) {
            if (isActive && uri != null) {
                if (!SESSION_URIS.contains(uri)) {
                    SESSION_URIS.add(uri);
                    Log.d(TAG, "Added to Session: " + uri + " | Total: " + SESSION_URIS.size());
                } else {
                    Log.d(TAG, "Duplicate URI skipped: " + uri);
                }
            }
        }
    }

    @Override
    public void onPackageReady(@NonNull PackageReadyParam param) {
        // カメラパッケージのみを対象にする
        String pkg = param.getPackageName();
        if (!isCameraPackage(pkg)) {
            return;
        }

        log(Log.INFO, TAG, "Targeting Camera App: " + pkg);

        // 0. DecorView の透明化・非表示化を物理的に阻止
        try {
            hook(View.class.getDeclaredMethod("setAlpha", float.class)).intercept(chain -> {
                View view = (View) chain.getThisObject();
                if (isDecorView(view) && isCameraContext(view.getContext())) {
                    List<Object> args = chain.getArgs();
                    float alpha = (float) args.get(0);
                    if (alpha < 1.0f) args.set(0, 1.0f);
                }
                return chain.proceed();
            });
            hook(View.class.getDeclaredMethod("setVisibility", int.class)).intercept(chain -> {
                View view = (View) chain.getThisObject();
                if (isDecorView(view) && isCameraContext(view.getContext())) {
                    List<Object> args = chain.getArgs();
                    int vis = (int) args.get(0);
                    if (vis != View.VISIBLE) args.set(0, View.VISIBLE);
                }
                return chain.proceed();
            });
        } catch (Throwable ignored) {}

        // 1. Keyguard の解除要求（PIN 画面表示）を完全にブロック
        try {
            Method dismissMethod = KeyguardManager.class.getDeclaredMethod(
                    "requestDismissKeyguard", Activity.class, KeyguardManager.KeyguardDismissCallback.class);
            hook(dismissMethod).intercept(chain -> {
                log(Log.WARN, TAG, "BLOCKED: requestDismissKeyguard (Preventing PIN screen)");
                return null;
            });
        } catch (Throwable ignored) {}

        // 2. Activity Visibility Spoofing
        try {
            hook(Activity.class.getDeclaredMethod("hasWindowFocus")).intercept(chain -> {
                if (isCameraActivity((Activity) chain.getThisObject())) return true;
                return (Boolean) chain.proceed();
            });
            hook(Activity.class.getDeclaredMethod("isResumed")).intercept(chain -> {
                if (isCameraActivity((Activity) chain.getThisObject())) return true;
                return (Boolean) chain.proceed();
            });
        } catch (Throwable ignored) {}

        // 3. Intent の動的書き換え
        try {
            hook(Activity.class.getDeclaredMethod("getIntent")).intercept(chain -> {
                Intent intent = (Intent) chain.proceed();
                if (isCameraActivity((Activity) chain.getThisObject()) && intent != null) {
                    if (intent.getBooleanExtra("com.miui.camera.extra.START_BY_KEYGUARD", false)) {
                        intent.putExtra("is_secure_camera", true);
                        intent.putExtra("ShowCameraWhenLocked", true);
                    }
                }
                return intent;
            });
        } catch (Throwable ignored) {}

        // 4. SurfaceView/View 非表示化を阻止
        try {
            hook(View.class.getMethod("setVisibility", int.class)).intercept(chain -> {
                View v = (View) chain.getThisObject();
                if (isCameraContext(v.getContext()) && !isDecorView(v)) {
                    List<Object> args = chain.getArgs();
                    int vis = (int) args.get(0);
                    if (vis != View.VISIBLE) args.set(0, View.VISIBLE);
                }
                return chain.proceed();
            });
        } catch (Throwable ignored) {}

        // 5. Secure Gallery Redirect
        try {
            Method startAct = Activity.class.getDeclaredMethod("startActivity", Intent.class);
            hook(startAct).intercept(chain -> {
                handleGalleryRedirect((Context) chain.getThisObject(), (Intent) chain.getArgs().get(0));
                return chain.proceed();
            });

            Method startRes = Activity.class.getDeclaredMethod("startActivityForResult", Intent.class, int.class);
            hook(startRes).intercept(chain -> {
                handleGalleryRedirect((Context) chain.getThisObject(), (Intent) chain.getArgs().get(0));
                return chain.proceed();
            });

            Method startCtx = ContextWrapper.class.getDeclaredMethod("startActivity", Intent.class);
            hook(startCtx).intercept(chain -> {
                handleGalleryRedirect((Context) chain.getThisObject(), (Intent) chain.getArgs().get(0));
                return chain.proceed();
            });
        } catch (Throwable t) {
            log(Log.ERROR, TAG, "Failed to hook gallery redirect", t);
        }

        // 6. ライフサイクルフック（+ 自動終了機能の実装）
        String[] criticalMethods = {"attachBaseContext", "onCreate", "onStart", "onResume", "onWindowFocusChanged", "onDestroy"};

        for (String mname : criticalMethods) {
            try {
                Method m;
                if ("attachBaseContext".equals(mname)) {
                    m = ContextWrapper.class.getDeclaredMethod("attachBaseContext", Context.class);
                } else if ("onCreate".equals(mname)) {
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
                    if (thisObj instanceof Activity) {
                        Activity act = (Activity) thisObj;
                        boolean isTarget = isCameraActivity(act);

                        if (isTarget) {
                            // onDestroy でセッションをクリーンアップ
                            if ("onDestroy".equals(methodName)) {
                                if (SessionManager.isActive) {
                                    SessionManager.end();
                                }
                                return chain.proceed();
                            }

                            if ("onWindowFocusChanged".equals(methodName)) {
                                boolean hasFocus = (boolean) ((List<?>) chain.getArgs()).get(0);
                                if (!hasFocus) return chain.proceed();
                            }

                            if ("onCreate".equals(methodName)) {
                                Object res = chain.proceed();

                                Intent intent = act.getIntent();
                                // フラグで判定（GCam/MIUI 共通）
                                boolean isLockscreenLaunch = intent != null && intent.getBooleanExtra("com.miui.camera.extra.START_BY_KEYGUARD", false);

                                if (isLockscreenLaunch) {
                                    // 【修正】ここ（カメラプロセス内）で Session を開始
                                    SessionManager.start(); 
                                    log(Log.INFO, TAG, "Secure Lockscreen launch detected. Session Started.");
                                    
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
                                            act.registerReceiver(screenOffReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
                                        } else {
                                            act.registerReceiver(screenOffReceiver, filter);
                                        }
                                    } catch (Exception e) {
                                        log(Log.WARN, TAG, "Failed to register receiver: " + e.getMessage());
                                    }
                                }

                                applyWindowAndBufferFixes(act);
                                return res;
                            }
                            applyWindowAndBufferFixes(act);
                        }
                    }
                    return chain.proceed();
                });
            } catch (Throwable ignored) {}
        }

        // 7. プレビュー画像の絞り込み（写真保存のトラッキング）
        try {
            hook(ContentResolver.class.getDeclaredMethod("insert", Uri.class, ContentValues.class))
                .intercept(chain -> {
                    if (SessionManager.isActive) {
                        Uri uri = (Uri) chain.getArgs().get(0);
                        Uri returnedUri = (Uri) chain.getArgs().get(1);
                        if (returnedUri != null) {
                            SessionManager.add(returnedUri);
                        }
                        return returnedUri;
                    }
                    return chain.proceed();
                });
        } catch (Throwable ignored) {}

        // --- Added try-catch block for the update hook ---
        try {
            hook(ContentResolver.class.getDeclaredMethod("update", Uri.class, ContentValues.class, String.class, String[].class))
                .intercept(chain -> {
                    if (SessionManager.isActive) {
                        Uri uri = (Uri) chain.getArgs().get(0);
                        ContentValues values = (ContentValues) chain.getArgs().get(1);

                        if (uri != null && values != null) {
                            boolean isFinished = false;
                            if (Build.VERSION.SDK_INT >= 29 && values.containsKey(MediaStore.MediaColumns.IS_PENDING)) {
                                isFinished = (Integer) values.get(MediaStore.MediaColumns.IS_PENDING) == 0;
                            } else if (values.containsKey(MediaStore.Images.Media.DATA)) {
                                isFinished = true;
                            }

                            if (isFinished) {
                                SessionManager.add(uri);
                            }
                        }
                    }
                    return chain.proceed();
                });
        } catch (Throwable ignored) {}

        // 8. その他システムフック群
        try {
            Class<?> callbackClass = Class.forName("android.hardware.camera2.CameraManager$AvailabilityCallback", true, param.getClassLoader());
            hook(callbackClass.getDeclaredMethod("onCameraUnavailable", String.class)).intercept(chain -> {
                log(Log.INFO, TAG, "Blocked onCameraUnavailable for ID: " + ((List<?>) chain.getArgs()).get(0));
                return null;
            });
        } catch (Throwable ignored) {}

        try {
            Class<?> biometricClass = Class.forName("android.hardware.biometrics.BiometricManager", true, param.getClassLoader());
            hook(biometricClass.getDeclaredMethod("canAuthenticate", int.class)).intercept(chain -> 0);
        } catch (Throwable ignored) {}
    }

    // --- ヘルパーメソッド群 ---

    private boolean isCameraPackage(String pkg) {
        if (pkg == null) return false;
        return pkg.equals("com.android.camera") || 
               pkg.contains("GoogleCamera") || 
               pkg.equals("org.codeaurora.snapcam") || 
               pkg.contains("camera");
    }

    private boolean isCameraActivity(Activity act) {
        try { 
            return act != null && isCameraPackage(act.getPackageName()); 
        }
        catch (Exception e) { 
            try { return act.getClass().getName().contains("camera"); } 
            catch (Exception e2) { return false; } 
        }
    }

    private boolean isCameraContext(Context ctx) {
        if (ctx == null) return false;
        try { return isCameraPackage(ctx.getPackageName()); } 
        catch (Exception e) { return ctx.getClass().getName().contains("camera"); }
    }

    private boolean isDecorView(View v) {
        if (v == null) return false;
        return v.getClass().getName().endsWith("DecorView");
    }

    private void handleGalleryRedirect(Context ctx, Intent intent) {
        if (ctx == null || intent == null || intent.getAction() == null) return;
        if (!SessionManager.isActive) return;
    
        try {
            if (!isCameraPackage(ctx.getPackageName())) return;
        } catch (Exception e) { return; }
    
        String action = intent.getAction();
        // GCamのレビューアクションを確実に捉える
        boolean isGallery = Intent.ACTION_VIEW.equals(action) ||
                            Intent.ACTION_PICK.equals(action) ||
                            action.contains("REVIEW") ||
                            action.contains("STILL_IMAGE_CAMERA");
    
        if (isGallery) {
            Log.i(TAG, "Redirecting to SecureViewer: Force hijacking intent");
    
            ArrayList<Uri> uriList = new ArrayList<>(SessionManager.SESSION_URIS);
            if (uriList.isEmpty() && intent.getData() != null) {
                uriList.add(intent.getData());
            }
    
            // 既存のインテントを完全に再利用・改変して、システムを騙す
            intent.setComponent(new ComponentName(
                    "com.github.droserasprout.lockscreencamera",
                    "com.github.droserasprout.lockscreencamera.SecureViewerActivity"
            ));
            
            // Googleフォトがリストに並ぶのを防ぐ
            intent.setPackage(null); 
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH) {
                intent.setSelector(null);
            }
    
            // 複数枚のプレビューに対応させるためのClipData
            if (!uriList.isEmpty()) {
                ClipData clipData = ClipData.newRawUri("Photos", uriList.get(0));
                for (int i = 1; i < uriList.size(); i++) {
                    clipData.addItem(new ClipData.Item(uriList.get(i)));
                }
                intent.setClipData(clipData);
            }
    
            intent.putParcelableArrayListExtra("session_photos_list", uriList);
            
            // ロック解除要求を回避するためのフラグ群
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION |
                            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | // 永続権限
                            Intent.FLAG_ACTIVITY_NEW_TASK |
                            Intent.FLAG_ACTIVITY_CLEAR_TOP |
                            Intent.FLAG_ACTIVITY_NO_ANIMATION);
    
            // ロック画面上に表示するための隠しフラグ (SHOW_WHEN_LOCKED)
            intent.addFlags(0x00080000 | 0x00400000 | 0x00200000); 
    
            // ここで startActivity を呼ぶのではなく、この intent 自体を書き換えた状態で
            // 元のメソッド（proceed）に戻すことで、カメラアプリに「自分の意志で」起動させる
            Log.d(TAG, "Intent modification complete. Proceeding with hijacked intent.");
        }
    }

    // システムサーバー側の起動ロジックを最適化
        @Override
    public void onSystemServerStarting(@NonNull SystemServerStartingParam param) {
        try {
            Class<?> gestureClass = Class.forName("com.android.server.GestureLauncherService", true, param.getClassLoader());
            Method handleCameraGesture = gestureClass.getDeclaredMethod("handleCameraGesture", boolean.class, int.class);

            hook(handleCameraGesture).intercept(chain -> {
                try {
                    Method getContextMethod = chain.getThisObject().getClass().getMethod("getContext");
                    Context context = (Context) getContextMethod.invoke(chain.getThisObject());

                    KeyguardManager km = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
                    boolean isLocked = (km != null && km.isKeyguardLocked());

                    // 1. デフォルトカメラを解決
                    PackageManager pm = context.getPackageManager();
                    Intent resolveIntent = new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE);
                    ResolveInfo info = pm.resolveActivity(resolveIntent, PackageManager.MATCH_DEFAULT_ONLY);
                    
                    // SECURE が解決できない場合は標準アクションにフォールバック
                    if (info == null || info.activityInfo.name.contains("Resolver")) {
                        resolveIntent = new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA);
                        info = pm.resolveActivity(resolveIntent, PackageManager.MATCH_DEFAULT_ONLY);
                    }

                    String targetPkg = "com.android.camera";
                    String targetCls = "com.android.camera.Camera";

                    // 2. 解決結果が有効かつ Resolver でない場合はそちらを優先
                    if (info != null && !info.activityInfo.name.contains("Resolver")) {
                        targetPkg = info.activityInfo.packageName;
                        targetCls = info.activityInfo.name;
                    }

                    // 3. Intent 生成
                    Intent intent = new Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE);

                    // 4. MIUI カメラの場合は Resolver を完全にスキップするため Component を強制指定
                    //    GCam などのサードパーティ製がデフォルトに設定されている場合も同様に明示起動
                    if (targetPkg != null && targetCls != null) {
                        intent.setComponent(new ComponentName(targetPkg, targetCls));
                    }

                    int flags = 0x00040000 | Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP;

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
                    intent.putExtra("android.intent.extra.CAMERA_OPEN_ONLY", true);
                    intent.putExtra("com.android.systemui.camera_launch_source", "lockscreen_affordance");

                    ActivityOptions options = ActivityOptions.makeBasic();
                    if (Build.VERSION.SDK_INT >= 34) {
                        options.setPendingIntentBackgroundActivityStartMode(2);
                    }

                    // SessionManager.start() はカメラアプリ側の onCreate で実行されるためここでは呼ばない
                    context.startActivity(intent, options.toBundle());
                    return true;
                } catch (Throwable t) {
                    log(Log.ERROR, TAG, "Failed to launch", t);
                }
                return chain.proceed();
            });
        } catch (Throwable t) {
            log(Log.WARN, TAG, "System hook skipped", t);
        }
    }
    private void applyWindowAndBufferFixes(Activity activity) {
        try {
            activity.setShowWhenLocked(true);
            activity.setTurnScreenOn(true);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                activity.setInheritShowWhenLocked(true);
            }

            Window window = activity.getWindow();
            if (window != null) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE);
                window.setFormat(PixelFormat.TRANSLUCENT);

                WindowManager.LayoutParams lp = window.getAttributes();
                lp.flags |= WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                          | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                          | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                          | WindowManager.LayoutParams.FLAG_FULLSCREEN
                          | WindowManager.LayoutParams.FLAG_ALLOW_LOCK_WHILE_SCREEN_ON;
                
                // 描画レイヤーを最前面に固定し、背面に隠れるのを防ぐ
                lp.flags |= WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN 
                          | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;

                lp.flags &= ~WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD;

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
                }
                window.setAttributes(lp);
                window.addFlags(lp.flags);

                window.setFlags(
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                );

                View decorView = window.getDecorView();
                if (decorView != null) {
                    decorView.setAlpha(1.0f);
                    decorView.setVisibility(View.VISIBLE);
                    decorView.requestFocus();
                }
            }

            for (String fieldName : TARGET_BOOLEAN_FIELDS) {
                setFieldFast(activity, fieldName, true);
            }
            setFieldFast(activity, "mIsNormalIntent", false);
            setFieldFast(activity, "mShowEnteringAnimation", false);
            setFieldFast(activity, "mKeyguardStatus", 1);
            setFieldFast(activity, "mIsSecureCameraId", 0);

        } catch (Throwable t) {
            log(Log.DEBUG, TAG, "UI Fixes failed: " + t.toString());
        }
    }

    private void setFieldFast(Object obj, String fieldName, Object value) {
        try {
            Class<?> current = obj.getClass();
            String cacheKey = current.getName() + ":" + fieldName;
            Field f = fieldCache.get(cacheKey);

            if (f == null && !fieldCache.containsKey(cacheKey)) {
                while (current != null && !current.getName().equals("android.app.Activity")) {
                    try {
                        f = current.getDeclaredField(fieldName);
                        f.setAccessible(true);
                        break;
                    } catch (NoSuchFieldException e) {
                        current = current.getSuperclass();
                    }
                }
                fieldCache.put(cacheKey, f);
            }

            if (f != null) f.set(obj, value);
        } catch (Throwable ignored) {}
    }
                                     }
