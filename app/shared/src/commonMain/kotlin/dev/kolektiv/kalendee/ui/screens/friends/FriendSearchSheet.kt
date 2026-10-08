package dev.kolektiv.kalendee.ui.screens.friends

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.kolektiv.kalendee.client.UserSearchResultOut
import dev.kolektiv.kalendee.ui.AppState
import dev.kolektiv.kalendee.ui.FriendRequestAcceptedStatus
import dev.kolektiv.kalendee.ui.ServerUi
import dev.kolektiv.kalendee.ui.components.messageOf
import dev.kolektiv.kalendee.ui.design.DText
import dev.kolektiv.kalendee.ui.design.DType
import dev.kolektiv.kalendee.ui.design.LocalKalendeeColors
import dev.kolektiv.kalendee.ui.design.LocalKalendeeDimens
import dev.kolektiv.kalendee.ui.design.components.DAvatar
import dev.kolektiv.kalendee.ui.design.components.DBadge
import dev.kolektiv.kalendee.ui.design.components.DBadgeColor
import dev.kolektiv.kalendee.ui.design.components.DBadgeSize
import dev.kolektiv.kalendee.ui.design.components.DButton
import dev.kolektiv.kalendee.ui.design.components.DButtonSize
import dev.kolektiv.kalendee.ui.design.components.DIcon
import dev.kolektiv.kalendee.ui.design.components.DListItem
import dev.kolektiv.kalendee.ui.design.components.DSelect
import dev.kolektiv.kalendee.ui.design.components.DSelectOption
import dev.kolektiv.kalendee.ui.design.components.DSheet
import dev.kolektiv.kalendee.ui.design.components.DSpinner
import dev.kolektiv.kalendee.ui.design.components.DTextField
import dev.kolektiv.kalendee.ui.design.semibold
import dev.kolektiv.kalendee.ui.icons.Lucide
import kotlinx.coroutines.launch

internal const val FriendRelationshipNone = "none"
internal const val FriendRelationshipPendingOut = "pending_out"
internal const val FriendRelationshipPendingIn = "pending_in"
internal const val FriendRelationshipFriends = "friends"

/** Badge label for a search-result relationship, or null when there is no badge. */
internal fun friendRelationshipLabel(relationship: String): String? = when (relationship) {
    FriendRelationshipPendingOut -> "Requested"
    FriendRelationshipPendingIn -> "Respond in requests"
    FriendRelationshipFriends -> "Friends"
    else -> null
}

/**
 * Bottom sheet that searches users on one of the signed-in [servers] and sends
 * friend requests. With several servers a selector chooses the target; with one
 * the search runs against it directly.
 */
@Composable
fun FriendSearchSheet(
    state: AppState,
    servers: List<ServerUi>,
    onDismiss: () -> Unit,
) {
    val colors = LocalKalendeeColors.current
    val dimens = LocalKalendeeDimens.current
    val scope = rememberCoroutineScope()
    var selectedServerId by remember { mutableStateOf(servers.firstOrNull()?.account?.profile?.id) }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<UserSearchResultOut>?>(null) }
    var searchedQuery by remember { mutableStateOf("") }

    val serverIds = servers.map { it.account.profile.id }
    LaunchedEffect(serverIds) {
        if (selectedServerId !in serverIds) selectedServerId = serverIds.firstOrNull()
        results = null
        error = null
    }

    fun search() {
        val serverId = selectedServerId ?: return
        val trimmed = query.trim()
        if (trimmed.isEmpty() || searching) return
        scope.launch {
            searching = true
            error = null
            try {
                results = state.searchUsers(serverId, trimmed)
                searchedQuery = trimmed
            } catch (e: Exception) {
                error = messageOf(e)
                results = null
            } finally {
                searching = false
            }
        }
    }

    DSheet(onDismissRequest = onDismiss) {
        DText(text = "Add friend", style = DType.lg.semibold())
        Spacer(Modifier.height(dimens.space3))
        if (servers.isEmpty()) {
            DText(
                text = "Sign in to a server to add friends.",
                style = DType.sm,
                color = colors.mutedContent,
            )
            Spacer(Modifier.height(dimens.space2))
            return@DSheet
        }
        if (servers.size > 1) {
            DSelect(
                selected = selectedServerId,
                options = servers.map {
                    DSelectOption(value = it.account.profile.id, label = it.account.profile.name)
                },
                onSelect = { option ->
                    selectedServerId = option.value
                    results = null
                    error = null
                },
                label = "Server",
            )
            Spacer(Modifier.height(dimens.space3))
        }
        Row(
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(dimens.space2),
        ) {
            DTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.weight(1f),
                label = "Username or email",
                placeholder = "jane or jane@example.com",
                enabled = !searching,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search() }),
            )
            DButton(
                onClick = { search() },
                enabled = query.isNotBlank() && !searching,
                loading = searching,
            ) {
                DIcon(icon = Lucide.Search, size = 16.dp)
                DText(text = "Search", modifier = Modifier.padding(start = 6.dp))
            }
        }
        Spacer(Modifier.height(dimens.space3))
        when {
            searching -> SearchStatusRow(label = "Searching…")

            error != null -> DText(
                text = error.orEmpty(),
                style = DType.sm,
                color = colors.error,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )

            results == null -> DText(
                text = "Type a username or email to find people.",
                style = DType.sm,
                color = colors.mutedContent,
            )

            results.orEmpty().isEmpty() -> DText(
                text = "No users found for \"$searchedQuery\".",
                style = DType.sm,
                color = colors.mutedContent,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            else -> Column(
                modifier = Modifier
                    .heightIn(max = 320.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                results.orEmpty().forEach { result ->
                    selectedServerId?.let { serverId ->
                        FriendSearchResultRow(state = state, serverId = serverId, result = result)
                    }
                }
            }
        }
        Spacer(Modifier.height(dimens.space2))
    }
}

@Composable
private fun FriendSearchResultRow(
    state: AppState,
    serverId: String,
    result: UserSearchResultOut,
) {
    val colors = LocalKalendeeColors.current
    val dimens = LocalKalendeeDimens.current
    val scope = rememberCoroutineScope()
    var relationship by remember(result.userId) { mutableStateOf(result.relationship) }
    var busy by remember(result.userId) { mutableStateOf(false) }
    var error by remember(result.userId) { mutableStateOf<String?>(null) }

    Column {
        DListItem(
            title = result.displayName,
            subtitle = "@${result.username}",
            leading = { DAvatar(initials = result.displayName, size = 32.dp) },
            trailing = {
                val label = friendRelationshipLabel(relationship)
                when {
                    relationship == FriendRelationshipNone -> DButton(
                        onClick = {
                            scope.launch {
                                busy = true
                                error = null
                                try {
                                    val out = state.sendFriendRequest(serverId, result.username)
                                    relationship = if (out.status == FriendRequestAcceptedStatus) {
                                        FriendRelationshipFriends
                                    } else {
                                        FriendRelationshipPendingOut
                                    }
                                } catch (e: Exception) {
                                    error = messageOf(e)
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        size = DButtonSize.Sm,
                        loading = busy,
                        enabled = !busy,
                        modifier = Modifier.heightIn(min = 40.dp),
                    ) {
                        DText("Add")
                    }

                    label != null -> DBadge(
                        text = label,
                        color = if (relationship == FriendRelationshipFriends) {
                            DBadgeColor.Success
                        } else {
                            DBadgeColor.Warning
                        },
                        size = DBadgeSize.Xs,
                    )

                    else -> DBadge(text = relationship, size = DBadgeSize.Xs)
                }
            },
        )
        if (error != null) {
            DText(
                text = error.orEmpty(),
                style = DType.xs,
                color = colors.error,
                modifier = Modifier.padding(
                    start = dimens.space3,
                    end = dimens.space3,
                    bottom = dimens.space1,
                ),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun SearchStatusRow(label: String) {
    val colors = LocalKalendeeColors.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        DSpinner(size = 14.dp, strokeWidth = 2.dp)
        DText(
            text = label,
            style = DType.sm,
            color = colors.mutedContent,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}
