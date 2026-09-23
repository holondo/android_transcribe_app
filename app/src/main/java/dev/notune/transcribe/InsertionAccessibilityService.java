package dev.notune.transcribe;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.Toast;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Hosts the Flow bubble: watches field focus and the keyboard window, owns the
 * overlay views, records through {@link BubbleRecorder} and inserts the text.
 * All decisions live in {@link BubbleController}.
 */
public class InsertionAccessibilityService extends AccessibilityService implements BubbleHost {
    private static final String TAG = "FlowBubble";
    /** Coalesces bursts of accessibility events into one snapshot. */
    private static final long SNAPSHOT_DELAY_MS = 60;
    private static final float SHAKE_G = 2.5f;
    private static final long SHAKE_WINDOW_MS = 800;
    private static final long SHAKE_MIN_GAP_MS = 120;

    private static volatile InsertionAccessibilityService sInstance;

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final Runnable mSnapshotTask = this::takeSnapshot;
    private final BubbleRecorder mRecorder = new BubbleRecorder();

    private BubbleController mController;
    private BubbleHost.Settings mSettings;
    private WindowManager mWindowManager;

    private static final int BLUR_DP = 24;
    private static final long SHRINK_ANIM_MS = 150;

    private BubbleStyler mStyler;
    private BubbleWindow mBubbleWindow;
    private View mBubbleView;
    private View mBubbleDisc;
    private ImageView mBubbleIcon;
    private ProgressBar mBubbleProgress;
    private boolean mModelLoading;
    private boolean mChipArmed, mTargetArmed;
    private boolean mSamsungBlurOn;
    private java.util.function.Consumer<Boolean> mBlurListener;
    private View mChipView;
    private WindowManager.LayoutParams mChipParams;
    private View mTargetView;
    private WindowManager.LayoutParams mTargetParams;

    private SensorManager mSensorManager;
    private SensorEventListener mShakeListener;
    private long mLastPeakAt;

    // --- Static access for MainActivity (same process) -----------------------------

    public static boolean isRunning() {
        return sInstance != null;
    }

    /** Tells a running service to re-read the bubble settings. */
    public static void notifyPrefsChanged() {
        InsertionAccessibilityService svc = sInstance;
        if (svc != null) svc.mMain.post(svc::onPrefsChanged);
    }

    public static void openSettings(Context ctx) {
        ctx.startActivity(new Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    // --- Service lifecycle -----------------------------------------------------------

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();
        sInstance = this;
        mWindowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        mSensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        mSettings = BubblePrefs.load(this);

        Set<String> excluded = new HashSet<>();
        excluded.add(getPackageName());
        excluded.add("com.android.systemui");
        excluded.addAll(Arrays.asList(getResources().getStringArray(R.array.bank_packages)));

        mStyler = new BubbleStyler(this);
        if (Build.VERSION.SDK_INT >= 31) {
            // Battery saver and developer options can turn blur off at run time.
            mBlurListener = enabled -> {
                Log.i(TAG, "cross-window blur " + (enabled ? "on" : "off"));
                restyle();
            };
            mWindowManager.addCrossWindowBlurEnabledListener(getMainExecutor(), mBlurListener);
        }

        mRecorder.initNative(this);
        mController = new BubbleController(this, excluded);
        Log.i(TAG, "service connected");
        scheduleSnapshot();
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // Night mode or wallpaper colours may have changed.
        if (mStyler != null) mStyler.invalidate();
        restyle();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (mController == null || event == null) return;
        switch (event.getEventType()) {
            case AccessibilityEvent.TYPE_VIEW_FOCUSED:
            case AccessibilityEvent.TYPE_WINDOWS_CHANGED:
            case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED:
                scheduleSnapshot();
                break;
            default:
                break;
        }
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (mController != null) mController.onTrimMemory(level);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        shutdown();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        shutdown();
        super.onDestroy();
    }

    private void shutdown() {
        if (sInstance == this) sInstance = null;
        mMain.removeCallbacks(mSnapshotTask);
        if (mController != null) {
            mController.onServiceStopping();
            mController = null;
            mRecorder.cleanupNative();
        }
        if (Build.VERSION.SDK_INT >= 31 && mBlurListener != null && mWindowManager != null) {
            mWindowManager.removeCrossWindowBlurEnabledListener(mBlurListener);
            mBlurListener = null;
        }
        hideBubble();
        removeView(mChipView);
        mChipView = null;
        removeView(mTargetView);
        mTargetView = null;
    }

    private void onPrefsChanged() {
        mSettings = BubblePrefs.load(this);
        if (mController != null) mController.onPrefsChanged();
        applyGeometry();
        restyle();
    }

    // --- Focus snapshot --------------------------------------------------------------

    private void scheduleSnapshot() {
        mMain.removeCallbacks(mSnapshotTask);
        mMain.postDelayed(mSnapshotTask, SNAPSHOT_DELAY_MS);
    }

    private void takeSnapshot() {
        if (mController == null) return;
        boolean keyboardVisible = false;
        int keyboardTop = 0;
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            for (AccessibilityWindowInfo w : windows) {
                if (w.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                    Rect r = new Rect();
                    w.getBoundsInScreen(r);
                    Log.d(TAG, "ime window " + r.toShortString());
                    // A docked on-screen keyboard spans most of the width. With
                    // a physical keyboard, Gboard shows only a narrow floating
                    // toolbar, which does not count as an on-screen keyboard.
                    if (r.height() > 0 && r.width() >= screenWidth() / 2) {
                        keyboardVisible = true;
                        keyboardTop = r.top;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "getWindows failed", e);
        }

        FieldInfo field = null;
        String pkg = null;
        AccessibilityNodeInfo node = null;
        try {
            node = findFocusedField();
            if (node != null) {
                pkg = node.getPackageName() != null ? node.getPackageName().toString() : null;
                field = new FieldInfo(
                        node.isEditable(),
                        node.isVisibleToUser(),
                        node.isPassword(),
                        node.getInputType(),
                        node.getClassName() != null ? node.getClassName().toString() : null,
                        node.getViewIdResourceName());
            }
        } catch (Exception e) {
            Log.w(TAG, "findFocus failed", e);
        } finally {
            if (node != null) node.recycle();
        }

        KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        boolean locked = km != null && km.isKeyguardLocked();
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "snapshot pkg=" + pkg + " kb=" + keyboardVisible + "@" + keyboardTop
                    + " locked=" + locked + (field == null ? " field=null"
                    : " editable=" + field.editable + " type=0x" + Integer.toHexString(field.inputType)
                    + " cls=" + field.className + " id=" + field.viewId));
        }
        mController.onFocusSnapshot(field, pkg, keyboardVisible, keyboardTop, locked);
    }

    /** Upper bound on nodes visited when looking inside a focused host view. */
    private static final int MAX_FOCUS_SEARCH_NODES = 400;

    /**
     * The input-focused node. Jetpack Compose (and some custom views) report
     * the host View as input focus; the real editable field is a focused
     * descendant, so look for it when the focus node itself is not editable.
     */
    private AccessibilityNodeInfo findFocusedField() {
        AccessibilityNodeInfo focus = findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        if (focus == null || focus.isEditable()) return focus;
        java.util.ArrayDeque<AccessibilityNodeInfo> queue = new java.util.ArrayDeque<>();
        queue.add(focus);
        int visited = 0;
        AccessibilityNodeInfo found = null;
        while (!queue.isEmpty() && visited < MAX_FOCUS_SEARCH_NODES) {
            AccessibilityNodeInfo n = queue.poll();
            visited++;
            if (n != focus && n.isEditable() && n.isFocused()) {
                found = n;
                break;
            }
            for (int i = 0; i < n.getChildCount(); i++) {
                AccessibilityNodeInfo child = n.getChild(i);
                if (child != null) queue.add(child);
            }
        }
        return found != null ? found : focus;
    }

    // --- Native callbacks (any thread) -----------------------------------------------

    @SuppressWarnings("unused")
    public void onStatusUpdate(String status) {
        if (status == null) return;
        Log.i(TAG, "status: " + status);
        if (status.startsWith("Error")) {
            mMain.post(() -> {
                if (mController != null) mController.onError(status);
            });
        }
    }

    @SuppressWarnings("unused")
    public void onAudioLevel(float level) {
        mMain.post(() -> {
            if (mController != null) mController.onAudioLevel(level);
        });
    }

    @SuppressWarnings("unused")
    public void onTextTranscribed(String text) {
        mMain.post(() -> {
            if (mController != null) mController.onText(text);
        });
    }

    // --- BubbleHost: overlay ---------------------------------------------------------

    private WindowManager.LayoutParams overlayParams(int w, int h, boolean touchable) {
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN;
        if (!touchable) flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                w, h, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                flags, PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START;
        return p;
    }

    // Full-size bubble geometry from the controller; the window may be
    // smaller or moved while shrunk.
    private int mBubbleX, mBubbleY, mBubbleSize;
    private BubbleController.Mode mMode = BubbleController.Mode.IDLE;
    private float mLevel;
    private boolean mShrunk;

    private BubbleAppearance appearance() {
        return BubbleAppearance.resolve(settings(), BubblePrefs.isNight(this), blurSupported(),
                mMode, mShrunk);
    }

    private boolean blurSupported() {
        return BlurSupport.any(mWindowManager);
    }

    @Override
    public void showBubble(int x, int y, int sizePx) {
        mBubbleX = x;
        mBubbleY = y;
        mBubbleSize = sizePx;
        if (mBubbleView == null) {
            mBubbleView = LayoutInflater.from(this).inflate(R.layout.bubble_overlay, null);
            mBubbleDisc = mBubbleView.findViewById(R.id.bubble_disc);
            mBubbleIcon = mBubbleView.findViewById(R.id.bubble_icon);
            mBubbleProgress = mBubbleView.findViewById(R.id.bubble_progress);
            mBubbleView.setOnTouchListener(this::onBubbleTouch);
            mShrunk = false;
            try {
                mBubbleWindow = new BubbleWindow(this, mBubbleView);
                int[] b = windowBounds(appearance());
                mBubbleWindow.show(b[0], b[1], b[2], b[2]);
            } catch (Exception e) {
                Log.e(TAG, "bubble window failed", e);
                mBubbleView = null;
                mBubbleWindow = null;
                return;
            }
        }
        applyGeometry();
        restyle();
    }

    /** Window x, y and size for an appearance, around the full-size position. */
    private int[] windowBounds(BubbleAppearance a) {
        if (a.shrink == BubbleAppearance.Shrink.NONE) {
            return new int[]{mBubbleX, mBubbleY, mBubbleSize};
        }
        int win = dpToPx(a.currentWindowDp());
        int cx = mBubbleX + mBubbleSize / 2;
        int cy = mBubbleY + mBubbleSize / 2;
        int x = cx - win / 2;
        if (a.shrink == BubbleAppearance.Shrink.DOT) {
            // The dot hugs the screen edge.
            x = settings().side == BubbleController.SIDE_LEFT ? 0 : screenWidth() - win;
        }
        return new int[]{x, cy - win / 2, win};
    }

    private void applyGeometry() {
        if (mBubbleWindow == null) return;
        BubbleAppearance a = appearance();
        int[] b = windowBounds(a);
        mBubbleWindow.setBounds(b[0], b[1], b[2], b[2]);
        int disc = dpToPx(a.currentDiscDp());
        setViewSize(mBubbleDisc, disc);
        setViewSize(mBubbleIcon, Math.max(1, disc / 2));
        setViewSize(mBubbleProgress, Math.max(1, disc / 2));
    }

    private boolean onBubbleTouch(View v, MotionEvent e) {
        if (mController == null) return false;
        int x = (int) e.getRawX();
        int y = (int) e.getRawY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mController.onTouchDown(x, y);
                return true;
            case MotionEvent.ACTION_MOVE:
                mController.onTouchMove(x, y);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mController.onTouchUp(x, y);
                return true;
            default:
                return false;
        }
    }

    @Override
    public void moveBubble(int x, int y) {
        mBubbleX = x;
        mBubbleY = y;
        applyGeometry();
    }

    @Override
    public void hideBubble() {
        if (mBubbleWindow != null) mBubbleWindow.dismiss();
        mBubbleWindow = null;
        mBubbleView = null;
        mBubbleDisc = null;
        mBubbleIcon = null;
        mBubbleProgress = null;
        mShrunk = false;
        mSamsungBlurOn = false;
    }

    @Override
    public void renderMode(BubbleController.Mode mode, float audioLevel, boolean modelLoading) {
        mMode = mode;
        mLevel = audioLevel;
        mModelLoading = modelLoading;
        restyle();
    }

    /** Re-applies the style: mode, theme, blur support or settings changed. */
    private void restyle() {
        if (mBubbleView == null) return;
        BubbleAppearance a = appearance();
        boolean night = BubblePrefs.isNight(this);
        switch (mMode) {
            case RECORDING_TAP:
            case RECORDING_HOLD:
                mBubbleIcon.setVisibility(View.VISIBLE);
                mBubbleIcon.setImageResource(R.drawable.ic_stop);
                float scale = 1f + 0.35f * Math.max(0f, Math.min(1f, mLevel));
                mBubbleIcon.setScaleX(scale);
                mBubbleIcon.setScaleY(scale);
                mBubbleProgress.setVisibility(View.GONE);
                mBubbleView.setContentDescription(getString(R.string.bubble_recording));
                break;
            case PROCESSING:
                mBubbleIcon.setVisibility(View.GONE);
                mBubbleProgress.setVisibility(View.VISIBLE);
                mBubbleView.setContentDescription(getString(
                        mModelLoading ? R.string.bubble_loading : R.string.bubble_processing));
                break;
            default:
                mBubbleIcon.setVisibility(View.VISIBLE);
                mBubbleIcon.setImageResource(R.drawable.ic_mic);
                mBubbleIcon.setScaleX(1f);
                mBubbleIcon.setScaleY(1f);
                mBubbleProgress.setVisibility(View.GONE);
                mBubbleView.setContentDescription(getString(R.string.bubble_idle));
                break;
        }
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "restyle style=" + a.style + " blur=" + a.blur + " night=" + night
                    + " shrink=" + a.shrink + " alpha=" + a.alpha);
        }
        // Real blur: AOSP window blur where the device has it, else Samsung's
        // view blur (Galaxy phones turn AOSP cross-window blur off).
        boolean aospBlur = a.blur && BlurSupport.crossWindow(mWindowManager);
        boolean samsungBlur = a.blur && !aospBlur && BlurSupport.samsung();
        int fill = mStyler.applyBubble(mBubbleDisc, mBubbleIcon, mBubbleProgress, a, mMode, night,
                samsungBlur);
        if (mBubbleWindow != null) mBubbleWindow.setBlurRadius(aospBlur ? dpToPx(BLUR_DP) : 0);
        if (samsungBlur) {
            float corner = dpToPx(a.currentDiscDp()) / 2f;
            if (BlurSupport.applySamsung(mBubbleDisc, dpToPx(BLUR_DP), fill, corner)) {
                mSamsungBlurOn = true;
            } else {
                // Refused at run time: paint the frosted fallback instead.
                mStyler.applyBubble(mBubbleDisc, mBubbleIcon, mBubbleProgress, a, mMode, night, false);
            }
        } else if (mSamsungBlurOn) {
            BlurSupport.clearSamsung(mBubbleDisc);
            mSamsungBlurOn = false;
        }
        if (mChipView != null) {
            mStyler.applyChip(mChipView, mChipView.findViewById(R.id.chip_icon), a, night, mChipArmed);
        }
        if (mTargetView != null) {
            mStyler.applyTarget(mTargetView, mTargetView.findViewById(R.id.target_icon), a, night,
                    mTargetArmed);
        }
    }

    @Override
    public void setShrunk(boolean shrunk) {
        if (mBubbleView == null || shrunk == mShrunk) {
            mShrunk = shrunk;
            return;
        }
        int before = mBubbleDisc.getWidth();
        mShrunk = shrunk;
        applyGeometry();
        restyle();
        // Animate the disc from its old size to the new one.
        int after = dpToPx(appearance().currentDiscDp());
        if (before > 0 && after > 0) {
            float from = before / (float) after;
            mBubbleDisc.setScaleX(from);
            mBubbleDisc.setScaleY(from);
            mBubbleDisc.animate().scaleX(1f).scaleY(1f).setDuration(SHRINK_ANIM_MS).start();
        }
    }

    @Override
    public void showCancelChip(boolean visible, int x, int y, int sizePx, boolean armed) {
        mChipArmed = armed;
        if (!visible) {
            removeView(mChipView);
            mChipView = null;
            return;
        }
        if (mChipView == null) {
            mChipView = LayoutInflater.from(this).inflate(R.layout.bubble_cancel_chip, null);
            mChipView.setOnClickListener(v -> {
                if (mController != null) mController.onCancelChipTap();
            });
            mChipParams = overlayParams(sizePx, sizePx, true);
            mChipParams.x = x;
            mChipParams.y = y;
            try {
                mWindowManager.addView(mChipView, mChipParams);
            } catch (Exception e) {
                Log.e(TAG, "addView (chip) failed", e);
                mChipView = null;
                return;
            }
        }
        mChipParams.x = x;
        mChipParams.y = y;
        mStyler.applyChip(mChipView, mChipView.findViewById(R.id.chip_icon), appearance(),
                BubblePrefs.isNight(this), armed);
        mWindowManager.updateViewLayout(mChipView, mChipParams);
    }

    @Override
    public void showSnoozeTarget(boolean visible, int x, int y, int sizePx, boolean armed) {
        mTargetArmed = armed;
        if (!visible) {
            removeView(mTargetView);
            mTargetView = null;
            return;
        }
        if (mTargetView == null) {
            mTargetView = LayoutInflater.from(this).inflate(R.layout.bubble_snooze_target, null);
            mTargetParams = overlayParams(sizePx, sizePx, false);
            try {
                mWindowManager.addView(mTargetView, mTargetParams);
            } catch (Exception e) {
                Log.e(TAG, "addView (target) failed", e);
                mTargetView = null;
                return;
            }
        }
        mTargetParams.x = x;
        mTargetParams.y = y;
        mStyler.applyTarget(mTargetView, mTargetView.findViewById(R.id.target_icon), appearance(),
                BubblePrefs.isNight(this), armed);
        mWindowManager.updateViewLayout(mTargetView, mTargetParams);
    }

    private void removeView(View v) {
        if (v == null || mWindowManager == null) return;
        try {
            mWindowManager.removeView(v);
        } catch (Exception ignored) {
        }
    }

    private static void setViewSize(View v, int px) {
        if (v == null) return;
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        lp.width = px;
        lp.height = px;
        v.setLayoutParams(lp);
    }

    // --- BubbleHost: recording and engine ---------------------------------------------

    @Override
    public void startRecording() {
        mRecorder.startRecordingNative();
    }

    @Override
    public void stopRecording() {
        mRecorder.stopRecordingNative();
    }

    @Override
    public void cancelRecording() {
        mRecorder.cancelRecordingNative();
    }

    @Override
    public boolean isEngineLoaded() {
        return mRecorder.isEngineLoadedNative();
    }

    @Override
    public void loadEngine() {
        final BubbleRecorder.LoadSink sink = new BubbleRecorder.LoadSink(this);
        new Thread(() -> {
            long t0 = SystemClock.elapsedRealtime();
            Log.i(TAG, "load start");
            boolean ok = mRecorder.loadEngineNative(sink);
            Log.i(TAG, "load end ok=" + ok + " in " + (SystemClock.elapsedRealtime() - t0) + " ms");
            mMain.post(() -> {
                if (mController != null) mController.onEngineLoaded(ok);
            });
        }, "bubble-load").start();
    }

    @Override
    public void unloadEngineIfIdle() {
        new Thread(() -> {
            boolean unloaded = mRecorder.unloadNative();
            Log.i(TAG, "unload " + (unloaded ? "done" : "refused"));
            mMain.post(() -> {
                if (mController != null) mController.onUnloadResult(unloaded);
            });
        }, "bubble-unload").start();
    }

    // --- BubbleHost: output ------------------------------------------------------------

    @Override
    public boolean insertText(String text) {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("Transcription", text));

        AccessibilityNodeInfo focused = null;
        try {
            focused = findFocusedField();
            if (focused == null || !isSafeEditable(focused)) return false;
            // ACTION_PASTE inserts the clipboard at the app-managed cursor
            // without reading or reconstructing the existing field content.
            return focused.performAction(AccessibilityNodeInfo.ACTION_PASTE);
        } catch (Exception e) {
            Log.w(TAG, "Insertion failed", e);
            return false;
        } finally {
            if (focused != null) focused.recycle();
        }
    }

    private static boolean isSafeEditable(AccessibilityNodeInfo node) {
        if (!node.isEditable() || !node.isVisibleToUser()) return false;
        CharSequence cls = node.getClassName();
        FieldInfo info = new FieldInfo(true, true, node.isPassword(), node.getInputType(),
                cls != null ? cls.toString() : null, null);
        return !info.isPasswordField();
    }

    @Override
    public void saveHistory(String text) {
        TranscriptionHistory.get(this).insert(text, TranscriptionHistory.SOURCE_BUBBLE);
    }

    @Override
    public void vibrate(Haptic kind) {
        Vibrator vib = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (vib == null || !vib.hasVibrator()) return;
        VibrationEffect effect;
        switch (kind) {
            case CANCEL:
                effect = VibrationEffect.createWaveform(new long[]{0, 25, 70, 25}, -1);
                break;
            case SNOOZE_ARMED:
                effect = Build.VERSION.SDK_INT >= 29
                        ? VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
                        : VibrationEffect.createOneShot(10, VibrationEffect.DEFAULT_AMPLITUDE);
                break;
            default:
                effect = VibrationEffect.createOneShot(20, VibrationEffect.DEFAULT_AMPLITUDE);
                break;
        }
        vib.vibrate(effect);
    }

    @Override
    public void toast(Message message) {
        int res;
        switch (message) {
            case INSERTED: res = R.string.bubble_inserted; break;
            case LIMIT_WARNING: res = R.string.bubble_limit_warning; break;
            case LOAD_FAILED: res = R.string.bubble_load_failed; break;
            case SNOOZED: res = R.string.bubble_snoozed; break;
            default: res = R.string.bubble_copied; break;
        }
        Toast.makeText(this, res, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void toastText(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void setShakeListening(boolean on) {
        if (mSensorManager == null) return;
        if (on && mShakeListener == null) {
            Sensor accel = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            if (accel == null) return;
            mShakeListener = new SensorEventListener() {
                @Override
                public void onSensorChanged(SensorEvent event) {
                    onAccel(event.values[0], event.values[1], event.values[2]);
                }

                @Override
                public void onAccuracyChanged(Sensor sensor, int accuracy) {
                }
            };
            mSensorManager.registerListener(mShakeListener, accel,
                    SensorManager.SENSOR_DELAY_UI, mMain);
            Log.i(TAG, "shake listening on");
        } else if (!on && mShakeListener != null) {
            mSensorManager.unregisterListener(mShakeListener);
            mShakeListener = null;
            Log.i(TAG, "shake listening off");
        }
    }

    /** A shake is two peaks above {@link #SHAKE_G} within {@link #SHAKE_WINDOW_MS}. */
    private void onAccel(float x, float y, float z) {
        float g = (float) Math.sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH;
        if (g < SHAKE_G) return;
        long now = SystemClock.elapsedRealtime();
        long gap = now - mLastPeakAt;
        if (gap < SHAKE_MIN_GAP_MS) return;
        if (gap <= SHAKE_WINDOW_MS) {
            mLastPeakAt = 0;
            if (mController != null) mController.onShake();
        } else {
            mLastPeakAt = now;
        }
    }

    // --- BubbleHost: settings ------------------------------------------------------------

    @Override
    public Settings settings() {
        if (mSettings == null) mSettings = BubblePrefs.load(this);
        return mSettings;
    }

    @Override
    public void saveSide(int side) {
        BubblePrefs.setSide(this, side);
        mSettings = BubblePrefs.load(this);
    }

    @Override
    public void saveOffsetY(int offsetY) {
        BubblePrefs.setOffsetY(this, offsetY);
        mSettings = BubblePrefs.load(this);
    }

    @Override
    public void saveSnoozeUntil(long epochMs) {
        BubblePrefs.setSnoozeUntil(this, epochMs);
        mSettings = BubblePrefs.load(this);
    }

    // --- BubbleHost: environment ------------------------------------------------------------

    @Override
    public int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }

    @Override
    public int screenWidth() {
        return realMetrics().widthPixels;
    }

    @Override
    public int screenHeight() {
        return realMetrics().heightPixels;
    }

    @SuppressWarnings("deprecation")
    private DisplayMetrics realMetrics() {
        DisplayMetrics dm = new DisplayMetrics();
        mWindowManager.getDefaultDisplay().getRealMetrics(dm);
        return dm;
    }

    @Override
    public int statusBarHeight() {
        int id = getResources().getIdentifier("status_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : dpToPx(24);
    }

    @Override
    public int navigationBarHeight() {
        int id = getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        return id > 0 ? getResources().getDimensionPixelSize(id) : dpToPx(48);
    }

    @Override
    public long now() {
        return System.currentTimeMillis();
    }

    @Override
    public void postDelayed(Runnable r, long ms) {
        mMain.postDelayed(r, ms);
    }

    @Override
    public void cancel(Runnable r) {
        mMain.removeCallbacks(r);
    }

    @Override
    public void log(String message) {
        Log.i(TAG, message);
    }
}
