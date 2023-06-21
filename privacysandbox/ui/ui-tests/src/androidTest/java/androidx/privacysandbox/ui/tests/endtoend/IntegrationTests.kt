/*
 * Copyright 2023 The Android Open Source Project
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

package androidx.privacysandbox.ui.tests.endtoend

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.View.OnLayoutChangeListener
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.privacysandbox.ui.client.SandboxedUiAdapterFactory
import androidx.privacysandbox.ui.client.view.SandboxedSdkUiSessionState
import androidx.privacysandbox.ui.client.view.SandboxedSdkUiSessionStateChangedListener
import androidx.privacysandbox.ui.client.view.SandboxedSdkView
import androidx.privacysandbox.ui.core.SandboxedUiAdapter
import androidx.privacysandbox.ui.provider.toCoreLibInfo
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.filters.MediumTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.testutils.withActivity
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
@RunWith(Parameterized::class)
@MediumTest
class IntegrationTests(private val invokeBackwardsCompatFlow: Boolean) {

    @get:Rule
    var activityScenarioRule = ActivityScenarioRule(MainActivity::class.java)

    companion object {
        const val TEST_ONLY_USE_REMOTE_ADAPTER = "testOnlyUseRemoteAdapter"
        const val TIMEOUT = 1000.toLong()

        @JvmStatic
        @Parameterized.Parameters(name = "{index}: invokeBackwardsCompatFlow={0}")
        fun data(): Array<Any> = arrayOf(
            arrayOf(true),
            arrayOf(false),
        )
    }

    private lateinit var context: Context
    private lateinit var activity: AppCompatActivity
    private lateinit var view: SandboxedSdkView
    private lateinit var stateChangeListener: TestStateChangeListener
    private lateinit var errorLatch: CountDownLatch

    @Before
    fun setup() {
        // TODO(b/300397160): Enable backward compat test on S- devices
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)

        context = InstrumentationRegistry.getInstrumentation().context
        activity = activityScenarioRule.withActivity { this }
        view = SandboxedSdkView(context)
        errorLatch = CountDownLatch(1)
        stateChangeListener = TestStateChangeListener(errorLatch)
        view.addStateChangedListener(stateChangeListener)
        activity.runOnUiThread {
            val linearLayout = LinearLayout(context)
            linearLayout.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            activity.setContentView(linearLayout)
            view.layoutParams = LinearLayout.LayoutParams(100, 100)
            linearLayout.addView(view)
        }
    }

    @Ignore // b/271299184
    @Test
    fun testChangingSandboxedSdkViewLayoutChangesChildLayout() {
        val adapter = TestSandboxedUiAdapter(
            false /* hasFailiningTestSession */
        )
        val coreLibInfo = getCoreLibInfoFromAdapter(adapter)
        val userRemoteAdapter = SandboxedUiAdapterFactory.createFromCoreLibInfo(coreLibInfo)
        view.setAdapter(userRemoteAdapter)

        val layoutChangeLatch = CountDownLatch(1)
        val childAddedLatch = CountDownLatch(1)

        val hierarchyChangeListener = object : ViewGroup.OnHierarchyChangeListener {
            override fun onChildViewAdded(parent: View, child: View) {
                childAddedLatch.countDown()
            }

            override fun onChildViewRemoved(p0: View?, p1: View?) {
            }
        }
        view.setOnHierarchyChangeListener(hierarchyChangeListener)

        val onLayoutChangeListener: OnLayoutChangeListener =
            object : OnLayoutChangeListener {
                override fun onLayoutChange(
                    view: View?,
                    left: Int,
                    top: Int,
                    right: Int,
                    bottom: Int,
                    old_left: Int,
                    old_top: Int,
                    old_right: Int,
                    old_bottom: Int
                ) {
                    assertTrue(left == 10 && top == 10 && right == 10 && bottom == 10)
                    layoutChangeLatch.countDown()
                    view?.removeOnLayoutChangeListener(this)
                }
            }
        childAddedLatch.await(TIMEOUT, TimeUnit.MILLISECONDS)
        assertTrue(childAddedLatch.count == 0.toLong())
        view.getChildAt(0).addOnLayoutChangeListener(onLayoutChangeListener)
        view.layout(10, 10, 10, 10)
        layoutChangeLatch.await(2000, TimeUnit.MILLISECONDS)
        assertTrue(layoutChangeLatch.count == 0.toLong())
        assertTrue(stateChangeListener.currentState == SandboxedSdkUiSessionState.Active)
    }

    @Test
    fun testOpenSession_onSetAdapter() {
        val adapter = TestSandboxedUiAdapter(false)
        val coreLibInfo = getCoreLibInfoFromAdapter(adapter)
        val userRemoteAdapter = SandboxedUiAdapterFactory.createFromCoreLibInfo(coreLibInfo)
        view.setAdapter(userRemoteAdapter)

        assertWithMessage("openSession is called on adapter")
                .that(adapter.isOpenSessionCalled).isTrue()
        var isSessionInitialised = try {
            adapter.session
            true
        } catch (e: UninitializedPropertyAccessException) {
            false
        }
        assertTrue(isSessionInitialised)
    }

    @Test
    fun testOpenSession_fromAdapter() {
        val adapter = TestSandboxedUiAdapter(false)
        val coreLibInfo = getCoreLibInfoFromAdapter(adapter)
        val adapterFromCoreLibInfo = SandboxedUiAdapterFactory.createFromCoreLibInfo(coreLibInfo)
        val testSessionClient = TestSessionClient()

        adapterFromCoreLibInfo.openSession(
            context,
            Binder(),
            10 /* initialWidth */,
            10 /* initialHeight */,
            true,
            Runnable::run,
            testSessionClient
        )

        assertThat(adapter.isOpenSessionCalled).isTrue()
        assertThat(testSessionClient.isSessionOpened).isTrue()
    }

    @Test
    fun testConfigurationChanged() {
        val sdkAdapter = TestSandboxedUiAdapter(/*hasfailingTestSession=*/false)
        val coreLibInfo = getCoreLibInfoFromAdapter(sdkAdapter)
        val adapter = SandboxedUiAdapterFactory.createFromCoreLibInfo(coreLibInfo)

        view.setAdapter(adapter)
        assertThat(sdkAdapter.isOpenSessionCalled).isTrue()

        activity.runOnUiThread {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }

        val testSession = sdkAdapter.session as TestSandboxedUiAdapter.TestSession
        assertWithMessage("Configuration changed").that(testSession.config?.orientation)
                .isEqualTo(Configuration.ORIENTATION_LANDSCAPE)
    }

    /**
     * Tests that the provider receives Z-order change updates.
     */
    @Test
    fun testZOrderChanged() {
        val adapter = TestSandboxedUiAdapter(/* hasFailingTestSession=*/false)
        val coreLibInfo = getCoreLibInfoFromAdapter(adapter)
        val adapterFromCoreLibInfo = SandboxedUiAdapterFactory.createFromCoreLibInfo(coreLibInfo)
        view.setAdapter(adapterFromCoreLibInfo)
        assertThat(adapter.isOpenSessionCalled).isTrue()

        view.orderProviderUiAboveClientUi(!adapter.initialZOrderOnTop)
        val testSession = adapter.session as TestSandboxedUiAdapter.TestSession
        assertThat(testSession.zOrderChanged).isTrue()
    }

    /**
     * Tests that the provider does not receive Z-order updates if the Z-order is unchanged.
     */
    @Test
    fun testZOrderUnchanged() {
        val adapter = TestSandboxedUiAdapter(/* hasFailingTestSession=*/false)
        val coreLibInfo = getCoreLibInfoFromAdapter(adapter)
        val adapterFromCoreLibInfo = SandboxedUiAdapterFactory.createFromCoreLibInfo(coreLibInfo)
        view.setAdapter(adapterFromCoreLibInfo)
        assertThat(adapter.isOpenSessionCalled).isTrue()

        view.orderProviderUiAboveClientUi(adapter.initialZOrderOnTop)
        val testSession = adapter.session as TestSandboxedUiAdapter.TestSession
        assertThat(testSession.zOrderChanged).isFalse()
    }

    @Test
    fun testHostCanSetZOrderAboveBeforeOpeningSession() {
        val adapter = openSessionAndWaitToBeActive(true)
        injectInputEventOnView()
        // the injected touch should be handled by the provider in Z-above mode
        assertThat(adapter.touchedLatch.await(TIMEOUT, TimeUnit.MILLISECONDS)).isTrue()
    }

    @Test
    fun testHostCanSetZOrderBelowBeforeOpeningSession() {
        // TODO(b/300396631): Skip for backward compats
        assumeTrue(!invokeBackwardsCompatFlow)

        val adapter = openSessionAndWaitToBeActive(false)
        injectInputEventOnView()
        // the injected touch should not reach the provider in Z-below mode
        assertThat(adapter.touchedLatch.await(TIMEOUT, TimeUnit.MILLISECONDS)).isFalse()
    }

    @Test
    fun testSessionError() {
        val adapter = TestSandboxedUiAdapter(true)
        val coreLibInfo = getCoreLibInfoFromAdapter(adapter)
        val adapterThatFailsToCreateUi =
            SandboxedUiAdapterFactory.createFromCoreLibInfo(coreLibInfo)
        view.setAdapter(adapterThatFailsToCreateUi)
        assertThat(errorLatch.await(TIMEOUT, TimeUnit.MILLISECONDS)).isTrue()
        assertThat(stateChangeListener.error?.message).isEqualTo("Test Session Exception")
    }

    // TODO(b/300056633): Replace with actual e2e test flow that triggers resize request
    @Test
    fun test_ProviderInitiatedResize_ReceivedByClient() {
        val sdkAdapter = TestSandboxedUiAdapter(/*hasfailingTestSession=*/false)
        val coreLibInfo = getCoreLibInfoFromAdapter(sdkAdapter)
        val adapter = SandboxedUiAdapterFactory.createFromCoreLibInfo(coreLibInfo)
        val testSessionClient = TestSessionClient()

        adapter.openSession(
            context,
            Binder(),
            /*initialWidth=*/ 10,
            /*initialHeight=*/ 10,
            true,
            Runnable::run,
            testSessionClient
        )
        assertThat(testSessionClient.isSessionOpened).isTrue()

        // Request resize from Session side
        val testSession = sdkAdapter.session as TestSandboxedUiAdapter.TestSession
        testSession.sessionClient.onResizeRequested(20, 30)

        // Verify SessionClient received the request
        assertWithMessage("Resized width").that(testSessionClient.resizedWidth).isEqualTo(20)
        assertWithMessage("Resized height").that(testSessionClient.resizedHeight).isEqualTo(30)
    }

    // TODO(b/300056633): Replace with actual e2e test flow that triggers resize request
    @Test
    fun test_ClientInitiatedResize_ReceivedByProvider() {
        val sdkAdapter = TestSandboxedUiAdapter(/*hasfailingTestSession=*/false)
        val coreLibInfo = getCoreLibInfoFromAdapter(sdkAdapter)
        val adapter = SandboxedUiAdapterFactory.createFromCoreLibInfo(coreLibInfo)
        val testSessionClient = TestSessionClient()
        adapter.openSession(
            context,
            Binder(),
            /*initialWidth=*/ 10,
            /*initialHeight=*/ 10,
            true,
            Runnable::run,
            testSessionClient
        )
        assertThat(testSessionClient.isSessionOpened).isTrue()

        // Notify resized from the client
        testSessionClient.session.notifyResized(20, 30)

        // Verify Session received the request
        val testSession = sdkAdapter.session as TestSandboxedUiAdapter.TestSession
        assertWithMessage("Resized width").that(testSession.resizedWidth).isEqualTo(20)
        assertWithMessage("Resized height").that(testSession.resizedHeight).isEqualTo(30)
    }

    private fun getCoreLibInfoFromAdapter(sdkAdapter: SandboxedUiAdapter): Bundle {
        val bundle = sdkAdapter.toCoreLibInfo(context)
        bundle.putBoolean(TEST_ONLY_USE_REMOTE_ADAPTER, !invokeBackwardsCompatFlow)
        return bundle
    }

    private fun openSessionAndWaitToBeActive(initialZOrder: Boolean): TestSandboxedUiAdapter {
        val adapter = TestSandboxedUiAdapter(/*hasFailingTestSession=*/ false)
        val coreLibInfo = getCoreLibInfoFromAdapter(adapter)
        val adapterFromCoreLibInfo = SandboxedUiAdapterFactory.createFromCoreLibInfo(coreLibInfo)
        view.orderProviderUiAboveClientUi(initialZOrder)
        view.setAdapter(adapterFromCoreLibInfo)
        val activeLatch = CountDownLatch(1)
        view.addStateChangedListener { state ->
            if (state is SandboxedSdkUiSessionState.Active) {
                activeLatch.countDown()
            }
        }
        assertThat(activeLatch.await(TIMEOUT, TimeUnit.MILLISECONDS)).isTrue()
        return adapter
    }

    private fun injectInputEventOnView() {
        activity.runOnUiThread {
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            InstrumentationRegistry.getInstrumentation().uiAutomation.injectInputEvent(
                MotionEvent.obtain(
                    SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), MotionEvent.ACTION_DOWN,
                    (location[0] + 1).toFloat(),
                    (location[1] + 1).toFloat(), 0), false)
        }
    }

    class TestStateChangeListener(private val errorLatch: CountDownLatch) :
        SandboxedSdkUiSessionStateChangedListener {
        var currentState: SandboxedSdkUiSessionState? = null
        var error: Throwable? = null

        override fun onStateChanged(state: SandboxedSdkUiSessionState) {
            currentState = state
            if (state is SandboxedSdkUiSessionState.Error) {
                error = state.throwable
                errorLatch.countDown()
            }
        }
    }

    /**
     *  TestSandboxedUiAdapter provides content from a fake SDK to show on the host's UI.
     *
     *  A [SandboxedUiAdapter] is supposed to fetch the content from SandboxedSdk, but we fake the
     *  source of content in this class.
     *
     *  If [hasFailingTestSession] is true, the fake server side logic returns error.
     */
    class TestSandboxedUiAdapter(
        private val hasFailingTestSession: Boolean
    ) : SandboxedUiAdapter {

        private val openSessionLatch: CountDownLatch = CountDownLatch(1)

        val isOpenSessionCalled: Boolean
            get() = openSessionLatch.await(TIMEOUT, TimeUnit.MILLISECONDS)

        var initialZOrderOnTop = false
        var touchedLatch = CountDownLatch(1)

        lateinit var session: SandboxedUiAdapter.Session

        override fun openSession(
            context: Context,
            windowInputToken: IBinder,
            initialWidth: Int,
            initialHeight: Int,
            isZOrderOnTop: Boolean,
            clientExecutor: Executor,
            client: SandboxedUiAdapter.SessionClient
        ) {
            initialZOrderOnTop = isZOrderOnTop
            session = if (hasFailingTestSession) {
                FailingTestSession(context, client)
            } else {
                TestSession(context, client)
            }
            client.onSessionOpened(session)
            openSessionLatch.countDown()
        }

        /**
         * A failing session that always sends error notice to the client when content is requested.
         */
        inner class FailingTestSession(
            private val context: Context,
            private val sessionClient: SandboxedUiAdapter.SessionClient
        ) : SandboxedUiAdapter.Session {
            override val view: View
                get() {
                    sessionClient.onSessionError(Throwable("Test Session Exception"))
                    return View(context)
                }

            override fun notifyResized(width: Int, height: Int) {
            }

            override fun notifyZOrderChanged(isZOrderOnTop: Boolean) {
            }

            override fun notifyConfigurationChanged(configuration: Configuration) {
            }

            override fun close() {
            }
        }

        inner class TestSession(
            private val context: Context,
            val sessionClient: SandboxedUiAdapter.SessionClient
        ) : SandboxedUiAdapter.Session {

            private val configLatch = CountDownLatch(1)
            private val resizeLatch = CountDownLatch(1)
            private val zOrderLatch = CountDownLatch(1)

            var config: Configuration? = null
                get() {
                    configLatch.await(TIMEOUT, TimeUnit.MILLISECONDS)
                    return field
                }

            var zOrderChanged = false
                get() {
                    zOrderLatch.await(TIMEOUT, TimeUnit.MILLISECONDS)
                    return field
                }

            var resizedWidth = 0
                get() {
                    resizeLatch.await(TIMEOUT, TimeUnit.MILLISECONDS)
                    return field
                }

            var resizedHeight = 0
                get() {
                    resizeLatch.await(TIMEOUT, TimeUnit.MILLISECONDS)
                    return field
                }

            override val view: View
                get() {
                    return View(context).also {
                        it.setOnTouchListener { _, _ ->
                            touchedLatch.countDown()
                            true
                        }
                    }
                }

            override fun notifyResized(width: Int, height: Int) {
                resizedWidth = width
                resizedHeight = height
                resizeLatch.countDown()
            }

            override fun notifyZOrderChanged(isZOrderOnTop: Boolean) {
                zOrderChanged = true
                zOrderLatch.countDown()
            }

            override fun notifyConfigurationChanged(configuration: Configuration) {
                config = configuration
                configLatch.countDown()
            }

            override fun close() {
            }
        }
    }

    class TestSessionClient : SandboxedUiAdapter.SessionClient {
        private val sessionOpenedLatch = CountDownLatch(1)
        private val resizeRequestedLatch = CountDownLatch(1)

        lateinit var session: SandboxedUiAdapter.Session

        val isSessionOpened: Boolean
            get() = sessionOpenedLatch.await(TIMEOUT, TimeUnit.MILLISECONDS)

        var resizedWidth = 0
            get() {
                resizeRequestedLatch.await(TIMEOUT, TimeUnit.MILLISECONDS)
                return field
            }

        var resizedHeight = 0
            get() {
                resizeRequestedLatch.await(TIMEOUT, TimeUnit.MILLISECONDS)
                return field
            }

        override fun onSessionOpened(session: SandboxedUiAdapter.Session) {
            this.session = session
            sessionOpenedLatch.countDown()
        }

        override fun onSessionError(throwable: Throwable) {
        }

        override fun onResizeRequested(width: Int, height: Int) {
            resizedWidth = width
            resizedHeight = height
            resizeRequestedLatch.countDown()
        }
    }
}
