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

#include <benchmark/Benchmark.h>

#include "PathParser.h"

#include <SkPath.h>

using namespace android;
using namespace android::uirenderer;

BENCHMARK_NO_ARG(BM_PathParser_parseStringPath);
void BM_PathParser_parseStringPath::Run(int iter) {
    const char* pathString = "M 1 1 m 2 2, l 3 3 L 3 3 H 4 h4 V5 v5, Q6 6 6 6 q 6 6 6 6t 7 7 T 7 7 C 8 8 8 8 8 8 c 8 8 8 8 8 8 S 9 9 9 9 s 9 9 9 9 A 10 10 0 1 1 10 10 a 10 10 0 1 1 10 10";
    SkPath skPath;
    size_t length = strlen(pathString);
    StartBenchmarkTiming();
    for (int i = 0; i < iter; i++) {
        PathParser::parseStringForSkPath(&skPath, pathString, length);
    }
    StopBenchmarkTiming();
}