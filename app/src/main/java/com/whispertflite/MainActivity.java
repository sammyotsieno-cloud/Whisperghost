package com.whispertflite;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.preference.PreferenceManager;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.util.Pair;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.EditText;
import androidx.activity.OnBackPressedCallback;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.github.houbb.opencc4j.util.ZhConverterUtil;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.whispertflite.asr.Recorder;
import com.whispertflite.asr.Whisper;
import com.whispertflite.asr.WhisperResult;
import com.whispertflite.utils.HapticFeedback;
import com.whispertflite.utils.InputLang;
import com.whispertflite.utils.LanguagePairAdapter;
import com.whispertflite.utils.ThemeUtils;

import org.woheller69.freeDroidWarn.FreeDroidWarn;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {
    private Context mContext;
    private static final String TAG = "MainActivity";

    // whisper-small.tflite works well for multi-lingual
    public static final String MULTI_LINGUAL_EU_MODEL_FAST = "whisper-base.EUROPEAN_UNION.tflite";
    public static final String MULTI_LINGUAL_TOP_WORLD_FAST = "whisper-base.TOP_WORLD.tflite";
    public static final String MULTI_LINGUAL_TOP_WORLD_SLOW = "whisper-small.TOP_WORLD.tflite";
    public static final String MULTI_LINGUAL_MODEL_FAST = "whisper-base.tflite";
    public static final String MULTI_LINGUAL_MODEL_SLOW = "whisper-small.tflite";
    public static final String ENGLISH_ONLY_MODEL = "whisper-tiny.en.tflite";
    // English only model ends with extension ".en.tflite"
    public static final String ENGLISH_ONLY_MODEL_EXTENSION = ".en.tflite";
    public static final String ENGLISH_ONLY_VOCAB_FILE = "filters_vocab_en.bin";
    public static final String MULTILINGUAL_VOCAB_FILE = "filters_vocab_multilingual.bin";


    private TextView tvStatus;
    private EditText tvResult;
    private FloatingActionButton fabCopy;
    private ImageButton btnRecord;
    private LinearLayout layoutModeChinese;
    private LinearLayout layoutTTS;
    private CheckBox append;
    private CheckBox translate;
    private CheckBox modeSimpleChinese;
    private CheckBox modeTTS;
    private ProgressBar processingBar;
    private ImageButton btnInfo;

    private Recorder mRecorder = null;
    private Whisper mWhisper = null;

    private File sdcardDataFolder = null;
    private File selectedTfliteFile = null;
    private SharedPreferences sp = null;
    private Spinner spinnerTflite;
    private CountDownTimer countDownTimer;
    private Spinner spinnerLanguage;
    private int langToken = -1;
    private long startTime = 0;
    private TextToSpeech tts;

    private final androidx.activity.result.ActivityResultLauncher<String> pickAudio =
            registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.GetContent(), uri -> {
                if (uri != null) {
                    setupPlayer(uri);
                    transcribeFileCpp(uri);
                }
            });

    @Override
    protected void onDestroy() {
        try { com.whispertflite.cpp.WhisperCpp.requestCancel(); } catch (Throwable ignored) {}
        uiHandler.removeCallbacksAndMessages(null);
        if (player != null) { player.release(); player = null; }
        deinitModel();
        deinitTTS();
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        if (player != null && player.isPlaying()) player.pause();
        stopProcessing();
        super.onPause();
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mContext = this;
        setContentView(R.layout.activity_main);
        ThemeUtils.setStatusBarAppearance(this);
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        checkInputMethodEnabled();
        processingBar = findViewById(R.id.processing_bar);
        findViewById(R.id.btnPickFile).setOnClickListener(v -> pickAudio.launch("audio/*"));
        setupCppUi();
        findViewById(R.id.btnSaveSrt).setOnClickListener(v -> saveSrtFile());
        findViewById(R.id.tvResult).setOnTouchListener(this::onTranscriptTouch);
        findViewById(R.id.btnShare).setOnClickListener(v -> shareText());
        findViewById(R.id.btnSaveTxt).setOnClickListener(v -> saveTxtFile());
        sp = PreferenceManager.getDefaultSharedPreferences(this);
        append = findViewById(R.id.mode_append);

        layoutTTS = findViewById(R.id.layout_tts);
        modeTTS = findViewById(R.id.mode_tts);
        modeTTS.setOnCheckedChangeListener((compoundButton, isChecked) -> {
            if (isChecked) {
                tts = new TextToSpeech(mContext, status -> {
                    if (status == TextToSpeech.SUCCESS) {
                        int result = tts.setLanguage(Locale.US);
                        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                            runOnUiThread(() -> {
                                Toast.makeText(mContext, mContext.getString(R.string.tts_language_not_supported),Toast.LENGTH_SHORT).show();
                                modeTTS.setChecked(false);
                            });

                        }
                    } else {
                        runOnUiThread(() -> Toast.makeText(mContext, mContext.getString(R.string.tts_initialization_failed),Toast.LENGTH_SHORT).show());
                    }
                });
            } else {
                deinitTTS();
            }
        });

        translate = findViewById(R.id.mode_translate);
        translate.setOnCheckedChangeListener((compoundButton, isChecked) -> {
            layoutTTS.setVisibility(isChecked ? View.VISIBLE:View.GONE);
            if (layoutTTS.getVisibility() == View.GONE) modeTTS.setChecked(false);
        });

        // Call the method to copy specific file types from assets to data folder
        sdcardDataFolder = this.getExternalFilesDir(null);

        ArrayList<File> tfliteFiles = getFilesWithExtension(sdcardDataFolder, ".tflite");

        // Initialize default model to use
        initModel();

        btnInfo = findViewById(R.id.btnInfo);
        btnInfo.setOnClickListener(view -> startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/woheller69/whisperIME#Donate"))));

        spinnerLanguage = findViewById(R.id.spnrLanguage);
        List<Pair<String, String>> languagePairs = LanguagePairAdapter.getLanguagePairs(this);
        LanguagePairAdapter languagePairAdapter = new LanguagePairAdapter(this, android.R.layout.simple_spinner_item, languagePairs);
        languagePairAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerLanguage.setAdapter(languagePairAdapter);

        spinnerLanguage.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
                langToken = InputLang.getIdForLanguage(InputLang.getLangList(),languagePairs.get(i).first);
                SharedPreferences.Editor editor = sp.edit();
                editor.putString("language",languagePairs.get(i).first);
                editor.apply();
            }

            @Override
            public void onNothingSelected(AdapterView<?> adapterView) {

            }
        });

        selectedTfliteFile = new File(sdcardDataFolder, sp.getString("modelName", MULTI_LINGUAL_TOP_WORLD_SLOW));
        ArrayAdapter<File> tfliteAdapter = getFileArrayAdapter(tfliteFiles);
        int position = tfliteAdapter.getPosition(selectedTfliteFile);
        spinnerTflite = findViewById(R.id.spnrTfliteFiles);
        spinnerTflite.setAdapter(tfliteAdapter);
        spinnerTflite.setSelection(position,false);
        if (selectedTfliteFile.getName().equals(MULTI_LINGUAL_EU_MODEL_FAST) || selectedTfliteFile.getName().equals(MULTI_LINGUAL_TOP_WORLD_FAST) || selectedTfliteFile.getName().equals(MULTI_LINGUAL_TOP_WORLD_SLOW)){
            spinnerLanguage.setEnabled(true);
            String langCode = sp.getString("language", "auto");
            spinnerLanguage.setSelection(languagePairAdapter.getIndexByCode(langCode));
        } else {
            spinnerLanguage.setSelection(0);
            spinnerLanguage.setEnabled(false);
        }
        spinnerTflite.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                deinitModel();
                selectedTfliteFile = (File) parent.getItemAtPosition(position);
                SharedPreferences.Editor editor = sp.edit();
                editor.putString("modelName",selectedTfliteFile.getName());
                editor.apply();
                initModel();
                if (selectedTfliteFile.getName().equals(MULTI_LINGUAL_EU_MODEL_FAST) || selectedTfliteFile.getName().equals(MULTI_LINGUAL_TOP_WORLD_FAST) || selectedTfliteFile.getName().equals(MULTI_LINGUAL_TOP_WORLD_SLOW)){
                    spinnerLanguage.setEnabled(true);
                    String langCode = sp.getString("language", "auto");
                    spinnerLanguage.setSelection(languagePairAdapter.getIndexByCode(langCode));
                } else {
                    spinnerLanguage.setSelection(0);
                    spinnerLanguage.setEnabled(false);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // Handle case when nothing is selected, if needed
            }
        });


        // Implementation of record button functionality
        btnRecord = findViewById(R.id.btnRecord);

        btnRecord.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                // Pressed
                runOnUiThread(() -> btnRecord.setBackgroundResource(R.drawable.rounded_button_background_pressed));
                Log.d(TAG, "Start recording...");
                if (!mWhisper.isInProgress()) {
                    HapticFeedback.vibrate(this);
                    startRecording();
                    runOnUiThread(() -> processingBar.setProgress(100));
                    countDownTimer = new CountDownTimer(30000, 1000) {
                        @Override
                        public void onTick(long l) {
                            runOnUiThread(() -> processingBar.setProgress((int) (l / 300)));
                        }
                        @Override
                        public void onFinish() {}
                    };
                    countDownTimer.start();
                } else (Toast.makeText(this,getString(R.string.please_wait),Toast.LENGTH_SHORT)).show();

            } else if (event.getAction() == MotionEvent.ACTION_UP) {
                // Released
                runOnUiThread(() -> btnRecord.setBackgroundResource(R.drawable.rounded_button_background));
                if (mRecorder != null && mRecorder.isInProgress()) {
                    Log.d(TAG, "Recording is in progress... stopping...");
                    stopRecording();
                }
            }
            return true;
        });

        layoutModeChinese = findViewById(R.id.layout_mode_chinese);
        modeSimpleChinese = findViewById(R.id.mode_simple_chinese);
        modeSimpleChinese.setChecked(sp.getBoolean("simpleChinese",false));  //default to traditional Chinese
        modeSimpleChinese.setOnCheckedChangeListener((compoundButton, isChecked) -> {
            SharedPreferences.Editor editor = sp.edit();
            editor.putBoolean("simpleChinese", isChecked);
            editor.apply();
            tvResult.setText("");
        });

        tvStatus = findViewById(R.id.tvStatus);
        tvStatus.setText("Build " + BuildConfig.VERSION_NAME);
        tvResult = findViewById(R.id.tvResult);
        setupUi2();
        tvResult.setOnClickListener(view -> tvResult.setCursorVisible(true));
        getOnBackPressedDispatcher().addCallback(new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (tvResult.isCursorVisible()) tvResult.setCursorVisible(false);
                else finish();
            }
        });
        fabCopy = findViewById(R.id.fabCopy);
        fabCopy.setOnClickListener(v -> {
            // Get the text from tvResult
            String textToCopy = tvResult.getText().toString().trim();

            // Copy the text to the clipboard
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText(getString(R.string.model_output), textToCopy);
            clipboard.setPrimaryClip(clip);
        });

        // Audio recording functionality
        mRecorder = new Recorder(this);
        mRecorder.setListener(new Recorder.RecorderListener() {
            @Override
            public void onUpdateReceived(String message) {
                Log.d(TAG, "Update is received, Message: " + message);
                if (message.equals(Recorder.MSG_RECORDING)) {
                    runOnUiThread(() -> tvStatus.setText(getString(R.string.record_button) +"…"));
                    if (!append.isChecked()) runOnUiThread(() -> tvResult.setText(""));
                    runOnUiThread(() -> btnRecord.setBackgroundResource(R.drawable.rounded_button_background_pressed));
                } else if (message.equals(Recorder.MSG_RECORDING_DONE)) {
                    HapticFeedback.vibrate(mContext);
                    runOnUiThread(() -> btnRecord.setBackgroundResource(R.drawable.rounded_button_background));

                    if (translate.isChecked()) startProcessing(Whisper.ACTION_TRANSLATE);
                    else startProcessing(Whisper.ACTION_TRANSCRIBE);
                } else if (message.equals(Recorder.MSG_RECORDING_ERROR)) {
                    HapticFeedback.vibrate(mContext);
                    if (countDownTimer!=null) { countDownTimer.cancel();}
                    runOnUiThread(() -> {
                        btnRecord.setBackgroundResource(R.drawable.rounded_button_background);
                        processingBar.setProgress(0);
                        tvStatus.setText(getString(R.string.error_no_input));
                    });
                }
            }

        });
        FreeDroidWarn.showWarningOnUpgrade(this, BuildConfig.VERSION_CODE);
        if (GithubStar.shouldShowStarDialog(this)) GithubStar.starDialog(this, "https://github.com/woheller69/whisperIME");
        // Assume this Activity is the current activity, check record permission
        checkPermissions();

    }

    private void checkInputMethodEnabled() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        List<InputMethodInfo> enabledInputMethodList = imm.getEnabledInputMethodList();

        String myInputMethodId = getPackageName() + "/" + WhisperInputMethodService.class.getName();
        boolean inputMethodEnabled = false;
        for (InputMethodInfo imi : enabledInputMethodList) {
            if (imi.getId().equals(myInputMethodId)) {
                inputMethodEnabled = true;
                break;
            }
        }
        if (!inputMethodEnabled) {
            Intent intent = new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS);
            startActivity(intent);
        }
    }

    // Model initialization
    private void initModel() {
        File modelFile = new File(sdcardDataFolder, sp.getString("modelName", MULTI_LINGUAL_TOP_WORLD_SLOW));
        boolean isMultilingualModel = !(modelFile.getName().endsWith(ENGLISH_ONLY_MODEL_EXTENSION));
        String vocabFileName = isMultilingualModel ? MULTILINGUAL_VOCAB_FILE : ENGLISH_ONLY_VOCAB_FILE;
        File vocabFile = new File(sdcardDataFolder, vocabFileName);

        mWhisper = new Whisper(this);
        mWhisper.loadModel(modelFile, vocabFile, isMultilingualModel);
        Log.d(TAG, "Initialized: " + modelFile.getName());
        mWhisper.setListener(new Whisper.WhisperListener() {
            @Override
            public void onUpdateReceived(String message) {
                Log.d(TAG, "Update is received, Message: " + message);
                if (message.contains("not initialized") || message.contains("failed") || message.contains("Failed")) runOnUiThread(() -> Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show());

                if (message.equals(Whisper.MSG_PROCESSING)) {
                    runOnUiThread(() -> tvStatus.setText(getString(R.string.processing)));
                    startTime = System.currentTimeMillis();
                    runOnUiThread(() -> spinnerTflite.setEnabled(false));
                }
            }

            @Override
            public void onResultReceived(WhisperResult whisperResult) {
                long timeTaken = System.currentTimeMillis() - startTime;
                runOnUiThread(() -> tvStatus.setText(getString(R.string.processing_done) + timeTaken + "\u2009ms" + "\n"+ getString(R.string.language) + " " + new Locale(whisperResult.getLanguage()).getDisplayLanguage() + " " + (whisperResult.getTask() == Whisper.Action.TRANSCRIBE ? getString(R.string.mode_transcription) : getString(R.string.mode_translation))));
                runOnUiThread(() -> processingBar.setIndeterminate(false));
                Log.d(TAG, "Result: " + whisperResult.getResult() + " " + whisperResult.getLanguage() + " " + (whisperResult.getTask() == Whisper.Action.TRANSCRIBE ? "transcribing" : "translating"));
                if ((whisperResult.getLanguage().equals("zh")) && (whisperResult.getTask() == Whisper.Action.TRANSCRIBE)){
                    runOnUiThread(() -> layoutModeChinese.setVisibility(View.VISIBLE));
                    boolean simpleChinese = sp.getBoolean("simpleChinese",false);  //convert to desired Chinese mode
                    String result = simpleChinese ? ZhConverterUtil.toSimple(whisperResult.getResult()) : ZhConverterUtil.toTraditional(whisperResult.getResult());
                    runOnUiThread(() -> tvResult.append(result));
                } else {
                    runOnUiThread(() -> layoutModeChinese.setVisibility(View.GONE));
                    runOnUiThread(() -> tvResult.append(whisperResult.getResult()));
                }
                runOnUiThread(() -> spinnerTflite.setEnabled(true));
                if (modeTTS.isChecked()){
                    tts.speak(whisperResult.getResult(), TextToSpeech.QUEUE_ADD, null, null);
                }
            }
        });
    }

    private void deinitModel() {
        if (mWhisper != null) {
            mWhisper.unloadModel();
            mWhisper = null;
        }
    }

    private void deinitTTS(){
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
    }

    private @NonNull ArrayAdapter<File> getFileArrayAdapter(ArrayList<File> tfliteFiles) {
        ArrayAdapter<File> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, tfliteFiles) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                TextView textView = view.findViewById(android.R.id.text1);
                if ((getItem(position).getName()).equals(MULTI_LINGUAL_MODEL_SLOW))
                    textView.setText(R.string.multi_lingual_slow);
                else if ((getItem(position).getName()).equals(MULTI_LINGUAL_TOP_WORLD_SLOW))
                    textView.setText(R.string.multi_lingual_slow);
                else if ((getItem(position).getName()).equals(ENGLISH_ONLY_MODEL))
                    textView.setText(R.string.english_only_fast);
                else if ((getItem(position).getName()).equals(MULTI_LINGUAL_MODEL_FAST))
                    textView.setText(R.string.multi_lingual_fast);
                else if ((getItem(position).getName()).equals(MULTI_LINGUAL_EU_MODEL_FAST))
                    textView.setText(R.string.multi_lingual_fast);
                else if ((getItem(position).getName()).equals(MULTI_LINGUAL_TOP_WORLD_FAST))
                    textView.setText(R.string.multi_lingual_fast);
                else
                    textView.setText(getItem(position).getName().substring(0, getItem(position).getName().length() - ".tflite".length()));

                return view;
            }

            @Override
            public View getDropDownView(int position, View convertView, ViewGroup parent) {
                View view = super.getDropDownView(position, convertView, parent);
                TextView textView = view.findViewById(android.R.id.text1);
                if ((getItem(position).getName()).equals(MULTI_LINGUAL_MODEL_SLOW))
                    textView.setText(R.string.multi_lingual_slow);
                else if ((getItem(position).getName()).equals(MULTI_LINGUAL_TOP_WORLD_SLOW))
                    textView.setText(R.string.multi_lingual_slow);
                else if ((getItem(position).getName()).equals(ENGLISH_ONLY_MODEL))
                    textView.setText(R.string.english_only_fast);
                else if ((getItem(position).getName()).equals(MULTI_LINGUAL_MODEL_FAST))
                    textView.setText(R.string.multi_lingual_fast);
                else if ((getItem(position).getName()).equals(MULTI_LINGUAL_EU_MODEL_FAST))
                    textView.setText(R.string.multi_lingual_fast);
                else if ((getItem(position).getName()).equals(MULTI_LINGUAL_TOP_WORLD_FAST))
                    textView.setText(R.string.multi_lingual_fast);
                else
                    textView.setText(getItem(position).getName().substring(0, getItem(position).getName().length() - ".tflite".length()));

                return view;
            }
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        return adapter;
    }

    private void checkPermissions() {
        List<String> perms = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            perms.add(Manifest.permission.RECORD_AUDIO);
            Toast.makeText(this, getString(R.string.need_record_audio_permission), Toast.LENGTH_SHORT).show();
        }
        if ((Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) && (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)){
            perms.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!perms.isEmpty()) {
            requestPermissions(perms.toArray(new String[] {}), 0);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "Record permission is granted");
        } else {
            Log.d(TAG, "Record permission is not granted");
        }
    }

    // Recording calls
    private void startRecording() {
        checkPermissions();
        mRecorder.start();
    }

    private void stopRecording() {
        mRecorder.stop();
    }

    // Transcription calls

    private volatile boolean fileJobRunning = false;

    private void transcribeFile(android.net.Uri uri) {
        if (mWhisper == null || mWhisper.isInProgress() || fileJobRunning) {
            Toast.makeText(this, getString(R.string.please_wait), Toast.LENGTH_SHORT).show();
            return;
        }
        fileJobRunning = true;
        partChars.clear(); partMarks.clear(); clearHighlight(tvResult.getText());
        partMs.clear();
        if (!append.isChecked()) tvResult.setText("");
        tvStatus.setText("Decoding audio...");
        new Thread(() -> {
            try {
                byte[] pcm = com.whispertflite.utils.AudioDecoder.decode(this, uri);
                java.util.List<Integer> cuts = com.whispertflite.utils.AudioDecoder.silenceCuts(pcm);
                int total = cuts.size() - 1;
                for (int i = 0; i < total; i++) {
                    int from = cuts.get(i);
                    int to = cuts.get(i + 1);
                    com.whispertflite.asr.RecordBuffer.setOutputBuffer(java.util.Arrays.copyOfRange(pcm, from, to));
                    final int part = i + 1;
                    final int startMs = from / 32;
                    runOnUiThread(() -> { tvStatus.setText("Part " + part + " of " + total); partChars.add(tvResult.length()); partMs.add(startMs); });
                    startProcessing(translate.isChecked() ? Whisper.ACTION_TRANSLATE : Whisper.ACTION_TRANSCRIBE);
                    while (mWhisper != null && mWhisper.isInProgress()) Thread.sleep(200);
                    runOnUiThread(() -> tvResult.append(" "));
                }
                runOnUiThread(() -> tvStatus.setText("Finished: " + tvResult.getText().length() + " characters"));
            } catch (Exception e) {
                Log.e(TAG, "File transcription failed", e);
                runOnUiThread(() -> tvStatus.setText("Failed: " + e.getMessage()));
            } finally {
                fileJobRunning = false;
            }
        }).start();
    }


    private android.media.MediaPlayer player;
    private final android.os.Handler uiHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final float[] speeds = {0.75f, 1.0f, 1.25f, 1.5f};
    private int speedIdx = 1;

    private String fmt(int ms) {
        int s = ms / 1000;
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    private void setupPlayer(android.net.Uri uri) {
        uiHandler.removeCallbacksAndMessages(null);
        if (player != null) { player.release(); player = null; }
        try {
            player = new android.media.MediaPlayer();
            player.setDataSource(this, uri);
            player.prepare();
        } catch (Exception e) {
            Log.e(TAG, "Player setup failed", e);
            player = null;
            findViewById(R.id.layout_player).setVisibility(View.GONE);
            return;
        }
        findViewById(R.id.layout_player).setVisibility(View.VISIBLE);
        final ImageButton play = findViewById(R.id.btnPlay);
        final android.widget.SeekBar seek = findViewById(R.id.seekAudio);
        final TextView time = findViewById(R.id.tvTime);
        final TextView speed = findViewById(R.id.btnSpeed);
        final int dur = player.getDuration();
        seek.setMax(dur);
        seek.setProgress(0);
        play.setImageResource(R.drawable.ic_play);
        time.setText("0:00 / " + fmt(dur));
        speedIdx = 1;
        speed.setText("1.0x");
        play.setOnClickListener(v -> {
            if (player == null) return;
            if (player.isPlaying()) {
                player.pause();
            } else {
                player.setPlaybackParams(player.getPlaybackParams().setSpeed(speeds[speedIdx]));
                player.start();
            }
        });
        speed.setOnClickListener(v -> {
            speedIdx = (speedIdx + 1) % speeds.length;
            speed.setText(speeds[speedIdx] + "x");
            if (player != null && player.isPlaying())
                player.setPlaybackParams(player.getPlaybackParams().setSpeed(speeds[speedIdx]));
        });
        seek.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar s, int p, boolean fromUser) {
                if (fromUser && player != null) player.seekTo(p);
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar s) {}
            @Override public void onStopTrackingTouch(android.widget.SeekBar s) {}
        });
        uiHandler.post(new Runnable() {
            @Override public void run() {
                if (player == null) return;
                try {
                    int pos = player.getCurrentPosition();
                    if (loopEndMs > 0 && pos >= loopEndMs && ((android.widget.CheckBox) findViewById(R.id.chk_repeat)).isChecked()) { player.seekTo((int) loopStartMs); pos = (int) loopStartMs; if (!player.isPlaying()) player.start(); }
                    seek.setProgress(pos);
                    time.setText(fmt(pos) + " / " + fmt(dur));
                    play.setImageResource(player.isPlaying() ? R.drawable.ic_pause : R.drawable.ic_play);
                    try { updateSyncHighlight(pos); } catch (Exception ignored2) {}
                } catch (IllegalStateException ignored) {}
                uiHandler.postDelayed(this, 100);
            }
        });
    }


    private final java.util.ArrayList<Integer> partChars = new java.util.ArrayList<>();
    private final java.util.ArrayList<Integer> partMs = new java.util.ArrayList<>();
    private long loopStartMs = -1;
    private long loopEndMs = -1;

    private final androidx.activity.result.ActivityResultLauncher<android.content.Intent> saveTxt =
            registerForActivityResult(new androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(), result -> {
                android.content.Intent data = result.getData();
                if (result.getResultCode() != RESULT_OK || data == null || data.getData() == null) return;
                try (java.io.OutputStream os = getContentResolver().openOutputStream(data.getData())) {
                    os.write((this.saveKind.equals("srt") ? buildSrt() : tvResult.getText().toString()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
                } catch (Exception ex) {
                    Toast.makeText(this, "Save failed: " + ex.getMessage(), Toast.LENGTH_LONG).show();
                }
            });

    private void saveTxtFile() {
        android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(android.content.Intent.CATEGORY_OPENABLE);
        i.setType("text/plain");
        saveKind = "txt";
        i.putExtra(android.content.Intent.EXTRA_TITLE, "transcript.txt");
        saveTxt.launch(i);
    }

    private void shareText() {
        android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(android.content.Intent.EXTRA_TEXT, tvResult.getText().toString());
        startActivity(android.content.Intent.createChooser(i, "Share transcript"));
    }

    private boolean onTranscriptTouch(android.view.View v, android.view.MotionEvent e) {
        if (e.getAction() != android.view.MotionEvent.ACTION_UP) return false;
        if (editMode) return false;
        android.widget.CheckBox jump = findViewById(R.id.chk_tap_jump);
        if (!jump.isChecked() || player == null || partChars.isEmpty()) return false;
        int offset = tvResult.getOffsetForPosition(e.getX(), e.getY());
        int idx = -1;
        for (int i = 0; i < partChars.size(); i++) if (i < partMarks.size() && segStart(i) >= 0 && segStart(i) <= offset) idx = i;
        if (idx < 0) return false;
        int start = partMs.get(idx);
        int end = idx + 1 < partMs.size() ? partMs.get(idx + 1) : player.getDuration();
        android.widget.CheckBox rep = findViewById(R.id.chk_repeat);
        loopStartMs = rep.isChecked() ? start : -1;
        loopEndMs = rep.isChecked() ? end : -1;
        player.seekTo(start);
        player.setPlaybackParams(player.getPlaybackParams().setSpeed(speeds[speedIdx]));
        player.start();
        return false;
    }


    private static final String[] CPP_NAMES = {"Small (190 MB) - recommended", "Base (60 MB) - fastest", "Medium (540 MB) - most accurate", "Large v3 turbo (575 MB)", "Small q8 (264 MB)", "Base q8 (80 MB)"};
    private static final String[] CPP_FILES = {"ggml-small-q5_1.bin", "ggml-base-q5_1.bin", "ggml-medium-q5_0.bin", "ggml-large-v3-turbo-q5_0.bin", "ggml-small-q8_0.bin", "ggml-base-q8_0.bin"};
    private final java.util.ArrayList<Integer> partEndMs = new java.util.ArrayList<>();
    private final java.util.ArrayList<String> partTexts = new java.util.ArrayList<>();
    private String saveKind = "txt";

    private void setupCppUi() {
        android.widget.Spinner s = findViewById(R.id.spnrCppModel);
        android.widget.ArrayAdapter<String> a = new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_item, CPP_NAMES);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(a);
    }

    private java.io.File ensureCppModel(String fileName) throws java.io.IOException {
        java.io.File dir = getExternalFilesDir(null);
        java.io.File model = new java.io.File(dir, fileName);
        if (model.exists() && model.length() > 1000000) return model;
        java.io.File part = new java.io.File(dir, fileName + ".part");
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL("https://huggingface.co/ggerganov/whisper.cpp/resolve/main/" + fileName).openConnection();
        c.setConnectTimeout(20000);
        c.setReadTimeout(30000);
        c.connect();
        if (c.getResponseCode() != 200) throw new java.io.IOException("Download failed: HTTP " + c.getResponseCode());
        long total = c.getContentLengthLong();
        long done = 0;
        long lastUi = 0;
        try (java.io.InputStream in = c.getInputStream(); java.io.FileOutputStream out = new java.io.FileOutputStream(part)) {
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                done += n;
                long now = System.currentTimeMillis();
                if (now - lastUi > 500) {
                    lastUi = now;
                    final String msg = total > 0 ? (done * 100 / total) + "%" : (done / 1048576) + " MB";
                    runOnUiThread(() -> tvStatus.setText("Downloading model: " + msg));
                }
            }
        }
        if (total > 0 && done != total) throw new java.io.IOException("Download incomplete, try again");
        if (!part.renameTo(model)) throw new java.io.IOException("Could not save the model file");
        return model;
    }

    private float[] toFloats(byte[] pcm) {
        int n = pcm.length / 2;
        float[] out = new float[n];
        for (int i = 0; i < n; i++) {
            out[i] = (short) ((pcm[2 * i] & 0xff) | (pcm[2 * i + 1] << 8)) / 32768f;
        }
        return out;
    }

    private void transcribeFileCpp(android.net.Uri uri) {
        if (fileJobRunning) {
            Toast.makeText(this, getString(R.string.please_wait), Toast.LENGTH_SHORT).show();
            return;
        }
        fileJobRunning = true;
        final int modelIdx = ((android.widget.Spinner) findViewById(R.id.spnrCppModel)).getSelectedItemPosition();
        String l = ((android.widget.EditText) findViewById(R.id.edtLang)).getText().toString().trim();
        final String lang = l.isEmpty() ? "auto" : l;
        final String vocab = ((android.widget.EditText) findViewById(R.id.edtVocab)).getText().toString().trim();
        final boolean tr = translate.isChecked();
        int tParsed = 4;
        try { tParsed = Integer.parseInt(((android.widget.EditText) findViewById(R.id.edtThreads)).getText().toString().trim()); } catch (Exception ignored) {}
        final int thr = Math.max(1, Math.min(8, tParsed));
        if (!append.isChecked()) tvResult.setText("");
        partChars.clear(); partMarks.clear(); clearHighlight(tvResult.getText());
        partMs.clear();
        partEndMs.clear();
        partTexts.clear();
        findViewById(R.id.btnRecord).setEnabled(false);
        deinitModel();
        tvStatus.setText("Preparing...");
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        new Thread(() -> {
            long ctx = 0;
            try {
                java.io.File model = ensureCppModel(CPP_FILES[modelIdx]);
                runOnUiThread(() -> tvStatus.setText("Loading model..."));
                ctx = com.whispertflite.cpp.WhisperCpp.initContext(model.getAbsolutePath());
                if (ctx == 0) throw new java.io.IOException("Could not load the model (not enough memory?)");
                runOnUiThread(() -> tvStatus.setText("Decoding audio..."));
                byte[] pcm = com.whispertflite.utils.AudioDecoder.decode(this, uri);
                float[] audio = toFloats(pcm);
                pcm = null;
                if (audio.length < 1600) throw new java.io.IOException("Audio is empty");
                final long startedAt = System.currentTimeMillis();
                runOnUiThread(() -> tvStatus.setText("Transcribing... (this can take a while)"));
                int threads = thr;
                com.whispertflite.cpp.WhisperCpp.setProgressListener(p -> runOnUiThread(() -> tvStatus.setText("Transcribing " + p + "% (" + ((System.currentTimeMillis() - startedAt) / 1000) + " s)")));
                int rc = com.whispertflite.cpp.WhisperCpp.transcribe(ctx, threads, audio, lang, tr, vocab.isEmpty() ? null : vocab);
                audio = null;
                if (rc != 0) throw new java.io.IOException("Engine stopped (code " + rc + ")");
                final int count = com.whispertflite.cpp.WhisperCpp.segmentCount(ctx);
                for (int i = 0; i < count; i++) {
                    final String text = new String(com.whispertflite.cpp.WhisperCpp.segmentText(ctx, i), java.nio.charset.StandardCharsets.UTF_8).trim();
                    final int ms0 = (int) (com.whispertflite.cpp.WhisperCpp.segmentT0(ctx, i) * 10);
                    final int ms1 = (int) (com.whispertflite.cpp.WhisperCpp.segmentT1(ctx, i) * 10);
                    runOnUiThread(() -> {
                        partChars.add(tvResult.length());
                        partMs.add(ms0);
                        partEndMs.add(ms1);
                        partTexts.add(text);
                        int segFrom = tvResult.length();
                        tvResult.append(text + "\n");
                        Object segMark = new Object();
                        tvResult.getText().setSpan(segMark, segFrom, segFrom + text.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                        partMarks.add(segMark);
                    });
                }
                final long secs = (System.currentTimeMillis() - startedAt) / 1000;
                final String info = com.whispertflite.cpp.WhisperCpp.systemInfo();
                runOnUiThread(() -> tvStatus.setText("Finished: " + count + " sentences in " + secs + " s"));
            } catch (Throwable e) {
                Log.e(TAG, "cpp transcription failed", e);
                final String msg = (e instanceof OutOfMemoryError) ? "Out of memory" : String.valueOf(e.getMessage());
                runOnUiThread(() -> tvStatus.setText("Failed: " + msg));
            } finally {
                if (ctx != 0) com.whispertflite.cpp.WhisperCpp.freeContext(ctx);
                fileJobRunning = false;
                runOnUiThread(() -> {
                    findViewById(R.id.btnRecord).setEnabled(true);
                    getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    initModel();
                });
            }
        }).start();
    }

    private String srtTime(long ms) {
        return String.format(Locale.US, "%02d:%02d:%02d,%03d", ms / 3600000, (ms / 60000) % 60, (ms / 1000) % 60, ms % 1000);
    }

    private String buildSrt() {
        String[] raw = tvResult.getText().toString().split("\n");
        java.util.ArrayList<String> lines = new java.util.ArrayList<>();
        for (String s : raw) if (!s.trim().isEmpty()) lines.add(s.trim());
        boolean useEdited = lines.size() == partTexts.size();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < partTexts.size(); i++) {
            sb.append(i + 1).append("\n");
            sb.append(srtTime(partMs.get(i))).append(" --> ").append(srtTime(partEndMs.get(i))).append("\n");
            sb.append(useEdited ? lines.get(i) : partTexts.get(i)).append("\n\n");
        }
        return sb.toString();
    }

    private void saveSrtFile() {
        if (partTexts.isEmpty()) {
            Toast.makeText(this, "Transcribe an audio file first", Toast.LENGTH_SHORT).show();
            return;
        }
        saveKind = "srt";
        android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(android.content.Intent.CATEGORY_OPENABLE);
        i.setType("application/octet-stream");
        i.putExtra(android.content.Intent.EXTRA_TITLE, "transcript.srt");
        saveTxt.launch(i);
    }


    private boolean editMode = false;
    private final java.util.ArrayList<Object> partMarks = new java.util.ArrayList<>();
    private android.text.style.BackgroundColorSpan hiSeg;
    private android.text.style.BackgroundColorSpan hiWord;
    private android.text.style.ForegroundColorSpan hiWordText;
    private int lastSegShown = -1;
    private int lastWordShown = -1;

    private void setupUi2() {
        final TextView toggle = findViewById(R.id.btnToggleSettings);
        final View card = findViewById(R.id.card_settings);
        card.setVisibility(View.GONE);
        toggle.setOnClickListener(v -> {
            boolean show = card.getVisibility() != View.VISIBLE;
            card.setVisibility(show ? View.VISIBLE : View.GONE);
            toggle.setText(show ? "Settings  \u25B2" : "Settings  \u25BC");
        });
        final TextView mini = findViewById(R.id.tvStatusMini);
        mini.setText(tvStatus.getText());
        tvStatus.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable ed) { mini.setText(ed.toString()); }
        });
        tvResult.setShowSoftInputOnFocus(false);
        final TextView edit = findViewById(R.id.btnEditToggle);
        edit.setOnClickListener(v -> {
            android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            editMode = !editMode;
            if (editMode) {
                tvResult.setShowSoftInputOnFocus(true);
                tvResult.setCursorVisible(true);
                tvResult.requestFocus();
                imm.showSoftInput(tvResult, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT);
                edit.setText("Done");
            } else {
                imm.hideSoftInputFromWindow(tvResult.getWindowToken(), 0);
                tvResult.setShowSoftInputOnFocus(false);
                tvResult.setCursorVisible(false);
                tvResult.clearFocus();
                edit.setText("Edit");
            }
        });
    }

    private int segStart(int i) {
        return tvResult.getText().getSpanStart(partMarks.get(i));
    }

    private void clearHighlight(android.text.Editable text) {
        if (hiSeg != null) {
            text.removeSpan(hiSeg);
            text.removeSpan(hiWord);
            text.removeSpan(hiWordText);
        }
        lastSegShown = -1;
        lastWordShown = -1;
    }

    private void scrollToOffset(int offset) {
        android.text.Layout lo = tvResult.getLayout();
        android.widget.ScrollView sv = findViewById(R.id.scrollView);
        if (lo == null || sv == null || offset < 0 || offset > tvResult.length()) return;
        int line = lo.getLineForOffset(offset);
        int y = lo.getLineTop(line) + tvResult.getTotalPaddingTop();
        int h = sv.getHeight();
        int top = sv.getScrollY();
        if (y < top || y > top + h - h / 4) {
            sv.smoothScrollTo(0, Math.max(0, y - h / 4));
        }
    }

    private void updateSyncHighlight(int posMs) {
        if (partMarks.isEmpty() || partMs.size() != partMarks.size() || partEndMs.size() != partMarks.size()) return;
        android.text.Editable text = tvResult.getText();
        int idx = -1;
        for (int i = 0; i < partMs.size(); i++) {
            if (partMs.get(i) <= posMs && segStart(i) >= 0) idx = i;
        }
        if (idx < 0) {
            clearHighlight(text);
            return;
        }
        int s = segStart(idx);
        int e = text.getSpanEnd(partMarks.get(idx));
        if (s < 0 || e <= s) return;
        int ms0 = partMs.get(idx);
        int ms1 = Math.max(partEndMs.get(idx), ms0 + 1);
        float frac = Math.max(0f, Math.min(1f, (posMs - ms0) / (float) (ms1 - ms0)));
        int c = s + (int) (frac * (e - s));
        if (c >= e) c = e - 1;
        while (c > s && text.charAt(c - 1) != ' ') c--;
        int c2 = c;
        while (c2 < e && text.charAt(c2) != ' ') c2++;
        if (c2 <= c) c2 = Math.min(e, c + 1);
        if (idx == lastSegShown && c == lastWordShown) return;
        boolean segChanged = idx != lastSegShown;
        lastSegShown = idx;
        lastWordShown = c;
        if (hiSeg == null) {
            hiSeg = new android.text.style.BackgroundColorSpan(androidx.core.content.ContextCompat.getColor(this, R.color.accentSoft));
            hiWord = new android.text.style.BackgroundColorSpan(androidx.core.content.ContextCompat.getColor(this, R.color.colorAccentDark));
            hiWordText = new android.text.style.ForegroundColorSpan(0xFFFFFFFF);
        }
        text.setSpan(hiSeg, s, e, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(hiWord, c, c2, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(hiWordText, c, c2, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (segChanged) scrollToOffset(s);
    }

    private void startProcessing(Whisper.Action action) {
        if (countDownTimer!=null) { countDownTimer.cancel();}
        runOnUiThread(() -> {
            processingBar.setProgress(0);
            processingBar.setIndeterminate(true);
        });
        mWhisper.setAction(action);
        mWhisper.setLanguage(langToken);
        mWhisper.start();
    }

    private void stopProcessing() {
        if (fileJobRunning) return;
        processingBar.setIndeterminate(false);
        if (mWhisper != null && mWhisper.isInProgress()) mWhisper.stop();
    }

    public ArrayList<File> getFilesWithExtension(File directory, String extension) {
        ArrayList<File> filteredFiles = new ArrayList<>();

        // Check if the directory is accessible
        if (directory != null && directory.exists()) {
            File[] files = directory.listFiles();

            // Filter files by the provided extension
            if (files != null) {
                for (File file : files) {
                    if (file.isFile() && file.getName().endsWith(extension)) {
                        filteredFiles.add(file);
                    }
                }
            }
        }

        return filteredFiles;
    }

}