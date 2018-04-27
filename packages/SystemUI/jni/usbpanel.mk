LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)

LOCAL_SRC_FILES:= \
    com_android_systemui_settings_UsbPanelBrightnessController.cpp \

LOCAL_C_INCLUDES += \
    $(JNI_H_INCLUDE) \
    $(LOCAL_PATH)/libusb1.0

    
LOCAL_SHARED_LIBRARIES := \
    libnativehelper \
    libjnigraphics \
    liblog \
    libusb1.0
    
LOCAL_LDLIBS := -llog

LOCAL_MODULE := libsetbacklight_jni
LOCAL_MODULE_TAGS := optional

include $(BUILD_SHARED_LIBRARY)

include $(LOCAL_PATH)/libusb1.0/android/jni/Android.mk
