//
// Created by jcrowder on 11/19/2025.
//
// jni_utils.h

#ifndef GSTREAMER_PIPELINER_1_0_JNI_UTILS_H
#define GSTREAMER_PIPELINER_1_0_JNI_UTILS_H

#include <jni.h>
#include <pthread.h>
#include <glib.h>           /* For gchar* */
#include "gstreamer_app.h"  /* Including this to pull the CustomData definition */

// GLOBAL VARIABLES (will be defined in jni_utils.c)
extern JavaVM *java_vm;
extern pthread_key_t current_jni_env;
extern jfieldID custom_data_field_id;
extern jmethodID set_message_method_id;
extern jmethodID on_gstreamer_initialized_method_id;
extern jmethodID on_gstreamer_error_method_id;
extern jmethodID on_gstreamer_state_changed_method_id;
extern gchar *saved_pipeline_string;                     /* global pipeline string */

// UTILITY FUNCTIONS will be implemented in jni_utils.c
JNIEnv *get_jni_env (void);
void set_ui_message (const gchar *message, CustomData *data);
void set_ui_error (const gchar *error, CustomData *data);
void set_ui_state (const gchar *state, CustomData *data);
void detach_current_thread (void *env);

// JNI POINTER ACCESS MACROS
// Converts jlong field to a CustomData* pointer.
#if GLIB_SIZEOF_VOID_P == 8
# define GET_CUSTOM_DATA(env, thiz, fieldID) (CustomData*)(*env)->GetLongField (env, thiz, fieldID)
# define SET_CUSTOM_DATA(env, thiz, fieldID, data) (*env)->SetLongField (env, thiz, fieldID, (jlong)data)
#else
# define GET_CUSTOM_DATA(env, thiz, fieldID) (CustomData *)(jint)(*env)->GetLongField (env, thiz, fieldID)
# define SET_CUSTOM_DATA(env, thiz, fieldID, data) (*env)->SetLongField (env, thiz, fieldID, (jlong)(jint)data)
#endif

// A function for the gstreamer thread to call when the window is ready
void check_initialization_complete (JNIEnv *env, CustomData *data);


#endif //GSTREAMER_PIPELINER_1_0_JNI_UTILS_H
