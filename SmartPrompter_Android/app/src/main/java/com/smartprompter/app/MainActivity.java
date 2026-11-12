package com.smartprompter.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
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

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 智能提词器主界面 (v8 - 三级架构 + OPPO TWS 深度保活)
 * 一级界面：草稿列表（文档1、文档2... 含标题与内容预览，支持新建与删除）
 * 二级界面：编辑标题、提词内容与提词参数设置
 * 三级界面：全屏提词（单击屏幕/耳机启停，上下滑屏/耳机调速 ±10 px/s，单一镜像水平+180°）
 */
public class MainActivity extends AppCompatActivity {

    private static final String PREF_NAME = "SmartPrompterPrefs";
    private static final String KEY_DRAFTS_JSON = "key_drafts_json";
    private static final String KEY_SPEED = "key_speed";
    private static final String KEY_FONT_SIZE = "key_font_size";
    private static final String KEY_MIRROR = "key_mirror";
    private static final String KEY_FOCUS_LINE = "key_focus_line";

    private static final int SPEED_STEP = 10;

    private static final String DEFAULT_SAMPLE = "大家好，欢迎来到今天的分享。\n\n"
            + "今天我们要聊的是：如何用科技让工作与创作更高效。\n\n"
            + "当你站在镜头前，眼神要坚定，语速要平稳，自然呼吸。\n\n"
            + "通过蓝牙耳机遥控，双手无需触碰屏幕，按一下耳机即可随时暂停或继续；\n\n"
            + "感觉语速跟不上？上下滑动屏幕或耳机音量键，每次加减 10 px/s 滚动速度！\n\n"
            + "祝你拍摄顺利，录制一遍过！";

    private static class Draft {
        String id;
        String title;
        String content;

        Draft(String id, String title, String content) {
            this.id = id;
            this.title = title;
            this.content = content;
        }
    }

    // 三级视图容器
    private LinearLayout layoutDraftList;
    private LinearLayout layoutEditor;
    private FrameLayout layoutPrompter;

    // 一级：草稿列表控件
    private LinearLayout draftListContainer;
    private TextView tvDraftCount;

    // 二级：编辑与参数控件
    private EditText etDraftTitle;
    private EditText etScript;
    private TextView tvWordCount;
    private SeekBar seekSpeed;
    private TextView tvSpeedValue;
    private SeekBar seekFontSize;
    private TextView tvFontSizeValue;
    private CheckBox cbMirror;
    private CheckBox cbFocusLine;
    private TextView tvRemoteSignalBadge;

    // 三级：提词控件
    private View viewFocusLine;
    private TextView tvPrompterText;
    private LinearLayout framePrompterContent;
    private ScrollView prompterScrollContainer;
    private TextView tvPrompterHud;
    private LinearLayout layoutFloatingBar;
    private ImageButton btnPrompterPlayPause;
    private TextView tvPrompterSpeedIndicator;

    // 数据与状态
    private final List<Draft> drafts = new ArrayList<>();
    private int currentDraftIndex = -1;
    private boolean isUpdatingEditorFields = false;

    private int currentSpeed = 45;
    private int currentFontSize = 48;
    private boolean isMirror = false;
    private boolean showFocusLine = true;
    private boolean isPlaying = false;
    private boolean isPrompterActive = false;

    private final Handler scrollHandler = new Handler(Looper.getMainLooper());
    private float currentScrollY = 0f;
    private long lastFrameTime = 0;

    // 蓝牙耳机 MediaSession 与极微底噪保活音轨
    private MediaSessionCompat mediaSession;
    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;
    private AudioTrack silentAudioTrack;
    private VolumeChangeReceiver volumeReceiver;
    private int midVolume = 7;
    private int savedOriginalVolume = -1;
    private long lastMediaKeyTime = 0;

    private GestureDetector tapGestureDetector;
    private float swipeAnchorY = 0f;
    private boolean isSwipeAdjustingSpeed = false;

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
        renderDraftList();

    }

    private void initViews() {
        layoutDraftList = findViewById(R.id.layoutDraftList);
        layoutEditor = findViewById(R.id.layoutEditor);
        layoutPrompter = findViewById(R.id.layoutPrompter);

        draftListContainer = findViewById(R.id.draftListContainer);
        tvDraftCount = findViewById(R.id.tvDraftCount);

        etDraftTitle = findViewById(R.id.etDraftTitle);
        etScript = findViewById(R.id.etScript);
        tvWordCount = findViewById(R.id.tvWordCount);
        seekSpeed = findViewById(R.id.seekSpeed);
        tvSpeedValue = findViewById(R.id.tvSpeedValue);
        seekFontSize = findViewById(R.id.seekFontSize);
        tvFontSizeValue = findViewById(R.id.tvFontSizeValue);
        cbMirror = findViewById(R.id.cbMirror);
        cbFocusLine = findViewById(R.id.cbFocusLine);
        tvRemoteSignalBadge = findViewById(R.id.tvRemoteSignalBadge);

        viewFocusLine = findViewById(R.id.viewFocusLine);
        tvPrompterText = findViewById(R.id.tvPrompterText);
        framePrompterContent = findViewById(R.id.framePrompterContent);
        prompterScrollContainer = findViewById(R.id.prompterScrollContainer);
        tvPrompterHud = findViewById(R.id.tvPrompterHud);
        layoutFloatingBar = findViewById(R.id.layoutFloatingBar);
        btnPrompterPlayPause = findViewById(R.id.btnPrompterPlayPause);
        tvPrompterSpeedIndicator = findViewById(R.id.tvPrompterSpeedIndicator);
    }

    private void loadSavedPreferences() {
        SharedPreferences sp = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        currentSpeed = sp.getInt(KEY_SPEED, 45);
        currentFontSize = sp.getInt(KEY_FONT_SIZE, 48);
        isMirror = sp.getBoolean(KEY_MIRROR, false);
        showFocusLine = sp.getBoolean(KEY_FOCUS_LINE, true);

        seekSpeed.setProgress(currentSpeed);
        tvSpeedValue.setText(currentSpeed + " px/s");
        tvPrompterSpeedIndicator.setText(currentSpeed + " px");

        seekFontSize.setProgress(currentFontSize);
        tvFontSizeValue.setText(currentFontSize + " sp");

        cbMirror.setChecked(isMirror);
        cbFocusLine.setChecked(showFocusLine);

        drafts.clear();
        String json = sp.getString(KEY_DRAFTS_JSON, "");
        if (!json.isEmpty()) {
            try {
                JSONArray arr = new JSONArray(json);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject obj = arr.getJSONObject(i);
                    drafts.add(new Draft(
                            obj.optString("id", UUID.randomUUID().toString()),
                            obj.optString("title", "文档" + (i + 1)),
                            obj.optString("content", "")
                    ));
                }
            } catch (Exception ignored) {}
        }

        if (drafts.isEmpty()) {
            drafts.add(new Draft(UUID.randomUUID().toString(), "文档1（示例）", DEFAULT_SAMPLE));
            saveDrafts();
        }
    }

    private void saveDrafts() {
        try {
            JSONArray arr = new JSONArray();
            for (Draft d : drafts) {
                JSONObject obj = new JSONObject();
                obj.put("id", d.id);
                obj.put("title", d.title);
                obj.put("content", d.content);
                arr.put(obj);
            }
            getSharedPreferences(PREF_NAME, MODE_PRIVATE)
                    .edit()
                    .putString(KEY_DRAFTS_JSON, arr.toString())
                    .apply();
        } catch (Exception ignored) {}
    }

    private void saveSettings() {
        getSharedPreferences(PREF_NAME, MODE_PRIVATE).edit()
                .putInt(KEY_SPEED, currentSpeed)
                .putInt(KEY_FONT_SIZE, currentFontSize)
                .putBoolean(KEY_MIRROR, isMirror)
                .putBoolean(KEY_FOCUS_LINE, showFocusLine)
                .apply();
    }

    // =========================================================================
    // 一级界面：草稿列表渲染与交互
    // =========================================================================

    private void renderDraftList() {
        draftListContainer.removeAllViews();
        tvDraftCount.setText("共 " + drafts.size() + " 篇提词文稿");

        float density = getResources().getDisplayMetrics().density;
        int pad = (int) (14 * density);
        int marginBottom = (int) (12 * density);

        for (int i = 0; i < drafts.size(); i++) {
            final int index = i;
            Draft draft = drafts.get(i);

            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setPadding(pad, pad, pad, pad);

            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Color.parseColor("#1E293B"));
            bg.setCornerRadius(12 * density);
            bg.setStroke((int) (1 * density), Color.parseColor("#334155"));
            card.setBackground(bg);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            lp.bottomMargin = marginBottom;
            card.setLayoutParams(lp);

            // 标题行 + 删除按钮
            LinearLayout topRow = new LinearLayout(this);
            topRow.setOrientation(LinearLayout.HORIZONTAL);
            topRow.setGravity(android.view.Gravity.CENTER_VERTICAL);

            TextView tvTitle = new TextView(this);
            tvTitle.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            tvTitle.setText(draft.title.isEmpty() ? "未命名草稿" : draft.title);
            tvTitle.setTextColor(Color.WHITE);
            tvTitle.setTextSize(16f);
            tvTitle.setTypeface(null, android.graphics.Typeface.BOLD);

            Button btnDelete = new Button(this, null, android.R.attr.borderlessButtonStyle);
            btnDelete.setText("删除");
            btnDelete.setTextColor(Color.parseColor("#F43F5E"));
            btnDelete.setTextSize(12f);
            btnDelete.setMinimumWidth(0);
            btnDelete.setMinWidth(0);
            btnDelete.setOnClickListener(v -> {
                drafts.remove(index);
                saveDrafts();
                renderDraftList();
                Toast.makeText(this, "已删除草稿", Toast.LENGTH_SHORT).show();
            });

            topRow.addView(tvTitle);
            topRow.addView(btnDelete);

            // 内容预览
            TextView tvPreview = new TextView(this);
            String cleanContent = draft.content.replace("\n", " ").trim();
            tvPreview.setText(cleanContent.isEmpty() ? "（暂无内容，点击编辑）" : cleanContent);
            tvPreview.setTextColor(Color.parseColor("#94A3B8"));
            tvPreview.setTextSize(13f);
            tvPreview.setMaxLines(2);
            tvPreview.setEllipsize(android.text.TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams prevLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            prevLp.topMargin = (int) (6 * density);
            tvPreview.setLayoutParams(prevLp);

            // 字数统计
            int len = draft.content.trim().length();
            int sec = Math.round((len / 240.0f) * 60);
            TextView tvMeta = new TextView(this);
            tvMeta.setText(len + " 字 · 约 " + sec + " 秒");
            tvMeta.setTextColor(Color.parseColor("#818CF8"));
            tvMeta.setTextSize(11f);
            LinearLayout.LayoutParams metaLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            metaLp.topMargin = (int) (8 * density);
            tvMeta.setLayoutParams(metaLp);

            card.addView(topRow);
            card.addView(tvPreview);
            card.addView(tvMeta);

            card.setOnClickListener(v -> openDraftInEditor(index));
            draftListContainer.addView(card);
        }
    }

    private void createNewDraft() {
        String title = "文档" + (drafts.size() + 1);
        drafts.add(0, new Draft(UUID.randomUUID().toString(), title, ""));
        saveDrafts();
        openDraftInEditor(0);
    }

    private void openDraftInEditor(int index) {
        currentDraftIndex = index;
        Draft d = drafts.get(index);

        isUpdatingEditorFields = true;
        etDraftTitle.setText(d.title);
        etScript.setText(d.content);
        isUpdatingEditorFields = false;

        updateWordCount();

        layoutDraftList.setVisibility(View.GONE);
        layoutEditor.setVisibility(View.VISIBLE);
    }

    private void backToDraftList() {
        saveCurrentDraftFromEditor();
        renderDraftList();
        layoutEditor.setVisibility(View.GONE);
        layoutDraftList.setVisibility(View.VISIBLE);
    }

    private void saveCurrentDraftFromEditor() {
        if (currentDraftIndex >= 0 && currentDraftIndex < drafts.size()) {
            Draft d = drafts.get(currentDraftIndex);
            d.title = etDraftTitle.getText().toString().trim();
            if (d.title.isEmpty()) d.title = "未命名草稿";
            d.content = etScript.getText().toString();
            saveDrafts();
        }
    }

    // =========================================================================
    // 蓝牙耳机保活与 MediaSession 拦截 (OPPO / ColorOS 深度适配)
    // =========================================================================

    private void startSilentAudioKeepAlive() {
        try {
            if (silentAudioTrack != null) {
                if (silentAudioTrack.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
                    silentAudioTrack.play();
                }
                return;
            }
            int sampleRate = 8000;
            int numSamples = 8000; // 1秒极微底噪 PCM (-90dB，人耳完全听不见，但能绕过 ColorOS 静音检测)
            byte[] pcm = new byte[numSamples * 2];
            for (int i = 0; i < numSamples; i++) {
                short val = (short) ((i % 2 == 0) ? 1 : -1);
                pcm[i * 2] = (byte) (val & 0xff);
                pcm[i * 2 + 1] = (byte) ((val >> 8) & 0xff);
            }

            silentAudioTrack = new AudioTrack(
                    new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build(),
                    new AudioFormat.Builder()
                            .setSampleRate(sampleRate)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build(),
                    pcm.length,
                    AudioTrack.MODE_STATIC,
                    AudioManager.AUDIO_SESSION_ID_GENERATE
            );
            silentAudioTrack.write(pcm, 0, pcm.length);
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

        // 注入标准 MediaMetadata，确保 ColorOS 媒体中心识别本应用为有效播放器
        mediaSession.setMetadata(new MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, "智能提词器")
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, "耳机遥控模式")
                .build());

        updatePlaybackState(PlaybackStateCompat.STATE_PLAYING);

        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override
            public void onPlay() {
                if (isDebounced()) return;
                runOnUiThread(() -> {
                    if (isPrompterActive) {
                        togglePrompterPlayPause("TWS耳机");
                    } else {
                        tvRemoteSignalBadge.setText("🟢 收到耳机指令 (开启提词后生效)");
                    }
                });
            }

            @Override
            public void onPause() {
                if (isDebounced()) return;
                runOnUiThread(() -> {
                    if (isPrompterActive) {
                        togglePrompterPlayPause("TWS耳机");
                    } else {
                        tvRemoteSignalBadge.setText("🟢 收到耳机指令 (开启提词后生效)");
                    }
                });
            }

            @Override
            public void onSkipToNext() {
                runOnUiThread(() -> adjustSpeed(SPEED_STEP, "耳机切歌(下一首)"));
            }

            @Override
            public void onSkipToPrevious() {
                runOnUiThread(() -> adjustSpeed(-SPEED_STEP, "耳机切歌(上一首)"));
            }

            @Override
            public boolean onMediaButtonEvent(Intent mediaButtonEvent) {
                KeyEvent event = mediaButtonEvent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
                if (event != null && event.getAction() == KeyEvent.ACTION_DOWN) {
                    int keyCode = event.getKeyCode();
                    if (keyCode == KeyEvent.KEYCODE_HEADSETHOOK
                            || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                            || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY
                            || keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE) {
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
        if (now - lastMediaKeyTime < 350) {
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
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build())
                    .setAcceptsDelayedFocusGain(true)
                    .setOnAudioFocusChangeListener(focusChange -> {
                        if ((focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT || focusChange == AudioManager.AUDIOFOCUS_LOSS)
                                && isPrompterActive) {
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
                .setState(state, (long) currentScrollY, 1.0f)
                .build();
        mediaSession.setPlaybackState(playbackState);
    }

    private void setupVolumeReceiver() {
        if (audioManager != null) {
            int maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            midVolume = Math.max(1, maxVol / 2);
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
                    adjustSpeed(SPEED_STEP, "耳机音量上滑 ➔ 加速");
                } else {
                    adjustSpeed(-SPEED_STEP, "耳机音量下滑 ➔ 减速");
                }

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
                    adjustSpeed(SPEED_STEP, "音量加 ➔ 加速");
                }
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    adjustSpeed(-SPEED_STEP, "音量减 ➔ 减速");
                }
                return true;
            } else if (keyCode == KeyEvent.KEYCODE_HEADSETHOOK
                    || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
                    || keyCode == KeyEvent.KEYCODE_MEDIA_PLAY
                    || keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE
                    || keyCode == KeyEvent.KEYCODE_SPACE) {
                if (event.getAction() == KeyEvent.ACTION_DOWN && !isDebounced()) {
                    togglePrompterPlayPause("TWS耳机/按键");
                }
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private void setupEventListeners() {
        findViewById(R.id.btnNewDraft).setOnClickListener(v -> createNewDraft());
        findViewById(R.id.btnBackToDrafts).setOnClickListener(v -> backToDraftList());

        TextWatcher draftWatcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (!isUpdatingEditorFields) {
                    updateWordCount();
                    saveCurrentDraftFromEditor();
                }
            }
            @Override public void afterTextChanged(Editable s) {}
        };
        etDraftTitle.addTextChangedListener(draftWatcher);
        etScript.addTextChangedListener(draftWatcher);

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
                saveSettings();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        seekFontSize.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                currentFontSize = Math.max(24, progress);
                tvFontSizeValue.setText(currentFontSize + " sp");
                saveSettings();
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) {}
            @Override public void onStopTrackingTouch(SeekBar seekBar) {}
        });

        cbMirror.setOnCheckedChangeListener((btn, isChecked) -> {
            isMirror = isChecked;
            saveSettings();
        });

        cbFocusLine.setOnCheckedChangeListener((btn, isChecked) -> {
            showFocusLine = isChecked;
            saveSettings();
        });

        findViewById(R.id.btnLaunchPrompter).setOnClickListener(v -> openPrompter());
        findViewById(R.id.btnPrompterExit).setOnClickListener(v -> exitPrompter());
        findViewById(R.id.btnPrompterRestart).setOnClickListener(v -> restartPrompter());
        btnPrompterPlayPause.setOnClickListener(v -> togglePrompterPlayPause("屏幕按钮"));
        findViewById(R.id.btnPrompterSpeedMinus).setOnClickListener(v -> adjustSpeed(-SPEED_STEP, "手动减速"));
        findViewById(R.id.btnPrompterSpeedPlus).setOnClickListener(v -> adjustSpeed(SPEED_STEP, "手动加速"));
        findViewById(R.id.btnPrompterMirror).setOnClickListener(v -> {
            isMirror = !isMirror;
            cbMirror.setChecked(isMirror);
            applyMirror();
            saveSettings();
            showHud("🪞 镜像(水平+180°): " + (isMirror ? "开" : "关"));
            wakeFloatingBar();
        });

        tapGestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (isPrompterActive) {
                    togglePrompterPlayPause("点击屏幕");
                    wakeFloatingBar();
                }
                return true;
            }
        });

        prompterScrollContainer.setOnTouchListener((v, event) -> {
            tapGestureDetector.onTouchEvent(event);

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    swipeAnchorY = event.getRawY();
                    isSwipeAdjustingSpeed = false;
                    break;
                case MotionEvent.ACTION_MOVE:
                    float dy = swipeAnchorY - event.getRawY();
                    if (Math.abs(dy) >= 85f) {
                        isSwipeAdjustingSpeed = true;
                        if (dy > 0) {
                            adjustSpeed(SPEED_STEP, "上滑屏幕 ➔ 加速");
                        } else {
                            adjustSpeed(-SPEED_STEP, "下滑屏幕 ➔ 减速");
                        }
                        swipeAnchorY = event.getRawY();
                        wakeFloatingBar();
                    }
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    wakeFloatingBar();
                    if (isSwipeAdjustingSpeed) {
                        isSwipeAdjustingSpeed = false;
                        return true;
                    }
                    break;
            }
            return true;
        });
    }

    private void updateWordCount() {
        int count = etScript.getText().toString().trim().length();
        int estimatedSec = Math.round((count / 240.0f) * 60);
        tvWordCount.setText(count + " 字 · 约 " + estimatedSec + " 秒");
    }

    private void openPrompter() {
        saveCurrentDraftFromEditor();
        isPrompterActive = true;
        saveSettings();

        startSilentAudioKeepAlive();
        requestExclusiveAudioFocus();
        if (audioManager != null) {
            try {
                savedOriginalVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, midVolume, 0);
            } catch (Exception ignored) {}
        }
        if (mediaSession != null) {
            mediaSession.setActive(true);
            updatePlaybackState(PlaybackStateCompat.STATE_PLAYING);
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
            prompterScrollContainer.setScaleX(-1.0f);
            prompterScrollContainer.setRotation(180f);
        } else {
            prompterScrollContainer.setScaleX(1.0f);
            prompterScrollContainer.setRotation(0f);
        }
    }

    private void startPrompter(String source) {
        if (isPlaying) return;
        isPlaying = true;
        currentScrollY = prompterScrollContainer.getScrollY();
        startSilentAudioKeepAlive();
        updatePlaybackState(PlaybackStateCompat.STATE_PLAYING);
        lastFrameTime = System.nanoTime();
        scrollHandler.post(scrollRunnable);
        btnPrompterPlayPause.setImageResource(android.R.drawable.ic_media_pause);
        showHud("▶ 开始滚动 (" + source + ")");
    }

    private void pausePrompter(String source) {
        if (!isPlaying) return;
        isPlaying = false;
        updatePlaybackState(PlaybackStateCompat.STATE_PLAYING);
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
        saveSettings();
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
        startSilentAudioKeepAlive();
        requestExclusiveAudioFocus();
        if (mediaSession != null) {
            mediaSession.setActive(true);
            updatePlaybackState(PlaybackStateCompat.STATE_PLAYING);
        }
    }

    @Override
    public void onBackPressed() {
        if (isPrompterActive) {
            exitPrompter();
        } else if (layoutEditor.getVisibility() == View.VISIBLE) {
            backToDraftList();
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
