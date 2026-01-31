//
// Created by jcrowder on 11/19/2025.
//
// gstreamer_app.h

#ifndef GSTREAMER_PIPELINER_1_0_GSTREAMER_APP_H
#define GSTREAMER_PIPELINER_1_0_GSTREAMER_APP_H

// Include Dependencies for Gstreamer
#include <gst/gst.h>
#include <android/native_window.h>
#include <jni.h>
#include <pthread.h>

typedef struct _CustomData {
    jobject app;                  /* Application instance (Global Reference) */
    GstElement *pipeline;         /* The running pipeline */
    GMainContext *context;        /* GLib context */
    GMainLoop *main_loop;         /* GLib main loop */
    gboolean initialized;         /* Flag for initialized status */
    GstElement *video_sink;       /* The video sink element */
    ANativeWindow *native_window; /* The Android native window */
    gchar *error_message;         /* Last error message (thread-safe storage) */
    pthread_mutex_t error_mutex;  /* Mutex for error message access */
} CustomData;

// PUBLIC CONTROL FUNCTIONS will be implemented in gstreamer_app.c
// These functions wrap thread creation and control to keep the JNI_bridge clean.

// Function to start the Gstreamer worker thread
void gst_app_start (CustomData *data);

// Function to get and clear the last error (thread-safe)
gchar* gst_app_get_error (CustomData *data);

// Function to stop the Gstreamer worker thread
void gst_app_stop (CustomData *data);

// Control Functions
void gst_app_set_state (CustomData *data, GstState state);
void gst_app_set_window (JNIEnv *env, CustomData *data, ANativeWindow *window);
void gst_app_set_pipeline_string(const gchar *new_string);
void gst_app_reinit(CustomData *data);                               /* This allows for dynamic pipeline changes */


#endif //GSTREAMER_PIPELINER_1_0_GSTREAMER_APP_H
