package com.guykn.smartchessboard2.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.annotation.LayoutRes
import androidx.fragment.app.Fragment

abstract class UiActionCallbacksFragment(@LayoutRes contentLayoutId: Int) : Fragment(contentLayoutId) {
    val uiActionCallbacks: UiActionCallbacks
        get() {
            return requireContext() as? UiActionCallbacks ?: error("All activities must implement uiActionCallbacks")
        }
}