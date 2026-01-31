package com.android.pipeliner;

import android.content.Context;
import android.content.SharedPreferences;

public class PipelineStatusTracker {
    private static final String PREFS_NAME = "pipeline_status";
    private static final String KEY_ACTIVE_PIPELINE = "active_pipeline_id";
    
    private SharedPreferences prefs;
    
    public PipelineStatusTracker(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
    
    public void setActivePipeline(String pipelineId) {
        prefs.edit().putString(KEY_ACTIVE_PIPELINE, pipelineId).apply();
    }
    
    public String getActivePipeline() {
        return prefs.getString(KEY_ACTIVE_PIPELINE, null);
    }
    
    public void clearActivePipeline() {
        prefs.edit().remove(KEY_ACTIVE_PIPELINE).apply();
    }
    
    public boolean isActive(String pipelineId) {
        String active = getActivePipeline();
        return active != null && active.equals(pipelineId);
    }
}
