package com.smartprompter.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

/**
 * 智能提词器主界面 (OPPO / ColorOS & TWS 耳机深度适配版)
 * 1. 使用 ScrollView 彻底解决长文本截断、显示半截的问题
 * 2. 内置静音 AudioTrack 音频流 + MediaSessionCompat.Callback (onPlay/onPause)，
 *    确保 OPPO Enco Air5 Pro 等 TWS 耳机单击暂停/继续稳定生效
 * 3. 音量加/减直接调节滚动速度，并拦截系统音量弹窗
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
    private LinearLayout framePrompterContent;
    private ScrollView prompterScrollContainer;
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
    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;
    private AudioTrack silentAudioTrack;
    private VolumeChangeReceiver volumeReceiver;
    private int lastAudioVolume = -1;
    private int midVolume = 7;
    private int savedOriginalVolume = -1;
    private long lastMediaKeyTime = 0;

    private final Handler hudHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        initViews();
        loadSavedPreferences();
        startSilentAudioKeepAlive();
        setupMediaSessionAndAudioFocus();
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
        prompterScrollContainer = findViewById(R.id.prompterScrollContainer);
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

    private void startSilentAudioKeepAlive() {
        try {
            int sampleRate = 8000;
            int numSamples = 8000;
            byte[] silence = new byte[numSamples * 2];

            silentAudioTrack = new AudioTrack(
                    new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    new AudioFormat.Builder()
                            .setSampleRate(sampleRate)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build(),
                    silence.length,
                    AudioTrack.MODE_STATIC,
                    AudioManager.AUDIO_SESSION_ID_GENERATE
            );
            silentAudioTrack.write(silence, 0, silence.length);
            silentAudioTrack.setLoopPoints(0, numSamples, -1);
            silentAudioTrack.play();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void setupMediaSessionAndAudioFocus() {
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        requestExclusiveAudioFocus();

        mediaSession = new MediaSessionCompat(this, "SmartPrompterMediaSession");
        mediaSession.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);

        updatePlaybackState(PlaybackStateCompat.STATE_PAUSED);

        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override
            public void onPlay() {
                if (isDebounced()) return;
                runOnUiThread(() -> {
                    if (isPrompterActive) {
                        startPrompter("耳机播放");
                    } else {
                        tvRemoteSignalBadge.setText("🟢 收到耳机播放指令 (开启提词后生效)");
                    }
                });
            }

            @Override
            public void onPause() {
                if (isDebounced()) return;
                runOnUiThread(() -> {
                    if (isPrompterActive) {
                        pausePrompter("耳机暂停");
                    } else {
                        tvRemoteSignalBadge.setText("🟢 收到耳机暂停指令 (开启提词后生效)");
                    }
                });
            }

            @Override
            public void onSkipToNext() {
                runOnUiThread(() -> adjustSpeed(5, "耳机切歌(下一首)"));
            }

            @Override
            public void onSkipToPrevious() {
                runOnUiThread(() -> adjustSpeed(-5, "耳机切歌(上一首)"));
            }

            @Override
            public boolean onMediaButtonEvent(Intent mediaButtonEvent) {
                KeyEvent event = mediaButtonEvent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                if (event != null && event.getAction() == KeyEvent.ACTION_DOWN) {
                    int keyCode = event.getKeyCode();
                    if (keyCode == KeyEvent.KEYCODE_HEADSETHOOK || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
                        if (!isDebounced()) {
                            runOnUiThread(() -> {
                                if (isPrompterActive) {
                                    togglePrompterPlayPause("TWS耳机");
                                } else {
                                    tvRemoteSignalBadge.setText("🟢 收到TWS耳机按键信号");
                                }
                            });
                        }
                        return true;
                    } else if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY) {
                        onPlay();
                        return true;
                    } else if (keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE) {
                        onPause();
                        return true;
                    } else if (keyCode == KeyEvent.KEYCODE_MEDIA_NEXT) {
                        onSkipToNext();
                        return true;
                    } else if (keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
                        onSkipToPrevious();
                        return true;
                    }
                }
                return super.onMediaButtonEvent(mediaButtonEvent);
            }
        });

        mediaSession.setActive(true);
        tvRemoteSignalBadge.setText("🟢 蓝牙耳机按键与音量监听已激活");
        tvRemoteSignalBadge.setTextColor(Color.parseColor("#34D399"));
    }

    private boolean isDebounced() {
        long now = System.currentTimeMillis();
        if (now - lastMediaKeyTime < 250) {
            return true;
        }
        lastMediaKeyTime = now;
        return false;
    }

    private void requestExclusiveAudioFocus() {
        if (audioManager == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build())
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener(focusChange -> {
                        if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT && isPrompterActive) {
                            requestExclusiveAudioFocus();
                        }
                    })
                    .build();
            audioManager.requestAudioFocus(audioFocusRequest);
        } else {
            audioManager.requestAudioFocus(null, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        }
    }

    private void updatePlaybackState(int state) {
        if (mediaSession == null) return;
        PlaybackStateCompat playbackState = new PlaybackStateCompat.Builder()
                .setActions(PlaybackStateCompat.ACTION_PLAY
                        | PlaybackStateCompat.ACTION_PAUSE
                        | PlaybackStateCompat.ACTION_PLAY_PAUSE
                        | PlaybackStateCompat.ACTION_SKIP_TO_NEXT
                        | PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS)
                .setState(state, (long) currentScrollY, state == PlaybackStateCompat.STATE_PLAYING ? 1.0f : 0.0f)
                .build();
        mediaSession.setPlaybackState(playbackState);
    }

    private void setupVolumeReceiver() {
        if (audioManager != null) {
            int maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            midVolume = Math.max(1, maxVol / 2);
            lastAudioVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        }

        volumeReceiver = new VolumeChangeReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction("android.media.VOLUME_CHANGED_ACTION");

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(volumeReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(volumeReceiver, filter);
        }
    }

    private class VolumeChangeReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!isPrompterActive) return;
            if ("android.media.VOLUME_CHANGED_ACTION".equals(intent.getAction())) {
                int streamType = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1);
                if (streamType != -1 && streamType != AudioManager.STREAM_MUSIC) return;

                int volume = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", -1);
                if (volume == -1 || volume == midVolume) return;

                if (volume > midVolume) {
                    adjustSpeed(+5, "耳机/音量上滑 ➔ 滚速加快一档");
                } else {
                    adjustSpeed(-5, "耳机/音量下滑 ➔ 滚速放慢一档");
                }

                // 静默归位到中间音量，防止滑到最大/最小后无法继续调速
                if (audioManager != null) {
                    try {
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, midVolume, 0);
                    } catch (Exception ignored) {}
                }
            }
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (isPrompterActive) {
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    adjustSpeed(+5, "音量加 ➔ 滚速加快一档");
                }
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    adjustSpeed(-5, "音量减 ➔ 滚速放慢一档");
                }
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_HEADSETHOOK
                    || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                    || keyCode == KeyEvent.KEYCODE_SPACE) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && !isDebounced()) {
                    togglePrompterPlayPause("按键控制");
                }
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_MEDIA_PLAY) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && !isDebounced()) {
                    startPrompter("耳机播放");
                }
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && !isDebounced()) {
                    pausePrompter("耳机暂停");
                }
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

        prompterScrollContainer.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_MOVE) {
                currentScrollY = prompterScrollContainer.getScrollY();
                wakeFloatingBar();
            }
            return false;
        });
    }

    private void updateWordCount() {
        int count = etScript.getText().toString().trim().length();
        int estimatedSec = Math.round((count / 240.0f) * 60);
        tvWordCount.setText(count + " 字 · 约 " + estimatedSec + " 秒");
    }

    private void openPrompter() {
        isPrompterActive = true;
        savePreferences();
        requestExclusiveAudioFocus();
        if (audioManager != null) {
            try {
                savedOriginalVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, midVolume, 0);
            } catch (Exception ignored) {}
        }
        if (mediaSession != null) {
            mediaSession.setActive(true);
        }

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
        prompterScrollContainer.post(() -> prompterScrollContainer.scrollTo(0, 0));

        wakeFloatingBar();
        startPrompter("开启提词");
    }

    private void exitPrompter() {
        pausePrompter("退出提词");
        isPrompterActive = false;
        if (audioManager != null && savedOriginalVolume != -1) {
            try {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, savedOriginalVolume, 0);
                savedOriginalVolume = -1;
            } catch (Exception ignored) {}
        }

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
        currentScrollY = prompterScrollContainer.getScrollY();
        updatePlaybackState(PlaybackStateCompat.STATE_PLAYING);
        lastFrameTime = System.nanoTime();
        scrollHandler.post(scrollRunnable);
        btnPrompterPlayPause.setImageResource(android.R.drawable.ic_media_pause);
        showHud("▶ 开始滚动 (" + source + ")");
    }

    private void pausePrompter(String source) {
        if (!isPlaying) return;
        isPlaying = false;
        updatePlaybackState(PlaybackStateCompat.STATE_PAUSED);
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
        prompterScrollContainer.scrollTo(0, 0);
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

            currentScrollY += currentSpeed * deltaSeconds;
            int targetY = Math.round(currentScrollY);
            prompterScrollContainer.scrollTo(0, targetY);

            int maxScroll = Math.max(0, framePrompterContent.getHeight() - prompterScrollContainer.getHeight());
            if (maxScroll > 0 && targetY >= maxScroll) {
                pausePrompter("台词结束");
                return;
            }

            scrollHandler.postDelayed(this, 16);
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
    protected void onResume() {
        super.onResume();
        requestExclusiveAudioFocus();
        if (mediaSession != null) {
            mediaSession.setActive(true);
        }
    }

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
        if (silentAudioTrack != null) {
            try {
                silentAudioTrack.stop();
                silentAudioTrack.release();
            } catch (Exception ignored) {}
        }
        if (mediaSession != null) {
            mediaSession.release();
        }
        if (volumeReceiver != null) {
            unregisterReceiver(volumeReceiver);
        }
        if (audioManager != null && audioFocusRequest != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioManager.abandonAudioFocusRequest(audioFocusRequest);
        }
        scrollHandler.removeCallbacks(scrollRunnable);
    }
}
