package dev.starling.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

// ---------------------------------------------------------------------------
// Sezioni: quelle originali di Starling + Mod & Upgrade di Doge Client.
// ---------------------------------------------------------------------------
enum class Screen(val label: String, val emoji: String) {
    Home("Home", "🏠"),
    Mods("Mod", "🧩"),
    Friends("Amici", "🐕"),
    Settings("Impostazioni", "⚙️"),
}

// ---------------------------------------------------------------------------
// MainScreen — shell del launcher (header, contenuto, NavigationBar M3)
// ---------------------------------------------------------------------------
@Composable
fun MainScreen(
    account: Account,
    onAccountChange: (Account) -> Unit,
    nativeReady: Boolean,
    nativeError: String? = null,
    install: InstallState,
    onPlay: () -> Unit,
    modsDir: File,
) {
    var selected by rememberSaveable { mutableStateOf(Screen.Home) }
    var showAccount by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = showAccount) { showAccount = false }
    BackHandler(enabled = !showAccount && selected != Screen.Home) { selected = Screen.Home }

    DogeScreenBackground {
        Column(Modifier.fillMaxSize()) {
            DogeTopBar(
                account = account,
                onAvatarClick = { showAccount = true },
            )

            Box(Modifier.weight(1f)) {
                when (selected) {
                    Screen.Home -> HomeTab(
                        account = account,
                        nativeReady = nativeReady,
                        nativeError = nativeError,
                        install = install,
                        modsDir = modsDir,
                        onPlay = onPlay,
                        onOpenAccount = { showAccount = true },
                    )
                    Screen.Mods -> ModsContent()
                    Screen.Friends -> FriendsContent()
                    Screen.Settings -> SettingsContent()
                }
            }

            DogeBottomNavigation(
                selectedScreen = selected,
                onItemClick = { selected = it },
            )
        }

        if (showAccount) {
            AccountScreen(
                account = account,
                onDismiss = { showAccount = false },
                onSave = {
                    onAccountChange(it)
                    showAccount = false
                },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Header originale: wordmark DOGE CLIENT + avatar cane (apre il profilo)
// ---------------------------------------------------------------------------
@Composable
private fun DogeTopBar(
    account: Account,
    onAvatarClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 20.dp, end = 16.dp, top = 12.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = "DOGE CLIENT",
                style = MaterialTheme.typography.headlineMedium,
                color = DogePrimary,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 1.sp,
            )
            Text(
                "LAUNCHER · MINECRAFT",
                style = MaterialTheme.typography.labelSmall,
                color = DogeTextSecondary,
                fontSize = 9.sp,
                letterSpacing = 1.6.sp,
            )
        }

        Box(
            Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(DogeSurfaceHigh)
                .border(2.dp, DogePrimary.copy(alpha = 0.6f), CircleShape)
                .clickable { onAvatarClick() },
            contentAlignment = Alignment.Center,
        ) {
            Text("🐶", fontSize = 24.sp)
        }
    }
}

// ---------------------------------------------------------------------------
// Home / Play
// ---------------------------------------------------------------------------
@Composable
private fun HomeTab(
    account: Account,
    nativeReady: Boolean,
    nativeError: String?,
    install: InstallState,
    modsDir: File,
    onPlay: () -> Unit,
    onOpenAccount: () -> Unit,
) {
    val ctx = LocalContext.current
    val modCount = remember(modsDir, install.ready) {
        modsDir.listFiles { file -> file.isFile && file.extension.equals("jar", true) }?.size ?: 0
    }
    val runtimePresent = remember(install.ready, install.error) {
        GameInstaller.effectiveRuntimeLib(ctx).exists()
    }

    var version by rememberSaveable { mutableStateOf(GameInstaller.selectedVersion(ctx)) }
    val versions = remember(install.ready) { GameInstaller.availableVersions(ctx) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(6.dp))

        // --- Selettore versione + profilo -------------------------------
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            versions.forEach { candidate ->
                DogePill(
                    text = candidate,
                    color = if (candidate == version) DogeAccent else DogeTextSecondary,
                    dot = candidate == version,
                    modifier = Modifier.clickable {
                        version = candidate
                        GameInstaller.setSelectedVersion(ctx, candidate)
                    },
                )
            }
            Spacer(Modifier.weight(1f))
            DogePill(
                text = account.nickname,
                color = DogePrimary,
                dot = false,
                modifier = Modifier.clickable { onOpenAccount() },
            )
        }

        Spacer(Modifier.height(10.dp))

        // --- Pulsante Play originale (osso) ------------------------------
        PlayButton(install = install, onPlay = onPlay)

        Spacer(Modifier.height(4.dp))

        Text(
            text = when {
                install.error != null -> "Azione richiesta"
                install.running -> install.phase.ifBlank { "Preparazione…" }
                else -> "Pronto per giocare"
            },
            style = MaterialTheme.typography.headlineMedium,
            color = DogeTextPrimary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = install.detail.ifBlank { "Minecraft $version · Java 21" },
            style = MaterialTheme.typography.bodyMedium,
            color = DogeTextSecondary,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )

        Spacer(Modifier.height(18.dp))

        if (install.running) {
            DownloadCard(install)
            Spacer(Modifier.height(12.dp))
        }

        if (install.error != null) {
            ErrorCard(
                message = install.error,
                nativeReady = nativeReady,
                technicalDetail = nativeError,
                onRetry = onPlay,
            )
            Spacer(Modifier.height(12.dp))
        }

        // --- Stato motore nativo Rust ------------------------------------
        DogeCard {
            DogeSectionTitle("Motore nativo")
            Spacer(Modifier.height(10.dp))
            DogeInfoRow(
                "Bridge Rust (8 bit)",
                if (nativeReady) "caricato" else "non caricato",
                if (nativeReady) DogeSuccess else DogeDanger,
            )
            DogeInfoRow(
                "Host JVM",
                if (nativeReady) "pronto" else "non disponibile",
                if (nativeReady) DogeSuccess else DogeDanger,
            )
            DogeInfoRow(
                "Runtime Java",
                if (runtimePresent) "presente" else "mancante",
                if (runtimePresent) DogeSuccess else DogeDanger,
            )
            DogeInfoRow("Versione selezionata", version)
            if (!nativeReady) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Le librerie native non sono state caricate: il gioco non può " +
                        "partire finché non sono presenti nell'APK. La UI resta utilizzabile.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = DogeTextSecondary,
                    fontSize = 12.sp,
                )
                if (nativeError != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        nativeError,
                        style = MaterialTheme.typography.bodyMedium,
                        color = DogeDanger,
                        fontSize = 11.sp,
                    )
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // --- Riepilogo ----------------------------------------------------
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            StatCard("Versione", version, Modifier.weight(1f))
            StatCard("Mod", modCount.toString(), Modifier.weight(1f))
            StatCard("Java", "21", Modifier.weight(1f))
        }

        Spacer(Modifier.height(14.dp))

        // --- Profilo ------------------------------------------------------
        DogeCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                DogeAvatar(account.nickname, size = 48.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        account.nickname,
                        style = MaterialTheme.typography.titleMedium,
                        color = DogeTextPrimary,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        if (account.microsoft) "Account Microsoft" else "Account offline",
                        style = MaterialTheme.typography.bodyMedium,
                        color = DogeTextSecondary,
                    )
                }
                DogePill(
                    text = if (account.microsoft) "MS" else "OFFLINE",
                    color = if (account.microsoft) DogeSuccess else DogePrimary,
                    dot = false,
                )
            }
            Spacer(Modifier.height(12.dp))
            DogeGhostButton(
                text = "Gestisci account",
                modifier = Modifier.fillMaxWidth(),
                onClick = onOpenAccount,
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun PlayButton(install: InstallState, onPlay: () -> Unit) {
    val transition = rememberInfiniteTransition(label = "play-glow")
    val pulse by transition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "play-pulse",
    )

    Box(Modifier.size(190.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(188.dp * pulse)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(
                        listOf(DogeAccent.copy(alpha = 0.20f), Color.Transparent)
                    )
                )
        )
        Box(
            Modifier
                .size(152.dp)
                .clip(CircleShape)
                .background(DogePrimary.copy(alpha = 0.10f))
        )
        Box(
            Modifier
                .size(134.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(listOf(Color(0xFF2A2617), Color(0xFF17171A)))
                )
                .border(2.dp, DogePrimary, CircleShape)
        )
        Button(
            onClick = onPlay,
            enabled = !install.running,
            modifier = Modifier.size(108.dp),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = DogeSurface,
                contentColor = DogeAccent,
                disabledContainerColor = DogeSurface.copy(alpha = 0.6f),
                disabledContentColor = DogeAccent,
            ),
            elevation = ButtonDefaults.buttonElevation(
                defaultElevation = 8.dp,
                pressedElevation = 2.dp,
                disabledElevation = 0.dp,
            ),
            contentPadding = PaddingValues(0.dp),
        ) {
            if (install.running) {
                CircularProgressIndicator(
                    modifier = Modifier.size(38.dp),
                    color = DogeAccent,
                    strokeWidth = 3.dp,
                )
            } else {
                Text("🦴", fontSize = 40.sp)
            }
        }
    }
}

@Composable
private fun DownloadCard(install: InstallState) {
    val animated by animateFloatAsState(
        targetValue = (install.percent / 100f).coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 350),
        label = "download-progress",
    )

    DogeCard(accent = true) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    install.phase.ifBlank { "Preparazione…" },
                    style = MaterialTheme.typography.titleMedium,
                    color = DogeTextPrimary,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    install.detail,
                    style = MaterialTheme.typography.bodyMedium,
                    color = DogeTextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(
                "${install.percent}%",
                fontSize = 24.sp,
                fontWeight = FontWeight.ExtraBold,
                color = DogeAccent,
            )
        }

        Spacer(Modifier.height(14.dp))

        LinearProgressIndicator(
            progress = { animated },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(8.dp)),
            color = DogePrimary,
            trackColor = Color(0x1FFFFFFF),
        )

        Spacer(Modifier.height(10.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                if (install.total > 0) {
                    "${formatMb(install.downloaded)} / ${formatMb(install.total)}"
                } else {
                    "Calcolo dimensione…"
                },
                style = MaterialTheme.typography.labelSmall,
                color = DogeTextSecondary,
                fontSize = 11.sp,
            )
            Text(
                "Minecraft ${GameInstaller.MC_VERSION} · Java",
                style = MaterialTheme.typography.labelSmall,
                color = DogeTextSecondary,
                fontSize = 11.sp,
            )
        }
    }
}

@Composable
private fun ErrorCard(
    message: String,
    nativeReady: Boolean,
    technicalDetail: String?,
    onRetry: () -> Unit,
) {
    DogeCard(accent = true) {
        Text(
            "Avvio non riuscito",
            style = MaterialTheme.typography.titleMedium,
            color = DogeDanger,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = DogeTextSecondary,
        )
        if (technicalDetail != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                technicalDetail,
                style = MaterialTheme.typography.bodyMedium,
                color = DogeDanger,
                fontSize = 11.sp,
            )
        }
        if (!nativeReady) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Suggerimento: verifica che libjvm.so e le librerie native siano " +
                    "presenti in jniLibs/arm64-v8a e reinstalla l'APK.",
                style = MaterialTheme.typography.bodyMedium,
                color = DogeTextSecondary,
                fontSize = 12.sp,
            )
        }
        Spacer(Modifier.height(14.dp))
        DogeGhostButton(
            text = "Riprova",
            modifier = Modifier.fillMaxWidth(),
            onClick = onRetry,
        )
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    DogeCard(modifier = modifier, padding = 12.dp) {
        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                value,
                style = MaterialTheme.typography.titleLarge,
                color = DogePrimary,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = DogeTextSecondary,
                fontSize = 9.sp,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Bottom Navigation Material 3 (stile originale Starling, colori Doge)
// ---------------------------------------------------------------------------
@Composable
private fun DogeBottomNavigation(
    selectedScreen: Screen,
    onItemClick: (Screen) -> Unit,
) {
    val itemColors = NavigationBarItemDefaults.colors(
        selectedIconColor = DogeAccent,
        selectedTextColor = DogeAccent,
        indicatorColor = DogePrimary.copy(alpha = 0.22f),
        unselectedIconColor = DogeTextSecondary,
        unselectedTextColor = DogeTextSecondary,
    )

    NavigationBar(
        containerColor = DogeSurface,
        contentColor = DogeTextPrimary,
        tonalElevation = 0.dp,
    ) {
        Screen.values().forEach { screen ->
            NavigationBarItem(
                selected = selectedScreen == screen,
                onClick = { onItemClick(screen) },
                icon = { Text(screen.emoji, fontSize = 20.sp) },
                label = {
                    Text(
                        screen.label,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                colors = itemColors,
            )
        }
    }
}
