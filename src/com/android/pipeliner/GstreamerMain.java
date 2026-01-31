package com.android.pipeliner;


import android.Manifest;
import android.annotation.SuppressLint;
import android.app.PictureInPictureParams;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.appcompat.app.AppCompatActivity;

import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.ContextCompat;

//IDK about this import
import org.freedesktop.gstreamer.GStreamer;

public class GstreamerMain extends AppCompatActivity implements SurfaceHolder.Callback {
    private native void nativeInit();     // Initialize native code, build pipeline, etc
    private native void nativeFinalize(); // Destroy pipeline and shutdown native code
    private native void nativePlay();     // Set pipeline to PLAYING
    private native void nativePause();    // Set pipeline to PAUSED
    private static native boolean nativeClassInit(); // Initialize native class: cache Method IDs for callbacks
    private native void nativeSurfaceInit(Object surface);
    private native void nativeSurfaceFinalize();
    private native void nativeSetPipeline(String pipeline);
    private native void nativeReinit();
    private native String nativeGetError(); // Poll for errors (thread-safe)
    private android.view.Menu menu;

    private long native_custom_data;      // Native code will use this to keep private data

    private boolean is_playing_desired;   // Whether the user asked to go to PLAYING
    
    private Handler errorPollHandler;     // Handler for error polling
    private static final int ERROR_POLL_INTERVAL_MS = 500; // Poll every 500ms
    
    private static final int CAMERA_PERMISSION_REQUEST = 100;
    private ActivityResultLauncher<String[]> cameraPermissionLauncher;
    
    private static String lastProcessedIntentHash = null; // Static to survive rotation

    // Pull custom pipelines
    private static final String PREF_NAME = "GStreamerPrefs";
    private static final String KEY_FULL_PIPELINE = "FullPipeline";
    private ActivityResultLauncher<Intent> settingsActivityLauncher;

    //Menu
    @SuppressLint("ResourceType")
    @Override
    public boolean onCreateOptionsMenu(android.view.Menu menu) {
        getMenuInflater().inflate(R.layout.main_menu, menu);
        this.menu = menu;
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item) {
    int id = item.getItemId();
    
    // Handle back button
    if (id == android.R.id.home) {
        onBackPressed();
        return true;
    }
    
    if (id == R.id.button_play) {
            is_playing_desired = true;
            nativePlay();
            return true;
    }
    else if (id == R.id.button_stop) {
        is_playing_desired = false;
        nativePause();
        return true;
    }
    if (id == R.id.action_settings) {
        // Open current pipeline for editing
        String currentPipeline = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getString(KEY_FULL_PIPELINE, "");
        Intent intent = new Intent(this, SettingsActivity.class);
        intent.putExtra("pipeline", currentPipeline);
        settingsActivityLauncher.launch(intent);
        return true;
    }

    return super.onOptionsItemSelected(item);
    }

    private void loadAndSetPipeline() {
        String pipeline = getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                .getString(KEY_FULL_PIPELINE, "videotestsrc ! autovideosink");
        Log.i("GStreamer", "loadAndSetPipeline() called - Pipeline: " + pipeline);
        nativeSetPipeline(pipeline);
        // Re init GSTREAMER
        nativeReinit();
        if (is_playing_desired) {
            nativePlay();
        } else {
            nativePause();
        }
    }

    //PiP
    private void enterPiPMode() {
        // Check for Android O (API 26) or newer
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {

            // Define the aspect ratio (e.g., 16:9)
            android.util.Rational aspectRatio = new android.util.Rational(16, 9);

            PictureInPictureParams params = new PictureInPictureParams.Builder()
                    .setAspectRatio(aspectRatio)
                    .build();

            // Check if video is playing before trying to enter PiP
            if (is_playing_desired) {
                enterPictureInPictureMode(params);
                Log.i("GStreamer", "Requested Picture-in-Picture mode.");
            }
        }
    }

    // Called when the activity is first created.
    @Override
    public void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        
        Log.i("GStreamer", "onCreate() called - lastProcessedIntentHash: " + lastProcessedIntentHash);
        
        // Initialize camera permission launcher
        cameraPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestMultiplePermissions(),
                result -> {
                    Boolean cameraGranted = result.get(Manifest.permission.CAMERA);
                    Boolean audioGranted = result.get(Manifest.permission.RECORD_AUDIO);
                    if (Boolean.TRUE.equals(cameraGranted)) {
                        Log.i("GStreamer", "Camera permission granted");
                        Toast.makeText(this, "Camera permission granted. You can now use camera pipelines.", Toast.LENGTH_SHORT).show();
                    } else {
                        Log.w("GStreamer", "Camera permission denied");
                        Toast.makeText(this, "Camera permission denied. Camera pipelines will not work.", Toast.LENGTH_LONG).show();
                    }
                }
        );
        
        // Initialize GStreamer and warn if it fails
        try {
            GStreamer.init(this);
        } catch (Exception e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        
        // Check and request camera permissions if needed
        checkCameraPermissions();

        setContentView(R.layout.main);

        settingsActivityLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK) {
                        Log.i("GStreamer", "Settings saved. Reloading pipeline...");
                        loadAndSetPipeline();
                    }
                }
        );

        SurfaceView sv = (SurfaceView) this.findViewById(R.id.surface_video);
        SurfaceHolder sh = sv.getHolder();
        sh.addCallback(this);

        if (savedInstanceState != null) {
            is_playing_desired = savedInstanceState.getBoolean("playing");
            Log.i ("GStreamer", "Activity created with saved state. Playing:" + is_playing_desired);
        } else {
            is_playing_desired = false;
            Log.i ("GStreamer", "Activity created without saved state. Playing: false");
        }
        
        // Always call handleIntent - it will check if this specific intent was already processed
        Log.i("GStreamer", "About to call handleIntent, lastProcessedIntentHash: " + lastProcessedIntentHash);
        handleIntent(getIntent());

        // Start error polling
        startErrorPolling();

        // Custom Pipeline (only load if intent didn't already load one)
        Log.i("GStreamer", "After handleIntent, lastProcessedIntentHash: " + lastProcessedIntentHash);
        if (lastProcessedIntentHash == null) {
            Log.i("GStreamer", "Hash is null, calling loadAndSetPipeline()");
            loadAndSetPipeline();
        } else {
            Log.i("GStreamer", "Hash is NOT null, skipping loadAndSetPipeline()");
        }

        nativeInit();
    }
    
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        lastProcessedIntentHash = null; // Reset hash for new intent
        handleIntent(intent);
    }
    
    private void handleIntent(Intent intent) {
        String pipelineName = "GStreamer";
        if (intent != null && intent.hasExtra("pipeline")) {
            String pipeline = intent.getStringExtra("pipeline");
            pipelineName = intent.getStringExtra("name");
            
            // Create a unique hash for this intent
            String intentHash = (pipeline + "|" + pipelineName).hashCode() + "";
            
            // Check if we've already processed this exact intent
            if (intentHash.equals(lastProcessedIntentHash)) {
                Log.i("GStreamer", "Skipping duplicate intent processing (hash: " + intentHash + ")");
                return;
            }
            
            if (pipelineName == null || pipelineName.isEmpty()) {
                pipelineName = "GStreamer";
            }
            Log.i("GStreamer", "Loading pipeline from intent: " + pipelineName + " (hash: " + intentHash + ")");
            getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_FULL_PIPELINE, pipeline)
                    .apply();
            lastProcessedIntentHash = intentHash; // Store hash to prevent re-processing
            is_playing_desired = true; // Auto-play when tapping from list
            loadAndSetPipeline();
        }
        
        // Update toolbar with pipeline name
        if (getSupportActionBar() != null) {
            getSupportActionBar().show();
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(pipelineName);
        }
    }
    
    private void startErrorPolling() {
        errorPollHandler = new Handler(Looper.getMainLooper());
        Runnable errorPollRunnable = new Runnable() {
            @Override
            public void run() {
                String error = nativeGetError();
                if (error != null && !error.isEmpty()) {
                    showError(error);
                }
                errorPollHandler.postDelayed(this, ERROR_POLL_INTERVAL_MS);
            }
        };
        errorPollHandler.post(errorPollRunnable);
    }
    
    private void showError(String error) {
        // Hide status container
        View statusContainer = findViewById(R.id.status_container);
        if (statusContainer != null) {
            statusContainer.setVisibility(View.GONE);
        }
        
        // Show error message
        TextView tv = (TextView) this.findViewById(R.id.textview_message);
        tv.setText(error);
        tv.setTextColor(android.graphics.Color.RED);
        Toast.makeText(this, error, Toast.LENGTH_LONG).show();
        is_playing_desired = false;
    }

    //PiP
    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        enterPiPMode();
    }

    //also PiP
    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode, Configuration newConfig) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig);

        // Assuming you have a reference to your SurfaceView here
        final GStreamerSurfaceView surfaceView = (GStreamerSurfaceView) findViewById(R.id.surface_video);

        // Assuming your controls are in a layout named 'layout_controls'
        // (If you moved them to the menu bar, this might only apply to other UI elements)
        final View controls = findViewById(R.id.button_play);
        final TextView message = findViewById(R.id.textview_message);

        // Handle the Video Detach/Attach
        if (isInPictureInPictureMode) {
            // --- DETACH (Entering PiP) ---

            // Show the video only in the small PiP window
            if (surfaceView != null) {
                surfaceView.setVisibility(View.VISIBLE);
            }

            // Hide the rest of the main app UI
            if (controls != null) controls.setVisibility(View.GONE);
            if (message != null) message.setVisibility(View.GONE);
            
            // Hide the action bar
            if (getSupportActionBar() != null) {
                getSupportActionBar().hide();
            }

        } else {
            // --- ATTACH (Exiting PiP / Returning to Full-Screen) ---

            // Show the video surface in the main app
            if (surfaceView != null) {
                surfaceView.setVisibility(View.VISIBLE);
            }

            // Restore the main app UI
            if (controls != null) controls.setVisibility(View.VISIBLE);
            if (message != null) message.setVisibility(View.VISIBLE);
            
            // Restore the action bar
            if (getSupportActionBar() != null) {
                getSupportActionBar().show();
            }
        }
    }

    protected void onSaveInstanceState (Bundle outState) {
        Log.d ("GStreamer", "Saving state, playing:" + is_playing_desired);
        outState.putBoolean("playing", is_playing_desired);
        super.onSaveInstanceState(outState);
    }

    protected void onDestroy() {
        // Stop error polling
        if (errorPollHandler != null) {
            errorPollHandler.removeCallbacksAndMessages(null);
        }
        nativeFinalize();
        super.onDestroy();
    }

    // Called from native code. This sets the content of the TextView from the UI thread.
    private void setMessage(final String message) {
        final TextView tv = (TextView) this.findViewById(R.id.textview_message);
        runOnUiThread (new Runnable() {
          public void run() {
            if (message != null && !message.isEmpty()) {
                tv.setText(message);
                tv.setVisibility(View.VISIBLE);
                
                // Auto-hide success messages after 3 seconds
                if (!message.toLowerCase().contains("error") && 
                    !message.toLowerCase().contains("failed")) {
                    tv.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            tv.setVisibility(View.GONE);
                        }
                    }, 3000);
                }
            } else {
                tv.setVisibility(View.GONE);
            }
          }
        });
    }
    
    // Called from native code when there's an error
    private void onGStreamerError(final String error) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                // Hide status indicator
                findViewById(R.id.status_container).setVisibility(View.GONE);
                
                // Show error in message area
                final TextView tv = (TextView) findViewById(R.id.textview_message);
                tv.setText("ERROR: " + error);
                tv.setVisibility(View.VISIBLE);
                tv.setTextColor(0xFFFF4444); // Red color for errors
                
                // Also show a toast for critical errors
                Toast.makeText(GstreamerMain.this, "Pipeline Error: " + error, Toast.LENGTH_LONG).show();
                Log.e("GStreamer", "Pipeline error: " + error);
                
                // Keep error visible - don't auto-hide
                is_playing_desired = false;
            }
        });
    }
    
    // Called from native code when pipeline state changes
    private void onGStreamerStateChanged(final String state) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                final View statusContainer = findViewById(R.id.status_container);
                final View loadingSpinner = findViewById(R.id.loading_spinner);
                final TextView statusText = (TextView) findViewById(R.id.status_text);
                final TextView messageView = (TextView) findViewById(R.id.textview_message);
                
                Log.i("GStreamer", "State changed to: " + state);
                
                // Hide error messages when state changes
                if (!state.equals("NULL") && !state.equals("ERROR")) {
                    messageView.setVisibility(View.GONE);
                }
                
                if (state.equals("PLAYING")) {
                    // Hide all status indicators when playing
                    statusContainer.setVisibility(View.GONE);
                } else if (state.equals("PAUSED")) {
                    // Show paused indicator without spinner
                    loadingSpinner.setVisibility(View.GONE);
                    statusText.setText("Paused");
                    statusContainer.setVisibility(View.VISIBLE);
                } else if (state.equals("READY") || state.equals("NULL")) {
                    // Show loading with spinner
                    loadingSpinner.setVisibility(View.VISIBLE);
                    statusText.setText("Buffering...");
                    statusContainer.setVisibility(View.VISIBLE);
                } else {
                    // For any other state (READY, NULL, etc), show with spinner
                    loadingSpinner.setVisibility(View.VISIBLE);
                    statusText.setText(state);
                    statusContainer.setVisibility(View.VISIBLE);
                }
            }
        });
    }

    // Called from native code. Native code calls this once it has created its pipeline and
    // the main loop is running, so it is ready to accept commands.
    private void onGStreamerInitialized () {
        Log.i("GStreamer", "Gst initialized. Restoring state, playing:" + is_playing_desired);
        // Restore previous playing state
        if (is_playing_desired) {
            nativePlay();
        } else {
            nativePause();
        }

        // Re-enable buttons, now that GStreamer is initialized
        if (menu != null) {
            final GstreamerMain activity = this;
            runOnUiThread(new Runnable() {
                public void run() {
                    activity.findViewById(R.id.button_play).setEnabled(true);
                    activity.findViewById(R.id.button_stop).setEnabled(true);
                }
            });
        }
    }
    
    // Check if camera permissions are granted, request if not
    private void checkCameraPermissions() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.i("GStreamer", "Camera/Audio permissions not granted, requesting...");
            cameraPermissionLauncher.launch(new String[] {
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            });
        } else {
            Log.i("GStreamer", "Camera/Audio permissions already granted");
        }
    }
    
    static {
        System.loadLibrary("gstreamer_android");
        System.loadLibrary("pipeliner");
        nativeClassInit();
    }

    public void surfaceChanged(SurfaceHolder holder, int format, int width,
            int height) {
        Log.d("GStreamer", "Surface changed to format " + format + " width "
                + width + " height " + height);
        nativeSurfaceInit (holder.getSurface());
    }

    public void surfaceCreated(SurfaceHolder holder) {
        Log.d("GStreamer", "Surface created: " + holder.getSurface());
    }

    public void surfaceDestroyed(SurfaceHolder holder) {
        Log.d("GStreamer", "Surface destroyed");
        nativeSurfaceFinalize ();
    }

}
