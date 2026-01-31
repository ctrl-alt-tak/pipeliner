package com.android.pipeliner;

import java.util.ArrayList;
import java.util.List;

public class PipelineTemplates {
    
    public static List<PipelineItem> getDefaultTemplates() {
        List<PipelineItem> templates = new ArrayList<>();

        templates.add(new PipelineItem(
             "VAST",
             "udpsrc address=239.255.1.2 port=1650 multicast-iface=tun0 ! application/x-rtp,media=video,clock-rate=90000,encoding-name=AV1 ! rtpjitterbuffer latency=100 ! rtpav1depay ! av1parse ! dav1ddec n-threads=8 ! autovideosink"
        ));

        templates.add(new PipelineItem(
            "CDS_HIGH_LOW",
            "udpsrc address=224.0.1.2 port=3000 ! queue2 ! tsparse ! tsdemux ! h264parse ! avdec_h264 ! glimagesink"
        ));
        
        // Mark favorites
        templates.get(0).setFavorite(true); // VAST is favorite
        templates.get(1).setFavorite(true); // CDS_HIGH_LOW is favorite
        
        return templates;
    }
    
    public static void loadDefaultTemplatesIfEmpty(PipelineStorage storage) {
        List<PipelineItem> existing = storage.loadPipelines();
        if (existing.isEmpty()) {
            List<PipelineItem> templates = getDefaultTemplates();
            for (PipelineItem template : templates) {
                storage.addPipeline(template);
            }
        }
    }
}
