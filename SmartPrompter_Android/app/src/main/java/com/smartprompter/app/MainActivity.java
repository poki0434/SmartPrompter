package com.smartprompter.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.Manifest;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ActivityInfo;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.text.Editable;
import android.text.Layout;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.text.TextWatcher;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

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
    private static final String KEY_ALIGN_LEFT = "key_align_left";
    private static final String KEY_VOICE_FOLLOW = "key_voice_follow";

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
    private CheckBox cbVoiceFollow;
    private TextView tvRemoteSignalBadge;

    // 三级：提词控件
    private View viewFocusLine;
    private TextView tvPrompterText;
    private LinearLayout framePrompterContent;
    private ScrollView prompterScrollContainer;
    private TextView tvPrompterHud;
    private HorizontalScrollView layoutFloatingBar;
    private LinearLayout layoutFloatingBarInner;
    private ImageButton btnPrompterMirror;
    private ImageButton btnPrompterAlign;
    private Button btnPrompterAi;
    private ImageButton btnPrompterPlayPause;
    private TextView tvPrompterSpeedIndicator;

    // 数据与状态
    private final List<Draft> drafts = new ArrayList<>();
    private int currentDraftIndex = -1;
    private boolean isNewUnsavedDraft = false;
    private String originalDraftTitle = "";
    private String originalDraftContent = "";
    private boolean isUpdatingEditorFields = false;

    private int currentSpeed = 45;
    private int currentFontSize = 48;
    private boolean isMirror = false;
    private boolean showFocusLine = true;
    private boolean isAlignLeft = false;
    private boolean isVoiceFollow = false;
    private int highlightedIndex = 0;
    private String currentPrompterScript = "";
    private VoiceFollower voiceFollower;
    private boolean isPlaying = false;
    private boolean isPrompterActive = false;
    private boolean isLandscape = true;

    private float currentScrollY = 0f;
    private long lastFrameTime = 0;

    // 蓝牙耳机 MediaSession 与极微底噪保活音轨
    private MediaSessionCompat mediaSession;
    private AudioManager audioManager;
    private AudioFocusRequest audioFocusRequest;
    private VolumeChangeReceiver volumeReceiver;
    private int midVolume = 7;
    private int savedOriginalVolume = -1;
    private float savedOriginalBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
    private long lastMediaKeyTime = 0;

    private GestureDetector tapGestureDetector;
    private float lastTouchY = 0f;

    private final Handler hudHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);

        initViews();
        loadSavedPreferences();
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
        cbVoiceFollow = findViewById(R.id.cbVoiceFollow);
        tvRemoteSignalBadge = findViewById(R.id.tvRemoteSignalBadge);

        viewFocusLine = findViewById(R.id.viewFocusLine);
        tvPrompterText = findViewById(R.id.tvPrompterText);
        framePrompterContent = findViewById(R.id.framePrompterContent);
        prompterScrollContainer = findViewById(R.id.prompterScrollContainer);
        tvPrompterHud = findViewById(R.id.tvPrompterHud);
        layoutFloatingBar = findViewById(R.id.layoutFloatingBar);
        layoutFloatingBarInner = findViewById(R.id.layoutFloatingBarInner);
        btnPrompterMirror = findViewById(R.id.btnPrompterMirror);
        btnPrompterAlign = findViewById(R.id.btnPrompterAlign);
        btnPrompterAi = findViewById(R.id.btnPrompterAi);
        btnPrompterPlayPause = findViewById(R.id.btnPrompterPlayPause);
        tvPrompterSpeedIndicator = findViewById(R.id.tvPrompterSpeedIndicator);
        framePrompterContent.setLayerType(View.LAYER_TYPE_HARDWARE, null);
    }

    private void loadSavedPreferences() {
        SharedPreferences sp = getSharedPreferences(PREF_NAME, MODE_PRIVATE);
        currentSpeed = sp.getInt(KEY_SPEED, 45);
        currentFontSize = sp.getInt(KEY_FONT_SIZE, 48);
        isMirror = sp.getBoolean(KEY_MIRROR, false);
        showFocusLine = sp.getBoolean(KEY_FOCUS_LINE, true);
        isAlignLeft = sp.getBoolean(KEY_ALIGN_LEFT, false);
        isVoiceFollow = sp.getBoolean(KEY_VOICE_FOLLOW, false);

        seekSpeed.setProgress(currentSpeed);
        tvSpeedValue.setText(currentSpeed + " px/s");
        tvPrompterSpeedIndicator.setText(String.valueOf(currentSpeed));

        seekFontSize.setProgress(currentFontSize);
        tvFontSizeValue.setText(currentFontSize + " sp");

        cbMirror.setChecked(isMirror);
        cbFocusLine.setChecked(showFocusLine);
        cbVoiceFollow.setChecked(isVoiceFollow);

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
                .putBoolean(KEY_ALIGN_LEFT, isAlignLeft)
                .putBoolean(KEY_VOICE_FOLLOW, isVoiceFollow)
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

    private String getTodayDateTitle() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
    }

    private void createNewDraft() {
        isNewUnsavedDraft = true;
        currentDraftIndex = -1;
        originalDraftTitle = getTodayDateTitle();
        originalDraftContent = "";

        isUpdatingEditorFields = true;
        etDraftTitle.setText(originalDraftTitle);
        etScript.setText("");
        isUpdatingEditorFields = false;

        updateWordCount();
        layoutDraftList.setVisibility(View.GONE);
        layoutEditor.setVisibility(View.VISIBLE);
    }

    private void openDraftInEditor(int index) {
        isNewUnsavedDraft = false;
        currentDraftIndex = index;
        Draft d = drafts.get(index);
        originalDraftTitle = d.title;
        originalDraftContent = d.content;

        isUpdatingEditorFields = true;
        etDraftTitle.setText(d.title);
        etScript.setText(d.content);
        isUpdatingEditorFields = false;

        updateWordCount();

        layoutDraftList.setVisibility(View.GONE);
        layoutEditor.setVisibility(View.VISIBLE);
    }

    private void backToDraftList() {
        String currentTitle = etDraftTitle.getText().toString().trim();
        String currentContent = etScript.getText().toString();
        boolean hasText = !currentContent.trim().isEmpty();
        boolean isModified = isNewUnsavedDraft
                || !currentTitle.equals(originalDraftTitle)
                || !currentContent.equals(originalDraftContent);

        if (hasText && isModified) {
            new AlertDialog.Builder(this)
                    .setTitle("保存草稿")
                    .setMessage("内容栏中有未保存的文字，是否保存到草稿列表？")
                    .setPositiveButton("保存", (dialog, which) -> {
                            finishBackToDraftList();
                    })
                    .setNegativeButton("不保存", (dialog, which) -> finishBackToDraftList())
                    .setNeutralButton("取消", null)
                    .show();
        } else {
            finishBackToDraftList();
        }
    }

    private void finishBackToDraftList() {
        renderDraftList();
        layoutEditor.setVisibility(View.GONE);
        layoutDraftList.setVisibility(View.VISIBLE);
    }

    private void saveCurrentDraftFromEditor() {
        String title = etDraftTitle.getText().toString().trim();
        if (title.isEmpty()) title = getTodayDateTitle();
        String content = etScript.getText().toString();

        if (isNewUnsavedDraft) {
            drafts.add(0, new Draft(UUID.randomUUID().toString(), title, content));
            currentDraftIndex = 0;
            isNewUnsavedDraft = false;
        } else if (currentDraftIndex >= 0 && currentDraftIndex < drafts.size()) {
            Draft d = drafts.get(currentDraftIndex);
            d.title = title;
            d.content = content;
        }
        originalDraftTitle = title;
        originalDraftContent = content;
        saveDrafts();
    }

    // =========================================================================
    // 蓝牙耳机保活与 MediaSession 拦截 (OPPO / ColorOS 深度适配)
    // =========================================================================

    private void setupMediaSessionAndAudioFocus() {
        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        requestExclusiveAudioFocus();

        mediaSession = new MediaSessionCompat(this, "PrompterMediaSession");
        mediaSession.setFlags(MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS
                | MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS);

        updatePlaybackState(PlaybackStateCompat.STATE_PAUSED);

        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override
            public void onPlay() {
                if (isDebounced()) return;
                runOnUiThread(() -> {
                    if (isPrompterActive) {
                        togglePrompterPlayPause("TWS耳机");
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
                        togglePrompterPlayPause("TWS耳机");
                    } else {
                        tvRemoteSignalBadge.setText("🟢 收到耳机暂停指令 (开启提词后生效)");
                    }
                });
            }

            @Override
            public void onStop() {
                if (isDebounced()) return;
                runOnUiThread(() -> {
                    if (isPrompterActive) {
                        togglePrompterPlayPause("TWS耳机");
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
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
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
                .setState(state, 0, 1.0f)
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
                tvPrompterSpeedIndicator.setText(String.valueOf(currentSpeed));
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

        cbVoiceFollow.setOnCheckedChangeListener((btn, isChecked) -> {
            isVoiceFollow = isChecked;
            saveSettings();
            if (isChecked) {
                ensureMicPermissionAndInitVoice();
            }
        });

        findViewById(R.id.btnLaunchPrompter).setOnClickListener(v -> openPrompter());
        findViewById(R.id.btnPrompterExit).setOnClickListener(v -> exitPrompter());
        findViewById(R.id.btnPrompterRestart).setOnClickListener(v -> restartPrompter());
        btnPrompterPlayPause.setOnClickListener(v -> togglePrompterPlayPause("屏幕按钮"));
        findViewById(R.id.btnPrompterSpeedMinus).setOnClickListener(v -> adjustSpeed(-SPEED_STEP, "手动减速"));
        findViewById(R.id.btnPrompterSpeedPlus).setOnClickListener(v -> adjustSpeed(SPEED_STEP, "手动加速"));
        btnPrompterMirror.setOnClickListener(v -> {
            isMirror = !isMirror;
            cbMirror.setChecked(isMirror);
            applyMirror();
        applyAlignment();
        updateAiButtonStyle();
            saveSettings();
            showHud("🪞 镜像(水平+180°): " + (isMirror ? "开" : "关"));
            wakeFloatingBar();
        });

        btnPrompterAlign.setOnClickListener(v -> {
            isAlignLeft = !isAlignLeft;
            applyAlignment();
            saveSettings();
            showHud(isAlignLeft ? "📐 已切换为左对齐" : "📐 已切换为居中对齐");
            wakeFloatingBar();
        });

        btnPrompterAi.setOnClickListener(v -> {
            isVoiceFollow = !isVoiceFollow;
            cbVoiceFollow.setChecked(isVoiceFollow);
            saveSettings();
            updateAiButtonStyle();
            if (isVoiceFollow) {
                pausePrompter("切换AI跟读");
                ensureMicPermissionAndInitVoice();
                showHud("🎤 AI跟读: 开");
            } else {
                if (voiceFollower != null) voiceFollower.stopListening();
                showHud("🎤 AI跟读: 关");
            }
            wakeFloatingBar();
        });

        findViewById(R.id.btnPrompterOrientation).setOnClickListener(v -> {
            isLandscape = !isLandscape;
            if (isLandscape) {
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
                showHud("📱 已切换为横屏");
            } else {
                setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                showHud("📱 已切换为竖屏");
            }
            updateFloatingBarSpacing();
            wakeFloatingBar();
        });

        findViewById(R.id.btnPrompterFontMinus).setOnClickListener(v -> {
            adjustFontSize(-4);
            wakeFloatingBar();
        });
        findViewById(R.id.btnPrompterFontPlus).setOnClickListener(v -> {
            adjustFontSize(4);
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
                    lastTouchY = event.getRawY();
                    break;
                case MotionEvent.ACTION_MOVE:
                    float rawY = event.getRawY();
                    float dy = isMirror ? (rawY - lastTouchY) : (lastTouchY - rawY);
                    lastTouchY = rawY;
                    int maxScroll = Math.max(0, framePrompterContent.getHeight() - prompterScrollContainer.getHeight());
                    currentScrollY = Math.max(0f, Math.min(maxScroll, currentScrollY + dy));
                    framePrompterContent.setTranslationY(-currentScrollY);
                    wakeFloatingBar();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    wakeFloatingBar();
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

        isLandscape = true;
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        updateFloatingBarSpacing();

        layoutEditor.setVisibility(View.GONE);
        layoutPrompter.setVisibility(View.VISIBLE);

        // 自动将屏幕亮度调至最高 (100%)
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        savedOriginalBrightness = lp.screenBrightness;
        lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL;
        getWindow().setAttributes(lp);

        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        currentPrompterScript = etScript.getText().toString();
        highlightedIndex = 0;
        renderKtvText();
        tvPrompterText.setTextSize(currentFontSize);

        applyMirror();
        viewFocusLine.setVisibility(showFocusLine ? View.VISIBLE : View.GONE);

        currentScrollY = 0f;
        framePrompterContent.setTranslationY(0f);
        prompterScrollContainer.scrollTo(0, 0);

        wakeFloatingBar();
        if (isVoiceFollow) {
            ensureMicPermissionAndInitVoice();
            showHud("🎤 AI 严格跟读模式已开启");
        } else {
            startPrompter("开启提词");
        }
    }

    private void exitPrompter() {
        pausePrompter("退出提词");
        if (voiceFollower != null) voiceFollower.stopListening();
        isPrompterActive = false;

        if (audioManager != null && savedOriginalVolume != -1) {
            try {
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, savedOriginalVolume, 0);
                savedOriginalVolume = -1;
            } catch (Exception ignored) {}
        }

        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);

        layoutPrompter.setVisibility(View.GONE);
        layoutEditor.setVisibility(View.VISIBLE);

        // 恢复进入提词前的屏幕亮度设置
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = savedOriginalBrightness;
        getWindow().setAttributes(lp);

        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
    }

    private void applyMirror() {
        if (isMirror) {
            prompterScrollContainer.setScaleX(-1.0f);
            prompterScrollContainer.setRotation(180f);
            btnPrompterMirror.setColorFilter(Color.parseColor("#818CF8"));
        } else {
            prompterScrollContainer.setScaleX(1.0f);
            prompterScrollContainer.setRotation(0f);
            btnPrompterMirror.setColorFilter(Color.parseColor("#CBD5E1"));
        }
    }

    private void updateAiButtonStyle() {
        if (btnPrompterAi == null) return;
        btnPrompterAi.setTextColor(Color.parseColor(isVoiceFollow ? "#38BDF8" : "#CBD5E1"));
    }

    private void applyAlignment() {
        if (isAlignLeft) {
            tvPrompterText.setGravity(Gravity.START);
            btnPrompterAlign.setImageResource(R.drawable.ic_align_left);
            btnPrompterAlign.setColorFilter(Color.parseColor("#818CF8"));
        } else {
            tvPrompterText.setGravity(Gravity.CENTER_HORIZONTAL);
            btnPrompterAlign.setImageResource(R.drawable.ic_align_center);
            btnPrompterAlign.setColorFilter(Color.parseColor("#CBD5E1"));
        }
    }

    private void startPrompter(String source) {
        if (isPlaying) return;
        isPlaying = true;
        updatePlaybackState(PlaybackStateCompat.STATE_PLAYING);
        lastFrameTime = 0L;
        Choreographer.getInstance().removeFrameCallback(frameCallback);
        if (voiceFollower != null) voiceFollower.release();
        Choreographer.getInstance().postFrameCallback(frameCallback);
        btnPrompterPlayPause.setImageResource(android.R.drawable.ic_media_pause);
        showHud("▶ 开始滚动 (" + source + ")");
    }

    private void pausePrompter(String source) {
        if (!isPlaying) return;
        isPlaying = false;
        updatePlaybackState(PlaybackStateCompat.STATE_PLAYING);
        Choreographer.getInstance().removeFrameCallback(frameCallback);
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
        highlightedIndex = 0;
        renderKtvText();
        currentScrollY = 0f;
        framePrompterContent.setTranslationY(0f);
        prompterScrollContainer.scrollTo(0, 0);
        showHud("⏮ 回到文稿开头");
    }

    private void ensureMicPermissionAndInitVoice() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO}, 200);
            return;
        }
        initAndStartVoiceFollower();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 200) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                initAndStartVoiceFollower();
            } else {
                isVoiceFollow = false;
                cbVoiceFollow.setChecked(false);
                saveSettings();
                Toast.makeText(this, "需要麦克风权限才能使用 AI 跟读", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void initAndStartVoiceFollower() {
        if (voiceFollower == null) {
            voiceFollower = new VoiceFollower(this, new VoiceFollower.Listener() {
                @Override
                public void onModelReady() {
                    if (isPrompterActive && isVoiceFollow) {
                        voiceFollower.startListening();
                        showHud("🎤 语音模型就绪，请开始朗读");
                    }
                }

                @Override
                public void onNewSpokenText(String deltaText) {
                    if (isPrompterActive && isVoiceFollow) {
                        handleStrictSpokenMatch(deltaText);
                    }
                }

                @Override
                public void onError(String msg) {
                    showHud("⚠️ " + msg);
                }
            });
            voiceFollower.initModelAsync();
        } else if (isPrompterActive && isVoiceFollow) {
            voiceFollower.startListening();
        }
    }

    /**
     * 严格跟读匹配：读对推进并 KTV 染色滚动；如果台词是 1 读成了 2，原地停住不滚
     */
    private void handleStrictSpokenMatch(String deltaText) {
        if (currentPrompterScript.isEmpty() || highlightedIndex >= currentPrompterScript.length()) return;
        boolean advanced = false;

        for (int i = 0; i < deltaText.length(); i++) {
            char spoken = normalizeChar(deltaText.charAt(i));
            if (isIgnorableChar(spoken)) continue;

            // 跳过标点与换行找到当前等待朗读的字
            int expectedIdx = skipPunctuation(highlightedIndex);
            if (expectedIdx >= currentPrompterScript.length()) break;

            char expected1 = normalizeChar(currentPrompterScript.charAt(expectedIdx));
            if (spoken == expected1) {
                highlightedIndex = skipPunctuation(expectedIdx + 1);
                advanced = true;
            } else {
                // 仅允许最多 1 个字的语音识别轻微漏字，绝不跳过整段或错词 (1 读成 2 不会匹配)
                int nextIdx = skipPunctuation(expectedIdx + 1);
                if (nextIdx < currentPrompterScript.length()) {
                    char expected2 = normalizeChar(currentPrompterScript.charAt(nextIdx));
                    if (spoken == expected2) {
                        highlightedIndex = skipPunctuation(nextIdx + 1);
                        advanced = true;
                    }
                }
            }
        }

        if (advanced) {
            renderKtvText();
            scrollToHighlightedLine();
        }
    }

    private int skipPunctuation(int idx) {
        while (idx < currentPrompterScript.length() && isIgnorableChar(currentPrompterScript.charAt(idx))) {
            idx++;
        }
        return idx;
    }

    private boolean isIgnorableChar(char c) {
        return !Character.isLetterOrDigit(c);
    }

    private char normalizeChar(char c) {
        c = Character.toLowerCase(c);
        switch (c) {
            case '0': return '零';
            case '1': return '一';
            case '2': return '二';
            case '3': return '三';
            case '4': return '四';
            case '5': return '五';
            case '6': return '六';
            case '7': return '七';
            case '8': return '八';
            case '9': return '九';
            default: return c;
        }
    }

    private void renderKtvText() {
        if (currentPrompterScript == null) currentPrompterScript = "";
        SpannableString ss = new SpannableString(currentPrompterScript);
        int end = Math.min(highlightedIndex, currentPrompterScript.length());
        if (end > 0) {
            ss.setSpan(new ForegroundColorSpan(Color.parseColor("#FACC15")),
                    0, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        tvPrompterText.setText(ss);
    }

    private void scrollToHighlightedLine() {
        Layout layout = tvPrompterText.getLayout();
        if (layout == null) return;
        int offset = Math.min(highlightedIndex, currentPrompterScript.length());
        int line = layout.getLineForOffset(offset);
        int lineTop = layout.getLineTop(line);
        int maxScroll = Math.max(0, framePrompterContent.getHeight() - prompterScrollContainer.getHeight());
        currentScrollY = Math.max(0f, Math.min(maxScroll, lineTop));
        framePrompterContent.animate().translationY(-currentScrollY).setDuration(180).start();
    }

    private void adjustFontSize(int delta) {
        currentFontSize = Math.max(24, Math.min(90, currentFontSize + delta));
        seekFontSize.setProgress(currentFontSize);
        tvFontSizeValue.setText(currentFontSize + " sp");
        tvPrompterText.setTextSize(currentFontSize);
        showHud("🔤 字号: " + currentFontSize + " sp");
        saveSettings();
    }

    private void adjustSpeed(int delta, String trigger) {
        currentSpeed = Math.max(10, Math.min(200, currentSpeed + delta));
        seekSpeed.setProgress(currentSpeed);
        tvSpeedValue.setText(currentSpeed + " px/s");
        tvPrompterSpeedIndicator.setText(String.valueOf(currentSpeed));
        showHud("⚡ 速度: " + currentSpeed + " px/s (" + trigger + ")");
        saveSettings();
    }

    private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!isPlaying) return;
            if (lastFrameTime == 0) {
                lastFrameTime = frameTimeNanos;
            }
            float deltaSeconds = (frameTimeNanos - lastFrameTime) / 1_000_000_000.0f;
            lastFrameTime = frameTimeNanos;

            // 防异常跳帧保护
            if (deltaSeconds > 0f && deltaSeconds < 0.1f) {
                currentScrollY += currentSpeed * deltaSeconds;
                // 使用 GPU 亚像素浮点位移 (setTranslationY) 替代整数 scrollTo，彻底消除掉帧与抖动
                framePrompterContent.setTranslationY(-currentScrollY);
            }

            int maxScroll = Math.max(0, framePrompterContent.getHeight() - prompterScrollContainer.getHeight());
            if (maxScroll > 0 && currentScrollY >= maxScroll) {
                pausePrompter("台词结束");
                return;
            }

            Choreographer.getInstance().postFrameCallback(this);
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

    private void updateFloatingBarSpacing() {
        if (layoutFloatingBarInner == null) return;
        float density = getResources().getDisplayMetrics().density;
        int gapPx = isLandscape ? (int) (8 * density) : 0;
        int padH = isLandscape ? (int) (14 * density) : (int) (6 * density);
        int padV = isLandscape ? (int) (6 * density) : (int) (4 * density);
        layoutFloatingBarInner.setPadding(padH, padV, padH, padV);

        for (int i = 0; i < layoutFloatingBarInner.getChildCount(); i++) {
            View child = layoutFloatingBarInner.getChildAt(i);
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) child.getLayoutParams();
            lp.setMarginStart(i == 0 ? 0 : gapPx);
            child.setLayoutParams(lp);
        }
    }

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
        if (mediaSession != null) {
            mediaSession.release();
        }
        if (volumeReceiver != null) {
            unregisterReceiver(volumeReceiver);
        }
        if (audioManager != null && audioFocusRequest != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioManager.abandonAudioFocusRequest(audioFocusRequest);
        }
        Choreographer.getInstance().removeFrameCallback(frameCallback);
    }
}
