/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.android.accessibility.utils;

import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction;
import androidx.annotation.Nullable;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat;
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat.AccessibilityActionCompat;
import com.google.android.accessibility.utils.Performance.EventId;
import com.google.android.libraries.accessibility.utils.log.LogUtils;
import java.util.concurrent.CopyOnWriteArrayList;

/** Used to perform an action on a AccessibilityNodeInfoCompat and log relevant information. */
public class PerformActionUtils {
  private static final String TAG = "PerformActionUtils";

  /** Listener notified before and after an accessibility action is performed on a node. */
  public interface ActionListener {
    void onActionPerformed(AccessibilityNodeInfoCompat node, int action);

    default void onActionPerforming(AccessibilityNodeInfoCompat node, int action) {}
  }

  private static final CopyOnWriteArrayList<ActionListener> actionListeners =
      new CopyOnWriteArrayList<>();

  public static void addActionListener(ActionListener listener) {
    if (listener != null) {
      actionListeners.addIfAbsent(listener);
    }
  }

  public static void removeActionListener(ActionListener listener) {
    if (listener != null) {
      actionListeners.remove(listener);
    }
  }

  public static boolean performAction(
      @Nullable AccessibilityNodeInfoCompat node, int action, @Nullable EventId eventId) {
    return performAction(node, action, null /* args */, eventId);
  }

  public static boolean performAction(
      @Nullable AccessibilityNodeInfoCompat node,
      int action,
      Bundle args,
      @Nullable EventId eventId) {
    if (node == null) {
      return false;
    }

    for (ActionListener listener : actionListeners) {
      listener.onActionPerforming(node, action);
    }
    boolean result = node.performAction(action, args);
    if (result) {
      for (ActionListener listener : actionListeners) {
        listener.onActionPerformed(node, action);
      }
    }
    LogUtils.d(
        TAG,
        "perform action=%d=%s returns %s with args=%s on node=%s for event=%s",
        action,
        AccessibilityNodeInfoUtils.actionToString(action),
        result,
        args,
        node,
        eventId);

    return result;
  }

  public static boolean showOnScreen(
      @Nullable AccessibilityNodeInfoCompat node, @Nullable EventId eventId) {
    return performAction(node, AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId(), eventId);
  }

  public static boolean isNodeSupportAction(AccessibilityNodeInfoCompat node, int actionId) {
    if (node == null) {
      return false;
    }

    for (AccessibilityActionCompat action : node.getActionList()) {
      if (actionId == action.getId()) {
        return true;
      }
    }

    return false;
  }
}
