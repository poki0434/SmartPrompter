package com.smartprompter.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

/**
 * 智能提词器主界面
 * 支持蓝牙耳机按键（播放/暂停）遥控与音量键（调大/调小）动态调速
 */
public class MainActivity extends AppCompatActivity {

    private static final String PREF_NAME = "SmartPrompterPrefs";
    private static final String KEY_SCRIPT = "key_script";
    private static final String KEY_SPEED = "key_speed";
    private static final String KEY_FONT_SIZE = "key_font_size";
    private static final String KEY_MIRROR = "key_mirror";
    private static final String KEY_FOCUS_LINE = "key_focus_line";

    private static final String DEFAULT_SAMPLE = "大家好，欢迎来到今天的分享。\n\n"
            + "今天我们要聊的是：如何用科技让工作与创作更高效。\n\n"
            + "当你站在镜头前，眼神要坚定，语速要平稳，自然呼吸。\n\n"
            + "通过蓝牙耳机遥控，双手无需触碰屏幕，按一下耳机即可随时暂停或继续；\n\n"
            + "感觉语速跟不上？直接按手机或耳机的音量键，随时加减滚动速度！\n\n"
            + "祝你拍摄顺利，录制一遍过！";

    private LinearLayout layoutEditor;
    private FrameLayout layoutPrompter;
    private EditText etScript;
    private TextView tvWordCount;
    private SeekBar seekSpeed;
    private TextView tvSpeedValue;
    private SeekBar seekFontSize;
    private TextView tvFontSizeValue;
    private CheckBox cbMirror;
    private CheckBox cbFocusLine;
    private View viewFocusLine;
    private TextView tvPrompterText;
    private FrameLayout framePrompterContent;
    private TextView tvRemoteSignalBadge;
    private TextView tvPrompterHud;

    private LinearLayout layoutFloatingBar;
    private ImageButton btnPrompterPlayPause;
    private TextView tvPrompterSpeedIndicator;

    private int currentSpeed = 45;
    private int currentFontSize = 48;
    private boolean isMirror = false;
    private boolean showFocusLine = true;
    private boolean isPlaying = false;
    private boolean isPrompterActive = false;

    private final Handler scrollHandler = new Handler(Looper.getMainLooper());
    private float currentScrollY = 0f;
    private long lastFrameTime = 0;

    private MediaSessionCompat mediaSession;
    private VolumeChangeReceiver volumeReceiver;
    private int lastAudioVolume = -1;

    private final Handler hudHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        initViews();
        loadSavedPreferences();
        setupMediaSession();
        setupVolumeReceiver();
        setupEventListeners();
    }

    private void initViews() {
        layoutEditor = findViewById(R.id.layoutEditor);
        layoutPrompter = findViewById(R.id.layoutPrompter);
        etScript = findViewById(R.id.etScript);
        tvWordCount = findViewById(R.id.tvWordCount);
        seekSpeed = findViewById(R.id.seekSpeed);
        tvSpeedValue = findViewById(R.id.tvSpeedValue);
        seekFontSize = findViewById(R.id.seekFontSize);
        tvFontSizeValue = findViewById(R.id.tvFontSizeValue);
        cbMirror = findViewById(R.id.cbMirror);
        cbFocusLine = findViewById(R.id.cbFocusLine);
        viewFocusLine = findViewById(R.id.viewFocusLine);
        tvPrompterText = findViewById(R.id.tvPrompterText);
        framePrompterContent = findViewById(R.id.framePrompterContent);
        tvRemoteSignalBadge = findViewById(R.id.tvRemoteSignalBadge);
        tvPrompterHud = findViewById(R.id.tvPrompterHud);

        layoutFloatingBar = findViewById(R.id.layoutFloatingBar);
        btnPrompterPlayPause = findViewById(R.id.btnPrompterPlayPause);
        tvPrompterSpeedIndicator = findViewById(R.id.tvPrompterSpeedIndicator);
    }

    private void loadSavedPreferences() {
        SharedPreferences sp = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        String savedText = sp.getString(KEY_SCRIPT, DEFAULT_SAMPLE);
        currentSpeed = sp.getInt(KEY_SPEED, 45);
        currentFontSize = sp.getInt(KEY_FONT_SIZE, 48);
        isMirror = sp.getBoolean(KEY_MIRROR, false);
        showFocusLine = sp.getBoolean(KEY_FOCUS_LINE, true);

        etScript.setText(savedText);
        seekSpeed.setProgress(currentSpeed);
        tvSpeedValue.setText(currentSpeed + " px/s");
        tvPrompterSpeedIndicator.setText(currentSpeed + " px");

        seekFontSize.setProgress(currentFontSize);
        tvFontSizeValue.setText(currentFontSize + " sp");

        cbMirror.setChecked(isMirror);
        cbFocusLine.setChecked(showFocusLine);

        updateWordCount();
    }

    private void savePreferences() {
        SharedPreferences.Editor editor = getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit();
        editor.putString(KEY_SCRIPT, etScript.getText().toString());
        editor.putInt(KEY_SPEED, currentSpeed);
        editor.putInt(KEY_FONT_SIZE, currentFontSize);
        editor.putBoolean(KEY_MIRROR, isMirror);
        editor.putBoolean(KEY_FOCUS_LINE, showFocusLine);
        editor.apply();
    }

    private void setupMediaSession() {
        mediaSession = new MediaSessionCompat(this, "PrompterMediaSession");
        mediaSession.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);

        PlaybackStateCompat state = new PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY
                        | PlaybackStateCompat.ACTION_PAUSE
                        | PlaybackStateCompat.ACTION_PLAY_PAUSE
                        | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                        | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)
                .setState(PlaybackStateCompat.STATE_PLAYING, 0, 1.0f)
                .build();
        mediaSession.setPlaybackState(state);

        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override
            public boolean onMediaButtonEvent(Intent mediaButtonEvent) {
                KeyEvent event = mediaButtonEvent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                if (event != null && event.getAction() == KeyEvent.ACTION_DOWN) {
                    int keyCode = event.getKeyCode();
                    switch (keyCode) {
                        case KeyEvent.KEYCODE_HEADSETHOOK:
                        case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                            togglePrompterPlayPause("蓝牙耳机按键");
                            return true;
                        case KeyEvent.KEYCODE_MEDIA_PLAY:
                            startPrompter("蓝牙耳机播放键");
                            return true;
                        case KeyEvent.KEYCODE_MEDIA_PAUSE:
                            pausePrompter("蓝牙耳机暂停键");
                            return true;
                        case KeyEvent.KEYCODE_MEDIA_NEXT:
                            adjustSpeed(5, "蓝牙耳机切歌(下一首)");
                            return true;
                        case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                            adjustSpeed(-5, "蓝牙耳机切歌(上一首)");
                            return true;
                    }
                }
                return super.onMediaButtonEvent(mediaButtonEvent);
            }
        });

        mediaSession.setActive(true);
        tvRemoteSignalBadge.setText("🟢 蓝牙耳机监听已就绪 (可按耳机键测试)");
        tvRemoteSignalBadge.setTextColor(Color.parseColor("#34D399"));
    }

    private void setupVolumeReceiver() {
        AudioManager audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (audioManager != null) {
            lastAudioVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        }

        volumeReceiver = new VolumeChangeReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction("android.media.VOLUME_CHANGED_ACTION");
        registerReceiver(volumeReceiver, filter);
    }

    private class VolumeChangeReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if ("android.media.VOLUME_CHANGED_ACTION".equals(intent.getAction())) {
                int volume = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", -1);
                if (lastAudioVolume != -1 && volume != -1 && volume != lastAudioVolume) {
                    if (volume > lastAudioVolume) {
                        adjustSpeed(+5, "蓝牙/侧键音量调大 ➔ 提词加速");
                    } else {
                        adjustSpeed(-5, "蓝牙/侧键音量调小 ➔ 提词减速");
                    }
                }
                lastAudioVolume = volume;
            }
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (isPrompterActive && event.getAction() == KeyEvent.ACTION_DOWN) {
            int keyCode = event.getKeyCode();
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                adjustSpeed(+5, "音量加键 ➔ 提词加速");
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                adjustSpeed(-5, "音量减键 ➔ 提词减速");
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_SPACE || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                togglePrompterPlayPause("空格/播放键");
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void setupEventListeners() {
        etScript.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                updateWordCount();
                savePreferences();
            }
            @Override public void afterTextChanged(android.text.Editable s) {}
        });

        findViewById(R.id.btnLoadSample).setOnClickListener(v -> {
            etScript.setText(DEFAULT_SAMPLE);
            Toast.makeText(this, "已载入示例台词", Toast.LENGTH_SHORT).show();
        });

        findViewById(R.id.btnClearScript).setOnClickListener(v -> {
            etScript.setText("");
            Toast.makeText(this, "已清空文稿", Toast.LENGTH_SHORT).show();
        });

        seekSpeed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                currentSpeed = Math.max(10, progress);
                tvSpeedValue.setText(currentSpeed + " px/s");
                tvPrompterSpeedIndicator.setText(currentSpeed + " px");
                savePreferences();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        seekFontSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                currentFontSize = Math.max(24, progress);
                tvFontSizeValue.setText(currentFontSize + " sp");
                savePreferences();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        cbMirror.setOnCheckedChangeListener((btn, isChecked) -> {
            isMirror = isChecked;
            savePreferences();
        });

        cbFocusLine.setOnCheckedChangeListener((btn, isChecked) -> {
            showFocusLine = isChecked;
            savePreferences();
        });

        findViewById(R.id.btnLaunchPrompter).setOnClickListener(v -> openPrompter());
        findViewById(R.id.btnPrompterExit).setOnClickListener(v -> exitPrompter());
        findViewById(R.id.btnPrompterRestart).setOnClickListener(v -> restartPrompter());
        btnPrompterPlayPause.setOnClickListener(v -> togglePrompterPlayPause("屏幕触控"));
        findViewById(R.id.btnPrompterSpeedMinus).setOnClickListener(v -> adjustSpeed(-5, "手动减速"));
        findViewById(R.id.btnPrompterSpeedPlus).setOnClickListener(v -> adjustSpeed(+5, "手动加速"));
        findViewById(R.id.btnPrompterMirror).setOnClickListener(v -> {
            isMirror = !isMirror;
            applyMirror();
            showHud("镜像翻转: " + (isMirror ? "开" : "关"));
            savePreferences();
        });

        layoutPrompter.setOnClickListener(v -> wakeFloatingBar());
    }

    private void updateWordCount() {
        int count = etScript.getText().toString().trim().length();
        int estimatedSec = Math.round((count / 240.0f) * 60);
        tvWordCount.setText(count + " 字 · 约 " + estimatedSec + " 秒");
    }

    private void openPrompter() {
        isPrompterActive = true;
        savePreferences();

        layoutEditor.setVisibility(View.GONE);
        layoutPrompter.setVisibility(View.VISIBLE);

        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        tvPrompterText.setText(etScript.getText().toString());
        tvPrompterText.setTextSize(currentFontSize);

        applyMirror();
        viewFocusLine.setVisibility(showFocusLine ? View.VISIBLE : View.GONE);

        currentScrollY = 0f;
        framePrompterContent.setTranslationY(0f);

        wakeFloatingBar();
        startPrompter("开启提词");
    }

    private void exitPrompter() {
        pausePrompter("退出提词");
        isPrompterActive = false;

        layoutPrompter.setVisibility(View.GONE);
        layoutEditor.setVisibility(View.VISIBLE);

        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
    }

    private void applyMirror() {
        if (isMirror) {
            framePrompterContent.setScaleX(-1.0f);
        } else {
            framePrompterContent.setScaleX(1.0f);
        }
    }

    private void startPrompter(String source) {
        if (isPlaying) return;
        isPlaying = true;
        lastFrameTime = System.nanoTime();
        scrollHandler.post(scrollRunnable);
        btnPrompterPlayPause.setImageResource(android.R.drawable.ic_media_pause);
        showHud("▶ 开始滚动 (" + source + ")");
    }

    private void pausePrompter(String source) {
        if (!isPlaying) return;
        isPlaying = false;
        scrollHandler.removeCallbacks(scrollRunnable);
        btnPrompterPlayPause.setImageResource(android.R.drawable.ic_media_play);
        showHud("⏸ 暂停滚动 (" + source + ")");
    }

    private void togglePrompterPlayPause(String source) {
        if (isPlaying) {
            pausePrompter(source);
        } else {
            startPrompter(source);
        }
    }

    private void restartPrompter() {
        currentScrollY = 0f;
        framePrompterContent.setTranslationY(0f);
        showHud("⏮ 回到文稿开头");
    }

    private void adjustSpeed(int delta, String trigger) {
        currentSpeed = Math.max(10, Math.min(200, currentSpeed + delta));
        seekSpeed.setProgress(currentSpeed);
        tvSpeedValue.setText(currentSpeed + " px/s");
        tvPrompterSpeedIndicator.setText(currentSpeed + " px");
        showHud("⚡ 速度: " + currentSpeed + " px/s (" + trigger + ")");
        savePreferences();
    }

    private final Runnable scrollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isPlaying) return;
            long now = System.nanoTime();
            float deltaSeconds = (now - lastFrameTime) / 1_000_000_000.0f;
            lastFrameTime = now;

            currentScrollY -= currentSpeed * deltaSeconds;
            framePrompterContent.setTranslationY(currentScrollY);

            int contentHeight = framePrompterContent.getHeight();
            if (contentHeight > 0 && Math.abs(currentScrollY) > contentHeight) {
                pausePrompter("台词结束");
                return;
            }

            scrollHandler.post(this);
        }
    };

    private void showHud(String message) {
        tvPrompterHud.setText(message);
        tvPrompterHud.setVisibility(View.VISIBLE);
        tvRemoteSignalBadge.setText(message);

        hudHandler.removeCallbacks(hideHudRunnable);
        hudHandler.postDelayed(hideHudRunnable, 2000);
    }

    private final Runnable hideHudRunnable = () -> tvPrompterHud.setVisibility(View.GONE);

    private void wakeFloatingBar() {
        layoutFloatingBar.setVisibility(View.VISIBLE);
        hudHandler.removeCallbacks(hideFloatingBarRunnable);
        hudHandler.postDelayed(hideFloatingBarRunnable, 4000);
    }

    private final Runnable hideFloatingBarRunnable = () -> {
        if (isPlaying && isPrompterActive) {
            layoutFloatingBar.setVisibility(View.GONE);
        }
    };

    @Override
    public void onBackPressed() {
        if (isPrompterActive) {
            exitPrompter();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mediaSession != null) {
            mediaSession.release();
        }
        if (volumeReceiver != null) {
            unregisterReceiver(volumeReceiver);
        }
        scrollHandler.removeCallbacks(scrollRunnable);
    }
}
