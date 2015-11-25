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

#include "TestUtils.h"

namespace android {
namespace uirenderer {

SkColor TestUtils::interpolateColor(float fraction, SkColor start, SkColor end) {
    int startA = (start >> 24) & 0xff;
    int startR = (start >> 16) & 0xff;
    int startG = (start >> 8) & 0xff;
    int startB = start & 0xff;

    int endA = (end >> 24) & 0xff;
    int endR = (end >> 16) & 0xff;
    int endG = (end >> 8) & 0xff;
    int endB = end & 0xff;

    return (int)((startA + (int)(fraction * (endA - startA))) << 24)
            | (int)((startR + (int)(fraction * (endR - startR))) << 16)
            | (int)((startG + (int)(fraction * (endG - startG))) << 8)
            | (int)((startB + (int)(fraction * (endB - startB))));
}

void TestUtils::drawTextToCanvas(TestCanvas* canvas, const char* text,
        const SkPaint& inPaint, float x, float y) {
   // copy to force TextEncoding (which JNI layer would have done)
   SkPaint paint(inPaint);
   paint.setTextEncoding(SkPaint::kGlyphID_TextEncoding);

   SkMatrix identity;
   identity.setIdentity();
   SkSurfaceProps surfaceProps(0, kUnknown_SkPixelGeometry);
   SkAutoGlyphCacheNoGamma autoCache(paint, &surfaceProps, &identity);

   float totalAdvance = 0;
   std::vector<glyph_t> glyphs;
   std::vector<float> positions;
   Rect bounds;
   while (*text != '\0') {
       SkUnichar unichar = SkUTF8_NextUnichar(&text);
       glyph_t glyph = autoCache.getCache()->unicharToGlyph(unichar);
       autoCache.getCache()->unicharToGlyph(unichar);

       // push glyph and its relative position
       glyphs.push_back(glyph);
       positions.push_back(totalAdvance);
       positions.push_back(0);

       // compute bounds
       SkGlyph skGlyph = autoCache.getCache()->getUnicharMetrics(unichar);
       Rect glyphBounds(skGlyph.fWidth, skGlyph.fHeight);
       glyphBounds.translate(totalAdvance + skGlyph.fLeft, skGlyph.fTop);
       bounds.unionWith(glyphBounds);

       // advance next character
       SkScalar skWidth;
       paint.getTextWidths(&glyph, sizeof(glyph), &skWidth, NULL);
       totalAdvance += skWidth;
   }
   bounds.translate(x, y);
   canvas->drawText(glyphs.data(), positions.data(), glyphs.size(), paint, x, y,
           bounds.left, bounds.top, bounds.right, bounds.bottom, totalAdvance);
}

} /* namespace uirenderer */
} /* namespace android */