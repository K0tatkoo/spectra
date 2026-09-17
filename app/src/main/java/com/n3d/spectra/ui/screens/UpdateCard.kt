package com.n3d.spectra.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.background
import com.n3d.spectra.ui.neu.NeuButton
import com.n3d.spectra.ui.neu.NeuCard
import com.n3d.spectra.ui.neu.NeuSwitch
import com.n3d.spectra.ui.neu.SettingRow
import com.n3d.spectra.ui.neu.neuInset
import com.n3d.spectra.ui.theme.LocalPalette
import com.n3d.spectra.ui.theme.Neumorph
import com.n3d.spectra.ui.theme.toComposeColor
import com.n3d.spectra.update.UpdateError
import com.n3d.spectra.update.UpdateManager
import com.n3d.spectra.update.UpdateState

/**
 * Keeping the app current from inside the app.
 *
 * Spectra is not on Play, so nothing else will ever tell somebody a new version
 * exists — and this is the only thing in the whole app that touches the
 * network, which is why the switch to stop it doing so is on the same card.
 */
@Composable
fun UpdateCard(updates: UpdateManager) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    val state by updates.state.collectAsStateWithLifecycle()
    var auto by remember { mutableStateOf(updates.autoCheck) }

    // Nothing reports the "install unknown apps" switch being flipped, so an
    // already-downloaded update is picked back up whenever this comes forward.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { updates.resumeInstall() }

    NeuCard {
        Text(
            "Installed: Spectra ${updates.installedVersionName}",
            color = palette.textFaint.toComposeColor(),
            fontSize = 11.sp,
        )
        Spacer(Modifier.height(10.dp))

        if (!updates.canSelfUpdate) {
            Text(
                "This build cannot replace itself.",
                color = palette.textFaint.toComposeColor(),
                fontSize = 11.sp,
            )
            return@NeuCard
        }

        when (val current = state) {
            is UpdateState.Idle -> CheckButton(updates)

            is UpdateState.Checking -> Note("Checking n3d-store.com…")

            is UpdateState.UpToDate -> {
                Note("This is the newest version.")
                Spacer(Modifier.height(10.dp))
                CheckButton(updates)
            }

            is UpdateState.Available -> {
                Text(
                    "Version ${current.release.versionName} is out",
                    color = palette.accent.toComposeColor(),
                    fontSize = 14.sp,
                )
                current.release.notes?.let {
                    Spacer(Modifier.height(4.dp))
                    Note(it)
                }
                Spacer(Modifier.height(12.dp))
                NeuButton(
                    onClick = { updates.download(current.release) },
                    label = "Download and install" +
                        UpdateManager.formatSize(current.release.sizeBytes)
                            .let { if (it.isEmpty()) "" else " · $it" },
                    primary = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                NeuButton(
                    onClick = updates::dismiss,
                    label = "Not now",
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            is UpdateState.Downloading -> {
                Note("Downloading…")
                Spacer(Modifier.height(8.dp))
                ProgressTrack(current.bytes, current.total)
                Spacer(Modifier.height(10.dp))
                NeuButton(
                    onClick = updates::cancel,
                    label = "Cancel",
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            is UpdateState.Ready -> {
                Note(
                    "The update is downloaded and checked. Android will not let an app " +
                        "install anything until you switch Spectra on under “Install " +
                        "unknown apps” — one switch, on the screen this button opens.",
                )
                Spacer(Modifier.height(10.dp))
                NeuButton(
                    onClick = { context.startActivity(updates.unknownSourcesIntent()) },
                    label = "Open that setting",
                    primary = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            is UpdateState.Installing -> Note("Opening Android's installer…")

            is UpdateState.Failed -> {
                Text(
                    listOfNotNull(message(current.error), current.detail).joinToString(" "),
                    color = palette.bad.toComposeColor(),
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                )
                Spacer(Modifier.height(10.dp))
                CheckButton(updates)
            }
        }

        SettingRow(
            "Look for updates by itself",
            "Once a day, and only the version number is asked for. Switch it off and " +
                "nothing in this app ever opens a connection on its own.",
        ) {
            NeuSwitch(auto) {
                auto = it
                updates.autoCheck = it
            }
        }
    }
}

@Composable
private fun CheckButton(updates: UpdateManager) {
    NeuButton(
        onClick = { updates.check() },
        label = "Check for updates",
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        color = LocalPalette.current.textFaint.toComposeColor(),
        fontSize = 11.sp,
        lineHeight = 15.sp,
    )
}

/** The slider's own track, without the thumb: an inset pill with the done part
    painted in, clipped so the accent never spills past the rounded end. */
@Composable
private fun ProgressTrack(done: Long, total: Long) {
    val palette = LocalPalette.current
    val fraction = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
    Box(Modifier.fillMaxWidth().height(8.dp)) {
        Box(Modifier.fillMaxWidth().height(8.dp).neuInset(Neumorph.RadiusPill, 3.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(percent = 50)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(8.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(palette.gradA.toComposeColor(), palette.accent.toComposeColor()),
                        ),
                    ),
            )
        }
    }
    Spacer(Modifier.height(4.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(
            UpdateManager.formatSize(done),
            color = palette.textFaint.toComposeColor(),
            fontSize = 10.sp,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        Text(
            UpdateManager.formatSize(total),
            color = palette.textFaint.toComposeColor(),
            fontSize = 10.sp,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}

private fun message(error: UpdateError): String = when (error) {
    UpdateError.Network -> "Could not reach n3d-store.com. Check your connection and try again."
    UpdateError.Server -> "The store answered with something unusable. Try again later."
    UpdateError.Checksum ->
        "The download did not match what the store published, so it was thrown away. " +
            "Nothing was installed."
    UpdateError.Signature ->
        "That file is signed by a different key and can never replace this app. " +
            "Nothing was installed."
    UpdateError.Package -> "That file is a different app. Nothing was installed."
    UpdateError.TooOld -> "The new version needs a newer Android than this phone runs."
    UpdateError.Storage -> "Not enough room to download the update."
    UpdateError.Install -> "Android refused the install."
}
