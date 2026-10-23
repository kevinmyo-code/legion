package com.kevin.legion.ui.dumb

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kevin.legion.dumb.AndroidKeptAppResolver
import com.kevin.legion.dumb.DumbModeViewModel
import com.kevin.legion.dumb.DumbUiState
import com.kevin.legion.dumb.KeptAppLauncher
import com.kevin.legion.dumb.KeptEntry
import com.kevin.legion.service.AriaForegroundService
import com.kevin.legion.ui.assistant.AssistantChatViewModel
import com.kevin.legion.ui.assistant.ChatPanelSheet
import com.kevin.legion.ui.assistant.ChatPanelState
import com.kevin.legion.ui.assistant.TypedChatUi
import com.kevin.legion.ui.home.BooksCallbacks
import com.kevin.legion.ui.home.BooksHome
import com.kevin.legion.ui.home.BooksSheet
import com.kevin.legion.ui.home.BooksViewModel
import com.kevin.legion.ui.home.rememberBooksFolderPicker
import com.kevin.legion.ui.theme.soft.SoftTheme

/**
 * HOME while dumb mode is on: the Leave flow when a leave is pending, Home A otherwise. Owns the
 * [DumbModeViewModel], the Books sheet (Books has no route of its own) and the typed assistant panel;
 * Maps, To-dos and Calendar are LEGION's own routes and arrive as callbacks.
 */
@Composable
@Suppress("LongMethod") // one screen wrapper wiring three sub-surfaces, as HomeScreen does
fun DumbHome(
    onOpenMaps: () -> Unit,
    onOpenTodos: () -> Unit,
    onOpenCalendar: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val viewModel: DumbModeViewModel = viewModel(factory = DumbModeViewModel.Factory(context))
    val ui by viewModel.state.collectAsStateWithLifecycle()
    val launcher = remember(context) { KeptAppLauncher(context, AndroidKeptAppResolver(context)) }
    var message by remember { mutableStateOf<String?>(null) }

    val booksViewModel: BooksViewModel = viewModel(factory = BooksViewModel.Factory(context))
    val booksState by booksViewModel.state.collectAsStateWithLifecycle()
    val openFolderPicker = rememberBooksFolderPicker(booksViewModel)
    val graph = remember(context) { (context.applicationContext as com.kevin.legion.MidnightApplication).dumb }
    // The folder picker is the system's window, not a kept app: tell the bounce LEGION opened it.
    val pickBooksFolder: () -> Unit = {
        graph.bounce.allowance.armGrace()
        openFolderPicker()
    }
    var booksOpen by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { booksViewModel.refresh() }
    // A book that opened closes the sheet, as on the normal HOME: the reader is what is left to see.
    val booksOpened = booksState.notice?.takeIf { !it.failed }
    LaunchedEffect(booksOpened) { if (booksOpened != null) booksOpen = false }

    // The assistant: the same typed chat panel the bottom strip opens, with the same talk path.
    val chatViewModel: AssistantChatViewModel = viewModel()
    val chat by chatViewModel.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { chatViewModel.refreshCompanionName() }
    val panel = remember { ChatPanelState() }
    LaunchedEffect(chat.entries) { panel.observe(chat.entries) }
    val typedChat = TypedChatUi(
        companionName = chat.companionName,
        entries = chat.entries,
        pendingReply = chat.pendingReply,
        onSend = chatViewModel::send,
        onNewConversation = chatViewModel::newConversation,
        unreadReply = panel.unread,
        onOpenChat = panel::show,
    )
    val talk: () -> Unit = {
        val micGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (micGranted) {
            context.startService(
                Intent(context, AriaForegroundService::class.java).setAction(AriaForegroundService.ACTION_TALK),
            )
        } else {
            message = "The microphone is not allowed for LEGION, so Talk did nothing. Type instead."
        }
    }

    val leave = ui.leave
    if (leave != null) {
        DumbLeaveScreen(
            ui = ui,
            leave = leave,
            onStay = viewModel::stay,
            onLeave = viewModel::leave,
        )
        return
    }

    DumbHomeContent(
        ui = ui,
        message = message,
        onEntry = { entry ->
            message = null
            when (entry) {
                KeptEntry.MAPS -> onOpenMaps()
                KeptEntry.TODOS -> onOpenTodos()
                KeptEntry.CALENDAR -> onOpenCalendar()
                KeptEntry.BOOKS -> booksOpen = true
                KeptEntry.ASSISTANT -> panel.show()
                else -> message = launcher.launch(entry)
            }
        },
        onLeave = viewModel::requestLeave,
        onOpenSettings = onOpenSettings,
    )
    SoftTheme {
        if (booksOpen) {
            BooksSheet(
                BooksHome(
                    state = booksState,
                    callbacks = BooksCallbacks(
                        onRead = booksViewModel::read,
                        onListen = booksViewModel::listen,
                        onContinue = booksViewModel::continueReading,
                        onPickFolder = pickBooksFolder,
                        onDismissNotice = booksViewModel::dismissNotice,
                    ),
                    cover = booksViewModel::cover,
                ),
                onDismiss = { booksOpen = false },
            )
        }
        if (panel.open) ChatPanelSheet(typedChat, onTalk = talk, onClose = panel::hide)
    }
}

/** Home A, stateless (prototype `Main.dc.html`): clock, date, running time, the kept list, the footer. */
@Composable
fun DumbHomeContent(
    ui: DumbUiState,
    message: String?,
    onEntry: (KeptEntry) -> Unit,
    onLeave: () -> Unit,
    onOpenSettings: () -> Unit = {},
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(DumbColors.ground)
            .padding(start = 28.dp, end = 28.dp, top = 40.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                ui.clock,
                color = DumbColors.text,
                fontFamily = DumbMono,
                fontSize = 64.sp,
                letterSpacing = (-2).sp,
                lineHeight = 64.sp,
            )
            Text(ui.date, color = DumbColors.dim, fontSize = 15.sp)
            Text(
                ui.statusLine,
                color = DumbColors.dim,
                fontFamily = DumbMono,
                fontSize = 13.sp,
                modifier = Modifier.clickable(onClickLabel = "Open dumb mode settings", onClick = onOpenSettings),
            )
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            ui.entries.forEach { entry ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 44.dp)
                        .clickable(role = Role.Button) { onEntry(entry) }
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(entry.label, color = DumbColors.text, fontSize = 30.sp, fontWeight = FontWeight.Medium)
                }
            }
            message?.let {
                Text(it, color = DumbColors.body, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                ui.peopleLine,
                color = DumbColors.dim,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f).padding(end = 12.dp),
            )
            PillButton("Leave", onLeave, primary = false)
        }
    }
}

/** The shared pill button: filled when [primary], outlined otherwise. 44dp minimum height. */
@Composable
internal fun PillButton(label: String, onClick: () -> Unit, primary: Boolean, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(percent = 50)
    Box(
        modifier
            .heightIn(min = 44.dp)
            .clip(shape)
            .then(
                if (primary) Modifier.background(DumbColors.text) else Modifier.border(1.dp, DumbColors.outline, shape),
            )
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (primary) DumbColors.ground else DumbColors.text,
            fontSize = 15.sp,
            fontWeight = if (primary) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
