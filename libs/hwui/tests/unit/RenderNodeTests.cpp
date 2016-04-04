/*
 * Copyright (C) 2016 The Android Open Source Project
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

#include <gtest/gtest.h>

#include "RenderNode.h"
#include "TreeInfo.h"
#include "tests/common/TestUtils.h"
#include "utils/Color.h"

using namespace android;
using namespace android::uirenderer;

TEST(RenderNode, hasParents) {
    auto child = TestUtils::createNode(0, 0, 200, 400,
            [](RenderProperties& props, TestCanvas& canvas) {
        canvas.drawColor(Color::Red_500, SkXfermode::kSrcOver_Mode);
    });
    auto parent = TestUtils::createNode(0, 0, 200, 400,
            [&child](RenderProperties& props, TestCanvas& canvas) {
        canvas.drawRenderNode(child.get());
    });

    TestUtils::syncHierarchyPropertiesAndDisplayList(parent);

    EXPECT_TRUE(child->hasParents()) << "Child node has no parent";
    EXPECT_FALSE(parent->hasParents()) << "Root node shouldn't have any parents";

    TestUtils::recordNode(*parent, [](TestCanvas& canvas) {
        canvas.drawColor(Color::Amber_500, SkXfermode::kSrcOver_Mode);
    });

    EXPECT_TRUE(child->hasParents()) << "Child should still have a parent";
    EXPECT_FALSE(parent->hasParents()) << "Root node shouldn't have any parents";

    TestUtils::syncHierarchyPropertiesAndDisplayList(parent);

    EXPECT_FALSE(child->hasParents()) << "Child should be removed";
    EXPECT_FALSE(parent->hasParents()) << "Root node shouldn't have any parents";
}