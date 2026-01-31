package com.android.pipeliner;

import android.content.Context;
import android.util.AttributeSet;
import android.util.Log;
import android.view.SurfaceView;
import android.view.View;

// A simple SurfaceView whose width and height can be set from the outside
public class GStreamerSurfaceView extends SurfaceView {
    public int media_width = 1920;
    public int media_height = 1080;

    // Mandatory constructors, they do not do much
    public GStreamerSurfaceView(Context context, AttributeSet attrs,
            int defStyle) {
        super(context, attrs, defStyle);
    }

    public GStreamerSurfaceView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public GStreamerSurfaceView (Context context) {
        super(context);
    }

    // Called by the layout manager to find out our size and give us some rules.
    // We will try to maximize our size, and preserve the media's aspect ratio if
    // we are given the freedom to do so.
    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int wsize = View.MeasureSpec.getSize(widthMeasureSpec);
        int hsize = View.MeasureSpec.getSize(heightMeasureSpec);
        
        // Fit video inside available space (letterbox) to avoid cropping
        float videoAspect = (float) media_width / media_height;
        float viewAspect = (float) wsize / hsize;
        
        int width, height;
        
        // Fit inside the screen without cropping
        if (videoAspect > viewAspect) {
            // Video is wider - fit to width
            width = wsize;
            height = (int) (wsize / videoAspect);
        } else {
            // Video is taller - fit to height
            height = hsize;
            width = (int) (hsize * videoAspect);
        }
        
        Log.i("GStreamer", "onMeasure: media=" + media_width + "x" + media_height + 
              " view=" + wsize + "x" + hsize + " final=" + width + "x" + height);
        
        setMeasuredDimension(width, height);
    }

}
