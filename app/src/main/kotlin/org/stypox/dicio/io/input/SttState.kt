/*
 * Taken from /e/OS Assistant
 *
 * Copyright (C) 2024 MURENA SAS
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.stypox.dicio.io.input

import org.stypox.dicio.ui.util.Progress

/**
 * State of a speech input device, without any engine-specific fields, so that the UI layer does not
 * depend on which recogniser is behind it.
 */
sealed interface SttState {
    /**
     * Never produced by a [SttInputDevice]: the UI sets it after a failed permission check.
     */
    data object NoMicrophonePermission : SttState

    /**
     * The STT engine has not been initialized yet (waiting for a locale to be available)
     */
    data object NotInitialized : SttState

    /**
     * The STT engine cannot be made available, e.g. because the current language is not supported
     */
    data object NotAvailable : SttState

    /**
     * The model is not present on disk, neither in unzipped and in zipped form.
     */
    data object NotDownloaded : SttState

    data class Downloading(
        val progress: Progress,
    ) : SttState

    data class ErrorDownloading(
        val throwable: Throwable
    ) : SttState

    data object Downloaded : SttState

    /**
     * Vosk models are distributed in Zip files that need unzipping to be ready.
     */
    data class Unzipping(
        val progress: Progress,
    ) : SttState

    data class ErrorUnzipping(
        val throwable: Throwable
    ) : SttState

    /**
     * The model is present on disk, but was not loaded in RAM yet.
     */
    data object NotLoaded : SttState

    /**
     * The model is being loaded, and [thenStartListening] indicates whether, once loading is
     * finished, the STT should start listening right away.
     */
    data class Loading(
        val thenStartListening: Boolean
    ) : SttState

    data class ErrorLoading(
        val throwable: Throwable
    ) : SttState

    /**
     * The model is ready in RAM, and can start listening at any time.
     */
    data object Loaded : SttState

    /**
     * The model is listening.
     */
    data object Listening : SttState

    /**
     * An external Android app has been asked to listen (e.g. through
     * `RecognizerIntent.ACTION_RECOGNIZE_SPEECH`), and may be listening but we don't know for
     * sure (maybe it's still loading). Therefore in the UI a "Waiting..." message should be shown
     * instead of "Listening..." to not confuse the user.
     */
    data object WaitingForResult : SttState
}
