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

#include "PathParser.h"

#include "jni.h"

#include <utils/Log.h>
#include <sstream>
#include <stdlib.h>
#include <string>
#include <vector>

namespace android {
namespace uirenderer {

static size_t nextStart(const char* s, size_t length, size_t startIndex) {
    size_t index = startIndex;
    while (index < length) {
        char c = s[index];
        // Note that 'e' or 'E' are not valid path commands, but could be
        // used for floating point numbers' scientific notation.
        // Therefore, when searching for next command, we should ignore 'e'
        // and 'E'.
        if ((((c - 'A') * (c - 'Z') <= 0) || ((c - 'a') * (c - 'z') <= 0))
                && c != 'e' && c != 'E') {
            return index;
        }
        index++;
    }
    return index;
}

/**
 * Calculate the position of the next comma or space or negative sign
 * @param s the string to search
 * @param start the position to start searching
 * @param result the result of the extraction, including the position of the
 * the starting position of next number, whether it is ending with a '-'.
 */
static void extract(int* outEndPosition, bool* outEndWithNegOrDot, const char* s, int start, int end) {
    // Now looking for ' ', ',', '.' or '-' from the start.
    int currentIndex = start;
    bool foundSeparator = false;
    *outEndWithNegOrDot = false;
    bool secondDot = false;
    bool isExponential = false;
    for (; currentIndex < end; currentIndex++) {
        bool isPrevExponential = isExponential;
        isExponential = false;
        char currentChar = s[currentIndex];
        switch (currentChar) {
        case ' ':
        case ',':
            foundSeparator = true;
            break;
        case '-':
            // The negative sign following a 'e' or 'E' is not a separator.
            if (currentIndex != start && !isPrevExponential) {
                foundSeparator = true;
                *outEndWithNegOrDot = true;
            }
            break;
        case '.':
            if (!secondDot) {
                secondDot = true;
            } else {
                // This is the second dot, and it is considered as a separator.
                foundSeparator = true;
                *outEndWithNegOrDot = true;
            }
            break;
        case 'e':
        case 'E':
            isExponential = true;
            break;
        }
        if (foundSeparator) {
            break;
        }
    }
    // In the case where nothing is found, we put the end position to the end of
    // our extract range. Otherwise, end position will be where separator is found.
    *outEndPosition = currentIndex;
}

/**
* Parse the floats in the string.
* This is an optimized version of parseFloat(s.split(",|\\s"));
*
* @param s the string containing a command and list of floats
* @return array of floats
*/
static void getFloats(std::vector<float>* outPoints, const char* pathStr, int start, int end) {

    if (pathStr[start] == 'z' || pathStr[start] == 'Z') {
        return;
    }
    int startPosition = start + 1;
    int endPosition = start;

    // The startPosition should always be the first character of the
    // current number, and endPosition is the character after the current
    // number.
    while (startPosition < end) {
        bool endWithNegOrDot;
        extract(&endPosition, &endWithNegOrDot, pathStr, startPosition, end);

        if (startPosition < endPosition) {
            outPoints->push_back(strtof(&pathStr[startPosition], NULL));
        }

        if (endWithNegOrDot) {
            // Keep the '-' or '.' sign with next number.
            startPosition = endPosition;
        } else {
            startPosition = endPosition + 1;
        }
    }
}

void PathParser::getPathDataFromString(PathData* data, const char* pathStr, size_t strLen) {
    if (pathStr == NULL) {
        return;
    }

    size_t start = 0;
    size_t end = 1;

    while (end < strLen) {
        end = nextStart(pathStr, strLen, end);
        std::vector<float> points;
        getFloats(&points, pathStr, start, end);
        data->verbs.push_back(pathStr[start]);
        data->verbSizes.push_back(points.size());
        data->points.insert(data->points.end(), points.begin(), points.end());
        start = end;
        end++;
    }

    if ((end - start) == 1 && pathStr[start] != '\0') {
        data->verbs.push_back(pathStr[start]);
        data->verbSizes.push_back(0);
    }

    int i = 0;
    while(pathStr[i] != '\0') {
       i++;
    }

}

void PathParser::dump(const PathData& data) {
    // Print out the path data.
    size_t start = 0;
    for (size_t i = 0; i < data.verbs.size(); i++) {
        std::ostringstream os;
        os << data.verbs[i];
        for (size_t j = 0; j < data.verbSizes[i]; j++) {
            os << " " << data.points[start + j];
        }
        start += data.verbSizes[i];
        ALOGD("%s", os.str().c_str());
    }

    std::ostringstream os;
    for (size_t i = 0; i < data.points.size(); i++) {
        os << data.points[i] << ", ";
    }
    ALOGD("points are : %s", os.str().c_str());
}

bool PathParser::parseStringForSkPath(SkPath* skPath, const char* pathStr, size_t strLen) {
    PathData pathData;
    getPathDataFromString(&pathData, pathStr, strLen);

    // Check if there is valid data coming out of parsing the string.
    if (pathData.verbs.size() == 0) {
        return false;
    }
    VectorDrawablePath::verbsToPath(skPath, &pathData);
    return true;
}

}; // namespace uirenderer
}; //namespace android