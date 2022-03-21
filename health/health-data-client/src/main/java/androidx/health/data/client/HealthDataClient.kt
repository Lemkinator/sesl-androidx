/*
 * Copyright (C) 2022 The Android Open Source Project
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
package androidx.health.data.client

import androidx.annotation.RestrictTo
import androidx.health.data.client.aggregate.AggregateDataRow
import androidx.health.data.client.aggregate.AggregateMetric
import androidx.health.data.client.metadata.DataOrigin
import androidx.health.data.client.permission.Permission
import androidx.health.data.client.records.Record
import androidx.health.data.client.request.ChangesTokenRequest
import androidx.health.data.client.request.ReadRecordsRequest
import androidx.health.data.client.response.ChangesResponse
import androidx.health.data.client.response.InsertRecordsResponse
import androidx.health.data.client.response.ReadRecordResponse
import androidx.health.data.client.response.ReadRecordsResponse
import androidx.health.data.client.time.TimeRangeFilter
import java.lang.IllegalStateException
import kotlin.reflect.KClass

/** Interface to access health and fitness records. */
interface HealthDataClient {
    /**
     * Returns a set of [Permission] granted by the user to this app, out of the input [permissions]
     * set.
     *
     * @throws RemoteException For any IPC transportation failures.
     * @throws IOException For any disk I/O issues.
     * @throws IllegalStateException If service is not available.
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY)
    suspend fun getGrantedPermissions(permissions: Set<Permission>): Set<Permission>

    /**
     * Inserts one or more [Record] and returns newly assigned
     * [androidx.health.data.client.metadata.Metadata.uid] generated. Insertion of multiple
     * [records] is executed in a transaction - if one fails, none is inserted.
     *
     * @param records List of records to insert
     * @return List of unique identifiers in the order of inserted records. nn
     * @throws RemoteException For any IPC transportation failures.
     * @throws SecurityException For requests with unpermitted access.
     * @throws IOException For any disk I/O issues.
     * @throws IllegalStateException If service is not available.
     */
    suspend fun insertRecords(records: List<Record>): InsertRecordsResponse

    /**
     * Updates one or more [Record] of given UIDs to newly specified values. Update of multiple
     * [records] is executed in a transaction - if one fails, none is inserted.
     *
     * @param records List of records to update
     * @throws RemoteException For any IPC transportation failures.
     * @throws SecurityException For requests with unpermitted access.
     * @throws IOException For any disk I/O issues.
     * @throws IllegalStateException If service is not available.
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY) suspend fun updateRecords(records: List<Record>)

    /**
     * Deletes one or more [Record] by their identifiers. Deletion of multiple [Record] is executed
     * in single transaction - if one fails, none is deleted.
     *
     * @param recordType Which type of [Record] to delete, such as `Steps::class`
     * @param uidsList List of uids of [Record] to delete
     * @param clientIdsList List of client IDs of [Record] to delete
     * @throws RemoteException For any IPC transportation failures.
     * @throws SecurityException For requests with unpermitted access.
     * @throws IOException For any disk I/O issues.
     * @throws IllegalStateException If service is not available.
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY)
    suspend fun deleteRecords(
        recordType: KClass<out Record>,
        uidsList: List<String>,
        clientIdsList: List<String>,
    )

    /**
     * Deletes one or more [Record] points of the given [recordType] in the given range
     * (automatically filtered to [Record] belonging to this application). Deletion of multiple
     * [Record] is executed in a transaction - if one fails, none is deleted.
     *
     * When a field is null in [TimeRangeFilter] then the filtered range is open-ended in that
     * direction. Hence if all fields are null in [TimeRangeFilter] then all data of the requested
     * [Record] type is deleted.
     *
     * @param recordType Which type of [Record] to delete, such as `Steps::class`
     * @param timeRangeFilter The [TimeRangeFilter] to delete from
     * @throws RemoteException For any IPC transportation failures.
     * @throws SecurityException For requests with unpermitted access.
     * @throws IOException For any disk I/O issues.
     * @throws IllegalStateException If service is not available.
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY)
    suspend fun deleteRecords(recordType: KClass<out Record>, timeRangeFilter: TimeRangeFilter)

    /**
     * Reads one [Record] point determined by its data type and UID.
     *
     * @param recordType Which type of [Record] to read, such as `Steps::class`
     * @param uid Uid of [Record] to read
     * @return The [Record] data point.
     * @throws RemoteException For any IPC transportation failures.
     * @throws SecurityException For requests with unpermitted access.
     * @throws IOException For any disk I/O issues.
     * @throws IllegalStateException If service is not available.
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY)
    suspend fun <T : Record> readRecord(recordType: KClass<T>, uid: String): ReadRecordResponse<T>

    /**
     * Retrieves a collection of [Record]s.
     *
     * @param T the type of [Record]
     * @param request [ReadRecordsRequest] object specifying time range and other filters
     *
     * @return a response containing a collection of [Record]s.
     * @throws RemoteException For any IPC transportation failures.
     * @throws SecurityException For requests with unpermitted access.
     * @throws IOException For any disk I/O issues.
     * @throws IllegalStateException If service is not available.
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY)
    suspend fun <T : Record> readRecords(request: ReadRecordsRequest<T>): ReadRecordsResponse<T>

    /**
     * Reads [AggregateMetric]s according to requested read criteria: [Record]s from
     * [dataOriginFilter] and within [timeRangeFilter]
     *
     * @param aggregateMetrics The [AggregateMetric]s to aggregate
     * @param timeRangeFilter The [TimeRangeFilter] to read from.
     * @param dataOriginFilter List of [DataOrigin] to read from, or empty for no filter.
     *
     * @return a response containing a collection of [Record]s.
     * @throws RemoteException For any IPC transportation failures.
     * @throws SecurityException For requests with unpermitted access.
     * @throws IOException For any disk I/O issues.
     * @throws IllegalStateException If service is not available.
     */
    // TODO(b/219327548): Expand this to reuse readRecords time range filter and data origin
    // filters.
    @RestrictTo(RestrictTo.Scope.LIBRARY)
    suspend fun aggregate(
        aggregateMetrics: Set<AggregateMetric>,
        timeRangeFilter: TimeRangeFilter,
        dataOriginFilter: List<DataOrigin>,
    ): AggregateDataRow

    // TODO(b/219327548): Adds overload with groupBy that return a list

    /**
     * Retrieves a changes-token, representing a point in time in the underlying Android Health
     * Platform for a given [ChangesTokenRequest]. Changes-tokens are used in [getChanges] to
     * retrieve changes since that point in time.
     *
     * Changes-tokens represent a point in time after which the client is interested in knowing the
     * changes for a set of interested types of [Record] and optional [DataOrigin] filters.
     *
     * Changes-tokens are only valid for 30 days after they're generated. Calls to [getChanges] with
     * an invalid changes-token will fail.
     *
     * @param request Includes interested types of record to observe changes and optional filters.
     * @throws RemoteException For any IPC transportation failures.
     * @throws SecurityException For requests with unpermitted access.
     * @throws IllegalStateException If service is not available.
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY)
    suspend fun getChangesToken(request: ChangesTokenRequest): String

    /**
     * Retrieves changes in Android Health Platform, from a specific point in time represented by
     * provided [changesToken].
     *
     * The response returned may not provide all the changes due to IPC or memory limits, see
     * [ChangesResponse.hasMore]. Clients can make more api calls to fetch more changes from the
     * Android Health Platform with updated [ChangesResponse.nextChangesToken].
     *
     * Provided [changesToken] may have expired if clients have not synced for extended period of
     * time (such as a month). In this case [ChangesResponse.changesTokenExpired] will be set, and
     * clients should generate a new changes-token via [getChangesToken].
     *
     * @param changesToken A Changes-Token that represents a specific point in time in Android
     * Health Platform.
     * @throws RemoteException For any IPC transportation failures.
     * @throws SecurityException For requests with unpermitted access.
     * @throws IllegalStateException If service is not available.
     */
    @RestrictTo(RestrictTo.Scope.LIBRARY)
    suspend fun getChanges(changesToken: String): ChangesResponse
}
