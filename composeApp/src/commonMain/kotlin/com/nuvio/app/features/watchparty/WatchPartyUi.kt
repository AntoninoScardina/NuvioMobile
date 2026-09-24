package com.nuvio.app.features.watchparty

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nuvio.app.features.player.PlayerLaunch
import com.nuvio.app.features.player.PlayerLaunchStore
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.navigation.NuvioNavigator
import com.nuvio.app.navigation.PlayerRoute
import nuvio.composeapp.generated.resources.*
import org.jetbrains.compose.resources.stringResource

/** Montato una volta in MainAppContent: dialog "unisciti" e apertura del player per i guest. */
@Composable
internal fun WatchPartyHost(navController: NuvioNavigator) {
    WatchPartyPlatformEffect()
    val session = WatchPartyManager.session
    val request by session.mediaRequest.collectAsState()
    val joinVisible by WatchPartyManager.joinDialogVisible.collectAsState()

    LaunchedEffect(request) {
        val media = request ?: return@LaunchedEffect
        session.consumeMediaRequest()
        WatchPartyManager.closeJoinDialog()
        val launch = media.toPlayerLaunch(ProfileRepository.activeProfileId)
        val launchId = PlayerLaunchStore.put(launch)
        val playerOpen = navController.currentRoute is PlayerRoute
        navController.navigate(PlayerRoute(launchId = launchId, title = launch.title)) {
            if (playerOpen) popUpTo<PlayerRoute> { inclusive = true }
        }
    }

    if (joinVisible) {
        WatchPartyJoinDialog(onDismiss = WatchPartyManager::closeJoinDialog)
    }
}

@Composable
internal expect fun WatchPartyPlatformEffect()

private fun WatchPartyMedia.toPlayerLaunch(profileId: Int): PlayerLaunch {
    val metaId = contentId ?: videoId ?: url
    val metaType = contentType ?: if (season != null) "series" else "movie"
    return PlayerLaunch(
        profileId = profileId,
        title = title ?: "Watch Party",
        sourceUrl = url,
        sourceHeaders = headers,
        poster = poster,
        background = backdrop,
        logo = logo,
        seasonNumber = season,
        episodeNumber = episode,
        episodeTitle = subtitle,
        streamTitle = streamName ?: title ?: "Watch Party",
        providerName = "Watch Party",
        contentType = metaType,
        videoId = videoId,
        parentMetaId = metaId,
        parentMetaType = metaType,
    )
}

@Composable
private fun WatchPartyJoinDialog(onDismiss: () -> Unit) {
    val session = WatchPartyManager.session
    val state by session.state.collectAsState()
    var code by remember { mutableStateOf("") }
    var invalid by remember { mutableStateOf(false) }
    val inRoom = state.role == WatchPartyRole.GUEST &&
        (state.isActive || state.status == WatchPartyStatus.ERROR)

    fun join() {
        invalid = !session.joinRoom(code)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.watch_party_join_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (inRoom) {
                    WatchPartyCode(state.code.orEmpty())
                    Text(
                        text = when {
                            state.status == WatchPartyStatus.ERROR ->
                                stringResource(Res.string.watch_party_error, state.error.orEmpty())
                            state.status == WatchPartyStatus.CONNECTING -> stringResource(Res.string.watch_party_connecting)
                            state.participants.isEmpty() -> stringResource(Res.string.watch_party_waiting)
                            else -> stringResource(Res.string.watch_party_waiting_host)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Text(stringResource(Res.string.watch_party_join_description), style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = code,
                        onValueChange = {
                            code = it.uppercase().filter(Char::isLetterOrDigit).take(WatchPartyProtocol.CODE_LENGTH)
                            invalid = false
                        },
                        label = { Text(stringResource(Res.string.watch_party_join_code_label)) },
                        singleLine = true,
                        isError = invalid,
                        supportingText = if (invalid) {
                            { Text(stringResource(Res.string.watch_party_invalid_code)) }
                        } else {
                            null
                        },
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.Characters,
                            autoCorrectEnabled = false,
                            imeAction = ImeAction.Done,
                        ),
                        keyboardActions = KeyboardActions(onDone = { join() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            if (inRoom) {
                TextButton(onClick = { session.leaveRoom() }) {
                    Text(stringResource(Res.string.watch_party_leave))
                }
            } else {
                TextButton(onClick = ::join, enabled = code.length == WatchPartyProtocol.CODE_LENGTH) {
                    Text(stringResource(Res.string.watch_party_join))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.watch_party_close)) }
        },
    )
}

/** Pannello del player: crea la stanza (host) o mostra codice e partecipanti. */
@Composable
internal fun WatchPartyPlayerDialog(canShare: Boolean, onDismiss: () -> Unit) {
    val session = WatchPartyManager.session
    val state by session.state.collectAsState()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.watch_party_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when {
                    state.status == WatchPartyStatus.ERROR -> Text(
                        stringResource(Res.string.watch_party_error, state.error.orEmpty()),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    !state.isActive -> Text(
                        stringResource(
                            if (canShare) Res.string.watch_party_create_description else Res.string.watch_party_not_shareable,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    else -> {
                        Text(
                            stringResource(
                                if (state.role == WatchPartyRole.HOST) Res.string.watch_party_share_code
                                else Res.string.watch_party_joined_code,
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        WatchPartyCode(state.code.orEmpty())
                        Text(
                            text = when {
                                state.status == WatchPartyStatus.CONNECTING -> stringResource(Res.string.watch_party_connecting)
                                state.participants.isEmpty() -> stringResource(Res.string.watch_party_waiting)
                                else -> stringResource(Res.string.watch_party_participants, state.participants.joinToString(", "))
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        },
        confirmButton = {
            when {
                state.isActive || state.status == WatchPartyStatus.ERROR -> TextButton(onClick = { session.leaveRoom() }) {
                    Text(stringResource(Res.string.watch_party_leave))
                }
                else -> TextButton(onClick = { session.createRoom() }, enabled = canShare) {
                    Text(stringResource(Res.string.watch_party_create))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.watch_party_close)) }
        },
    )
}

@Composable
private fun WatchPartyCode(code: String) {
    Text(
        text = code.chunked(3).joinToString(" "),
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 36.sp,
        letterSpacing = 4.sp,
        color = MaterialTheme.colorScheme.primary,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}
