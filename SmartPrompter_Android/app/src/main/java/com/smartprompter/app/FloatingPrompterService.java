package com.smartprompter.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.text.Layout;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.view.Choreographer;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.core.app.NotificationCompat;

/**
 * 半透明悬浮窗提词服务
 * 支持拖动位置、拉伸宽高、背景透明度、点击穿透锁定、蓝牙耳机控制、以及可选 AI 跟读变色
 */
public class FloatingPrompterService extends Service {

    public static final String EXTRA_SCRIPT = "extra_script";
    public static final String ACTION_UNLOCK = "com.smartprompter.app.ACTION_UNLOCK_FLOAT";
    public static final String ACTION_CLOSE = "com.smartprompter.app.ACTION_CLOSE_FLOAT";

    private static final String PREF_NAME = "SmartPrompterPrefs";
    private static final String KEY_FLOAT_X = "key_float_x";
    private static final String KEY_FLOAT_Y = "key_float_y";
    private static final String KEY_FLOAT_W = "key_float_w";
    private static final String KEY_FLOAT_H = "key_float_h";
    private static final String KEY_FLOAT_ALPHA = "key_float_alpha";
    private static final String KEY_SPEED = "key_speed";
    private static final String KEY_ALIGN_LEFT = "key_align_left";

    private WindowManager windowManager;
    private View floatView;
    private WindowManager.LayoutParams params;

    private LinearLayout floatRootContainer;
    private LinearLayout floatTextWrapper;
    private ScrollView floatScrollContainer;
    private TextView tvFloatText;
    private TextView tvFloatSpeed;
    private Button btnFloatPlay;
    private ImageButton btnFloatAlign;
    private Button btnFloatAi;

    private String scriptText = "";
    private int currentSpeed = 35;
    private int floatFontSize = 20;
    private int floatAlpha = 55; // 10 ~ 90 (%)
    private boolean isAlignLeft = false;
    private boolean isPlaying = true;
    private boolean isAiOn = false;
    private boolean isLocked = false;

    private float currentScrollY = 0f;
    private long lastFrameTime = 0L;
    private int highlightedIndex = 0;

    private VoiceFollower voiceFollower;
    private MediaSessionCompat mediaSession;
    private long lastMediaKeyTime = 0;

    private GestureDetector tapDetector;
    private float lastTouchY = 0f;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        startForegroundNotification(false);
        initOverlayWindow();
        setupMediaSession();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_UNLOCK.equals(action)) {
                setLocked(false);
                return START_STICKY;
            } else if (ACTION_CLOSE.equals(action)) {
                stopSelf();
                return START_NOT_STICKY;
            }
            String s = intent.getStringExtra(EXTRA_SCRIPT);
            if (s != null) {
                scriptText = s;
                highlightedIndex = 0;
                currentScrollY = 0f;
                if (floatTextWrapper != null) floatTextWrapper.setTranslationY(0f);
                renderText();
            }
        }
        return START_STICKY;
    }

    private void startForegroundNotification(boolean locked) {
        String channelId = "float_prompter_channel";
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm != null) {
            NotificationChannel ch = new NotificationChannel(
                    channelId, "悬浮窗提词", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
        }

        Intent unlockIntent = new Intent(this, FloatingPrompterService.class);
        unlockIntent.setAction(ACTION_UNLOCK);
        int pFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT
                : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent unlockPi = PendingIntent.getService(this, 1, unlockIntent, pFlags);

        Intent closeIntent = new Intent(this, FloatingPrompterService.class);
        closeIntent.setAction(ACTION_CLOSE);
        PendingIntent closePi = PendingIntent.getService(this, 2, closeIntent, pFlags);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, channelId)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("悬浮提词器运行中")
                .setContentText(locked ? "已锁定穿透：点此解锁悬浮窗" : "可随时拖动或调整大小")
                .setOngoing(true)
                .setContentIntent(unlockPi)
                .addAction(0, "解锁", unlockPi)
                .addAction(0, "关闭", closePi);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1002, builder.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1002, builder.build());
        }
    }

    private void initOverlayWindow() {
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        floatView = LayoutInflater.from(this).inflate(R.layout.floating_prompter, null);

        SharedPreferences sp = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        float density = getResources().getDisplayMetrics().density;
        int defW = (int) (310 * density);
        int defH = (int) (210 * density);

        int x = sp.getInt(KEY_FLOAT_X, (int) (20 * density));
        int y = sp.getInt(KEY_FLOAT_Y, (int) (40 * density));
        int w = sp.getInt(KEY_FLOAT_W, defW);
        int h = sp.getInt(KEY_FLOAT_H, defH);
        floatAlpha = sp.getInt(KEY_FLOAT_ALPHA, 55);
        currentSpeed = sp.getInt(KEY_SPEED, 35);
        isAlignLeft = sp.getBoolean(KEY_ALIGN_LEFT, false);

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        params = new WindowManager.LayoutParams(
                w, h, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = x;
        params.y = y;

        floatRootContainer = floatView.findViewById(R.id.floatRootContainer);
        floatTextWrapper = floatView.findViewById(R.id.floatTextWrapper);
        floatScrollContainer = floatView.findViewById(R.id.floatScrollContainer);
        tvFloatText = floatView.findViewById(R.id.tvFloatText);
        tvFloatSpeed = floatView.findViewById(R.id.tvFloatSpeed);
        btnFloatPlay = floatView.findViewById(R.id.btnFloatPlay);
        btnFloatAlign = floatView.findViewById(R.id.btnFloatAlign);
        btnFloatAi = floatView.findViewById(R.id.btnFloatAi);

        tvFloatSpeed.setText(String.valueOf(currentSpeed));
        tvFloatText.setTextSize(floatFontSize);
        applyBackgroundAlpha();
        applyAlignment();

        SeekBar opacitySeek = floatView.findViewById(R.id.floatOpacitySeek);
        opacitySeek.setProgress(floatAlpha);
        opacitySeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                floatAlpha = Math.max(5, progress);
                applyBackgroundAlpha();
                saveFloatPrefs();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        floatView.findViewById(R.id.btnFloatLock).setOnClickListener(v -> setLocked(true));
        floatView.findViewById(R.id.btnFloatClose).setOnClickListener(v -> stopSelf());

        btnFloatPlay.setOnClickListener(v -> togglePlayPause());
        floatView.findViewById(R.id.btnFloatRestart).setOnClickListener(v -> {
            highlightedIndex = 0;
            currentScrollY = 0f;
            floatTextWrapper.setTranslationY(0f);
            renderText();
        });
        floatView.findViewById(R.id.btnFloatSpeedMinus).setOnClickListener(v -> adjustSpeed(-10));
        floatView.findViewById(R.id.btnFloatSpeedPlus).setOnClickListener(v -> adjustSpeed(10));
        floatView.findViewById(R.id.btnFloatFontMinus).setOnClickListener(v -> {
            floatFontSize = Math.max(14, floatFontSize - 2);
            tvFloatText.setTextSize(floatFontSize);
        });
        floatView.findViewById(R.id.btnFloatFontPlus).setOnClickListener(v -> {
            floatFontSize = Math.min(36, floatFontSize + 2);
            tvFloatText.setTextSize(floatFontSize);
        });
        btnFloatAlign.setOnClickListener(v -> {
            isAlignLeft = !isAlignLeft;
            applyAlignment();
            saveFloatPrefs();
        });
        btnFloatAi.setOnClickListener(v -> {
            isAiOn = !isAiOn;
            btnFloatAi.setTextColor(Color.parseColor(isAiOn ? "#38BDF8" : "#CBD5E1"));
            if (isAiOn) {
                pauseScrolling();
                startVoiceFollow();
            } else if (voiceFollower != null) {
                voiceFollower.stopListening();
            }
        });
        floatView.findViewById(R.id.btnFloatOrient).setOnClickListener(v -> {
            int tmp = params.width;
            params.width = params.height;
            params.height = tmp;
            windowManager.updateViewLayout(floatView, params);
            saveFloatPrefs();
        });

        // 顶部拖动手柄
        floatView.findViewById(R.id.floatDragHandle).setOnTouchListener(new View.OnTouchListener() {
            int initX, initY;
            float downX, downY;
            @Override public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        initX = params.x; initY = params.y;
                        downX = event.getRawX(); downY = event.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        params.x = initX + (int) (event.getRawX() - downX);
                        params.y = initY + (int) (event.getRawY() - downY);
                        windowManager.updateViewLayout(floatView, params);
                        return true;
                    case MotionEvent.ACTION_UP:
                        saveFloatPrefs();
                        return true;
                }
                return false;
            }
        });

        // 右下角缩放手柄
        floatView.findViewById(R.id.floatResizeHandle).setOnTouchListener(new View.OnTouchListener() {
            int initW, initH;
            float downX, downY;
            @Override public boolean onTouch(View v, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        initW = params.width; initH = params.height;
                        downX = event.getRawX(); downY = event.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int minW = (int) (200 * density);
                        int minH = (int) (130 * density);
                        params.width = Math.max(minW, initW + (int) (event.getRawX() - downX));
                        params.height = Math.max(minH, initH + (int) (event.getRawY() - downY));
                        windowManager.updateViewLayout(floatView, params);
                        return true;
                    case MotionEvent.ACTION_UP:
                        saveFloatPrefs();
                        return true;
                }
                return false;
            }
        });

        // 文本区点击启停 + 上下拖拽调位置
        tapDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onSingleTapConfirmed(MotionEvent e) {
                togglePlayPause();
                return true;
            }
        });
        floatScrollContainer.setOnTouchListener((v, event) -> {
            tapDetector.onTouchEvent(event);
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    lastTouchY = event.getRawY();
                    break;
                case MotionEvent.ACTION_MOVE:
                    float rawY = event.getRawY();
                    float dy = lastTouchY - rawY;
                    lastTouchY = rawY;
                    int maxS = Math.max(0, floatTextWrapper.getHeight() - floatScrollContainer.getHeight());
                    currentScrollY = Math.max(0f, Math.min(maxS, currentScrollY + dy));
                    floatTextWrapper.setTranslationY(-currentScrollY);
                    break;
            }
            return true;
        });

        windowManager.addView(floatView, params);
        startScrolling();
    }

    private void setLocked(boolean locked) {
        isLocked = locked;
        if (locked) {
            params.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        } else {
            params.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        }
        windowManager.updateViewLayout(floatView, params);
        startForegroundNotification(locked);
    }

    private void applyBackgroundAlpha() {
        int a = Math.round((floatAlpha / 100.0f) * 255);
        floatRootContainer.setBackgroundColor(Color.argb(a, 15, 23, 42));
    }

    private void applyAlignment() {
        if (isAlignLeft) {
            tvFloatText.setGravity(Gravity.START);
            btnFloatAlign.setImageResource(R.drawable.ic_align_left);
            btnFloatAlign.setColorFilter(Color.parseColor("#818CF8"));
        } else {
            tvFloatText.setGravity(Gravity.CENTER_HORIZONTAL);
            btnFloatAlign.setImageResource(R.drawable.ic_align_center);
            btnFloatAlign.setColorFilter(Color.parseColor("#CBD5E1"));
        }
    }

    private void adjustSpeed(int delta) {
        currentSpeed = Math.max(10, Math.min(200, currentSpeed + delta));
        tvFloatSpeed.setText(String.valueOf(currentSpeed));
        saveFloatPrefs();
    }

    private void togglePlayPause() {
        if (isPlaying) pauseScrolling(); else startScrolling();
    }

    private void startScrolling() {
        if (isPlaying) return;
        isPlaying = true;
        btnFloatPlay.setText("⏸");
        lastFrameTime = 0L;
        Choreographer.getInstance().removeFrameCallback(frameCallback);
        Choreographer.getInstance().postFrameCallback(frameCallback);
    }

    private void pauseScrolling() {
        isPlaying = false;
        btnFloatPlay.setText("▶");
        Choreographer.getInstance().removeFrameCallback(frameCallback);
    }

    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override public void doFrame(long frameTimeNanos) {
            if (!isPlaying) return;
            if (lastFrameTime == 0) lastFrameTime = frameTimeNanos;
            float dt = (frameTimeNanos - lastFrameTime) / 1_000_000_000.0f;
            lastFrameTime = frameTimeNanos;
            if (dt > 0f && dt < 0.1f) {
                currentScrollY += currentSpeed * dt;
                floatTextWrapper.setTranslationY(-currentScrollY);
            }
            int maxS = Math.max(0, floatTextWrapper.getHeight() - floatScrollContainer.getHeight());
            if (maxS > 0 && currentScrollY >= maxS) {
                pauseScrolling();
                return;
            }
            Choreographer.getInstance().postFrameCallback(this);
        }
    };

    private void setupMediaSession() {
        mediaSession = new MediaSessionCompat(this, "FloatPrompterSession");
        mediaSession.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS | MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);
        mediaSession.setPlaybackState(new PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY | PlaybackStateCompat.ACTION_PAUSE | PlaybackStateCompat.ACTION_PLAY_PAUSE)
                .setState(PlaybackStateCompat.STATE_PAUSED, 0, 1.0f).build());
        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override public void onPlay() { handleTwsTap(); }
            @Override public void onPause() { handleTwsTap(); }
            @Override public boolean onMediaButtonEvent(Intent mediaButtonEvent) {
                KeyEvent ev = mediaButtonEvent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                if (ev != null && ev.getAction() == KeyEvent.ACTION_DOWN) {
                    int kc = ev.getKeyCode();
                    if (kc == KeyEvent.KEYCODE_MEDIA_PLAY || kc == KeyEvent.KEYCODE_MEDIA_PAUSE
                            || kc == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || kc == KeyEvent.KEYCODE_HEADSETHOOK) {
                        handleTwsTap();
                        return true;
                    }
                }
                return super.onMediaButtonEvent(mediaButtonEvent);
            }
        });
        mediaSession.setActive(true);
    }

    private void handleTwsTap() {
        long now = System.currentTimeMillis();
        if (now - lastMediaKeyTime < 350) return;
        lastMediaKeyTime = now;
        floatView.post(this::togglePlayPause);
    }

    private void startVoiceFollow() {
        if (voiceFollower == null) {
            voiceFollower = new VoiceFollower(this, new VoiceFollower.Listener() {
                @Override public void onModelReady() {
                    if (isAiOn) voiceFollower.startListening();
                }
                @Override public void onNewSpokenText(String deltaText) {
                    if (isAiOn) matchSpokenStrict(deltaText);
                }
                @Override public void onError(String msg) {}
            });
            voiceFollower.initModelAsync();
        } else {
            voiceFollower.startListening();
        }
    }

    private void matchSpokenStrict(String delta) {
        if (scriptText.isEmpty() || highlightedIndex >= scriptText.length()) return;
        boolean adv = false;
        for (int i = 0; i < delta.length(); i++) {
            char spk = norm(delta.charAt(i));
            if (!Character.isLetterOrDigit(spk)) continue;
            int idx = skipPunct(highlightedIndex);
            if (idx >= scriptText.length()) break;
            if (spk == norm(scriptText.charAt(idx))) {
                highlightedIndex = skipPunct(idx + 1);
                adv = true;
            } else {
                int idx2 = skipPunct(idx + 1);
                if (idx2 < scriptText.length() && spk == norm(scriptText.charAt(idx2))) {
                    highlightedIndex = skipPunct(idx2 + 1);
                    adv = true;
                }
            }
        }
        if (adv) {
            renderText();
            Layout layout = tvFloatText.getLayout();
            if (layout != null) {
                int line = layout.getLineForOffset(Math.min(highlightedIndex, scriptText.length()));
                int maxS = Math.max(0, floatTextWrapper.getHeight() - floatScrollContainer.getHeight());
                currentScrollY = Math.max(0f, Math.min(maxS, layout.getLineTop(line)));
                floatTextWrapper.animate().translationY(-currentScrollY).setDuration(180).start();
            }
        }
    }

    private int skipPunct(int idx) {
        while (idx < scriptText.length() && !Character.isLetterOrDigit(scriptText.charAt(idx))) idx++;
        return idx;
    }

    private char norm(char c) {
        c = Character.toLowerCase(c);
        switch (c) {
            case '0': return '零'; case '1': return '一'; case '2': return '二';
            case '3': return '三'; case '4': return '四'; case '5': return '五';
            case '6': return '六'; case '7': return '七'; case '8': return '八';
            case '9': return '九'; default: return c;
        }
    }

    private void renderText() {
        SpannableString ss = new SpannableString(scriptText);
        int end = Math.min(highlightedIndex, scriptText.length());
        if (end > 0) {
            ss.setSpan(new ForegroundColorSpan(Color.parseColor("#FACC15")), 0, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        tvFloatText.setText(ss);
    }

    private void saveFloatPrefs() {
        getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit()
                .putInt(KEY_FLOAT_X, params.x)
                .putInt(KEY_FLOAT_Y, params.y)
                .putInt(KEY_FLOAT_W, params.width)
                .putInt(KEY_FLOAT_H, params.height)
                .putInt(KEY_FLOAT_ALPHA, floatAlpha)
                .putInt(KEY_SPEED, currentSpeed)
                .putBoolean(KEY_ALIGN_LEFT, isAlignLeft)
                .apply();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Choreographer.getInstance().removeFrameCallback(frameCallback);
        if (voiceFollower != null) voiceFollower.release();
        if (mediaSession != null) mediaSession.release();
        if (floatView != null && windowManager != null) {
            try { windowManager.removeView(floatView); } catch (Exception ignored) {}
        }
    }
}
