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

package androidx.credentials.exceptions

import androidx.annotation.VisibleForTesting

/**
 * This is thrown when the create credential operation failed with no more detailed information.
 *
 * @see CreateCredentialException
 */
class CreateCredentialUnknownException @JvmOverloads constructor(
    errorMessage: CharSequence? = null
) : CreateCredentialException(TYPE_CREATE_CREDENTIAL_UNKNOWN_EXCEPTION, errorMessage) {

    /** @hide */
    companion object {
        @VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
        const val TYPE_CREATE_CREDENTIAL_UNKNOWN_EXCEPTION: String =
            "androidx.credentials.TYPE_CREATE_CREDENTIAL_UNKNOWN_EXCEPTION"
    }
}