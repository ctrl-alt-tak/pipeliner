//
// Created by jcrowder on 11/19/2025.
//
// jni_bridge.c

#include <jni.h>
#include <android/log.h>
#include <android/native_window_jni.h>
#include "gstreamer_app.h"
#include "jni_utils.h"

#include <glib.h>
#include <gst/video/video.h>

// JNI WRAPPER FUNCTIONS

JNIEXPORT void JNICALL
Java_com_kranzetech_pipeliner_GstreamerMain_nativeInit (JNIEnv *env, jobject thiz) {
    __android_log_print (ANDROID_LOG_INFO, "JNI_BRIDGE", "nativeInit: START - env=%p, thiz=%p", env, thiz);
    __android_log_print (ANDROID_LOG_INFO, "JNI_BRIDGE", "nativeInit: custom_data_field_id=%p", custom_data_field_id);
    
    CustomData *data = g_new0 (CustomData, 1);
    __android_log_print (ANDROID_LOG_INFO, "JNI_BRIDGE", "nativeInit: allocated CustomData at %p", data);
    
    SET_CUSTOM_DATA(env, thiz, custom_data_field_id, data);
    __android_log_print (ANDROID_LOG_INFO, "JNI_BRIDGE", "nativeInit: SET_CUSTOM_DATA complete");
    
    data->app = (*env)->NewGlobalRef (env, thiz);
    __android_log_print (ANDROID_LOG_INFO, "JNI_BRIDGE", "nativeInit: NewGlobalRef returned %p", data->app);
    
    if (!data->app) {
        __android_log_print (ANDROID_LOG_ERROR, "JNI_BRIDGE", "nativeInit: NewGlobalRef FAILED - cannot create global reference");
        return;
    }
    
    __android_log_print (ANDROID_LOG_INFO, "JNI_BRIDGE", "nativeInit: about to call gst_app_start");
    gst_app_start (data);
    __android_log_print (ANDROID_LOG_INFO, "JNI_BRIDGE", "nativeInit: gst_app_start returned");
    GST_DEBUG ("Created CustomData at %p and started thread", data);
}

JNIEXPORT void JNICALL
Java_com_kranzetech_pipeliner_GstreamerMain_nativeFinalize (JNIEnv *env, jobject thiz) {
    CustomData *data = GET_CUSTOM_DATA(env, thiz, custom_data_field_id);
    if (!data) return;
    
    // Mark the global ref as invalid BEFORE stopping, so callbacks won't try to use it
    jobject app_ref = data->app;
    data->app = NULL;
    
    gst_app_stop (data);

    if (app_ref) {
        GST_DEBUG ("Deleting GlobalRef for app object at %p", app_ref);
        (*env)->DeleteGlobalRef (env, app_ref);
    }

    // Destroy the error mutex before freeing CustomData
    pthread_mutex_destroy(&data->error_mutex);

    GST_DEBUG ("Freeing CustomData at %p", data);
    g_free (data);
}

// Set pipeline playing
JNIEXPORT void JNICALL
Java_com_kranzetech_pipeliner_GstreamerMain_nativePlay (JNIEnv *env, jobject thiz) {
    CustomData *data = GET_CUSTOM_DATA(env, thiz, custom_data_field_id);
    gst_app_set_state (data, GST_STATE_PLAYING);
}

// Set pipline paused
JNIEXPORT void JNICALL
Java_com_kranzetech_pipeliner_GstreamerMain_nativePause (JNIEnv *env, jobject thiz) {
    CustomData *data = GET_CUSTOM_DATA(env, thiz, custom_data_field_id);
    gst_app_set_state (data, GST_STATE_PAUSED);
}

// Set or update pipeline string globaly
JNIEXPORT void JNICALL
Java_com_kranzetech_pipeliner_GstreamerMain_nativeSetPipeline (JNIEnv *env, jobject thiz, jstring pipeline_string) {
    const gchar *str = (*env)->GetStringUTFChars (env, pipeline_string, 0);
    gst_app_set_pipeline_string(str);
    (*env)->ReleaseStringUTFChars (env, pipeline_string, str);
}

// Clean old pipeline and start a new one
JNIEXPORT void JNICALL
Java_com_kranzetech_pipeliner_GstreamerMain_nativeReinit (JNIEnv *env, jobject thiz) {
    CustomData *data = GET_CUSTOM_DATA(env, thiz, custom_data_field_id);
    if (!data) return;
    gst_app_reinit (data);
}

// Poll for errors (thread-safe)
JNIEXPORT jstring JNICALL
Java_com_kranzetech_pipeliner_GstreamerMain_nativeGetError (JNIEnv *env, jobject thiz) {
    CustomData *data = GET_CUSTOM_DATA(env, thiz, custom_data_field_id);
    if (!data) return NULL;
    
    gchar *error = gst_app_get_error(data);
    if (!error) return NULL;
    
    jstring jerror = (*env)->NewStringUTF(env, error);
    g_free(error);
    return jerror;
}

// Receives Android surface and passes the native window handle
JNIEXPORT void JNICALL
Java_com_kranzetech_pipeliner_GstreamerMain_nativeSurfaceInit (JNIEnv *env, jobject thiz, jobject surface) {
    CustomData *data = GET_CUSTOM_DATA(env, thiz, custom_data_field_id);
    if (!data) {
        __android_log_print(ANDROID_LOG_ERROR, "JNI_BRIDGE", "NULL CustomData in nativeSurfaceInit");
        return;
    }
    
    ANativeWindow *new_native_window = ANativeWindow_fromSurface(env, surface);
    if (!new_native_window) {
        __android_log_print(ANDROID_LOG_ERROR, "JNI_BRIDGE", "Failed to get ANativeWindow from surface");
        return;
    }
    
    gst_app_set_window(env, data, new_native_window);
}

// Release native window
JNIEXPORT void JNICALL
Java_com_kranzetech_pipeliner_GstreamerMain_nativeSurfaceFinalize (JNIEnv *env, jobject thiz) {
    CustomData *data = GET_CUSTOM_DATA(env, thiz, custom_data_field_id);
    if (!data) return;

    GST_DEBUG ("Finalizing native window %p", data->native_window);

    if (data->video_sink) {
    gst_video_overlay_set_window_handle (GST_VIDEO_OVERLAY (data->video_sink), (guintptr) NULL);
    gst_app_set_state (data, GST_STATE_READY);
    }

    if (data->native_window) {
        ANativeWindow_release (data->native_window);
    }

    data->native_window = NULL;
    data->initialized = FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_kranzetech_pipeliner_GstreamerMain_nativeClassInit (JNIEnv *env, jclass klass) {
    __android_log_print (ANDROID_LOG_INFO, "JNI_BRIDGE", "nativeClassInit: START");
    
    custom_data_field_id = (*env)->GetFieldID(env, klass, "native_custom_data", "J");
    __android_log_print (ANDROID_LOG_INFO, "JNI_BRIDGE", "nativeClassInit: custom_data_field_id = %p", custom_data_field_id);
    
    set_message_method_id = (*env)->GetMethodID(env, klass, "setMessage", "(Ljava/lang/String;)V");
    __android_log_print (ANDROID_LOG_INFO, "JNI_BRIDGE", "nativeClassInit: set_message_method_id = %p", set_message_method_id);
    
    on_gstreamer_initialized_method_id = (*env)->GetMethodID(env, klass, "onGStreamerInitialized", "()V");
    __android_log_print (ANDROID_LOG_INFO, "JNI_BRIDGE", "nativeClassInit: on_gstreamer_initialized_method_id = %p", on_gstreamer_initialized_method_id);
    
    on_gstreamer_error_method_id = (*env)->GetMethodID(env, klass, "onGStreamerError", "(Ljava/lang/String;)V");
    __android_log_print (ANDROID_LOG_INFO, "JNI_BRIDGE", "nativeClassInit: on_gstreamer_error_method_id = %p", on_gstreamer_error_method_id);
    
    on_gstreamer_state_changed_method_id = (*env)->GetMethodID(env, klass, "onGStreamerStateChanged", "(Ljava/lang/String;)V");
    __android_log_print (ANDROID_LOG_INFO, "JNI_BRIDGE", "nativeClassInit: on_gstreamer_state_changed_method_id = %p", on_gstreamer_state_changed_method_id);

    if (!custom_data_field_id || !set_message_method_id || !on_gstreamer_initialized_method_id || !on_gstreamer_error_method_id || !on_gstreamer_state_changed_method_id) {
        __android_log_print (ANDROID_LOG_ERROR, "JNI_BRIDGE", "Calling class does not implement all required methods");
        return JNI_FALSE;
    }
    __android_log_print (ANDROID_LOG_INFO, "JNI_BRIDGE", "nativeClassInit: SUCCESS - all method IDs initialized");
    return JNI_TRUE;
}

// JNI Registration

// List of implemented native methods
static JNINativeMethod methods[] = {
        {"nativeInit", "()V", (void *) Java_com_kranzetech_pipeliner_GstreamerMain_nativeInit},
        {"nativeFinalize", "()V", (void *) Java_com_kranzetech_pipeliner_GstreamerMain_nativeFinalize},
        {"nativePlay", "()V", (void *) Java_com_kranzetech_pipeliner_GstreamerMain_nativePlay},
        {"nativePause", "()V", (void *) Java_com_kranzetech_pipeliner_GstreamerMain_nativePause},
        {"nativeSurfaceInit", "(Ljava/lang/Object;)V", (void *) Java_com_kranzetech_pipeliner_GstreamerMain_nativeSurfaceInit},
        {"nativeSurfaceFinalize", "()V", (void *) Java_com_kranzetech_pipeliner_GstreamerMain_nativeSurfaceFinalize},
        {"nativeClassInit", "()Z", (void *) Java_com_kranzetech_pipeliner_GstreamerMain_nativeClassInit},
        {"nativeSetPipeline", "(Ljava/lang/String;)V", (void *) Java_com_kranzetech_pipeliner_GstreamerMain_nativeSetPipeline},
        {"nativeReinit", "()V", (void *) Java_com_kranzetech_pipeliner_GstreamerMain_nativeReinit},
        {"nativeGetError", "()Ljava/lang/String;", (void *) Java_com_kranzetech_pipeliner_GstreamerMain_nativeGetError}
};

JNIEXPORT jint
JNI_OnLoad (JavaVM *vm, void *reserved) {
    __android_log_print (ANDROID_LOG_INFO, "JNI_BRIDGE", "JNI_OnLoad: CALLED - vm=%p", vm);
    
    JNIEnv *env = NULL;
    java_vm = vm;
    __android_log_print (ANDROID_LOG_INFO, "JNI_BRIDGE", "JNI_OnLoad: Set java_vm=%p", java_vm);

    if ((*vm)->GetEnv (vm, (void **) &env, JNI_VERSION_1_4) != JNI_OK){
        __android_log_print (ANDROID_LOG_ERROR, "JNI_BRIDGE", "Could not retrieve JNI Env");
        return 0;
    }

    jclass klass = (*env)->FindClass (env, "com/android/pipeliner/GstreamerMain");
    __android_log_print (ANDROID_LOG_INFO, "JNI_BRIDGE", "JNI_OnLoad: Found class, registering natives");
    
    (*env)->RegisterNatives (env, klass, methods, G_N_ELEMENTS (methods));
    pthread_key_create (&current_jni_env, detach_current_thread);
    
    __android_log_print (ANDROID_LOG_INFO, "JNI_BRIDGE", "JNI_OnLoad: SUCCESS");
    return JNI_VERSION_1_4;
}

















