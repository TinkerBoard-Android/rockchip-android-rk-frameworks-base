/*
 * Copyright (C) 2015 The Android Open Source Project
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

#ifndef ANDROID_HWUI_PATHPARSER_H
#define ANDROID_HWUI_PATHPARSER_H

#include "VectorDrawablePath.h"

#include <jni.h>
#include <android/log.h>
#include <cutils/compiler.h>

namespace android {
namespace uirenderer {

class PathParser {
public:
    /**
     * Parse the string literal and create a Skia Path. Return true on success.
     */
    ANDROID_API static bool parseStringForSkPath(SkPath* outPath, const char* pathStr,
            size_t strLength);
    static void getPathDataFromString(PathData* outData, const char* pathStr, size_t strLength);
    static void dump(const PathData& data);
};

}; // namespace uirenderer
}; // namespace android
#endif //ANDROID_HWUI_PATHPARSER_H