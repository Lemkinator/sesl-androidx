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

package androidx.datastore.core

import android.content.Context
import android.os.Bundle
import androidx.datastore.core.handlers.NoOpCorruptionHandler
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.core.okio.OkioSerializer
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.testing.TestMessageProto.FooProto
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.google.protobuf.ExtensionRegistryLite
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import kotlin.coroutines.CoroutineContext
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.newSingleThreadContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path.Companion.toPath
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

private const val PATH_BUNDLE_KEY: String = "path"
private const val STORAGE_BUNDLE_KEY: String = "storage"
private const val STORAGE_FILE: String = "FILE"
private const val STORAGE_OKIO: String = "OKIO"
private val PROTO_SERIALIZER: Serializer<FooProto> = ProtoSerializer<FooProto>(
    FooProto.getDefaultInstance(),
    ExtensionRegistryLite.getEmptyRegistry()
)
private val PROTO_OKIO_SERIALIZER: OkioSerializer<FooProto> = ProtoOkioSerializer<FooProto>(
    FooProto.getDefaultInstance(),
    ExtensionRegistryLite.getEmptyRegistry()
)
private const val TEST_TEXT: String = "abc"
internal val WRITE_TEXT: (FooProto) -> FooProto = { f: FooProto ->
    f.toBuilder().setText(TEST_TEXT).build()
}
private val WRITE_BOOLEAN: (FooProto) -> FooProto = { f: FooProto ->
    f.toBuilder().setBoolean(true).build()
}
private val INCREMENT_INTEGER: (FooProto) -> FooProto = { f: FooProto ->
    f.toBuilder().setInteger(f.integer + 1).build()
}

private val DEFAULT_FOO: FooProto = FooProto.getDefaultInstance()
private val FOO_WITH_TEXT: FooProto =
    FooProto.newBuilder().setText(TEST_TEXT).build()
private val FOO_WITH_TEXT_AND_BOOLEAN: FooProto =
    FooProto.newBuilder().setText(TEST_TEXT).setBoolean(true).build()

private val FILESYSTEM = FileSystem.SYSTEM

@ExperimentalCoroutinesApi
private fun createDataStore(
    bundle: Bundle,
    scope: TestScope,
    corruptionHandler: CorruptionHandler<FooProto> = NoOpCorruptionHandler<FooProto>(),
    context: CoroutineContext = UnconfinedTestDispatcher()
): DataStore<FooProto> {
    val file = File(bundle.getString(PATH_BUNDLE_KEY)!!)
    val produceFile = { file }
    val variant = StorageVariant.valueOf(bundle.getString(STORAGE_BUNDLE_KEY)!!)
    val storage = if (variant == StorageVariant.FILE) {
        FileStorage(
            PROTO_SERIALIZER,
            { MultiProcessCoordinator(context, it) },
            produceFile
        )
    } else {
        OkioStorage(
            FILESYSTEM,
            PROTO_OKIO_SERIALIZER,
            { _, _ -> MultiProcessCoordinator(context, file) },
            { file.absolutePath.toPath() }
        )
    }
    return DataStoreImpl(
        storage = storage,
        scope = scope,
        corruptionHandler = corruptionHandler
    )
}

internal enum class StorageVariant(val storage: String) {
    FILE(STORAGE_FILE), OKIO(STORAGE_OKIO)
}

@OptIn(DelicateCoroutinesApi::class)
@ExperimentalCoroutinesApi
@RunWith(JUnit4::class)
class MultiProcessDataStoreMultiProcessTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var testFile: File
    private lateinit var dataStoreContext: CoroutineContext
    private lateinit var dataStoreScope: TestScope

    private val mainContext: Context = ApplicationProvider.getApplicationContext()

    private fun createDataStoreBundle(path: String, variant: StorageVariant): Bundle {
        val data = Bundle()
        data.putString(PATH_BUNDLE_KEY, path)
        data.putString(STORAGE_BUNDLE_KEY, variant.storage)
        return data
    }

    @Before
    fun setUp() {
        testFile = tempFolder.newFile()
        dataStoreContext = UnconfinedTestDispatcher()
        dataStoreScope = TestScope(dataStoreContext + Job())
    }

    @Test
    fun testInterleavedUpdateDataWithLocalRead_file() =
        testInterleavedUpdateDataWithLocalRead_runner(StorageVariant.FILE)

    @Test
    fun testInterleavedUpdateDataWithLocalRead_okio() =
        testInterleavedUpdateDataWithLocalRead_runner(StorageVariant.OKIO)

    private fun testInterleavedUpdateDataWithLocalRead_runner(variant: StorageVariant) =
        runTest(UnconfinedTestDispatcher(), timeout = 10000.milliseconds) {
            val testData: Bundle = createDataStoreBundle(testFile.absolutePath, variant)
            val dataStore: DataStore<FooProto> =
                createDataStore(testData, dataStoreScope, context = dataStoreContext)
            val serviceClasses = mapOf(
                StorageVariant.FILE to InterleavedUpdateDataWithReadFileService::class,
                StorageVariant.OKIO to InterleavedUpdateDataWithReadOkioService::class
            )
            val connection: BlockingServiceConnection =
                setUpService(
                    mainContext,
                    serviceClasses[variant]!!.java,
                    testData
                )

            // Invalidate any local cache
            assertThat(dataStore.data.first()).isEqualTo(DEFAULT_FOO)
            signalService(connection)

            // Queue and start local write
            val writeStarted = CompletableDeferred<Unit>()
            val finishWrite = CompletableDeferred<Unit>()

            val write = async {
                dataStore.updateData {
                    writeStarted.complete(Unit)
                    finishWrite.await()
                    FOO_WITH_TEXT
                }
            }
            writeStarted.await()

            // Queue remote write
            signalService(connection)

            // Local uncached read; this should see data initially written remotely.
            assertThat(dataStore.data.first()).isEqualTo(
                FooProto.newBuilder().setInteger(1).build()
            )

            // Unblock writes; the local write is delayed to ensure the remote write remains blocked.
            val remoteWrite = async(newSingleThreadContext("blockedWriter")) {
                signalService(connection)
            }

            val localWrite = async(newSingleThreadContext("unblockLocalWrite")) {
                delay(500)
                finishWrite.complete(Unit)
                write.await()
            }

            localWrite.await()
            remoteWrite.await()

            assertThat(dataStore.data.first()).isEqualTo(FOO_WITH_TEXT_AND_BOOLEAN)
        }

    open class InterleavedUpdateDataWithReadFileService(
        private val scope: TestScope = TestScope(UnconfinedTestDispatcher() + Job())
    ) : DirectTestService() {
        override fun beforeTest(testData: Bundle) {
            store = createDataStore(testData, scope)
        }

        override fun runTest() = runBlocking<Unit> {
            store.updateData {
                INCREMENT_INTEGER(it)
            }

            waitForSignal()

            val write = async {
                store.updateData {
                    WRITE_BOOLEAN(it)
                }
            }
            waitForSignal()
            write.await()
        }
    }

    class InterleavedUpdateDataWithReadOkioService : InterleavedUpdateDataWithReadFileService()

    @Test
    fun testUpdateDataExceptionUnblocksOtherProcessFromWriting_file() =
        testUpdateDataExceptionUnblocksOtherProcessFromWriting_runner(StorageVariant.FILE)

    @Test
    fun testUpdateDataExceptionUnblocksOtherProcessFromWriting_okio() =
        testUpdateDataExceptionUnblocksOtherProcessFromWriting_runner(StorageVariant.OKIO)

    private fun testUpdateDataExceptionUnblocksOtherProcessFromWriting_runner(
        variant: StorageVariant
    ) = runTest(timeout = 10000.milliseconds) {
        val testData: Bundle = createDataStoreBundle(testFile.absolutePath, variant)
        val dataStore: DataStore<FooProto> =
            createDataStore(testData, dataStoreScope, context = dataStoreContext)
        val serviceClasses = mapOf(
            StorageVariant.FILE to FailedUpdateDataFileService::class,
            StorageVariant.OKIO to FailedUpdateDataOkioService::class
        )
        val connection: BlockingServiceConnection =
            setUpService(mainContext, serviceClasses[variant]!!.java, testData)

        val blockWrite = CompletableDeferred<Unit>()
        val waitForWrite = CompletableDeferred<Unit>()

        val write = async {
            try {
                dataStore.updateData {
                    blockWrite.await()
                    throw IOException("Something went wrong")
                }
            } catch (e: IOException) {
                waitForWrite.complete(Unit)
            }
        }

        assertThat(write.isActive).isTrue()
        assertThat(write.isCompleted).isFalse()

        blockWrite.complete(Unit)
        waitForWrite.await()

        assertThat(write.isActive).isFalse()
        assertThat(write.isCompleted).isTrue()

        signalService(connection)

        assertThat(dataStore.data.first()).isEqualTo(FOO_WITH_TEXT)
    }

    open class FailedUpdateDataFileService(
        private val scope: TestScope = TestScope(UnconfinedTestDispatcher() + Job())
    ) : DirectTestService() {
        override fun beforeTest(testData: Bundle) {
            store = createDataStore(testData, scope)
        }

        override fun runTest() = runBlocking<Unit> {
            store.updateData {
                WRITE_TEXT(it)
            }
        }
    }

    class FailedUpdateDataOkioService : FailedUpdateDataFileService()

    @Test
    fun testUpdateDataCancellationUnblocksOtherProcessFromWriting_file() =
        testUpdateDataCancellationUnblocksOtherProcessFromWriting_runner(StorageVariant.FILE)

    @Test
    fun testUpdateDataCancellationUnblocksOtherProcessFromWriting_okio() =
        testUpdateDataCancellationUnblocksOtherProcessFromWriting_runner(StorageVariant.OKIO)

    private fun testUpdateDataCancellationUnblocksOtherProcessFromWriting_runner(
        variant: StorageVariant
    ) = runTest(UnconfinedTestDispatcher(), timeout = 10000.milliseconds) {
        val localScope = TestScope(UnconfinedTestDispatcher() + Job())
        val testData: Bundle = createDataStoreBundle(testFile.absolutePath, variant)
        val dataStore: DataStore<FooProto> =
            createDataStore(testData, localScope, context = dataStoreContext)
        val serviceClasses = mapOf(
            StorageVariant.FILE to CancelledUpdateDataFileService::class,
            StorageVariant.OKIO to CancelledUpdateDataOkioService::class
        )
        val connection: BlockingServiceConnection =
            setUpService(mainContext, serviceClasses[variant]!!.java, testData)

        val blockWrite = CompletableDeferred<Unit>()

        val write = localScope.async {
            dataStore.updateData {
                blockWrite.await()
                WRITE_BOOLEAN(it)
            }
        }

        assertThat(write.isActive).isTrue()
        assertThat(write.isCompleted).isFalse()

        // dataStore.updateData cancelled immediately
        localScope.coroutineContext.cancelChildren()

        assertThat(write.isActive).isFalse()
        assertThat(write.isCompleted).isTrue()

        signalService(connection)

        // able to read the new value written from the other process
        assertThat(dataStore.data.first()).isEqualTo(FOO_WITH_TEXT)
    }

    // A duplicate from CancelledUpdateDataService to make sure Android framework would create a
    // new process for this test. Otherwise the test would hang infinitely because the tests bind
    // to an existing service created by the previous test.
    open class CancelledUpdateDataFileService(
        private val scope: TestScope = TestScope(UnconfinedTestDispatcher() + Job())
    ) : DirectTestService() {
        override fun beforeTest(testData: Bundle) {
            store = createDataStore(testData, scope)
        }

        override fun runTest() = runBlocking<Unit> {
            store.updateData {
                WRITE_TEXT(it)
            }
        }
    }

    class CancelledUpdateDataOkioService : CancelledUpdateDataFileService()

    @Test
    fun testReadUpdateCorrupt_file() = testReadUpdateCorrupt_runner(StorageVariant.FILE)

    @Test
    fun testReadUpdateCorrupt_okio() = testReadUpdateCorrupt_runner(StorageVariant.OKIO)

    private fun testReadUpdateCorrupt_runner(variant: StorageVariant) =
        runTest(timeout = 10000.milliseconds) {
            FileOutputStream(testFile).use {
                OutputStreamWriter(it).write("garbage")
            }
            val testData: Bundle = createDataStoreBundle(testFile.absolutePath, variant)
            val serviceClasses = mapOf(
                StorageVariant.FILE to InterleavedHandlerUpdateDataFileService::class,
                StorageVariant.OKIO to InterleavedHandlerUpdateDataOkioService::class
            )
            val connection: BlockingServiceConnection =
                setUpService(mainContext, serviceClasses[variant]!!.java, testData)
            val corruptionHandler = ReplaceFileCorruptionHandler<FooProto> {
                signalService(connection)
                FOO_WITH_TEXT_AND_BOOLEAN
            }
            val dataStore: DataStore<FooProto> =
                createDataStore(testData, dataStoreScope, corruptionHandler, dataStoreContext)

            // Other proc starts TEST_TEXT then waits for signal within handler
            signalService(connection)

            assertThat(dataStore.data.first()).isEqualTo(FOO_WITH_TEXT)

            // version file should be ready at this point
            val sharedCounter = SharedCounter.create {
                File(testFile.absolutePath + ".version")
            }
            // only 1 write should be done to handle the corruption, so version is incremented by 1
            assertThat(sharedCounter.getValue()).isEqualTo(1)
        }

    open class InterleavedHandlerUpdateDataFileService(
        private val scope: TestScope = TestScope(UnconfinedTestDispatcher() + Job())
    ) : DirectTestService() {
        override fun beforeTest(testData: Bundle) {
            val corruptionHandler: CorruptionHandler<FooProto> =
                ReplaceFileCorruptionHandler<FooProto> {
                    waitForSignal()
                    DEFAULT_FOO
                }
            store = createDataStore(testData, scope, corruptionHandler)
        }

        override fun runTest() = runBlocking<Unit> {
            store.updateData {
                WRITE_TEXT(it)
            }
        }
    }

    class InterleavedHandlerUpdateDataOkioService : InterleavedHandlerUpdateDataFileService()
}
