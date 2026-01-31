package com.android.pipeliner;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.Settings;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.snackbar.Snackbar;
import android.view.LayoutInflater;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.List;

public class PipelineListActivity extends AppCompatActivity implements PipelineAdapter.OnPipelineClickListener {

    private RecyclerView recyclerView;
    private PipelineAdapter adapter;
    private PipelineStorage storage;
    private TextView emptyView;
    private TextView pipelineCount;
    private ActivityResultLauncher<Intent> videoPlayerLauncher;
    private ActivityResultLauncher<String> requestPermissionLauncher;
    private ActivityResultLauncher<Intent> manageStorageLauncher;
    private PipelineItem pipelineToShare; // Temp holder for share operation
    private static String lastProcessedImportUri = null; // Track last imported file

    private void checkStoragePermission() {
        // Android 11+ (API 30+) requires MANAGE_EXTERNAL_STORAGE for top-level directory access
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                // Show dialog explaining why we need this permission
                new AlertDialog.Builder(this)
                    .setTitle("Storage Access Required")
                    .setMessage("This app needs 'All files access' permission to save pipelines to /sdcard/GStreamerPipelines/.\n\nPlease enable it in the next screen.")
                    .setPositiveButton("Grant Access", (dialog, which) -> {
                        try {
                            Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                            intent.setData(Uri.parse("package:" + getPackageName()));
                            manageStorageLauncher.launch(intent);
                        } catch (Exception e) {
                            // Fallback to general settings
                            Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                            manageStorageLauncher.launch(intent);
                        }
                    })
                    .setNegativeButton("Cancel", (dialog, which) -> {
                        Toast.makeText(this, "Pipeline backups won't be saved without storage access", Toast.LENGTH_LONG).show();
                    })
                    .show();
            } else {
                storage.createBackupDirectoryWithPermission();
            }
        }
        // Android 6.0 - 9.0 (API 23-28) use runtime permissions
        else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            } else {
                storage.createBackupDirectoryWithPermission();
            }
        } else {
            // Android 10 or older versions
            storage.createBackupDirectoryWithPermission();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_pipeline_list);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setTitle("Pipeliner");
        }

        storage = new PipelineStorage(this);
        
        // Setup MANAGE_EXTERNAL_STORAGE launcher for Android 11+
        manageStorageLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    if (Environment.isExternalStorageManager()) {
                        storage.createBackupDirectoryWithPermission();
                        Toast.makeText(this, "Storage access granted. Backup directory created.", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(this, "Storage access denied. Pipeline backups won't be saved to /sdcard/", Toast.LENGTH_LONG).show();
                    }
                }
            }
        );
        
        // Setup permission request launcher for Android 6-9
        requestPermissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(),
            isGranted -> {
                if (isGranted) {
                    storage.createBackupDirectoryWithPermission();
                    Toast.makeText(this, "Storage permission granted. Backup directory created.", Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(this, "Storage permission denied. Pipeline backups won't be saved.", Toast.LENGTH_LONG).show();
                }
            }
        );
        
        // Check and request storage permission for Android 6.0+
        checkStoragePermission();
        
        // Load default templates if this is the first time
        PipelineTemplates.loadDefaultTemplatesIfEmpty(storage);
        
        videoPlayerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                // User came back from video player, just refresh the list
                loadPipelines();
            }
        );
        
        recyclerView = findViewById(R.id.pipelineRecyclerView);
        emptyView = findViewById(R.id.emptyView);
        pipelineCount = findViewById(R.id.pipelineCount);
        FloatingActionButton fabAdd = findViewById(R.id.fabAdd);

        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        
        loadPipelines();
        
        // Check if opened from .gstpipe file - do this AFTER views are initialized
        handleIncomingIntent(getIntent());
        
        // FAB to add new pipeline
        fabAdd.setOnClickListener(v -> showAddPipelineDialog());

        // Swipe to delete
        ItemTouchHelper itemTouchHelper = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT) {
            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, @NonNull RecyclerView.ViewHolder target) {
                return false;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
                int position = viewHolder.getAdapterPosition();
                List<PipelineItem> pipelines = storage.getSortedPipelines("recent");
                PipelineItem deletedItem = pipelines.get(position);
                
                storage.deletePipeline(deletedItem.getId());
                loadPipelines();
                
                Snackbar.make(recyclerView, "Pipeline deleted", Snackbar.LENGTH_LONG)
                    .setAction("UNDO", v -> {
                        storage.addPipeline(deletedItem);
                        loadPipelines();
                    })
                    .show();
            }
        });
        itemTouchHelper.attachToRecyclerView(recyclerView);
    }

    private void loadPipelines() {
        List<PipelineItem> pipelines = storage.getSortedPipelines("recent");
        
        // Update count (null check for when activity is launched via import intent)
        int count = pipelines.size();
        if (pipelineCount != null) {
            pipelineCount.setText(String.valueOf(count));
        }
        
        // Null checks for views that may not be initialized yet
        if (recyclerView != null && emptyView != null) {
            if (pipelines.isEmpty()) {
                recyclerView.setVisibility(View.GONE);
                emptyView.setVisibility(View.VISIBLE);
            } else {
                recyclerView.setVisibility(View.VISIBLE);
                emptyView.setVisibility(View.GONE);
            }
        }
        
        // Null check for recyclerView before setting adapter
        if (recyclerView != null) {
            if (adapter == null) {
                adapter = new PipelineAdapter(this, pipelines, this);
                recyclerView.setAdapter(adapter);
            } else {
                adapter.updatePipelines(pipelines);
            }
        }
    }

    private void showAddPipelineDialog() {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_pipeline_edit, null);
        EditText nameInput = dialogView.findViewById(R.id.pipelineNameInput);
        EditText pipelineInput = dialogView.findViewById(R.id.pipelineInput);

        new AlertDialog.Builder(this)
            .setTitle("Add New Pipeline")
            .setView(dialogView)
            .setPositiveButton("Add", (dialog, which) -> {
                String name = nameInput.getText().toString().trim();
                String pipeline = pipelineInput.getText().toString().trim();
                
                if (name.isEmpty() || pipeline.isEmpty()) {
                    Toast.makeText(this, "Name and pipeline cannot be empty", Toast.LENGTH_SHORT).show();
                    return;
                }
                
                PipelineItem item = new PipelineItem(name, pipeline);
                storage.addPipeline(item);
                loadPipelines();
                Toast.makeText(this, "Pipeline added", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    @Override
    public void onPipelineClick(PipelineItem item) {
        // Update last used time
        item.setLastUsedTime(System.currentTimeMillis());
        storage.updatePipeline(item);
        
        // Launch video player activity
        Intent intent = new Intent(this, GstreamerMain.class);
        intent.putExtra("pipeline", item.getPipeline());
        intent.putExtra("name", item.getName());
        videoPlayerLauncher.launch(intent);
    }

    @Override
    public void onPipelineDelete(PipelineItem item) {
        new AlertDialog.Builder(this)
            .setTitle("Delete Pipeline")
            .setMessage("Are you sure you want to delete '" + item.getName() + "'?")
            .setPositiveButton("Delete", (dialog, which) -> {
                storage.deletePipeline(item.getId());
                loadPipelines();
                Toast.makeText(this, "Pipeline deleted", Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    @Override
    public void onPipelineEdit(PipelineItem item) {
        storage.updatePipeline(item);
        loadPipelines();
        Toast.makeText(this, "Pipeline updated", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onPipelineFavorite(PipelineItem item) {
        item.setFavorite(!item.isFavorite());
        storage.updatePipeline(item);
        loadPipelines();
    }
    
    @Override
    public void onPipelineShare(PipelineItem item) {
        try {
            // Create a temporary file in cache directory
            java.io.File cacheDir = getCacheDir();
            String filename = item.getName().replaceAll("[^a-zA-Z0-9_-]", "_") + ".gstpipe";
            java.io.File gstpipeFile = new java.io.File(cacheDir, filename);
            
            // Write pipeline to file
            java.io.FileWriter writer = new java.io.FileWriter(gstpipeFile);
            writer.write(item.getPipeline());
            writer.close();
            
            // Create URI using FileProvider
            Uri fileUri = androidx.core.content.FileProvider.getUriForFile(
                this,
                getApplicationContext().getPackageName() + ".fileprovider",
                gstpipeFile
            );
            
            // Create share intent
            Intent shareIntent = new Intent(Intent.ACTION_SEND);
            shareIntent.setType("application/octet-stream");
            shareIntent.putExtra(Intent.EXTRA_STREAM, fileUri);
            shareIntent.putExtra(Intent.EXTRA_SUBJECT, item.getName());
            shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            
            startActivity(Intent.createChooser(shareIntent, "Share Pipeline File"));
        } catch (Exception e) {
            Toast.makeText(this, "Share failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }
    
    private void handleIncomingIntent(Intent intent) {
        if (intent != null && Intent.ACTION_VIEW.equals(intent.getAction())) {
            Uri uri = intent.getData();
            if (uri != null) {
                // Check if we've already imported this exact URI
                String uriString = uri.toString();
                if (uriString.equals(lastProcessedImportUri)) {
                    android.util.Log.i("PipelineListActivity", "Skipping duplicate import of: " + uriString);
                    return;
                }
                lastProcessedImportUri = uriString;
                importSinglePipeline(uri);
            }
        }
    }
    
    private void importSinglePipeline(Uri uri) {
        try {
            InputStream is = getContentResolver().openInputStream(uri);
            BufferedReader reader = new BufferedReader(new InputStreamReader(is));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (sb.length() > 0) {
                    sb.append(" ");
                }
                sb.append(line);
            }
            reader.close();
            
            String pipeline = sb.toString().trim();
            if (!pipeline.isEmpty()) {
                // Extract filename as pipeline name
                String filename = uri.getLastPathSegment();
                if (filename != null && filename.contains(".gstpipe")) {
                    filename = filename.substring(0, filename.lastIndexOf(".gstpipe"));
                }
                if (filename == null || filename.isEmpty()) {
                    filename = "Imported Pipeline";
                }
                
                PipelineItem newItem = new PipelineItem(filename, pipeline);
                storage.addPipeline(newItem);
                loadPipelines();
                Toast.makeText(this, "Pipeline imported: " + filename, Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Pipeline file is empty", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Import failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
            android.util.Log.e("PipelineListActivity", "Import error", e);
        }
    }
    
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        lastProcessedImportUri = null; // Reset for new intent
        handleIncomingIntent(intent);
    }
}