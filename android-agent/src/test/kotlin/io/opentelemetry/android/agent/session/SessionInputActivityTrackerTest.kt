/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.android.agent.session

import android.app.Activity
import android.app.Application
import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Window
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import io.opentelemetry.android.OpenTelemetryRum
import io.opentelemetry.sdk.testing.assertj.OpenTelemetryAssertions.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
internal class SessionInputActivityTrackerTest {
    @Test
    fun `registers tracker for application contexts`() {
        val application = mockk<Application>()
        val openTelemetryRum = mockk<OpenTelemetryRum>()
        val callback = slot<Application.ActivityLifecycleCallbacks>()
        every { application.registerActivityLifecycleCallbacks(any()) } just Runs
        every { application.unregisterActivityLifecycleCallbacks(any()) } just Runs
        val instrumentation = SessionInputActivityInstrumentation {}

        instrumentation.install(application, openTelemetryRum)
        verify(exactly = 1) {
            application.registerActivityLifecycleCallbacks(capture(callback))
        }

        instrumentation.uninstall(application, openTelemetryRum)

        verify(exactly = 1) {
            application.unregisterActivityLifecycleCallbacks(callback.captured)
        }
    }

    @Test
    fun `does not register tracker for non-application contexts`() {
        val context = mockk<Context>(relaxed = true)
        val openTelemetryRum = mockk<OpenTelemetryRum>()
        val instrumentation = SessionInputActivityInstrumentation {}

        instrumentation.install(context, openTelemetryRum)
        instrumentation.uninstall(context, openTelemetryRum)

        verify(exactly = 0) { context.applicationContext }
    }

    @Test
    fun `wraps windows on create or first resume without duplicates`() {
        val createdFixture = WindowFixture()
        val resumedFixture = WindowFixture()
        val tracker = SessionInputActivityTracker {}

        tracker.onActivityCreated(createdFixture.activity, null)
        val createdCallback = createdFixture.installedCallback
        tracker.onActivityResumed(createdFixture.activity)
        tracker.onActivityResumed(resumedFixture.activity)
        val resumedCallback = resumedFixture.installedCallback
        tracker.onActivityResumed(resumedFixture.activity)

        assertThat(createdCallback).isInstanceOf(SessionInputWindowCallback::class.java)
        assertThat(createdFixture.installedCallback).isSameAs(createdCallback)
        assertThat(resumedCallback).isInstanceOf(SessionInputWindowCallback::class.java)
        assertThat(resumedFixture.installedCallback).isSameAs(resumedCallback)
    }

    @Test
    fun `resume leaves a callback replaced after activity creation in place`() {
        val fixture = WindowFixture()
        val replacementCallback = mockk<Window.Callback>()
        val tracker = SessionInputActivityTracker {}

        tracker.onActivityCreated(fixture.activity, null)
        fixture.installedCallback = replacementCallback
        tracker.onActivityResumed(fixture.activity)

        assertThat(fixture.installedCallback).isSameAs(replacementCallback)
    }

    @Test
    fun `foreign wrapper does not cause another session wrapper on resume`() {
        val fixture = WindowFixture()
        val tracker = SessionInputActivityTracker {}

        tracker.onActivityCreated(fixture.activity, null)
        val sessionCallback = fixture.installedCallback
        val foreignCallback = DelegatingWindowCallback(sessionCallback)
        fixture.installedCallback = foreignCallback

        repeat(5) { tracker.onActivityResumed(fixture.activity) }

        assertThat(fixture.installedCallback).isSameAs(foreignCallback)
        assertThat(foreignCallback.unwrap()).isSameAs(sessionCallback)
    }

    @Test
    fun `close restores a callback installed by the tracker`() {
        val fixture = WindowFixture()
        val tracker = SessionInputActivityTracker {}

        tracker.onActivityCreated(fixture.activity, null)
        tracker.close()

        assertThat(fixture.installedCallback).isSameAs(fixture.originalCallback)
    }

    @Test
    fun `close does not replace a callback installed later`() {
        val fixture = WindowFixture()
        val replacementCallback = mockk<Window.Callback>()
        val tracker = SessionInputActivityTracker {}

        tracker.onActivityCreated(fixture.activity, null)
        fixture.installedCallback = replacementCallback
        tracker.close()

        assertThat(fixture.installedCallback).isSameAs(replacementCallback)
    }

    @Test
    fun `destroy restores and detaches the activity callback`() {
        val fixture = WindowFixture()
        val recordActivity = mockk<() -> Unit>(relaxed = true)
        val event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 1f, 1f, 0)
        every { fixture.originalCallback.dispatchTouchEvent(event) } returns false
        val tracker = SessionInputActivityTracker(recordActivity)

        tracker.onActivityCreated(fixture.activity, null)
        val sessionCallback = fixture.installedCallback
        tracker.onActivityDestroyed(fixture.activity)
        sessionCallback.dispatchTouchEvent(event)

        assertThat(fixture.installedCallback).isSameAs(fixture.originalCallback)
        verify(exactly = 0) { recordActivity() }
        event.recycle()
    }

    @Test
    fun `close detaches a session callback nested below a foreign wrapper`() {
        val fixture = WindowFixture()
        val recordActivity = mockk<() -> Unit>(relaxed = true)
        val event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 1f, 1f, 0)
        every { fixture.originalCallback.dispatchTouchEvent(event) } returns false
        val tracker = SessionInputActivityTracker(recordActivity)

        tracker.onActivityCreated(fixture.activity, null)
        fixture.installedCallback = DelegatingWindowCallback(fixture.installedCallback)
        tracker.close()
        fixture.installedCallback.dispatchTouchEvent(event)

        verify(exactly = 0) { recordActivity() }
        event.recycle()
    }

    @Test
    fun `touch down records activity before delegating`() {
        val calls = mutableListOf<String>()
        val callback = mockk<Window.Callback>()
        val event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 1f, 1f, 0)
        every { callback.dispatchTouchEvent(event) } answers {
            calls += "delegate"
            true
        }
        val wrapper = SessionInputWindowCallback(callback) { calls += "activity" }

        val handled = wrapper.dispatchTouchEvent(event)

        assertThat(handled).isTrue()
        assertThat(calls).containsExactly("activity", "delegate")
        event.recycle()
    }

    @Test
    fun `records only initial key down and scroll from other input phases`() {
        val callback = mockk<Window.Callback>()
        val recordActivity = mockk<() -> Unit>(relaxed = true)
        val touchUp = MotionEvent.obtain(0, 0, MotionEvent.ACTION_UP, 1f, 1f, 0)
        val scroll = MotionEvent.obtain(0, 1, MotionEvent.ACTION_SCROLL, 1f, 1f, 0)
        val hover = MotionEvent.obtain(0, 2, MotionEvent.ACTION_HOVER_MOVE, 1f, 1f, 0)
        every { callback.dispatchTouchEvent(any()) } returns false
        every { callback.dispatchKeyEvent(any()) } returns false
        every { callback.dispatchGenericMotionEvent(any()) } returns false
        val wrapper = SessionInputWindowCallback(callback, recordActivity)

        wrapper.dispatchTouchEvent(touchUp)
        verify(exactly = 0) { recordActivity() }
        wrapper.dispatchKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0))
        wrapper.dispatchKeyEvent(KeyEvent(0, 1, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 1))
        wrapper.dispatchKeyEvent(KeyEvent(0, 2, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0))
        verify(exactly = 1) { recordActivity() }
        wrapper.dispatchGenericMotionEvent(scroll)
        wrapper.dispatchGenericMotionEvent(hover)

        verify(exactly = 2) { recordActivity() }
        listOf(touchUp, scroll, hover).forEach(MotionEvent::recycle)
    }

    @Test
    fun `activity failure does not prevent input delegation`() {
        val callback = mockk<Window.Callback>()
        val event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 1f, 1f, 0)
        every { callback.dispatchTouchEvent(event) } returns true
        val wrapper = SessionInputWindowCallback(callback) { error("observer failed") }

        assertThat(wrapper.dispatchTouchEvent(event)).isTrue()
        verify(exactly = 1) { callback.dispatchTouchEvent(event) }
        event.recycle()
    }

    private class DelegatingWindowCallback(
        private val callback: Window.Callback,
    ) : Window.Callback by callback {
        fun unwrap(): Window.Callback = callback
    }

    private class WindowFixture {
        val activity = mockk<Activity>()
        val window = mockk<Window>()
        val originalCallback = mockk<Window.Callback>()
        var installedCallback: Window.Callback = originalCallback

        init {
            every { activity.window } returns window
            every { window.callback } answers { installedCallback }
            every { window.callback = any() } answers { installedCallback = firstArg() }
        }
    }
}
