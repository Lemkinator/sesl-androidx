/*
 * Copyright 2022 The Android Open Source Project
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

package androidx.camera.core.processing

import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.os.Build
import android.os.Looper.getMainLooper
import android.util.Size
import android.view.Surface
import androidx.camera.core.CameraEffect
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.SurfaceRequest.Result.RESULT_REQUEST_CANCELLED
import androidx.camera.core.SurfaceRequest.TransformationInfo
import androidx.camera.core.impl.DeferrableSurface
import androidx.camera.core.impl.DeferrableSurface.SurfaceClosedException
import androidx.camera.core.impl.DeferrableSurface.SurfaceUnavailableException
import androidx.camera.core.impl.ImmediateSurface
import androidx.camera.core.impl.utils.TransformUtils.sizeToRect
import androidx.camera.core.impl.utils.executor.CameraXExecutors.mainThreadExecutor
import androidx.camera.core.impl.utils.futures.FutureCallback
import androidx.camera.core.impl.utils.futures.Futures
import androidx.camera.testing.fakes.FakeCamera
import androidx.concurrent.futures.CallbackToFutureAdapter
import com.google.common.truth.Truth.assertThat
import com.google.common.util.concurrent.ListenableFuture
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.internal.DoNotInstrument

/**
 * Unit tests for [SurfaceEdge].
 */
@RunWith(RobolectricTestRunner::class)
@DoNotInstrument
@Config(minSdk = Build.VERSION_CODES.LOLLIPOP)
class SurfaceEdgeTest {

    companion object {
        private val INPUT_SIZE = Size(640, 480)
    }

    private lateinit var surfaceEdge: SurfaceEdge
    private lateinit var fakeSurface: Surface
    private lateinit var fakeSurfaceTexture: SurfaceTexture

    @Before
    fun setUp() {
        surfaceEdge = SurfaceEdge(
            CameraEffect.PREVIEW, Size(640, 480),
            Matrix(), true, Rect(), 0, false
        )
        fakeSurfaceTexture = SurfaceTexture(0)
        fakeSurface = Surface(fakeSurfaceTexture)
    }

    @After
    fun tearDown() {
        surfaceEdge.close()
        fakeSurfaceTexture.release()
        fakeSurface.release()
    }

    @Test
    fun closeProviderAfterConnected_surfaceNotReleased() {
        // Arrange.
        val surfaceRequest = surfaceEdge.createSurfaceRequest(FakeCamera())
        var result: SurfaceRequest.Result? = null
        surfaceRequest.provideSurface(fakeSurface, mainThreadExecutor()) {
            result = it
        }
        // Act: close the provider
        surfaceRequest.deferrableSurface.close()
        shadowOf(getMainLooper()).idle()
        // Assert: the surface is not released because the parent is not closed.
        assertThat(result).isNull()
    }

    @Test(expected = SurfaceClosedException::class)
    fun connectToClosedProvider_getsException() {
        val closedDeferrableSurface = ImmediateSurface(fakeSurface).apply {
            this.close()
        }
        surfaceEdge.setProvider(closedDeferrableSurface)
    }

    @Test
    fun createSurfaceRequestAndCancel_cancellationIsPropagated() {
        // Arrange: create a SurfaceRequest.
        val surfaceRequest = surfaceEdge.createSurfaceRequest(FakeCamera())
        var throwable: Throwable? = null
        Futures.addCallback(
            surfaceEdge.deferrableSurface.surface,
            object : FutureCallback<Surface> {
                override fun onFailure(t: Throwable) {
                    throwable = t
                }

                override fun onSuccess(result: Surface?) {
                    throw IllegalStateException("Should not succeed.")
                }
            },
            mainThreadExecutor()
        )

        // Act: set it as "will not provide".
        surfaceRequest.willNotProvideSurface()
        shadowOf(getMainLooper()).idle()

        // Assert: the DeferrableSurface returns an error.
        assertThat(throwable).isInstanceOf(SurfaceUnavailableException::class.java)
    }

    @Test
    fun createSurfaceRequestWithClosedInstance_surfaceRequestCancelled() {
        // Arrange: create a SurfaceRequest from a closed LinkableSurface
        surfaceEdge.close()
        val surfaceRequest = surfaceEdge.createSurfaceRequest(FakeCamera())

        // Act: provide a Surface and get the result.
        var result: SurfaceRequest.Result? = null
        surfaceRequest.provideSurface(fakeSurface, mainThreadExecutor()) {
            result = it
        }
        shadowOf(getMainLooper()).idle()

        // Assert: the Surface is never used.
        assertThat(result!!.resultCode).isEqualTo(RESULT_REQUEST_CANCELLED)
    }

    @Test
    fun createSurfaceOutputWithClosedInstance_surfaceOutputNotCreated() {
        // Arrange: create a SurfaceOutput future from a closed LinkableSurface
        surfaceEdge.close()
        val surfaceOutput = createSurfaceOutputFuture(surfaceEdge)

        // Act: wait for the SurfaceOutput to return.
        var successful: Boolean? = null
        Futures.addCallback(surfaceOutput, object : FutureCallback<SurfaceOutput> {
            override fun onSuccess(result: SurfaceOutput?) {
                successful = true
            }

            override fun onFailure(t: Throwable) {
                successful = false
            }
        }, mainThreadExecutor())
        shadowOf(getMainLooper()).idle()

        // Assert: the SurfaceOutput is not created.
        assertThat(successful!!).isEqualTo(false)
    }

    @Test
    fun createSurfaceRequestAndInvalidate_edgeResets() {
        // Arrange: listen for the reset.
        var isReset = false
        val surfaceRequest = surfaceEdge.createSurfaceRequest(FakeCamera())
        surfaceEdge.addOnInvalidatedListener { isReset = true }
        // Act: invalidate the SurfaceRequest.
        surfaceRequest.invalidate()
        shadowOf(getMainLooper()).idle()
        // Assert: edge is reset.
        assertThat(isReset).isTrue()
    }

    @Test
    fun createSurfaceRequestAndProvide_surfaceIsPropagated() {
        // Arrange: create a SurfaceRequest.
        val surfaceRequest = surfaceEdge.createSurfaceRequest(FakeCamera())
        // Act: provide Surface.
        surfaceRequest.provideSurface(fakeSurface, mainThreadExecutor()) {}
        shadowOf(getMainLooper()).idle()
        // Assert: the surface is received.
        assertThat(surfaceEdge.deferrableSurface.surface.isDone).isTrue()
        assertThat(surfaceEdge.deferrableSurface.surface.get()).isEqualTo(fakeSurface)
    }

    @Test
    fun createSurfaceRequest_hasCameraTransformSetCorrectly() {
        assertThat(getSurfaceRequestHasTransform(true)).isTrue()
        assertThat(getSurfaceRequestHasTransform(false)).isFalse()
    }

    /**
     * Creates a [SurfaceEdge] with the given hasCameraTransform value, and returns the
     * [TransformationInfo.hasCameraTransform] from the [SurfaceRequest].
     */
    private fun getSurfaceRequestHasTransform(hasCameraTransform: Boolean): Boolean {
        // Arrange.
        val surface = SurfaceEdge(
            CameraEffect.PREVIEW, Size(640, 480), Matrix(), hasCameraTransform, Rect(), 0, false
        )
        var transformationInfo: TransformationInfo? = null

        // Act: get the hasCameraTransform bit from the SurfaceRequest.
        surface.createSurfaceRequest(FakeCamera()).setTransformationInfoListener(
            mainThreadExecutor()
        ) {
            transformationInfo = it
        }
        shadowOf(getMainLooper()).idle()
        surface.close()
        return transformationInfo!!.hasCameraTransform()
    }

    @Test
    fun setSourceSurfaceFutureAndProvide_surfaceIsPropagated() {
        // Arrange: set a ListenableFuture<Surface> as the source.
        var completer: CallbackToFutureAdapter.Completer<Surface>? = null
        val surfaceFuture = CallbackToFutureAdapter.getFuture {
            completer = it
            return@getFuture null
        }
        surfaceEdge.setProvider(object : DeferrableSurface() {
            override fun provideSurface(): ListenableFuture<Surface> {
                return surfaceFuture
            }
        })
        // Act: provide Surface.
        completer!!.set(fakeSurface)
        shadowOf(getMainLooper()).idle()
        // Assert: the surface is received.
        assertThat(surfaceEdge.deferrableSurface.surface.isDone).isTrue()
        assertThat(surfaceEdge.deferrableSurface.surface.get()).isEqualTo(fakeSurface)
    }

    @Test
    fun linkBothProviderAndConsumer_surfaceAndResultsArePropagatedE2E() {
        // Arrange: link a LinkableSurface with a SurfaceRequest and a SurfaceOutput.
        val surfaceRequest = surfaceEdge.createSurfaceRequest(FakeCamera())
        val surfaceOutputFuture = createSurfaceOutputFuture(surfaceEdge)
        var surfaceOutput: SurfaceOutput? = null
        Futures.transform(surfaceOutputFuture, {
            surfaceOutput = it
        }, mainThreadExecutor())

        // Act: provide a Surface via the SurfaceRequest.
        var isSurfaceReleased = false
        surfaceRequest.provideSurface(fakeSurface, mainThreadExecutor()) {
            isSurfaceReleased = true
        }
        shadowOf(getMainLooper()).idle()

        // Assert: SurfaceOutput is received and it contains the right Surface
        assertThat(surfaceOutput).isNotNull()
        var surfaceOutputCloseRequested = false
        val surface = surfaceOutput!!.getSurface(mainThreadExecutor()) {
            surfaceOutputCloseRequested = true
        }
        shadowOf(getMainLooper()).idle()
        assertThat(surface).isEqualTo(fakeSurface)
        assertThat(isSurfaceReleased).isEqualTo(false)

        // Act: close the LinkableSurface, signaling the intention to close the Surface.
        surfaceEdge.close()
        shadowOf(getMainLooper()).idle()

        // Assert: The close is propagated to the SurfaceRequest.
        assertThat(surfaceOutputCloseRequested).isEqualTo(true)
        assertThat(isSurfaceReleased).isEqualTo(false)

        // Act: close the LinkableSurface, signaling it's safe to release the Surface.
        surfaceOutput!!.close()
        shadowOf(getMainLooper()).idle()

        // Assert: The close is propagated to the SurfaceRequest.
        assertThat(isSurfaceReleased).isEqualTo(true)
    }

    @Test(expected = IllegalStateException::class)
    fun createSurfaceRequestTwice_throwsException() {
        surfaceEdge.createSurfaceRequest(FakeCamera())
        surfaceEdge.createSurfaceRequest(FakeCamera())
        shadowOf(getMainLooper()).idle()
    }

    @Test(expected = IllegalStateException::class)
    fun createSurfaceOutputTwice_throwsException() {
        createSurfaceOutputFuture(surfaceEdge)
        createSurfaceOutputFuture(surfaceEdge)
        shadowOf(getMainLooper()).idle()
    }

    @Test
    fun setRotationDegrees_sendTransformationInfoUpdate() {
        // Arrange.
        var transformationInfo: TransformationInfo? = null
        val surfaceRequest = surfaceEdge.createSurfaceRequest(FakeCamera())
        surfaceRequest.setTransformationInfoListener(mainThreadExecutor()) {
            transformationInfo = it
        }

        // Act.
        surfaceEdge.rotationDegrees = 90
        shadowOf(getMainLooper()).idle()

        // Assert.
        assertThat(transformationInfo).isNotNull()
        assertThat(transformationInfo!!.rotationDegrees).isEqualTo(90)
    }

    private fun createSurfaceOutputFuture(surfaceEdge: SurfaceEdge) =
        surfaceEdge.createSurfaceOutputFuture(
            INPUT_SIZE,
            sizeToRect(INPUT_SIZE),
            /*rotationDegrees=*/0,
            /*mirroring=*/false
        )
}