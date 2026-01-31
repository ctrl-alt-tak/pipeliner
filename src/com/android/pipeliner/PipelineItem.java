package com.android.pipeliner;

import java.util.UUID;

public class PipelineItem {
    private String id;
    private String name;
    private String pipeline;
    private long createdTime;
    private long lastUsedTime;
    private boolean isFavorite;
    private String category; // "test", "rtsp", "udp", "file", "effects", "custom"
    private int categoryColor; // Color for visual distinction

    public PipelineItem(String name, String pipeline) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.pipeline = pipeline;
        this.createdTime = System.currentTimeMillis();
        this.lastUsedTime = System.currentTimeMillis();
        this.isFavorite = false;
        this.category = detectCategory(pipeline);
        this.categoryColor = getCategoryColor(this.category);
    }

    // Constructor for loading from storage
    public PipelineItem(String id, String name, String pipeline, long createdTime, long lastUsedTime, boolean isFavorite) {
        this.id = id;
        this.name = name;
        this.pipeline = pipeline;
        this.createdTime = createdTime;
        this.lastUsedTime = lastUsedTime;
        this.isFavorite = isFavorite;
        this.category = detectCategory(pipeline);
        this.categoryColor = getCategoryColor(this.category);
    }

    private String detectCategory(String pipeline) {
        if (pipeline.contains("videotestsrc")) return "test";
        if (pipeline.contains("rtspsrc")) return "rtsp";
        if (pipeline.contains("udpsrc")) return "udp";
        if (pipeline.contains("filesrc")) return "file";
        if (pipeline.contains("edge") || pipeline.contains("aging") || pipeline.contains("mixer")) return "effects";
        return "custom";
    }

    private int getCategoryColor(String category) {
        switch (category) {
            case "test": return 0xFFFF9800; // Orange
            case "rtsp": return 0xFF03A9F4; // Blue
            case "udp": return 0xFF4CAF50; // Green
            case "custom": return 0xFF9C27B0; // Purple
            default: return 0xFF90A4AE; // Light grey
        }
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getPipeline() { return pipeline; }
    public long getCreatedTime() { return createdTime; }
    public long getLastUsedTime() { return lastUsedTime; }
    public boolean isFavorite() { return isFavorite; }
    public String getCategory() { return category; }
    public int getCategoryColor() { return categoryColor; }

    public void setName(String name) { this.name = name; }
    public void setPipeline(String pipeline) {
        this.pipeline = pipeline;
        this.category = detectCategory(pipeline);
        this.categoryColor = getCategoryColor(this.category);
    }
    public void setLastUsedTime(long time) { this.lastUsedTime = time; }
    public void setFavorite(boolean favorite) { this.isFavorite = favorite; }
}
