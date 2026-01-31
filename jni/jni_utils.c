//
// Created by jcrowder on 11/19/2025.
//
// jni_utils.c

#include "jni_utils.h"
#include <android/log.h> /* This is for direct logging */
#include <gst/video/video.h>

// Define global variables
JavaVM *java_vm = NULL;
pthread_key_t current_jni_env;
jfieldID custom_data_field_id = NULL;
jmethodID set_message_method_id = NULL;
jmethodID on_gstreamer_initialized_method_id = NULL;
jmethodID on_gstreamer_error_method_id = NULL;
jmethodID on_gstreamer_state_changed_method_id = NULL;
gchar *saved_pipeline_string = NULL;
extern pthread_t gst_app_thread;               /* Thread handle will be defined in gstreamer_app.c to manage thread lifecycle */

// PRIVATE THREAD ATTACH AND DETACH FUNCTIONS

// Register thread with VM
static JNIEnv *
attach_current_thread (void)
{
    JNIEnv *env;
    JavaVMAttachArgs args;
    args.version = JNI_VERSION_1_4;
    args.name = NULL;
    args.group = NULL;

    if ((*java_vm)->AttachCurrentThread (java_vm, &env, &args) < 0){
        __android_log_print(ANDROID_LOG_ERROR, "JNI_UTILS", "Failed to attach current thread");
        return NULL;
    }
    return env;
}

// Unregister thread with VM
void
detach_current_thread (void *env)
{
    (*java_vm)->DetachCurrentThread (java_vm);
}

// PUBLIC JNI UTILITY FUNCTIONS

// Retrieve the JNI environment for thread
JNIEnv *
get_jni_env (void)
{
    __android_log_print(ANDROID_LOG_INFO, "JNI_UTILS", "get_jni_env: ENTER - thread=%p", pthread_self());
    __android_log_print(ANDROID_LOG_INFO, "JNI_UTILS", "get_jni_env: java_vm=%p, current_jni_env=%p", java_vm, (void*)(intptr_t)current_jni_env);
    
    JNIEnv *env;
    if ((env = pthread_getspecific (current_jni_env)) == NULL) {
        __android_log_print(ANDROID_LOG_INFO, "JNI_UTILS", "get_jni_env: No cached env, attaching thread...");
        env = attach_current_thread();
        __android_log_print(ANDROID_LOG_INFO, "JNI_UTILS", "get_jni_env: attach_current_thread returned %p", env);
        if (env) {
            pthread_setspecific(current_jni_env, env);
            __android_log_print(ANDROID_LOG_INFO, "JNI_UTILS", "get_jni_env: Cached env for future calls");
        } else {
            __android_log_print(ANDROID_LOG_ERROR, "JNI_UTILS", "get_jni_env: attach_current_thread FAILED");
        }
    } else {
        __android_log_print(ANDROID_LOG_INFO, "JNI_UTILS", "get_jni_env: Using cached env=%p", env);
    }
    return env;
}

// Change UI TextView content
void
set_ui_message (const gchar * message, CustomData * data)
{
    if (!message) {
        __android_log_print(ANDROID_LOG_WARN, "JNI_UTILS", "NULL message in set_ui_message");
        return;
    }
    
    if (!data) {
        __android_log_print(ANDROID_LOG_ERROR, "JNI_UTILS", "NULL data in set_ui_message");
        return;
    }
    
    if (!data->app) {
        __android_log_print(ANDROID_LOG_WARN, "JNI_UTILS", "NULL data->app in set_ui_message - UI not ready yet");
        return;
    }
    
    JNIEnv *env = get_jni_env ();
    if (!env) {
        __android_log_print(ANDROID_LOG_ERROR, "JNI_UTILS", "Failed to get JNI environment in set_ui_message");
        return;
    }
    
    if (!set_message_method_id) {
        __android_log_print(ANDROID_LOG_WARN, "JNI_UTILS", "set_message_method_id not initialized - skipping UI update");
        return;
    }
    
    // Defensive: verify env is usable by checking for pending exceptions first
    if ((*env)->ExceptionCheck(env)) {
        __android_log_print(ANDROID_LOG_WARN, "JNI_UTILS", "Pending exception before NewStringUTF");
        (*env)->ExceptionClear(env);
        return;
    }
    
    jstring jmessage = (*env)->NewStringUTF (env, message);
    if (!jmessage || (*env)->ExceptionCheck(env)) {
        __android_log_print(ANDROID_LOG_ERROR, "JNI_UTILS", "Failed to create jstring");
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
        }
        return;
    }
    
    (*env)->CallVoidMethod (env, data->app, set_message_method_id, jmessage);

    if ((*env)->ExceptionCheck (env)){
        __android_log_print(ANDROID_LOG_ERROR, "JNI_UTILS", "Exception thrown in CallVoidMethod");
        (*env)->ExceptionDescribe(env);
        (*env)->ExceptionClear (env);
    }
    (*env)->DeleteLocalRef (env, jmessage);
}

// Send error message to UI
void
set_ui_error (const gchar * error, CustomData * data)
{
    if (!data) {
        __android_log_print(ANDROID_LOG_ERROR, "JNI_UTILS", "NULL data in set_ui_error");
        return;
    }
    
    if (!data->app) {
        __android_log_print(ANDROID_LOG_ERROR, "JNI_UTILS", "NULL data->app in set_ui_error");
        return;
    }
    
    if (!on_gstreamer_error_method_id) {
        __android_log_print(ANDROID_LOG_WARN, "JNI_UTILS", "on_gstreamer_error_method_id not initialized - skipping error UI update");
        return;
    }
    
    JNIEnv *env = get_jni_env ();
    if (!env) {
        __android_log_print(ANDROID_LOG_ERROR, "JNI_UTILS", "Failed to get JNI environment in set_ui_error");
        return;
    }
    
    jstring jerror = (*env)->NewStringUTF (env, error);
    if (!jerror) {
        __android_log_print(ANDROID_LOG_ERROR, "JNI_UTILS", "Failed to create jstring in set_ui_error");
        return;
    }
    
    (*env)->CallVoidMethod (env, data->app, on_gstreamer_error_method_id, jerror);

    if ((*env)->ExceptionCheck (env)){
        __android_log_print(ANDROID_LOG_ERROR, "JNI_UTILS", "Exception thrown in set_ui_error");
        (*env)->ExceptionClear (env);
    }
    (*env)->DeleteLocalRef (env, jerror);
}

// Send state change message to UI
void
set_ui_state (const gchar * state, CustomData * data)
{
    if (!data) {
        __android_log_print(ANDROID_LOG_ERROR, "JNI_UTILS", "NULL data in set_ui_state");
        return;
    }
    
    if (!data->app) {
        __android_log_print(ANDROID_LOG_ERROR, "JNI_UTILS", "NULL app in set_ui_state");
        return;
    }
    
    if (!on_gstreamer_state_changed_method_id) {
        __android_log_print(ANDROID_LOG_ERROR, "JNI_UTILS", "NULL method ID in set_ui_state");
        return;
    }
    
    JNIEnv *env = get_jni_env ();
    if (!env) {
        __android_log_print(ANDROID_LOG_ERROR, "JNI_UTILS", "Failed to get JNI environment in set_ui_state");
        return;
    }
    
    jstring jstate = (*env)->NewStringUTF (env, state);
    (*env)->CallVoidMethod (env, data->app, on_gstreamer_state_changed_method_id, jstate);

    if ((*env)->ExceptionCheck (env)){
        __android_log_print(ANDROID_LOG_ERROR, "JNI_UTILS", "Exception thrown in set_ui_state");
        (*env)->ExceptionClear (env);
    }
    (*env)->DeleteLocalRef (env, jstate);
}

// Check conditions to report that gstreamer is init
void
check_initialization_complete (JNIEnv *env, CustomData * data)
{
    if (!data) {
        __android_log_print(ANDROID_LOG_ERROR, "JNI_UTILS", "NULL data in check_initialization_complete");
        return;
    }
    
    if (!env) {
        __android_log_print(ANDROID_LOG_ERROR, "JNI_UTILS", "NULL JNI environment in check_initialization_complete");
        return;
    }
    
    if (!data->initialized && data->native_window && data->main_loop && data->video_sink && data->app){
        if (!on_gstreamer_initialized_method_id) {
            __android_log_print(ANDROID_LOG_WARN, "JNI_UTILS", "on_gstreamer_initialized_method_id not initialized - deferring");
            return;
        }
        gst_video_overlay_set_window_handle (GST_VIDEO_OVERLAY (data->video_sink), (guintptr) data->native_window);
        (*env)->CallVoidMethod (env, data->app, on_gstreamer_initialized_method_id);
        if ((*env)->ExceptionCheck (env)){
            __android_log_print(ANDROID_LOG_ERROR, "JNI_UTILS", "Exception thrown in check_initialization_complete (onGStreamerInitialized)");
            (*env)->ExceptionClear (env);
        }
        data->initialized = TRUE;
    }
}