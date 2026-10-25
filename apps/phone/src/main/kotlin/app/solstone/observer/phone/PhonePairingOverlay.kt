// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.solstone.observer.harness.PairLinkDispatchResult
import app.solstone.core.pl.PairLinkAcceptance
import app.solstone.core.pl.acceptPairLink
import app.solstone.observer.formfactor.phone.PairingMismatchResult
import app.solstone.observer.formfactor.phone.PairingSuccessMark
import app.solstone.observer.formfactor.phone.PhonePairingEvent
import app.solstone.observer.formfactor.phone.PhonePairingSession
import app.solstone.observer.formfactor.phone.PhonePairingState
import app.solstone.observer.formfactor.phone.SUPPORT_SITE_URL
import app.solstone.observer.formfactor.shared.CAMERA_OFF_FOR_SCAN
import app.solstone.observer.formfactor.shared.Camera2QrPreviewView
import app.solstone.observer.formfactor.shared.LegacyQrPreviewView
import app.solstone.observer.formfactor.shared.OPEN_ANDROID_SETTINGS
import app.solstone.observer.formfactor.shared.QrBackend
import app.solstone.observer.formfactor.shared.pairLinkDispatchText
import app.solstone.observer.harness.HarnessController
import app.solstone.observer.scaffold.CameraAsk
import app.solstone.observer.scaffold.ScanEntry
import app.solstone.observer.scaffold.scanEntry
import app.solstone.platform.work.SyncStores

@Composable
internal fun PhonePairingOverlay(
    state: PhonePairingState,
    cameraAsk: CameraAsk,
    hasCamera: Boolean,
    onAskForCamera: () -> Unit,
    onScannerNeedsCamera: () -> Unit,
    dispatch: (PhonePairingEvent) -> Unit,
    controller: HarnessController,
    stores: SyncStores,
    pairingViewModel: PhonePairingViewModel,
    onPairingCommitted: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val session = state.session
    if (session is PhonePairingSession.Closed) return

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        when (session) {
            is PhonePairingSession.Closed -> Unit
            is PhonePairingSession.Scanning -> {
                PhonePairingScanning(
                    cameraAsk = cameraAsk,
                    hasCamera = hasCamera,
                    onAskForCamera = onAskForCamera,
                    onScannerNeedsCamera = onScannerNeedsCamera,
                    dispatch = dispatch,
                    controller = controller,
                    onPairingCommitted = onPairingCommitted,
                )
            }
            is PhonePairingSession.Entry -> {
                PhonePairingEntry(
                    session = session,
                    dispatch = dispatch,
                )
            }
            is PhonePairingSession.Attempting -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                }
            }
            is PhonePairingSession.Held -> {
                val context = LocalContext.current
                val displayedPairing = currentPairing(stores)
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                        .verticalScroll(rememberScrollState())
                        .testTag("pairingHeld"),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    PairingSuccessMark(
                        coordinator = stores.journalIdentityCoordinator,
                        currentPairing = { currentPairing(stores) },
                        requestMark = { requestPairingMark(stores) },
                        onConfirmed = {},
                        onMismatch = {
                            val cleared = displayedPairing != null &&
                                pairingViewModel.mismatch(displayedPairing, controller, stores)
                            if (!cleared) {
                                PairingMismatchResult.LocalFailure
                            } else {
                                PairingMismatchResult.Disconnected
                            }
                        },
                        onMismatchCleared = {},
                        cleanupInProgress = pairingViewModel.mismatchInProgress,
                        onYes = { presented ->
                            pairingViewModel.confirm(context, presented, controller, stores)
                        },
                        onHelp = {
                            context.openUrl(SUPPORT_SITE_URL)
                        },
                        onScanAgain = { dispatch(PhonePairingEvent.OpenScan) },
                    )
                }
            }
        }
    }
}

@Composable
internal fun PhonePairingScanning(
    cameraAsk: CameraAsk,
    hasCamera: Boolean,
    onAskForCamera: () -> Unit,
    onScannerNeedsCamera: () -> Unit,
    dispatch: (PhonePairingEvent) -> Unit,
    controller: HarnessController,
    onPairingCommitted: () -> Unit = {},
) {
    val context = LocalContext.current
    val entry = scanEntry(hasCamera, cameraAsk)
    var scannerStatus by remember { mutableStateOf<String?>(null) }
    val onDecoded = { raw: String ->
        dispatch(PhonePairingEvent.Submit(acceptPairLink(raw)))
    }
    val onPasteLink = {
        dispatch(PhonePairingEvent.CloseScan)
    }

    when (entry) {
        ScanEntry.AskForCamera -> {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "on your journal's computer, open \"network\" and choose \"pair a device\". then scan the code it shows.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = onAskForCamera,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("pairingScanCode"),
                ) {
                    Text("scan a code")
                }
                Spacer(Modifier.height(16.dp))
                PasteLinkButton(
                    onClick = onPasteLink,
                )
            }
        }
        ScanEntry.AwaitAnswer -> {
            Box(Modifier.fillMaxSize()) {
                PasteLinkButton(
                    onClick = onPasteLink,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                )
            }
        }
        ScanEntry.CameraOff -> {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = CAMERA_OFF_FOR_SCAN,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = {
                        onScannerNeedsCamera()
                        val intent = Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null),
                        )
                        if (context !is Activity) {
                            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(OPEN_ANDROID_SETTINGS)
                }
                Spacer(Modifier.height(16.dp))
                PasteLinkButton(
                    onClick = onPasteLink,
                )
            }
        }
        ScanEntry.Scan -> {
            Box(Modifier.fillMaxSize()) {
                AndroidView(
                    factory = { ctx ->
                        when (phoneSpec.qrBackend) {
                            QrBackend.Camera2 -> Camera2QrPreviewView(
                                context = ctx,
                                controller = controller,
                                threadLabel = "phone",
                                onDecoded = onDecoded,
                                status = { scannerStatus = it },
                            )
                            QrBackend.Legacy -> LegacyQrPreviewView(
                                context = ctx,
                                controller = controller,
                                threadLabel = "phone",
                                onPairingCommitted = onPairingCommitted,
                                onDecoded = onDecoded,
                                status = { scannerStatus = it },
                            )
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
                scannerStatus?.let { message ->
                    Text(
                        message,
                        modifier = Modifier.align(Alignment.TopCenter).padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
                PasteLinkButton(
                    onClick = onPasteLink,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                )
            }
        }
    }
}

@Composable
private fun PasteLinkButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = 48.dp)
            .testTag("pairingPaste"),
    ) {
        Text("paste a link")
    }
}

@Composable
private fun PhonePairingEntry(
    session: PhonePairingSession.Entry,
    dispatch: (PhonePairingEvent) -> Unit,
) {
    var linkText by remember(session.stagedCanonical) {
        mutableStateOf(session.stagedCanonical.orEmpty())
    }
    val connect: () -> Unit = {
        val acceptance = acceptPairLink(linkText)
        dispatch(PhonePairingEvent.Submit(acceptance))
        if (acceptance is PairLinkAcceptance.Accepted) {
            dispatch(PhonePairingEvent.Connect)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        TextField(
            value = linkText,
            onValueChange = { linkText = it },
            label = { Text("pairing link") },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("pairingLinkField"),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = { connect() },
            ),
            singleLine = true,
        )
        if (session.invalidSubmit) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "enter a valid pairing link from your journal.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        val outcome = session.outcome
        if (outcome != null) {
            val failureText = pairLinkDispatchText(PairLinkDispatchResult.Attempted(outcome))
            if (failureText != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = failureText,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = connect,
            enabled = linkText.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("pairingConnect"),
        ) {
            Text("connect")
        }
        OutlinedButton(
            onClick = { dispatch(PhonePairingEvent.OpenScan) },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("pairingScan"),
        ) {
            Text("scan a code")
        }
    }
}
