package com.reactnativekeyboardcontroller.views

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsAnimationCompat
import androidx.core.view.WindowInsetsCompat
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.views.view.ReactViewGroup
import com.reactnativekeyboardcontroller.extensions.content
import com.reactnativekeyboardcontroller.extensions.removeSelf
import com.reactnativekeyboardcontroller.extensions.requestApplyInsetsWhenAttached
import com.reactnativekeyboardcontroller.extensions.rootView
import com.reactnativekeyboardcontroller.extensions.setupWindowDimensionsListener
import com.reactnativekeyboardcontroller.listeners.KeyboardAnimationCallback
import com.reactnativekeyboardcontroller.listeners.KeyboardAnimationCallbackConfig
import com.reactnativekeyboardcontroller.modal.ModalAttachedWatcher

private val TAG = EdgeToEdgeReactViewGroup::class.qualifiedName

@Suppress("detekt:TooManyFunctions")
@SuppressLint("ViewConstructor")
class EdgeToEdgeReactViewGroup(private val reactContext: ThemedReactContext) : ReactViewGroup(reactContext) {
  // props
  private var isStatusBarTranslucent = false
  private var isNavigationBarTranslucent = false
  private var active = false

  // internal class members
  private var eventView: ReactViewGroup? = null
  private var wasMounted = false
  private var callback: KeyboardAnimationCallback? = null
  private val config: KeyboardAnimationCallbackConfig
    get() = KeyboardAnimationCallbackConfig(
      persistentInsetTypes = WindowInsetsCompat.Type.systemBars(),
      deferredInsetTypes = WindowInsetsCompat.Type.ime(),
      dispatchMode = WindowInsetsAnimationCompat.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE,
      hasTranslucentNavigationBar = isNavigationBarTranslucent,
    )

  // managers/watchers
  private val modalAttachedWatcher = ModalAttachedWatcher(this, reactContext, ::config)

  init {
    reactContext.setupWindowDimensionsListener()
  }

  // region View life cycles
  override fun onAttachedToWindow() {
    super.onAttachedToWindow()

    if (!wasMounted) {
      // skip logic with callback re-creation if it was first render/mount
      wasMounted = true
      return
    }

    this.setupKeyboardCallbacks()
  }

  override fun onDetachedFromWindow() {
    super.onDetachedFromWindow()

    this.removeKeyboardCallbacks()
  }
  // endregion

  // region State manager helpers
  private fun setupWindowInsets() {
    val rootView = reactContext.rootView
    if (rootView != null) {
      ViewCompat.setOnApplyWindowInsetsListener(rootView) { v, insets ->
        val content = reactContext.content
        val params = FrameLayout.LayoutParams(
          FrameLayout.LayoutParams.MATCH_PARENT,
          FrameLayout.LayoutParams.MATCH_PARENT,
        )

        val shouldApplyZeroPaddingTop = !active || this.isStatusBarTranslucent
        val shouldApplyZeroPaddingBottom = !active || this.isNavigationBarTranslucent
        params.setMargins(
          0,
          if (shouldApplyZeroPaddingTop) {
            0
          } else {
            (
              insets?.getInsets(WindowInsetsCompat.Type.systemBars())?.top
                ?: 0
              )
          },
          0,
          if (shouldApplyZeroPaddingBottom) {
            0
          } else {
            insets?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom
              ?: 0
          },
        )

        content?.layoutParams = params

        val defaultInsets = ViewCompat.onApplyWindowInsets(v, insets)

        defaultInsets.replaceSystemWindowInsets(
          defaultInsets.systemWindowInsetLeft,
          if (this.isStatusBarTranslucent) 0 else defaultInsets.systemWindowInsetTop,
          defaultInsets.systemWindowInsetRight,
          defaultInsets.systemWindowInsetBottom,
        )
      }
    }
  }

  private fun goToEdgeToEdge(edgeToEdge: Boolean) {
    reactContext.currentActivity?.let {
      WindowCompat.setDecorFitsSystemWindows(
        it.window,
        !edgeToEdge,
      )
    }
  }

  private fun setupKeyboardCallbacks() {
    val activity = reactContext.currentActivity

    if (activity != null) {
      eventView = ReactViewGroup(context)
      val root = reactContext.content
      root?.addView(eventView)

      callback = KeyboardAnimationCallback(
        view = this,
        eventPropagationView = this,
        context = reactContext,
        config = config,
      )

      eventView?.let {
        ViewCompat.setWindowInsetsAnimationCallback(it, callback)
        ViewCompat.setOnApplyWindowInsetsListener(it, callback)
        it.requestApplyInsetsWhenAttached()
      }
    } else {
      Log.w(TAG, "Can not setup keyboard animation listener, since `currentActivity` is null")
    }
  }

  private fun removeKeyboardCallbacks() {
    callback?.destroy()

    // capture view into closure, because if `onDetachedFromWindow` and `onAttachedToWindow`
    // dispatched synchronously after each other (open application on Fabric), then `.post`
    // will destroy just newly created view (if we have a reference via `this`)
    // and we'll have a memory leak or zombie-view
    val view = eventView
    // we need to remove view asynchronously from `onDetachedFromWindow` method
    // otherwise we may face NPE when app is getting opened via universal link
    // see https://github.com/kirillzyusko/react-native-keyboard-controller/issues/242
    // for more details
    Handler(Looper.getMainLooper()).post { view.removeSelf() }
  }
  // endregion

  // region State managers
  private fun enable() {
    this.goToEdgeToEdge(true)
    this.setupWindowInsets()
    this.setupKeyboardCallbacks()
    modalAttachedWatcher.enable()
  }

  private fun disable() {
    this.goToEdgeToEdge(false)
    this.setupWindowInsets()
    this.removeKeyboardCallbacks()
    modalAttachedWatcher.disable()
  }
  // endregion

  // region Props setters
  fun setStatusBarTranslucent(isStatusBarTranslucent: Boolean) {
    this.isStatusBarTranslucent = isStatusBarTranslucent
  }

  fun setNavigationBarTranslucent(isNavigationBarTranslucent: Boolean) {
    this.isNavigationBarTranslucent = isNavigationBarTranslucent
  }

  fun setActive(active: Boolean) {
    this.active = active

    if (active) {
      this.enable()
    } else {
      this.disable()
    }
  }
  // endregion
}
