package com.craigeley.chat.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.craigeley.chat.ChatViewModel
import com.craigeley.chat.api.Store
import com.craigeley.chat.ui.theme.ChatColors
import com.craigeley.chat.ui.theme.ChatDimens
import com.craigeley.chat.ui.theme.ChatType

@Composable
fun SettingsScreen(viewModel: ChatViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    val currentUrl = Store.baseUrl(context).orEmpty()
    var editing by remember { mutableStateOf(false) }
    var draftUrl by remember { mutableStateOf(currentUrl) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(ChatDimens.screenPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ScreenHeader(title = "Settings", onBack = onBack)

        Spacer(modifier = Modifier.weight(1f))

        Text(text = "Server", style = ChatType.hint, color = ChatColors.onSurfaceDisabled)
        Spacer(modifier = Modifier.height(16.dp))
        if (editing) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                BasicTextField(
                    value = draftUrl,
                    onValueChange = { draftUrl = it },
                    singleLine = true,
                    textStyle = ChatType.body.copy(color = ChatColors.onSurface, textAlign = TextAlign.Center),
                    cursorBrush = SolidColor(ChatColors.onSurface),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = {
                        if (draftUrl.isNotBlank()) viewModel.updateServerUrl(draftUrl)
                        editing = false
                    }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(thickness = 1.dp, color = ChatColors.onSurfaceDisabled)
            }
        } else {
            HapticText(
                text = currentUrl.removePrefix("https://").removePrefix("http://").ifEmpty { "Tap to set" },
                style = ChatType.body,
                color = ChatColors.onSurface,
                textAlign = TextAlign.Center,
                onClick = {
                    draftUrl = currentUrl
                    editing = true
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = if (state.connected) "Connected" else "Offline — reconnecting…",
            style = ChatType.hint,
            color = ChatColors.onSurfaceDisabled,
        )

        Spacer(modifier = Modifier.height(24.dp))

        HapticText(
            text = "Refresh conversations",
            style = ChatType.body,
            color = ChatColors.onSurfaceDim,
            onClick = {
                viewModel.refresh()
                onBack()
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.weight(1f))

        HapticText(
            text = "Sign out",
            style = ChatType.body,
            color = ChatColors.onSurfaceDim,
            textAlign = TextAlign.Center,
            onClick = { viewModel.signOut() },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(16.dp))
    }
}
