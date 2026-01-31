//
// Created by jcrowder on 11/19/2025.
//
//gstreamer_app.c

#include "gstreamer_app.h"
#include <pthread.h>
#include "jni_utils.h"
#include <gst/video/video.h>
#include <gst/gst.h>
#include <android/log.h>

// GLOBAL VARIABLES
pthread_t gst_app_thread;
GST_DEBUG_CATEGORY_STATIC (debug_category);
#define GST_CAT_DEFAULT debug_category

// FORWARD DECLARATIONS
static void error_cb (GstBus * bus, GstMessage * msg, CustomData * data);
static void state_changed_cb (GstBus * bus, GstMessage * msg, CustomData * data);

// PRIVATE WORKER THREAD FUNCTION

// Main method for native code
static void *
app_function (void *userdata)
{
    GstBus *bus;
    CustomData *data = (CustomData *) userdata;
    GSource *bus_source;
    GError *error = NULL;

    GST_DEBUG_CATEGORY_INIT (debug_category, "pipeliner", 0, "Pipeliner");
    gst_debug_set_threshold_for_name ("pipeliner", GST_LEVEL_DEBUG);
    
    // Set GStreamer debug level to maximum
    gst_debug_set_default_threshold(GST_LEVEL_LOG);
    
    GST_DEBUG ("Worker thread started. CustomData at %p", data);

    // Initialize error handling
    pthread_mutex_init(&data->error_mutex, NULL);
    data->error_message = NULL;

    data->context = g_main_context_new ();
    g_main_context_push_thread_default (data->context);

    // Build gstreamer pipeline
    const gchar *launch_string = NULL;
    if (saved_pipeline_string && *saved_pipeline_string) {
        launch_string = saved_pipeline_string;
        GST_INFO("Using custom pipeline %s", launch_string);
    } else {
        // Fallback to safe pipeline
        launch_string = "videotestsrc pattern=ball ! videoconvert ! textoverlay text=FALLBACK font-desc=28 ! autovideosink";
        GST_INFO("Using default fallback pipeline %s", launch_string);
    }

    data->pipeline = gst_parse_launch (launch_string, &error);
    if (error) {
        gchar *message = g_strdup_printf ("Unable to build pipeline: %s", error->message);
        GST_ERROR("%s", message);
        
        // Store error in mutex-protected buffer AND call UI directly
        pthread_mutex_lock(&data->error_mutex);
        if (data->error_message) {
            g_free(data->error_message);
        }
        data->error_message = g_strdup(message);
        pthread_mutex_unlock(&data->error_mutex);
        
        set_ui_message (message, data);
        g_free (message);
        g_clear_error (&error);
        
        // Cleanup and exit - pipeline is NULL, can't continue
        // NOTE: Do NOT destroy error_mutex here - it must live as long as CustomData
        data->pipeline = NULL;
        g_main_context_pop_thread_default (data->context);
        g_main_context_unref (data->context);
        data->context = NULL;
        return NULL;
    }

    gst_element_set_state (data->pipeline, GST_STATE_READY);

    data->video_sink = gst_bin_get_by_interface (GST_BIN (data->pipeline), GST_TYPE_VIDEO_OVERLAY);
    if (!data->video_sink) {
        GST_ERROR("Could not find video sink in pipeline");
    } else {
        // If we already have a native window (e.g., after reinit), apply it now
        if (data->native_window) {
            GST_DEBUG("Applying existing native window %p to new pipeline", data->native_window);
            gst_video_overlay_set_window_handle (GST_VIDEO_OVERLAY (data->video_sink), (guintptr) data->native_window);
        }
    }

    bus = gst_element_get_bus (data->pipeline);
    bus_source = gst_bus_create_watch (bus);
    g_source_set_callback (bus_source, (GSourceFunc) gst_bus_async_signal_func, NULL, NULL);
    g_source_attach (bus_source, data->context);
    g_source_unref (bus_source);
    g_signal_connect (G_OBJECT (bus), "message::error", (GCallback) error_cb, data);
    g_signal_connect (G_OBJECT (bus), "message::state-changed", (GCallback) state_changed_cb, data);
    gst_object_unref (bus);

    GST_DEBUG("Entering main loop... (CustomData:%p)", data);
    data->main_loop = g_main_loop_new (data->context, FALSE);
    
    // NOTE: Do NOT call check_initialization_complete here
    // It makes JNI calls which can only be done from the UI thread.
    // It will be called from gst_app_set_window when the surface is ready.
    
    g_main_loop_run (data->main_loop);
    GST_DEBUG("Exiting main loop... (CustomData:%p)", data);

    // More cleanup...
    if (data->pipeline) {
        gst_element_set_state (data->pipeline, GST_STATE_NULL);
    }

    if (data->video_sink) {
        gst_object_unref (data->video_sink);
        data->video_sink = NULL;
    }
    if (data->pipeline) {
        gst_object_unref (data->pipeline);
        data->pipeline = NULL;
    }

    g_main_loop_unref (data->main_loop);
    data->main_loop = NULL;
    
    // Clean up error storage
    // NOTE: Do NOT destroy error_mutex here - it will be destroyed in nativeFinalize
    pthread_mutex_lock(&data->error_mutex);
    if (data->error_message) {
        g_free(data->error_message);
        data->error_message = NULL;
    }
    pthread_mutex_unlock(&data->error_mutex);
    
    g_main_context_pop_thread_default (data->context);
    g_main_context_unref (data->context);
    data->context = NULL;

    return NULL;
}

// Private bus handlers
// Get errors from bus and show them in UI
static void
error_cb (GstBus * bus, GstMessage * msg, CustomData * data) {
    if (!data) {
        GST_ERROR("NULL data in error_cb");
        return;
    }
    
    GError *err;
    gchar *debug_info;
    gchar *message_string;

    gst_message_parse_error(msg, &err, &debug_info);
    message_string = g_strdup_printf ("Error received from element %s: %s",
                                      GST_OBJECT_NAME (msg->src), err->message);
    GST_ERROR("%s", message_string);
    if (debug_info) {
        GST_ERROR("Debug info: %s", debug_info);
    }
    
    // Store error message in thread-safe manner for Java to poll
    pthread_mutex_lock(&data->error_mutex);
    if (data->error_message) {
        g_free(data->error_message);
    }
    data->error_message = g_strdup(message_string);
    pthread_mutex_unlock(&data->error_mutex);
    
    // Also call UI directly like tutorial-3
    set_ui_message (message_string, data);
    
    g_free (message_string);
    g_clear_error(&err);
    g_free(debug_info);
    
    if (data->pipeline) {
        gst_element_set_state(data->pipeline, GST_STATE_NULL);
    }
}

// Notify UI of pipeline state changes
static void
state_changed_cb (GstBus * bus, GstMessage * msg, CustomData * data) {
    if (!data || !data->pipeline) {
        GST_ERROR("NULL data or pipeline in state_changed_cb");
        return;
    }
    
    GstState old_state, new_state, pending_state;
    gst_message_parse_state_changed(msg, &old_state, &new_state, &pending_state);
    /* Only pay attention to messages coming from the pipeline, not its children */
    if (GST_MESSAGE_SRC (msg) == GST_OBJECT (data->pipeline)) {
        gchar *message = g_strdup_printf ("State changed to %s",
                                          gst_element_state_get_name (new_state));
        GST_INFO("%s", message);
        set_ui_message (message, data);
        g_free (message);
    }
}

// Public control functions will be called by JNI_bridge.c

// Start gstreamer thread
void
gst_app_start (CustomData *data){
    GST_DEBUG("Starting gstreamer thread...");
    pthread_create (&gst_app_thread, NULL, app_function, data);
}

// Stop gstreamer thread
void
gst_app_stop (CustomData *data) {
    if (!data) return;

    if (data->main_loop) {
        GST_DEBUG("Stopping gstreamer thread...");
        g_main_loop_quit(data->main_loop);
    }

    GST_DEBUG("Waiting on thread clean up...");
    pthread_join(gst_app_thread, NULL);
}

// Set state
void
gst_app_set_state (CustomData *data, GstState state) {
    if (!data || !data->pipeline) return;
    GST_DEBUG("Setting pipeline state to %s", gst_element_state_get_name(state));
    gst_element_set_state (data->pipeline, state);
}

// Set app window
void
gst_app_set_window (JNIEnv *env, CustomData *data, ANativeWindow *window) {
    if (!data) {
        GST_ERROR ("NULL CustomData in gst_app_set_window");
        return;
    }

    GST_DEBUG ("Received surface %p (native window %p)", window, window);

    if (data->native_window) {
        ANativeWindow_release (data->native_window);
        if (data->native_window == window) {
            GST_DEBUG ("New native window is the same as the previous one %p", data->native_window);
            if (data->video_sink) {
                gst_video_overlay_expose (GST_VIDEO_OVERLAY (data->video_sink));
                gst_video_overlay_expose (GST_VIDEO_OVERLAY (data->video_sink));
            }
            return;
        } else {
            GST_DEBUG ("Released previous native window %p", data->native_window);
            data->initialized = FALSE;
        }
    }
    
    data->native_window = window;

    check_initialization_complete (env, data);
}

// Save custom pipelines
void
gst_app_set_pipeline_string(const gchar *new_string) {
    if (saved_pipeline_string) {
        g_free(saved_pipeline_string);
    }
    saved_pipeline_string = g_strdup(new_string);
    GST_DEBUG("Saved custom pipeline: %s", saved_pipeline_string);
}

// Thread-safe error polling for Java
gchar*
gst_app_get_error (CustomData *data) {
    if (!data) {
        return NULL;
    }
    
    pthread_mutex_lock(&data->error_mutex);
    gchar *error = data->error_message;
    data->error_message = NULL;  // Clear after reading
    pthread_mutex_unlock(&data->error_mutex);
    
    return error;  // Caller must g_free() this
}

// Reinit pipeline
void
gst_app_reinit(CustomData *data) {
    gst_app_stop(data);
    data->initialized = FALSE;
    gst_app_start(data);
    GST_DEBUG("Reinitialized gstreamer with new pipeline");
}

