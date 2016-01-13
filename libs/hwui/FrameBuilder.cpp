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

#include "FrameBuilder.h"

#include "LayerUpdateQueue.h"
#include "RenderNode.h"
#include "renderstate/OffscreenBufferPool.h"
#include "utils/FatVector.h"
#include "utils/PaintUtils.h"
#include "utils/TraceUtils.h"

#include <SkCanvas.h>
#include <SkPathOps.h>
#include <utils/TypeHelpers.h>

namespace android {
namespace uirenderer {

FrameBuilder::FrameBuilder(const LayerUpdateQueue& layers, const SkRect& clip,
        uint32_t viewportWidth, uint32_t viewportHeight,
        const std::vector< sp<RenderNode> >& nodes, const Vector3& lightCenter)
        : mCanvasState(*this) {
    ATRACE_NAME("prepare drawing commands");

    mLayerBuilders.reserve(layers.entries().size());
    mLayerStack.reserve(layers.entries().size());

    // Prepare to defer Fbo0
    auto fbo0 = mAllocator.create<LayerBuilder>(viewportWidth, viewportHeight, Rect(clip));
    mLayerBuilders.push_back(fbo0);
    mLayerStack.push_back(0);
    mCanvasState.initializeSaveStack(viewportWidth, viewportHeight,
            clip.fLeft, clip.fTop, clip.fRight, clip.fBottom,
            lightCenter);

    // Render all layers to be updated, in order. Defer in reverse order, so that they'll be
    // updated in the order they're passed in (mLayerBuilders are issued to Renderer in reverse)
    for (int i = layers.entries().size() - 1; i >= 0; i--) {
        RenderNode* layerNode = layers.entries()[i].renderNode;
        const Rect& layerDamage = layers.entries()[i].damage;
        layerNode->computeOrdering();

        // map current light center into RenderNode's coordinate space
        Vector3 lightCenter = mCanvasState.currentSnapshot()->getRelativeLightCenter();
        layerNode->getLayer()->inverseTransformInWindow.mapPoint3d(lightCenter);

        saveForLayer(layerNode->getWidth(), layerNode->getHeight(), 0, 0,
                layerDamage, lightCenter, nullptr, layerNode);

        if (layerNode->getDisplayList()) {
            deferNodeOps(*layerNode);
        }
        restoreForLayer();
    }

    // Defer Fbo0
    for (const sp<RenderNode>& node : nodes) {
        if (node->nothingToDraw()) continue;
        node->computeOrdering();

        int count = mCanvasState.save(SkCanvas::kClip_SaveFlag | SkCanvas::kMatrix_SaveFlag);
        deferNodePropsAndOps(*node);
        mCanvasState.restoreToCount(count);
    }
}

void FrameBuilder::onViewportInitialized() {}

void FrameBuilder::onSnapshotRestored(const Snapshot& removed, const Snapshot& restored) {}

void FrameBuilder::deferNodePropsAndOps(RenderNode& node) {
    const RenderProperties& properties = node.properties();
    const Outline& outline = properties.getOutline();
    if (properties.getAlpha() <= 0
            || (outline.getShouldClip() && outline.isEmpty())
            || properties.getScaleX() == 0
            || properties.getScaleY() == 0) {
        return; // rejected
    }

    if (properties.getLeft() != 0 || properties.getTop() != 0) {
        mCanvasState.translate(properties.getLeft(), properties.getTop());
    }
    if (properties.getStaticMatrix()) {
        mCanvasState.concatMatrix(*properties.getStaticMatrix());
    } else if (properties.getAnimationMatrix()) {
        mCanvasState.concatMatrix(*properties.getAnimationMatrix());
    }
    if (properties.hasTransformMatrix()) {
        if (properties.isTransformTranslateOnly()) {
            mCanvasState.translate(properties.getTranslationX(), properties.getTranslationY());
        } else {
            mCanvasState.concatMatrix(*properties.getTransformMatrix());
        }
    }

    const int width = properties.getWidth();
    const int height = properties.getHeight();

    Rect saveLayerBounds; // will be set to non-empty if saveLayer needed
    const bool isLayer = properties.effectiveLayerType() != LayerType::None;
    int clipFlags = properties.getClippingFlags();
    if (properties.getAlpha() < 1) {
        if (isLayer) {
            clipFlags &= ~CLIP_TO_BOUNDS; // bounds clipping done by layer
        }
        if (CC_LIKELY(isLayer || !properties.getHasOverlappingRendering())) {
            // simply scale rendering content's alpha
            mCanvasState.scaleAlpha(properties.getAlpha());
        } else {
            // schedule saveLayer by initializing saveLayerBounds
            saveLayerBounds.set(0, 0, width, height);
            if (clipFlags) {
                properties.getClippingRectForFlags(clipFlags, &saveLayerBounds);
                clipFlags = 0; // all clipping done by savelayer
            }
        }

        if (CC_UNLIKELY(ATRACE_ENABLED() && properties.promotedToLayer())) {
            // pretend alpha always causes savelayer to warn about
            // performance problem affecting old versions
            ATRACE_FORMAT("%s alpha caused saveLayer %dx%d", node.getName(), width, height);
        }
    }
    if (clipFlags) {
        Rect clipRect;
        properties.getClippingRectForFlags(clipFlags, &clipRect);
        mCanvasState.clipRect(clipRect.left, clipRect.top, clipRect.right, clipRect.bottom,
                SkRegion::kIntersect_Op);
    }

    if (properties.getRevealClip().willClip()) {
        Rect bounds;
        properties.getRevealClip().getBounds(&bounds);
        mCanvasState.setClippingRoundRect(mAllocator,
                bounds, properties.getRevealClip().getRadius());
    } else if (properties.getOutline().willClip()) {
        mCanvasState.setClippingOutline(mAllocator, &(properties.getOutline()));
    }

    if (!mCanvasState.quickRejectConservative(0, 0, width, height)) {
        // not rejected, so defer render as either Layer, or direct (possibly wrapped in saveLayer)
        if (node.getLayer()) {
            // HW layer
            LayerOp* drawLayerOp = new (mAllocator) LayerOp(node);
            BakedOpState* bakedOpState = tryBakeOpState(*drawLayerOp);
            if (bakedOpState) {
                // Node's layer already deferred, schedule it to render into parent layer
                currentLayer().deferUnmergeableOp(mAllocator, bakedOpState, OpBatchType::Bitmap);
            }
        } else if (CC_UNLIKELY(!saveLayerBounds.isEmpty())) {
            // draw DisplayList contents within temporary, since persisted layer could not be used.
            // (temp layers are clipped to viewport, since they don't persist offscreen content)
            SkPaint saveLayerPaint;
            saveLayerPaint.setAlpha(properties.getAlpha());
            deferBeginLayerOp(*new (mAllocator) BeginLayerOp(
                    saveLayerBounds,
                    Matrix4::identity(),
                    nullptr, // no record-time clip - need only respect defer-time one
                    &saveLayerPaint));
            deferNodeOps(node);
            deferEndLayerOp(*new (mAllocator) EndLayerOp());
        } else {
            deferNodeOps(node);
        }
    }
}

typedef key_value_pair_t<float, const RenderNodeOp*> ZRenderNodeOpPair;

template <typename V>
static void buildZSortedChildList(V* zTranslatedNodes,
        const DisplayList& displayList, const DisplayList::Chunk& chunk) {
    if (chunk.beginChildIndex == chunk.endChildIndex) return;

    for (size_t i = chunk.beginChildIndex; i < chunk.endChildIndex; i++) {
        RenderNodeOp* childOp = displayList.getChildren()[i];
        RenderNode* child = childOp->renderNode;
        float childZ = child->properties().getZ();

        if (!MathUtils::isZero(childZ) && chunk.reorderChildren) {
            zTranslatedNodes->push_back(ZRenderNodeOpPair(childZ, childOp));
            childOp->skipInOrderDraw = true;
        } else if (!child->properties().getProjectBackwards()) {
            // regular, in order drawing DisplayList
            childOp->skipInOrderDraw = false;
        }
    }

    // Z sort any 3d children (stable-ness makes z compare fall back to standard drawing order)
    std::stable_sort(zTranslatedNodes->begin(), zTranslatedNodes->end());
}

template <typename V>
static size_t findNonNegativeIndex(const V& zTranslatedNodes) {
    for (size_t i = 0; i < zTranslatedNodes.size(); i++) {
        if (zTranslatedNodes[i].key >= 0.0f) return i;
    }
    return zTranslatedNodes.size();
}

template <typename V>
void FrameBuilder::defer3dChildren(ChildrenSelectMode mode, const V& zTranslatedNodes) {
    const int size = zTranslatedNodes.size();
    if (size == 0
            || (mode == ChildrenSelectMode::Negative&& zTranslatedNodes[0].key > 0.0f)
            || (mode == ChildrenSelectMode::Positive && zTranslatedNodes[size - 1].key < 0.0f)) {
        // no 3d children to draw
        return;
    }

    /**
     * Draw shadows and (potential) casters mostly in order, but allow the shadows of casters
     * with very similar Z heights to draw together.
     *
     * This way, if Views A & B have the same Z height and are both casting shadows, the shadows are
     * underneath both, and neither's shadow is drawn on top of the other.
     */
    const size_t nonNegativeIndex = findNonNegativeIndex(zTranslatedNodes);
    size_t drawIndex, shadowIndex, endIndex;
    if (mode == ChildrenSelectMode::Negative) {
        drawIndex = 0;
        endIndex = nonNegativeIndex;
        shadowIndex = endIndex; // draw no shadows
    } else {
        drawIndex = nonNegativeIndex;
        endIndex = size;
        shadowIndex = drawIndex; // potentially draw shadow for each pos Z child
    }

    float lastCasterZ = 0.0f;
    while (shadowIndex < endIndex || drawIndex < endIndex) {
        if (shadowIndex < endIndex) {
            const RenderNodeOp* casterNodeOp = zTranslatedNodes[shadowIndex].value;
            const float casterZ = zTranslatedNodes[shadowIndex].key;
            // attempt to render the shadow if the caster about to be drawn is its caster,
            // OR if its caster's Z value is similar to the previous potential caster
            if (shadowIndex == drawIndex || casterZ - lastCasterZ < 0.1f) {
                deferShadow(*casterNodeOp);

                lastCasterZ = casterZ; // must do this even if current caster not casting a shadow
                shadowIndex++;
                continue;
            }
        }

        const RenderNodeOp* childOp = zTranslatedNodes[drawIndex].value;
        deferRenderNodeOpImpl(*childOp);
        drawIndex++;
    }
}

void FrameBuilder::deferShadow(const RenderNodeOp& casterNodeOp) {
    auto& node = *casterNodeOp.renderNode;
    auto& properties = node.properties();

    if (properties.getAlpha() <= 0.0f
            || properties.getOutline().getAlpha() <= 0.0f
            || !properties.getOutline().getPath()
            || properties.getScaleX() == 0
            || properties.getScaleY() == 0) {
        // no shadow to draw
        return;
    }

    const SkPath* casterOutlinePath = properties.getOutline().getPath();
    const SkPath* revealClipPath = properties.getRevealClip().getPath();
    if (revealClipPath && revealClipPath->isEmpty()) return;

    float casterAlpha = properties.getAlpha() * properties.getOutline().getAlpha();

    // holds temporary SkPath to store the result of intersections
    SkPath* frameAllocatedPath = nullptr;
    const SkPath* casterPath = casterOutlinePath;

    // intersect the shadow-casting path with the reveal, if present
    if (revealClipPath) {
        frameAllocatedPath = createFrameAllocatedPath();

        Op(*casterPath, *revealClipPath, kIntersect_SkPathOp, frameAllocatedPath);
        casterPath = frameAllocatedPath;
    }

    // intersect the shadow-casting path with the clipBounds, if present
    if (properties.getClippingFlags() & CLIP_TO_CLIP_BOUNDS) {
        if (!frameAllocatedPath) {
            frameAllocatedPath = createFrameAllocatedPath();
        }
        Rect clipBounds;
        properties.getClippingRectForFlags(CLIP_TO_CLIP_BOUNDS, &clipBounds);
        SkPath clipBoundsPath;
        clipBoundsPath.addRect(clipBounds.left, clipBounds.top,
                clipBounds.right, clipBounds.bottom);

        Op(*casterPath, clipBoundsPath, kIntersect_SkPathOp, frameAllocatedPath);
        casterPath = frameAllocatedPath;
    }

    ShadowOp* shadowOp = new (mAllocator) ShadowOp(casterNodeOp, casterAlpha, casterPath,
            mCanvasState.getLocalClipBounds(),
            mCanvasState.currentSnapshot()->getRelativeLightCenter());
    BakedOpState* bakedOpState = BakedOpState::tryShadowOpConstruct(
            mAllocator, *mCanvasState.writableSnapshot(), shadowOp);
    if (CC_LIKELY(bakedOpState)) {
        currentLayer().deferUnmergeableOp(mAllocator, bakedOpState, OpBatchType::Shadow);
    }
}

void FrameBuilder::deferProjectedChildren(const RenderNode& renderNode) {
    const SkPath* projectionReceiverOutline = renderNode.properties().getOutline().getPath();
    int count = mCanvasState.save(SkCanvas::kMatrix_SaveFlag | SkCanvas::kClip_SaveFlag);

    // can't be null, since DL=null node rejection happens before deferNodePropsAndOps
    const DisplayList& displayList = *(renderNode.getDisplayList());

    const RecordedOp* op = (displayList.getOps()[displayList.projectionReceiveIndex]);
    const RenderNodeOp* backgroundOp = static_cast<const RenderNodeOp*>(op);
    const RenderProperties& backgroundProps = backgroundOp->renderNode->properties();

    // Transform renderer to match background we're projecting onto
    // (by offsetting canvas by translationX/Y of background rendernode, since only those are set)
    mCanvasState.translate(backgroundProps.getTranslationX(), backgroundProps.getTranslationY());

    // If the projection receiver has an outline, we mask projected content to it
    // (which we know, apriori, are all tessellated paths)
    mCanvasState.setProjectionPathMask(mAllocator, projectionReceiverOutline);

    // draw projected nodes
    for (size_t i = 0; i < renderNode.mProjectedNodes.size(); i++) {
        RenderNodeOp* childOp = renderNode.mProjectedNodes[i];

        int restoreTo = mCanvasState.save(SkCanvas::kMatrix_SaveFlag);
        mCanvasState.concatMatrix(childOp->transformFromCompositingAncestor);
        deferRenderNodeOpImpl(*childOp);
        mCanvasState.restoreToCount(restoreTo);
    }

    mCanvasState.restoreToCount(count);
}

/**
 * Used to define a list of lambdas referencing private FrameBuilder::onXX::defer() methods.
 *
 * This allows opIds embedded in the RecordedOps to be used for dispatching to these lambdas.
 * E.g. a BitmapOp op then would be dispatched to FrameBuilder::onBitmapOp(const BitmapOp&)
 */
#define OP_RECEIVER(Type) \
        [](FrameBuilder& frameBuilder, const RecordedOp& op) { frameBuilder.defer##Type(static_cast<const Type&>(op)); },
void FrameBuilder::deferNodeOps(const RenderNode& renderNode) {
    typedef void (*OpDispatcher) (FrameBuilder& frameBuilder, const RecordedOp& op);
    static OpDispatcher receivers[] = BUILD_DEFERRABLE_OP_LUT(OP_RECEIVER);

    // can't be null, since DL=null node rejection happens before deferNodePropsAndOps
    const DisplayList& displayList = *(renderNode.getDisplayList());
    for (const DisplayList::Chunk& chunk : displayList.getChunks()) {
        FatVector<ZRenderNodeOpPair, 16> zTranslatedNodes;
        buildZSortedChildList(&zTranslatedNodes, displayList, chunk);

        defer3dChildren(ChildrenSelectMode::Negative, zTranslatedNodes);
        for (size_t opIndex = chunk.beginOpIndex; opIndex < chunk.endOpIndex; opIndex++) {
            const RecordedOp* op = displayList.getOps()[opIndex];
            receivers[op->opId](*this, *op);

            if (CC_UNLIKELY(!renderNode.mProjectedNodes.empty()
                    && displayList.projectionReceiveIndex >= 0
                    && static_cast<int>(opIndex) == displayList.projectionReceiveIndex)) {
                deferProjectedChildren(renderNode);
            }
        }
        defer3dChildren(ChildrenSelectMode::Positive, zTranslatedNodes);
    }
}

void FrameBuilder::deferRenderNodeOpImpl(const RenderNodeOp& op) {
    if (op.renderNode->nothingToDraw()) return;
    int count = mCanvasState.save(SkCanvas::kClip_SaveFlag | SkCanvas::kMatrix_SaveFlag);

    // apply state from RecordedOp (clip first, since op's clip is transformed by current matrix)
    mCanvasState.writableSnapshot()->mutateClipArea().applyClip(op.localClip,
            *mCanvasState.currentSnapshot()->transform);
    mCanvasState.concatMatrix(op.localMatrix);

    // then apply state from node properties, and defer ops
    deferNodePropsAndOps(*op.renderNode);

    mCanvasState.restoreToCount(count);
}

void FrameBuilder::deferRenderNodeOp(const RenderNodeOp& op) {
    if (!op.skipInOrderDraw) {
        deferRenderNodeOpImpl(op);
    }
}

/**
 * Defers an unmergeable, strokeable op, accounting correctly
 * for paint's style on the bounds being computed.
 */
void FrameBuilder::deferStrokeableOp(const RecordedOp& op, batchid_t batchId,
        BakedOpState::StrokeBehavior strokeBehavior) {
    // Note: here we account for stroke when baking the op
    BakedOpState* bakedState = BakedOpState::tryStrokeableOpConstruct(
            mAllocator, *mCanvasState.writableSnapshot(), op, strokeBehavior);
    if (!bakedState) return; // quick rejected
    currentLayer().deferUnmergeableOp(mAllocator, bakedState, batchId);
}

/**
 * Returns batch id for tessellatable shapes, based on paint. Checks to see if path effect/AA will
 * be used, since they trigger significantly different rendering paths.
 *
 * Note: not used for lines/points, since they don't currently support path effects.
 */
static batchid_t tessBatchId(const RecordedOp& op) {
    const SkPaint& paint = *(op.paint);
    return paint.getPathEffect()
            ? OpBatchType::AlphaMaskTexture
            : (paint.isAntiAlias() ? OpBatchType::AlphaVertices : OpBatchType::Vertices);
}

void FrameBuilder::deferArcOp(const ArcOp& op) {
    deferStrokeableOp(op, tessBatchId(op));
}

static bool hasMergeableClip(const BakedOpState& state) {
    return state.computedState.clipState
            || state.computedState.clipState->mode == ClipMode::Rectangle;
}

void FrameBuilder::deferBitmapOp(const BitmapOp& op) {
    BakedOpState* bakedState = tryBakeOpState(op);
    if (!bakedState) return; // quick rejected

    // Don't merge non-simply transformed or neg scale ops, SET_TEXTURE doesn't handle rotation
    // Don't merge A8 bitmaps - the paint's color isn't compared by mergeId, or in
    // MergingDrawBatch::canMergeWith()
    if (bakedState->computedState.transform.isSimple()
            && bakedState->computedState.transform.positiveScale()
            && PaintUtils::getXfermodeDirect(op.paint) == SkXfermode::kSrcOver_Mode
            && op.bitmap->colorType() != kAlpha_8_SkColorType
            && hasMergeableClip(*bakedState)) {
        mergeid_t mergeId = reinterpret_cast<mergeid_t>(op.bitmap->getGenerationID());
        // TODO: AssetAtlas in mergeId
        currentLayer().deferMergeableOp(mAllocator, bakedState, OpBatchType::Bitmap, mergeId);
    } else {
        currentLayer().deferUnmergeableOp(mAllocator, bakedState, OpBatchType::Bitmap);
    }
}

void FrameBuilder::deferBitmapMeshOp(const BitmapMeshOp& op) {
    BakedOpState* bakedState = tryBakeOpState(op);
    if (!bakedState) return; // quick rejected
    currentLayer().deferUnmergeableOp(mAllocator, bakedState, OpBatchType::Bitmap);
}

void FrameBuilder::deferBitmapRectOp(const BitmapRectOp& op) {
    BakedOpState* bakedState = tryBakeOpState(op);
    if (!bakedState) return; // quick rejected
    currentLayer().deferUnmergeableOp(mAllocator, bakedState, OpBatchType::Bitmap);
}

void FrameBuilder::deferCirclePropsOp(const CirclePropsOp& op) {
    // allocate a temporary oval op (with mAllocator, so it persists until render), so the
    // renderer doesn't have to handle the RoundRectPropsOp type, and so state baking is simple.
    float x = *(op.x);
    float y = *(op.y);
    float radius = *(op.radius);
    Rect unmappedBounds(x - radius, y - radius, x + radius, y + radius);
    const OvalOp* resolvedOp = new (mAllocator) OvalOp(
            unmappedBounds,
            op.localMatrix,
            op.localClip,
            op.paint);
    deferOvalOp(*resolvedOp);
}

void FrameBuilder::deferFunctorOp(const FunctorOp& op) {
    BakedOpState* bakedState = tryBakeOpState(op);
    if (!bakedState) return; // quick rejected
    currentLayer().deferUnmergeableOp(mAllocator, bakedState, OpBatchType::Functor);
}

void FrameBuilder::deferLinesOp(const LinesOp& op) {
    batchid_t batch = op.paint->isAntiAlias() ? OpBatchType::AlphaVertices : OpBatchType::Vertices;
    deferStrokeableOp(op, batch, BakedOpState::StrokeBehavior::Forced);
}

void FrameBuilder::deferOvalOp(const OvalOp& op) {
    deferStrokeableOp(op, tessBatchId(op));
}

void FrameBuilder::deferPatchOp(const PatchOp& op) {
    BakedOpState* bakedState = tryBakeOpState(op);
    if (!bakedState) return; // quick rejected

    if (bakedState->computedState.transform.isPureTranslate()
            && PaintUtils::getXfermodeDirect(op.paint) == SkXfermode::kSrcOver_Mode
            && hasMergeableClip(*bakedState)) {
        mergeid_t mergeId = reinterpret_cast<mergeid_t>(op.bitmap->getGenerationID());
        // TODO: AssetAtlas in mergeId

        // Only use the MergedPatch batchId when merged, so Bitmap+Patch don't try to merge together
        currentLayer().deferMergeableOp(mAllocator, bakedState, OpBatchType::MergedPatch, mergeId);
    } else {
        // Use Bitmap batchId since Bitmap+Patch use same shader
        currentLayer().deferUnmergeableOp(mAllocator, bakedState, OpBatchType::Bitmap);
    }
}

void FrameBuilder::deferPathOp(const PathOp& op) {
    deferStrokeableOp(op, OpBatchType::Bitmap);
}

void FrameBuilder::deferPointsOp(const PointsOp& op) {
    batchid_t batch = op.paint->isAntiAlias() ? OpBatchType::AlphaVertices : OpBatchType::Vertices;
    deferStrokeableOp(op, batch, BakedOpState::StrokeBehavior::Forced);
}

void FrameBuilder::deferRectOp(const RectOp& op) {
    deferStrokeableOp(op, tessBatchId(op));
}

void FrameBuilder::deferRoundRectOp(const RoundRectOp& op) {
    deferStrokeableOp(op, tessBatchId(op));
}

void FrameBuilder::deferRoundRectPropsOp(const RoundRectPropsOp& op) {
    // allocate a temporary round rect op (with mAllocator, so it persists until render), so the
    // renderer doesn't have to handle the RoundRectPropsOp type, and so state baking is simple.
    const RoundRectOp* resolvedOp = new (mAllocator) RoundRectOp(
            Rect(*(op.left), *(op.top), *(op.right), *(op.bottom)),
            op.localMatrix,
            op.localClip,
            op.paint, *op.rx, *op.ry);
    deferRoundRectOp(*resolvedOp);
}

void FrameBuilder::deferSimpleRectsOp(const SimpleRectsOp& op) {
    BakedOpState* bakedState = tryBakeOpState(op);
    if (!bakedState) return; // quick rejected
    currentLayer().deferUnmergeableOp(mAllocator, bakedState, OpBatchType::Vertices);
}

static batchid_t textBatchId(const SkPaint& paint) {
    // TODO: better handling of shader (since we won't care about color then)
    return paint.getColor() == SK_ColorBLACK ? OpBatchType::Text : OpBatchType::ColorText;
}

void FrameBuilder::deferTextOp(const TextOp& op) {
    BakedOpState* bakedState = tryBakeOpState(op);
    if (!bakedState) return; // quick rejected

    batchid_t batchId = textBatchId(*(op.paint));
    if (bakedState->computedState.transform.isPureTranslate()
            && PaintUtils::getXfermodeDirect(op.paint) == SkXfermode::kSrcOver_Mode
            && hasMergeableClip(*bakedState)) {
        mergeid_t mergeId = reinterpret_cast<mergeid_t>(op.paint->getColor());
        currentLayer().deferMergeableOp(mAllocator, bakedState, batchId, mergeId);
    } else {
        currentLayer().deferUnmergeableOp(mAllocator, bakedState, batchId);
    }
}

void FrameBuilder::deferTextOnPathOp(const TextOnPathOp& op) {
    BakedOpState* bakedState = tryBakeOpState(op);
    if (!bakedState) return; // quick rejected
    currentLayer().deferUnmergeableOp(mAllocator, bakedState, textBatchId(*(op.paint)));
}

void FrameBuilder::deferTextureLayerOp(const TextureLayerOp& op) {
    BakedOpState* bakedState = tryBakeOpState(op);
    if (!bakedState) return; // quick rejected
    currentLayer().deferUnmergeableOp(mAllocator, bakedState, OpBatchType::TextureLayer);
}

void FrameBuilder::saveForLayer(uint32_t layerWidth, uint32_t layerHeight,
        float contentTranslateX, float contentTranslateY,
        const Rect& repaintRect,
        const Vector3& lightCenter,
        const BeginLayerOp* beginLayerOp, RenderNode* renderNode) {
    mCanvasState.save(SkCanvas::kClip_SaveFlag | SkCanvas::kMatrix_SaveFlag);
    mCanvasState.writableSnapshot()->initializeViewport(layerWidth, layerHeight);
    mCanvasState.writableSnapshot()->roundRectClipState = nullptr;
    mCanvasState.writableSnapshot()->setRelativeLightCenter(lightCenter);
    mCanvasState.writableSnapshot()->transform->loadTranslate(
            contentTranslateX, contentTranslateY, 0);
    mCanvasState.writableSnapshot()->setClip(
            repaintRect.left, repaintRect.top, repaintRect.right, repaintRect.bottom);

    // create a new layer repaint, and push its index on the stack
    mLayerStack.push_back(mLayerBuilders.size());
    auto newFbo = mAllocator.create<LayerBuilder>(layerWidth, layerHeight,
            repaintRect, beginLayerOp, renderNode);
    mLayerBuilders.push_back(newFbo);
}

void FrameBuilder::restoreForLayer() {
    // restore canvas, and pop finished layer off of the stack
    mCanvasState.restore();
    mLayerStack.pop_back();
}

// TODO: defer time rejection (when bounds become empty) + tests
// Option - just skip layers with no bounds at playback + defer?
void FrameBuilder::deferBeginLayerOp(const BeginLayerOp& op) {
    uint32_t layerWidth = (uint32_t) op.unmappedBounds.getWidth();
    uint32_t layerHeight = (uint32_t) op.unmappedBounds.getHeight();

    auto previous = mCanvasState.currentSnapshot();
    Vector3 lightCenter = previous->getRelativeLightCenter();

    // Combine all transforms used to present saveLayer content:
    // parent content transform * canvas transform * bounds offset
    Matrix4 contentTransform(*(previous->transform));
    contentTransform.multiply(op.localMatrix);
    contentTransform.translate(op.unmappedBounds.left, op.unmappedBounds.top);

    Matrix4 inverseContentTransform;
    inverseContentTransform.loadInverse(contentTransform);

    // map the light center into layer-relative space
    inverseContentTransform.mapPoint3d(lightCenter);

    // Clip bounds of temporary layer to parent's clip rect, so:
    Rect saveLayerBounds(layerWidth, layerHeight);
    //     1) transform Rect(width, height) into parent's space
    //        note: left/top offsets put in contentTransform above
    contentTransform.mapRect(saveLayerBounds);
    //     2) intersect with parent's clip
    saveLayerBounds.doIntersect(previous->getRenderTargetClip());
    //     3) and transform back
    inverseContentTransform.mapRect(saveLayerBounds);
    saveLayerBounds.doIntersect(Rect(layerWidth, layerHeight));
    saveLayerBounds.roundOut();

    // if bounds are reduced, will clip the layer's area by reducing required bounds...
    layerWidth = saveLayerBounds.getWidth();
    layerHeight = saveLayerBounds.getHeight();
    // ...and shifting drawing content to account for left/top side clipping
    float contentTranslateX = -saveLayerBounds.left;
    float contentTranslateY = -saveLayerBounds.top;

    saveForLayer(layerWidth, layerHeight,
            contentTranslateX, contentTranslateY,
            Rect(layerWidth, layerHeight),
            lightCenter,
            &op, nullptr);
}

void FrameBuilder::deferEndLayerOp(const EndLayerOp& /* ignored */) {
    const BeginLayerOp& beginLayerOp = *currentLayer().beginLayerOp;
    int finishedLayerIndex = mLayerStack.back();

    restoreForLayer();

    // record the draw operation into the previous layer's list of draw commands
    // uses state from the associated beginLayerOp, since it has all the state needed for drawing
    LayerOp* drawLayerOp = new (mAllocator) LayerOp(
            beginLayerOp.unmappedBounds,
            beginLayerOp.localMatrix,
            beginLayerOp.localClip,
            beginLayerOp.paint,
            &(mLayerBuilders[finishedLayerIndex]->offscreenBuffer));
    BakedOpState* bakedOpState = tryBakeOpState(*drawLayerOp);

    if (bakedOpState) {
        // Layer will be drawn into parent layer (which is now current, since we popped mLayerStack)
        currentLayer().deferUnmergeableOp(mAllocator, bakedOpState, OpBatchType::Bitmap);
    } else {
        // Layer won't be drawn - delete its drawing batches to prevent it from doing any work
        // TODO: need to prevent any render work from being done
        // - create layerop earlier for reject purposes?
        mLayerBuilders[finishedLayerIndex]->clear();
        return;
    }
}

void FrameBuilder::deferBeginUnclippedLayerOp(const BeginUnclippedLayerOp& op) {
    Matrix4 boundsTransform(*(mCanvasState.currentSnapshot()->transform));
    boundsTransform.multiply(op.localMatrix);

    Rect dstRect(op.unmappedBounds);
    boundsTransform.mapRect(dstRect);
    dstRect.doIntersect(mCanvasState.currentSnapshot()->getRenderTargetClip());

    // Allocate a holding position for the layer object (copyTo will produce, copyFrom will consume)
    OffscreenBuffer** layerHandle = mAllocator.create<OffscreenBuffer*>(nullptr);

    /**
     * First, defer an operation to copy out the content from the rendertarget into a layer.
     */
    auto copyToOp = new (mAllocator) CopyToLayerOp(op, layerHandle);
    BakedOpState* bakedState = BakedOpState::directConstruct(mAllocator,
            &(currentLayer().viewportClip), dstRect, *copyToOp);
    currentLayer().deferUnmergeableOp(mAllocator, bakedState, OpBatchType::CopyToLayer);

    /**
     * Defer a clear rect, so that clears from multiple unclipped layers can be drawn
     * both 1) simultaneously, and 2) as long after the copyToLayer executes as possible
     */
    currentLayer().deferLayerClear(dstRect);

    /**
     * And stash an operation to copy that layer back under the rendertarget until
     * a balanced EndUnclippedLayerOp is seen
     */
    auto copyFromOp = new (mAllocator) CopyFromLayerOp(op, layerHandle);
    bakedState = BakedOpState::directConstruct(mAllocator,
            &(currentLayer().viewportClip), dstRect, *copyFromOp);
    currentLayer().activeUnclippedSaveLayers.push_back(bakedState);
}

void FrameBuilder::deferEndUnclippedLayerOp(const EndUnclippedLayerOp& /* ignored */) {
    LOG_ALWAYS_FATAL_IF(currentLayer().activeUnclippedSaveLayers.empty(), "no layer to end!");

    BakedOpState* copyFromLayerOp = currentLayer().activeUnclippedSaveLayers.back();
    currentLayer().deferUnmergeableOp(mAllocator, copyFromLayerOp, OpBatchType::CopyFromLayer);
    currentLayer().activeUnclippedSaveLayers.pop_back();
}

} // namespace uirenderer
} // namespace android