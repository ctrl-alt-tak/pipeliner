package com.android.pipeliner;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Environment;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class PipelineStorage {
    private static final String TAG = "PipelineStorage";
    private static final String PREFS_NAME = "pipeline_storage";
    private static final String KEY_PIPELINES = "pipelines_json";
    private static final String BACKUP_DIR_NAME = "GStreamerPipelines";
    
    private SharedPreferences prefs;
    private Context context;

    public PipelineStorage(Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        createBackupDirectory();
    }
    
    /**
     * Creates the backup directory in /sdcard/GStreamerPipelines if it doesn't exist
     */
    private void createBackupDirectory() {
        File backupDir = getBackupDirectory();
        if (backupDir != null && !backupDir.exists()) {
            if (backupDir.mkdirs()) {
                Log.i(TAG, "Created backup directory: " + backupDir.getAbsolutePath());
            } else {
                Log.e(TAG, "Failed to create backup directory: " + backupDir.getAbsolutePath());
            }
        }
    }
    
    /**
     * Public method to create backup directory after permission is granted
     */
    public void createBackupDirectoryWithPermission() {
        createBackupDirectory();
        // After creating directory, save all existing pipelines to individual files
        List<PipelineItem> pipelines = loadPipelines();
        savePipelinesToIndividualFiles(pipelines);
    }
    
    /**
     * Returns the backup directory File object
     * Path: /sdcard/GStreamerPipelines (requires MANAGE_EXTERNAL_STORAGE on Android 11+)
     */
    public File getBackupDirectory() {
        if (Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
            return new File("/sdcard", BACKUP_DIR_NAME);
        }
        return null;
    }

    public List<PipelineItem> loadPipelines() {
        List<PipelineItem> pipelines = new ArrayList<>();
        String json = prefs.getString(KEY_PIPELINES, "[]");
        
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                pipelines.add(new PipelineItem(
                    obj.getString("id"),
                    obj.getString("name"),
                    obj.getString("pipeline"),
                    obj.getLong("createdTime"),
                    obj.getLong("lastUsedTime"),
                    obj.getBoolean("isFavorite")
                ));
            }
        } catch (JSONException e) {
            e.printStackTrace();
        }
        
        return pipelines;
    }

    public void savePipelines(List<PipelineItem> pipelines) {
        JSONArray array = new JSONArray();
        
        for (PipelineItem item : pipelines) {
            try {
                JSONObject obj = new JSONObject();
                obj.put("id", item.getId());
                obj.put("name", item.getName());
                obj.put("pipeline", item.getPipeline());
                obj.put("createdTime", item.getCreatedTime());
                obj.put("lastUsedTime", item.getLastUsedTime());
                obj.put("isFavorite", item.isFavorite());
                array.put(obj);
            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
        
        String jsonString = array.toString();
        prefs.edit().putString(KEY_PIPELINES, jsonString).apply();
        
        // Save each pipeline to its own file
        savePipelinesToIndividualFiles(pipelines);
    }
    
    /**
     * Saves each pipeline to its own file in /sdcard/GStreamerPipelines/
     */
    private void savePipelinesToIndividualFiles(List<PipelineItem> pipelines) {
        File backupDir = getBackupDirectory();
        if (backupDir == null || !backupDir.exists()) {
            Log.w(TAG, "Backup directory not available");
            return;
        }
        
        // Clean up old files first, is this needed.

        File[] existingFiles = backupDir.listFiles((dir, name) -> name.endsWith(".gstpipe"));
        //if (existingFiles != null) {
        //    for (File file : existingFiles) {
        //        file.delete();
        //    }
        //}

        // Save each pipeline to individual file
        for (PipelineItem item : pipelines) {
            savePipelineToFile(item);
        }
    }
    
    /**
     * Saves a single pipeline to a .gstpipe file
     */
    private void savePipelineToFile(PipelineItem item) {
        File backupDir = getBackupDirectory();
        if (backupDir == null || !backupDir.exists()) {
            return;
        }
        
        // Sanitize filename
        String safeFileName = item.getName().replaceAll("[^a-zA-Z0-9_\\-]", "_");
        File pipelineFile = new File(backupDir, safeFileName + ".gstpipe");
        
        try {
            JSONObject obj = new JSONObject();
            obj.put("name", item.getName());
            obj.put("pipeline", item.getPipeline());
            
            try (FileOutputStream fos = new FileOutputStream(pipelineFile)) {
                fos.write(obj.toString(2).getBytes()); // Pretty print with indent
                fos.flush();
                Log.i(TAG, "Pipeline saved to: " + pipelineFile.getAbsolutePath());
            }
        } catch (JSONException | IOException e) {
            Log.e(TAG, "Failed to save pipeline to file: " + item.getName(), e);
        }
    }
    
    /**
     * Loads all .gstpipe files from the directory
     * @return List of pipeline files found
     */
    public File[] listPipelineFiles() {
        File backupDir = getBackupDirectory();
        if (backupDir == null || !backupDir.exists()) {
            return new File[0];
        }
        
        File[] files = backupDir.listFiles((dir, name) -> name.endsWith(".gstpipe"));
        return files != null ? files : new File[0];
    }
    
    /**
     * Imports a single pipeline from a .gstpipe file
     * @param file The .gstpipe file to import
     * @return true if import was successful
     */
    public boolean importPipelineFromFile(File file) {
        if (file == null || !file.exists()) {
            Log.e(TAG, "Import file does not exist");
            return false;
        }
        
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[(int) file.length()];
            fis.read(buffer);
            String json = new String(buffer);
            
            JSONObject obj = new JSONObject(json);
            PipelineItem item = new PipelineItem(
                java.util.UUID.randomUUID().toString(),
                obj.getString("name"),
                obj.getString("pipeline"),
                System.currentTimeMillis(),
                System.currentTimeMillis(),
                false
            );
            
            addPipeline(item);
            Log.i(TAG, "Pipeline imported from: " + file.getAbsolutePath());
            return true;
        } catch (IOException | JSONException e) {
            Log.e(TAG, "Failed to import pipeline from file", e);
            return false;
        }
    }

    public void addPipeline(PipelineItem item) {
        List<PipelineItem> pipelines = loadPipelines();
        pipelines.add(item);
        savePipelines(pipelines);
    }

    public void updatePipeline(PipelineItem item) {
        List<PipelineItem> pipelines = loadPipelines();
        for (int i = 0; i < pipelines.size(); i++) {
            if (pipelines.get(i).getId().equals(item.getId())) {
                pipelines.set(i, item);
                break;
            }
        }
        savePipelines(pipelines);
    }

    public void deletePipeline(String id) {
        List<PipelineItem> pipelines = loadPipelines();
        pipelines.removeIf(item -> item.getId().equals(id));
        savePipelines(pipelines);
    }

    public List<PipelineItem> getSortedPipelines(String sortBy) {
        List<PipelineItem> pipelines = loadPipelines();
        
        switch (sortBy) {
            case "name":
                Collections.sort(pipelines, new Comparator<PipelineItem>() {
                    @Override
                    public int compare(PipelineItem a, PipelineItem b) {
                        return a.getName().compareToIgnoreCase(b.getName());
                    }
                });
                break;
            case "recent":
                Collections.sort(pipelines, new Comparator<PipelineItem>() {
                    @Override
                    public int compare(PipelineItem a, PipelineItem b) {
                        return Long.compare(b.getLastUsedTime(), a.getLastUsedTime());
                    }
                });
                break;
            case "created":
                Collections.sort(pipelines, new Comparator<PipelineItem>() {
                    @Override
                    public int compare(PipelineItem a, PipelineItem b) {
                        return Long.compare(b.getCreatedTime(), a.getCreatedTime());
                    }
                });
                break;
        }
        
        // Favorites always on top
        Collections.sort(pipelines, new Comparator<PipelineItem>() {
            @Override
            public int compare(PipelineItem a, PipelineItem b) {
                return Boolean.compare(b.isFavorite(), a.isFavorite());
            }
        });
        
        return pipelines;
    }

    public String exportToJson() {
        return prefs.getString(KEY_PIPELINES, "[]");
    }

    public boolean importFromJson(String json) {
        try {
            JSONArray array = new JSONArray(json);
            // Validate structure before importing
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                obj.getString("name");
                obj.getString("pipeline");
            }
            prefs.edit().putString(KEY_PIPELINES, json).apply();
            return true;
        } catch (JSONException e) {
            e.printStackTrace();
            return false;
        }
    }
}
