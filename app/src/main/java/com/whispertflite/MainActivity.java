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
                    transcribeFile(uri);
                }
            });

    @Override
    protected void onDestroy() {
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
        findViewById(R.id.btnLearn).setOnClickListener(v -> learnFromEdits());
        findViewById(R.id.btnCorrections).setOnClickListener(v -> correctionsDialog());
        findViewById(R.id.btnApplyThreads).setOnClickListener(v -> applyThreads());
        findViewById(R.id.tvResult).setOnTouchListener(this::onTranscriptTouch);
        findViewById(R.id.btnShare).setOnClickListener(v -> shareText());
        findViewById(R.id.btnSaveTxt).setOnClickListener(v -> exportDialog());
        sp = PreferenceManager.getDefaultSharedPreferences(this);
        loadThreads();
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
        setupSpelling();
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
        fileJobStartedAt = System.currentTimeMillis();
        partChars.clear(); partMarks.clear(); partEndMs.clear(); partTexts.clear(); clearHighlight(tvResult.getText());
        partMs.clear();
        if (!append.isChecked()) tvResult.setText("");
        tvStatus.setText("Decoding audio...");
        new Thread(() -> {
            try {
                byte[] pcm = com.whispertflite.utils.AudioDecoder.decode(this, uri);
                java.util.List<Integer> cuts = com.whispertflite.utils.AudioDecoder.silenceCuts(pcm);
                int total = cuts.size() - 1;
                final int[] chunkStart = new int[1];
                final int[] failedParts = new int[1];
                for (int i = 0; i < total; i++) {
                    int from = cuts.get(i);
                    int to = cuts.get(i + 1);
                    com.whispertflite.asr.RecordBuffer.setOutputBuffer(java.util.Arrays.copyOfRange(pcm, from, to));
                    final int part = i + 1;
                    final int startMs = from / 32;
                    runOnUiThread(() -> { tvStatus.setText("Part " + part + " of " + total); chunkStart[0] = tvResult.length(); });
                    startProcessing(translate.isChecked() ? Whisper.ACTION_TRANSLATE : Whisper.ACTION_TRANSCRIBE);
                    while (mWhisper != null && mWhisper.isInProgress()) Thread.sleep(200);
                    if (com.whispertflite.engine.WhisperEngineJava.lastError != null) failedParts[0]++;
                    final int[] spn = com.whispertflite.utils.AudioDecoder.speechSpanMs(pcm, from, to);
                    runOnUiThread(() -> finishChunk(chunkStart[0], spn[0], spn[1]));
                }
                runOnUiThread(() -> tvStatus.setText("Finished in " + ((System.currentTimeMillis() - fileJobStartedAt) / 1000) + " s, " + tvResult.getText().length() + " characters" + (failedParts[0] > 0 ? ", " + failedParts[0] + " part(s) failed" : "")));
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
                    os.write(this.buildExport(this.saveKind));
                    Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show();
                } catch (Exception ex) {
                    Toast.makeText(this, "Save failed: " + ex.getMessage(), Toast.LENGTH_LONG).show();
                }
            });

    private void saveTxtFile() {
        android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(android.content.Intent.CATEGORY_OPENABLE);
        i.setType("text/plain");
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
        if (!jump.isChecked() || player == null || partMarks.isEmpty()) return false;
        int offset = tvResult.getOffsetForPosition(e.getX(), e.getY());
        int idx = -1;
        for (int i = 0; i < partMarks.size(); i++) if (segStart(i) >= 0 && segStart(i) <= offset) idx = i;
        if (idx < 0) return false;
        int start = partMs.get(idx);
        int end = partEndMs.get(idx);
        android.widget.CheckBox rep = findViewById(R.id.chk_repeat);
        loopStartMs = rep.isChecked() ? start : -1;
        loopEndMs = rep.isChecked() ? end : -1;
        player.seekTo(start);
        player.setPlaybackParams(player.getPlaybackParams().setSpeed(speeds[speedIdx]));
        player.start();
        return false;
    }


    private boolean editMode = false;
    private final java.util.ArrayList<Object> partMarks = new java.util.ArrayList<>();
    private final java.util.ArrayList<Integer> partEndMs = new java.util.ArrayList<>();
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


    private long fileJobStartedAt = 0;

    private void loadThreads() {
        int n = sp.getInt("threads", 0);
        com.whispertflite.engine.WhisperEngineJava.NUM_THREADS = n;
        if (n > 0) ((android.widget.EditText) findViewById(R.id.edtThreads)).setText(String.valueOf(n));
    }

    private void applyThreads() {
        if (fileJobRunning || (mWhisper != null && mWhisper.isInProgress())) {
            Toast.makeText(this, getString(R.string.please_wait), Toast.LENGTH_SHORT).show();
            return;
        }
        int n = 0;
        try { n = Integer.parseInt(((android.widget.EditText) findViewById(R.id.edtThreads)).getText().toString().trim()); } catch (Exception ignored) {}
        n = Math.max(0, Math.min(8, n));
        sp.edit().putInt("threads", n).apply();
        com.whispertflite.engine.WhisperEngineJava.NUM_THREADS = n;
        deinitModel();
        initModel();
        Toast.makeText(this, n == 0 ? "Threads: auto" : "Threads: " + n, Toast.LENGTH_SHORT).show();
    }


    private static final String XML_HEAD = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>";
    private final java.util.ArrayList<String> partTexts = new java.util.ArrayList<>();
    private String saveKind = "txt";

    private java.util.ArrayList<String> splitSentences(String raw) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int n = raw.length();
        for (int i = 0; i < n; i++) {
            char c = raw.charAt(i);
            cur.append(c);
            boolean cjk = c == '\u3002' || c == '\uFF01' || c == '\uFF1F';
            boolean ascii = c == '.' || c == '!' || c == '?';
            boolean endHere = cjk || (ascii && (i + 1 >= n || Character.isWhitespace(raw.charAt(i + 1))));
            if (endHere) {
                String s = cur.toString().trim();
                if (!s.isEmpty()) out.add(s);
                cur.setLength(0);
            }
        }
        String rest = cur.toString().trim();
        if (!rest.isEmpty()) out.add(rest);
        return out;
    }

    // Called on the UI thread when one audio section has been transcribed: one sentence per line, with estimated times
    private void finishChunk(int startChar, int msA, int msB) {
        android.text.Editable ed = tvResult.getText();
        if (startChar < 0 || startChar > ed.length()) return;
        String raw = applyCorrections(applySpelling(ed.subSequence(startChar, ed.length()).toString().trim()));
        if (raw.isEmpty()) return;
        java.util.ArrayList<String> sents = splitSentences(raw);
        StringBuilder sb = new StringBuilder();
        int total = 0;
        for (String s : sents) {
            sb.append(s).append("\n");
            total += s.length();
        }
        ed.replace(startChar, ed.length(), sb.toString());
        if (total == 0) return;
        int pos = startChar;
        int acc = 0;
        for (String s : sents) {
            int a = msA + (int) ((long) (msB - msA) * acc / total);
            acc += s.length();
            int b = msA + (int) ((long) (msB - msA) * acc / total);
            Object mk = new Object();
            ed.setSpan(mk, pos, pos + s.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            partMarks.add(mk);
            partMs.add(a);
            partEndMs.add(b);
            partTexts.add(s);
            pos += s.length() + 1;
        }
    }

    private java.util.ArrayList<String[]> ruleList() {
        java.util.ArrayList<String[]> out = new java.util.ArrayList<>();
        for (String line : sp.getString("corrections", "").split("\n")) {
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String wrong = line.substring(0, eq).trim();
            String right = line.substring(eq + 1).trim();
            if (!wrong.isEmpty()) out.add(new String[]{wrong, right});
        }
        return out;
    }

    private java.util.regex.Pattern rulePattern(String wrong) {
        return java.util.regex.Pattern.compile("(?<![\\p{L}\\p{N}])" + java.util.regex.Pattern.quote(wrong) + "(?![\\p{L}\\p{N}])",
                java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE);
    }

    private String matchCase(String found, String right) {
        if (!right.isEmpty() && !found.isEmpty() && Character.isUpperCase(found.charAt(0)) && Character.isLowerCase(right.charAt(0))) {
            return Character.toUpperCase(right.charAt(0)) + right.substring(1);
        }
        return right;
    }

    private String applyCorrections(String text) {
        for (String[] r : ruleList()) {
            java.util.regex.Matcher m = rulePattern(r[0]).matcher(text);
            StringBuffer sb = new StringBuffer();
            while (m.find()) m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(matchCase(m.group(), r[1])));
            m.appendTail(sb);
            text = sb.toString();
        }
        return text;
    }

    private int fixCurrentText() {
        int count = 0;
        android.text.Editable ed = tvResult.getText();
        for (String[] r : ruleList()) {
            java.util.regex.Matcher m = rulePattern(r[0]).matcher(ed.toString());
            java.util.ArrayList<int[]> hits = new java.util.ArrayList<>();
            java.util.ArrayList<String> reps = new java.util.ArrayList<>();
            while (m.find()) {
                hits.add(new int[]{m.start(), m.end()});
                reps.add(matchCase(m.group(), r[1]));
            }
            for (int i = hits.size() - 1; i >= 0; i--) {
                ed.replace(hits.get(i)[0], hits.get(i)[1], reps.get(i));
                count++;
            }
        }
        return count;
    }

    private void correctionsDialog() {
        final android.widget.EditText box = new android.widget.EditText(this);
        box.setHint("One per line:  wrong word = right word");
        box.setText(sp.getString("corrections", ""));
        box.setMinLines(6);
        box.setGravity(android.view.Gravity.TOP);
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Corrections")
                .setView(box)
                .setPositiveButton("Save", (d, w) -> sp.edit().putString("corrections", box.getText().toString()).apply())
                .setNeutralButton("Save and fix text", (d, w) -> {
                    sp.edit().putString("corrections", box.getText().toString()).apply();
                    int n = fixCurrentText();
                    Toast.makeText(this, n + " fixes", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private String stampTime(long ms, char sep) {
        return String.format(Locale.US, "%02d:%02d:%02d%c%03d", ms / 3600000, (ms / 60000) % 60, (ms / 1000) % 60, sep, ms % 1000);
    }

    private String shortTime(long ms) {
        return String.format(Locale.US, "%02d:%02d:%02d", ms / 3600000, (ms / 60000) % 60, (ms / 1000) % 60);
    }

    private String xmlEsc(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void putZip(java.util.zip.ZipOutputStream z, String name, String content) throws java.io.IOException {
        z.putNextEntry(new java.util.zip.ZipEntry(name));
        z.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        z.closeEntry();
    }

    private byte[] buildDocx(java.util.List<String> paras) throws java.io.IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(bos);
        putZip(zip, "[Content_Types].xml", XML_HEAD + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\"application/xml\"/><Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/></Types>");
        putZip(zip, "_rels/.rels", XML_HEAD + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\"><Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/></Relationships>");
        StringBuilder body = new StringBuilder(XML_HEAD + "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body>");
        for (String p : paras) body.append("<w:p><w:r><w:t xml:space=\"preserve\">").append(xmlEsc(p)).append("</w:t></w:r></w:p>");
        body.append("</w:body></w:document>");
        putZip(zip, "word/document.xml", body.toString());
        zip.close();
        return bos.toByteArray();
    }

    private byte[] buildExport(String kind) throws java.io.IOException {
        java.util.ArrayList<String> lines = new java.util.ArrayList<>();
        for (String s : tvResult.getText().toString().split("\n")) if (!s.trim().isEmpty()) lines.add(s.trim());
        boolean timed = !partTexts.isEmpty();
        boolean useEdited = timed && lines.size() == partTexts.size();
        java.util.ArrayList<String> texts = new java.util.ArrayList<>();
        if (timed) for (int i = 0; i < partTexts.size(); i++) texts.add(useEdited ? lines.get(i) : partTexts.get(i));
        boolean stamps = ((android.widget.CheckBox) findViewById(R.id.chk_stamps)).isChecked();
        StringBuilder sb = new StringBuilder();
        if (kind.equals("srt")) {
            for (int i = 0; i < texts.size(); i++) {
                sb.append(i + 1).append("\n").append(stampTime(partMs.get(i), ',')).append(" --> ").append(stampTime(partEndMs.get(i), ',')).append("\n").append(texts.get(i)).append("\n\n");
            }
        } else if (kind.equals("vtt")) {
            sb.append("WEBVTT\n\n");
            for (int i = 0; i < texts.size(); i++) {
                sb.append(stampTime(partMs.get(i), '.')).append(" --> ").append(stampTime(partEndMs.get(i), '.')).append("\n").append(texts.get(i)).append("\n\n");
            }
        } else if (kind.equals("csv")) {
            sb.append("start,end,text\n");
            for (int i = 0; i < texts.size(); i++) {
                sb.append(stampTime(partMs.get(i), '.')).append(",").append(stampTime(partEndMs.get(i), '.')).append(",\"").append(texts.get(i).replace("\"", "\"\"")).append("\"\n");
            }
        } else {
            java.util.ArrayList<String> out = new java.util.ArrayList<>();
            if (timed && useEdited) {
                for (int i = 0; i < texts.size(); i++) out.add((stamps ? "[" + shortTime(partMs.get(i)) + "] " : "") + texts.get(i));
            } else {
                out.addAll(lines);
            }
            if (kind.equals("docx")) return buildDocx(out);
            for (String s : out) sb.append(s).append("\n");
        }
        return sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private void exportDialog() {
        final String[] names = {"Text (.txt)", "Word (.docx)", "Subtitles (.srt)", "Subtitles (.vtt)", "Spreadsheet (.csv)"};
        final String[] kinds = {"txt", "docx", "srt", "vtt", "csv"};
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Export as")
                .setItems(names, (d, which) -> launchExport(kinds[which]))
                .show();
    }

    private void launchExport(String kind) {
        if ((kind.equals("srt") || kind.equals("vtt") || kind.equals("csv")) && partTexts.isEmpty()) {
            Toast.makeText(this, "Subtitles and CSV need a transcribed audio file", Toast.LENGTH_LONG).show();
            return;
        }
        saveKind = kind;
        String mime = kind.equals("docx") ? "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                : kind.equals("txt") ? "text/plain" : kind.equals("csv") ? "text/csv" : "application/octet-stream";
        android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(android.content.Intent.CATEGORY_OPENABLE);
        i.setType(mime);
        i.putExtra(android.content.Intent.EXTRA_TITLE, "transcript." + kind);
        saveTxt.launch(i);
    }


    private String applySpelling(String text) {
        int mode = sp.getInt("spelling", 0);
        return mode == 0 ? text : com.whispertflite.utils.Spelling.convert(text, mode == 1);
    }

    private void setupSpelling() {
        final android.widget.Spinner s = findViewById(R.id.spnrSpelling);
        android.widget.ArrayAdapter<String> a = new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
                new String[]{"Leave as transcribed", "British (colour, centre)", "American (color, center)"});
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        s.setAdapter(a);
        s.setSelection(sp.getInt("spelling", 0), false);
        s.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View v, int pos, long id) {
                if (pos == sp.getInt("spelling", 0)) return;
                sp.edit().putInt("spelling", pos).apply();
                if (pos > 0) convertCurrentSpelling(pos == 1);
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
    }

    private void convertCurrentSpelling(boolean toUk) {
        android.text.Editable ed = tvResult.getText();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\p{L}+").matcher(ed.toString());
        java.util.ArrayList<int[]> hits = new java.util.ArrayList<>();
        java.util.ArrayList<String> reps = new java.util.ArrayList<>();
        while (m.find()) {
            String w = m.group();
            String r = com.whispertflite.utils.Spelling.convertWord(w, toUk);
            if (!r.equals(w)) {
                hits.add(new int[]{m.start(), m.end()});
                reps.add(r);
            }
        }
        for (int i = hits.size() - 1; i >= 0; i--) ed.replace(hits.get(i)[0], hits.get(i)[1], reps.get(i));
        Toast.makeText(this, hits.size() + " words changed", Toast.LENGTH_SHORT).show();
    }

    private static String stripTok(String t) {
        return t.replaceAll("^[^\\p{L}\\p{N}']+|[^\\p{L}\\p{N}']+$", "");
    }

    private java.util.ArrayList<String> wordTokens(String s) {
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        for (String t : s.split("\\s+")) {
            String x = stripTok(t);
            if (!x.isEmpty()) out.add(x);
        }
        return out;
    }

    // Compares the machine text with the text as you corrected it, and offers the changes as automatic rules
    private void learnFromEdits() {
        if (partTexts.isEmpty()) {
            Toast.makeText(this, "Transcribe an audio file first", Toast.LENGTH_LONG).show();
            return;
        }
        java.util.ArrayList<String> a = wordTokens(String.join(" ", partTexts));
        java.util.ArrayList<String> b = wordTokens(tvResult.getText().toString());
        final int n = a.size();
        final int m = b.size();
        if (m == 0) return;
        if ((long) n * m > 16000000L) {
            Toast.makeText(this, "Too long to compare (over about 4000 words). Try a shorter recording.", Toast.LENGTH_LONG).show();
            return;
        }
        int[][] d = new int[n + 1][m + 1];
        for (int i = 0; i <= n; i++) d[i][0] = i;
        for (int j = 0; j <= m; j++) d[0][j] = j;
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                int cost = a.get(i - 1).equalsIgnoreCase(b.get(j - 1)) ? 0 : 1;
                d[i][j] = Math.min(Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost);
            }
        }
        final int errors = d[n][m];
        if (errors == 0) {
            Toast.makeText(this, "No edits found. Correct the text with Edit first, then tap this again.", Toast.LENGTH_LONG).show();
            return;
        }
        java.util.LinkedHashMap<String, Integer> pairs = new java.util.LinkedHashMap<>();
        int pi = n;
        int pj = m;
        while (pi > 0 || pj > 0) {
            if (pi > 0 && pj > 0) {
                boolean same = a.get(pi - 1).equalsIgnoreCase(b.get(pj - 1));
                if (d[pi][pj] == d[pi - 1][pj - 1] + (same ? 0 : 1)) {
                    if (!same) {
                        String key = a.get(pi - 1).toLowerCase(Locale.ROOT) + "\t" + b.get(pj - 1);
                        pairs.put(key, pairs.containsKey(key) ? pairs.get(key) + 1 : 1);
                    }
                    pi--;
                    pj--;
                    continue;
                }
            }
            if (pi > 0 && d[pi][pj] == d[pi - 1][pj] + 1) pi--; else pj--;
        }
        int pct = Math.max(0, (int) Math.round(100.0 * (m - errors) / m));
        String hist = sp.getString("acc_log", "");
        String all = hist.isEmpty() ? String.valueOf(pct) : hist + "," + pct;
        String[] hh = all.split(",");
        StringBuilder recent = new StringBuilder();
        for (int k = Math.max(0, hh.length - 5); k < hh.length; k++) recent.append(recent.length() > 0 ? ", " : "").append(hh[k]).append("%");
        sp.edit().putString("acc_log", all).apply();
        final java.util.ArrayList<String[]> sug = new java.util.ArrayList<>();
        java.util.ArrayList<String> labels = new java.util.ArrayList<>();
        java.util.ArrayList<Boolean> pre = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, Integer> en : pairs.entrySet()) {
            String[] kv = en.getKey().split("\t");
            if (kv.length < 2) continue;
            sug.add(new String[]{kv[0], kv[1]});
            labels.add(kv[0] + "  \u2192  " + kv[1] + (en.getValue() > 1 ? "   (x" + en.getValue() + ")" : ""));
            pre.add(en.getValue() > 1);
            if (sug.size() >= 40) break;
        }
        String title = "Accuracy about " + pct + "%  (" + errors + " changes in " + m + " words)\nRecent checks: " + recent;
        if (sug.isEmpty()) {
            new androidx.appcompat.app.AlertDialog.Builder(this).setTitle(title)
                    .setMessage("No single-word replacements to learn from this time.").setPositiveButton("OK", null).show();
            return;
        }
        final boolean[] on = new boolean[sug.size()];
        for (int k = 0; k < on.length; k++) on[k] = pre.get(k);
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(title)
                .setMultiChoiceItems(labels.toArray(new String[0]), on, (dlg, which, isChecked) -> on[which] = isChecked)
                .setPositiveButton("Add ticked to Corrections", (dlg, w) -> addRules(sug, on))
                .setNegativeButton("Close", null)
                .show();
    }

    private void addRules(java.util.ArrayList<String[]> sug, boolean[] on) {
        java.util.HashSet<String> have = new java.util.HashSet<>();
        for (String[] r : ruleList()) have.add(r[0].toLowerCase(Locale.ROOT));
        StringBuilder sb = new StringBuilder(sp.getString("corrections", ""));
        int added = 0;
        for (int k = 0; k < sug.size(); k++) {
            if (!on[k] || have.contains(sug.get(k)[0])) continue;
            if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') sb.append("\n");
            sb.append(sug.get(k)[0]).append(" = ").append(sug.get(k)[1]).append("\n");
            added++;
        }
        sp.edit().putString("corrections", sb.toString()).apply();
        Toast.makeText(this, added + " rule(s) added to Corrections", Toast.LENGTH_SHORT).show();
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