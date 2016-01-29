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
package com.android.systemui.recents.tv.views;


import android.support.v17.leanback.widget.HorizontalGridView;
import android.util.AttributeSet;
import android.content.Context;
import android.view.View;
import com.android.systemui.R;
import com.android.systemui.recents.RecentsActivity;
import com.android.systemui.recents.events.EventBus;
import com.android.systemui.recents.events.ui.AllTaskViewsDismissedEvent;
import com.android.systemui.recents.model.Task;
import com.android.systemui.recents.model.TaskStack;
import com.android.systemui.recents.model.TaskStack.TaskStackCallbacks;
import com.android.systemui.recents.views.TaskViewAnimation;

import java.util.ArrayList;
import java.util.List;

/**
 * Horizontal Grid View Implementation to show the Task Stack for TV.
 */
public class TaskStackHorizontalGridView extends HorizontalGridView implements TaskStackCallbacks{

    private TaskStack mStack;
    private ArrayList<TaskCardView> mTaskViews = new ArrayList<>();
    private Task mFocusedTask;


    public TaskStackHorizontalGridView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onAttachedToWindow() {
        EventBus.getDefault().register(this, RecentsActivity.EVENT_BUS_PRIORITY + 1);
        setItemMargin((int) getResources().getDimension(R.dimen.recents_tv_gird_card_spacing));
        setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        super.onAttachedToWindow();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        EventBus.getDefault().unregister(this);
    }
    /**
     * Resets this view for reuse.
     */
    public void reset() {
        // Reset the focused task
        resetFocusedTask(getFocusedTask());
        requestLayout();
    }

    /**
     * @param task - Task to reset
     */
    private void resetFocusedTask(Task task) {
        if (task != null) {
            TaskCardView tv = getChildViewForTask(task);
            if (tv != null) {
                tv.requestFocus();
            }
        }
        mFocusedTask = null;
    }

    /**
     * Sets the task stack.
     * @param stack
     */
    public void setStack(TaskStack stack) {
        //Set new stack
        mStack = stack;
        if (mStack != null) {
            mStack.setCallbacks(this);
        }
        //Layout with new stack
        requestLayout();
    }

    /**
     * @return Returns the task stack.
     */
    public TaskStack getStack() {
        return mStack;
    }

    /**
     * @return - The focused task.
     */
    public Task getFocusedTask() {
        return mFocusedTask;
    }

    /**
     * @param task
     * @return Child view for given task
     */
    public TaskCardView getChildViewForTask(Task task) {
        List<TaskCardView> taskViews = getTaskViews();
        int taskViewCount = taskViews.size();
        for (int i = 0; i < taskViewCount; i++) {
            TaskCardView tv = taskViews.get(i);
            if (tv.getTask() == task) {
                return tv;
            }
        }
        return null;
    }

    public List<TaskCardView> getTaskViews() {
        return mTaskViews;
    }

    @Override
    public void onStackTaskAdded(TaskStack stack, Task newTask){
        getAdapter().notifyItemInserted(stack.getStackTasks().indexOf(newTask));
    }

    @Override
    public void onStackTaskRemoved(TaskStack stack, Task removedTask, boolean wasFrontMostTask,
            Task newFrontMostTask, TaskViewAnimation animation) {
        getAdapter().notifyItemRemoved(stack.getStackTasks().indexOf(removedTask));
        if (mFocusedTask == removedTask) {
            resetFocusedTask(removedTask);
        }
        // If there are no remaining tasks, then just close recents
        if (mStack.getStackTaskCount() == 0) {
            boolean shouldFinishActivity = (mStack.getStackTaskCount() == 0);
            if (shouldFinishActivity) {
                EventBus.getDefault().send(new AllTaskViewsDismissedEvent());
            }
        }
    }

    @Override
    public void onHistoryTaskRemoved(TaskStack stack, Task removedTask, TaskViewAnimation animation) {
        //No history task on tv
    }
}