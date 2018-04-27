/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

#define TAG "UsbPanelBrightnessController"

#include <jni.h>
#include <JNIHelp.h>
#include <android/log.h>

#include <stdio.h>
#include <libusb/libusb.h>

#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG,TAG ,__VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,TAG ,__VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,TAG ,__VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR,TAG ,__VA_ARGS__)
#define LOGF(...) __android_log_print(ANDROID_LOG_FATAL,TAG ,__VA_ARGS__)

const uint16_t device_vid = 0x03eb;
const uint16_t device_pid = 0x214e;
const uint8_t interface = 0;
const uint8_t ep = 0x02;

namespace android {

    static void writeBacklightValue(JNIEnv * env, jobject obj, jint v) {
        int value = v;
        unsigned char backlight_value = value; 
        libusb_device_handle *dev_handle = NULL;      
        libusb_device **devs;    
        struct libusb_device_descriptor desc;
        libusb_device *dev = NULL;
        int r;                    
        ssize_t cnt;          
        
        r = libusb_init(NULL);     
        if(r < 0) {
            LOGD("libusb init Error: %s - %d\n",libusb_strerror(libusb_error(r)), r);
            return;
        }        
        
        cnt = libusb_get_device_list(NULL, &devs); 
        if(cnt < 0) {
            LOGD("Get Device Error: %s - %d\n",libusb_strerror(libusb_error(cnt)), cnt);
        }          
        
        for (int i = 0; devs[i]; ++i)
        {
            r = libusb_get_device_descriptor(devs[i], &desc);
            if (r < 0){
              LOGD("Get device descriptor fail: %s - %d\n",libusb_strerror(libusb_error(r)), r);
              libusb_free_device_list(devs, 1);                      
              libusb_exit(NULL);       
              return ;
            }

            if (desc.idProduct == device_pid && desc.idVendor == device_vid){
              dev = devs[i];
              break;
            }
        }
          
        if(dev == NULL){
            LOGD("Usb panel not found");
            libusb_free_device_list(devs, 1);                      
            libusb_exit(NULL);       
            return ;
        }
        
        r = libusb_open(dev, &dev_handle);
        if(r != 0) {
                LOGD("Cannot open device: %s - %d\n",libusb_strerror(libusb_error(r)), r);
                libusb_free_device_list(devs, 1);                      
                libusb_exit(NULL);                                     
                return;
        }
        libusb_free_device_list(devs, 1);  
        
        if(libusb_kernel_driver_active(dev_handle, interface) == 1) {   
            if(libusb_detach_kernel_driver(dev_handle, interface) != 0)
                LOGD("Kernel driver detach fail");
        }
        
        r = libusb_claim_interface(dev_handle, interface);          
        if(r < 0) {
            LOGD("Cannot Claim Interface: %s - %d\n",libusb_strerror(libusb_error(r)), r);
            libusb_close(dev_handle);
            libusb_exit(NULL);
            return;
        }
        
        unsigned char write_buffer[64] = { 0 };
        int rr;
        int size;

        write_buffer[0] = 0x31;
        write_buffer[1] = 0xbc;
        write_buffer[2] = backlight_value;
        rr = libusb_interrupt_transfer(dev_handle, ep, write_buffer, 64, &size, 1000);
        if( rr < 0) { 
            LOGD("%s - %d\n",libusb_strerror(libusb_error(rr)), rr);
        } else {
            LOGD("interface %d, ep %d, Backlight setting to %d success.\n",interface, ep,  backlight_value);
        }
        
        r = libusb_release_interface(dev_handle, 0); 
        if(r!=0) {
            LOGD("Cannot Release Interface");
            return;
        }      
       
        libusb_attach_kernel_driver(dev_handle, interface);
        libusb_close(dev_handle);
        libusb_exit(NULL);
        return;
    
    }

    static JNINativeMethod sMethods[] = {
        {"nativeSetBacklightValue", "(I)V", (void *) writeBacklightValue},
    };

    int register_com_android_systemui_settings_UsbPanelBrightnessController(JNIEnv* env) 
    {
        return jniRegisterNativeMethods(env, "com/android/systemui/settings/UsbPanelBrightnessController",
            sMethods, NELEM(sMethods));
    }

}

jint JNI_OnLoad(JavaVM* jvm, void*) {
    JNIEnv *env = NULL;
    if (jvm->GetEnv((void**) &env, JNI_VERSION_1_6)) {
        return JNI_ERR;
    }

    if (android::register_com_android_systemui_settings_UsbPanelBrightnessController(env) == -1) {
        return JNI_ERR;
    }

    return JNI_VERSION_1_6;
}
