package com.android.pipeliner;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;

import androidx.appcompat.app.AppCompatActivity;
import android.util.Log;
import android.widget.Toast;

public class SettingsActivity extends AppCompatActivity {

    public static final String PREF_NAME = "GStreamerPrefs";
    public static final String KEY_FULL_PIPELINE = "FullPipeline";

    private EditText pipelineEdit;
    private SharedPreferences prefs;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Edit Pipeline");
        }

        prefs = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);

        pipelineEdit = findViewById(R.id.edittext_pipeline);
        Button saveButton = findViewById(R.id.button_save_pipeline);

        // Load current pipeline from intent or preferences
        String currentPipeline = getIntent().getStringExtra("pipeline");
        if (currentPipeline == null || currentPipeline.isEmpty()) {
            currentPipeline = prefs.getString(KEY_FULL_PIPELINE, "videotestsrc ! autovideosink");
        }
        pipelineEdit.setText(currentPipeline);

        saveButton.setOnClickListener(v -> saveAndApplySettings());
    }

    private void saveAndApplySettings() {
        String pipeline = pipelineEdit.getText().toString().trim();
        
        if (pipeline.isEmpty()) {
            Toast.makeText(this, "Pipeline cannot be empty", Toast.LENGTH_SHORT).show();
            return;
        }

        SharedPreferences.Editor editor = prefs.edit();
        editor.putString(KEY_FULL_PIPELINE, pipeline);
        editor.apply();

        Log.d("GStreamer-Pipeline", "Saved Pipeline: " + pipeline);

        setResult(RESULT_OK);
        Toast.makeText(this, "Pipeline Saved", Toast.LENGTH_SHORT).show();
        finish();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }
}