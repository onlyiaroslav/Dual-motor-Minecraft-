package dev.starling.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.starling.social.SocialBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections
import java.util.Locale

/**
 * Schermata "Amici" (Fase 6) in Compose.
 *
 * La logica nativa è invariata: [SocialBridge] gestisce lista amici persistente,
 * codici invito monouso (TTL 5 minuti) e mini-server auth LAN. Qui cambia solo
 * la UI, allineata al tema scuro/ambra del launcher.
 */
class FriendsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            DogeTheme {
                DogeScreenBackground {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .systemBarsPadding()
                    ) {
                        FriendsContent()
                    }
                }
            }
        }
    }
}

private const val WORLD_PORT = 25565
private const val AUTH_PORT = 25566
private const val FRIENDS_FILE = "social/friends.txt"
private val NICKNAME_REGEX = Regex("^[A-Za-z0-9_]{3,16}$")

private data class FriendEntry(val name: String, val online: Boolean)
private data class InviteInfo(
    val code: String,
    val host: String,
    val authPort: Int,
    val expiresAt: Long,
)

@Composable
fun FriendsContent(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var social by remember { mutableStateOf<SocialBridge?>(null) }
    var initError by remember { mutableStateOf<String?>(null) }
    var friends by remember { mutableStateOf<List<FriendEntry>>(emptyList()) }
    var authPort by remember { mutableIntStateOf(0) }
    var nickname by remember { mutableStateOf("") }
    var nicknameError by remember { mutableStateOf<String?>(null) }
    var invite by remember { mutableStateOf<InviteInfo?>(null) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var removeTarget by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    // --- Inizializzazione del layer nativo ------------------------------
    LaunchedEffect(Unit) {
        val result = withContext(Dispatchers.IO) {
            runCatching {
                val file = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, FRIENDS_FILE)
                file.parentFile?.mkdirs()
                val bridge = SocialBridge(file.absolutePath)
                val port = try {
                    bridge.startAuthServer(AUTH_PORT)
                } catch (_: IllegalStateException) {
                    try {
                        bridge.startAuthServer(0)
                    } catch (_: IllegalStateException) {
                        0
                    }
                }
                bridge to port
            }
        }
        result.onSuccess { (bridge, port) ->
            social = bridge
            authPort = port
        }
        result.onFailure {
            initError = it.message ?: it.javaClass.simpleName
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            social?.let { bridge ->
                runCatching { bridge.stopAuthServer() }
                runCatching { bridge.close() }
            }
        }
    }

    // --- Refresh periodico dello stato online ---------------------------
    LaunchedEffect(social) {
        val bridge = social ?: return@LaunchedEffect
        while (true) {
            val list = withContext(Dispatchers.IO) {
                runCatching {
                    bridge.listFriends().mapNotNull { entry ->
                        val parts = entry.split('|', limit = 3)
                        if (parts.size == 3) FriendEntry(parts[0], parts[2] == "1") else null
                    }
                }.getOrDefault(emptyList())
            }
            friends = list
            delay(1000)
        }
    }

    // --- Countdown del codice invito ------------------------------------
    LaunchedEffect(invite) {
        if (invite == null) return@LaunchedEffect
        while (true) {
            now = System.currentTimeMillis()
            val current = invite ?: break
            if (now > current.expiresAt) break
            delay(500)
        }
    }

    fun addFriend() {
        val name = nickname.trim()
        if (!NICKNAME_REGEX.matches(name)) {
            nicknameError = "Nickname non valido: 3-16 caratteri tra A-Z, 0-9 e _."
            return
        }
        val bridge = social ?: return
        scope.launch {
            busy = true
            val result = withContext(Dispatchers.IO) { runCatching { bridge.addFriend(name) } }
            busy = false
            result
                .onSuccess {
                    nickname = ""
                    nicknameError = null
                    toast(ctx, "Amico aggiunto: $name")
                }
                .onFailure { nicknameError = it.message ?: "Impossibile aggiungere l'amico" }
        }
    }

    fun createInvite() {
        val bridge = social ?: return
        scope.launch {
            busy = true
            val host = withContext(Dispatchers.IO) { localIpv4() }
            if (host == null) {
                busy = false
                toast(ctx, "Wi-Fi non connesso: impossibile generare l'invito")
                return@launch
            }
            val result = withContext(Dispatchers.IO) {
                runCatching { bridge.generateInvite(host, WORLD_PORT) }
            }
            busy = false
            result
                .onSuccess { code ->
                    invite = InviteInfo(
                        code = code,
                        host = host,
                        authPort = authPort,
                        expiresAt = System.currentTimeMillis() +
                            SocialBridge.tokenTtlSeconds() * 1000L,
                    )
                }
                .onFailure {
                    toast(ctx, "Impossibile generare il codice: ${it.message}")
                }
        }
    }

    val hostIp = remember(authPort) { localIpv4() }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp)
    ) {
        // --- Header ------------------------------------------------------
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "Amici",
                    style = MaterialTheme.typography.headlineLarge,
                    color = DogeTextPrimary,
                )
                Text(
                    "Invita gli amici nel tuo mondo LAN",
                    style = MaterialTheme.typography.bodyMedium,
                    color = DogeTextSecondary,
                )
            }
            DogePill(
                text = if (authPort > 0) "SERVER ATTIVO" else "SERVER OFFLINE",
                color = if (authPort > 0) DogeSuccess else DogeTextSecondary,
            )
        }

        if (initError != null) {
            DogeCard(accent = true) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Warning,
                        contentDescription = null,
                        tint = DogeDanger,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Layer social non disponibile",
                        style = MaterialTheme.typography.titleMedium,
                        color = DogeDanger,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    initError ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = DogeTextSecondary,
                )
            }
            Spacer(Modifier.height(12.dp))
        }

        // --- Stato server ------------------------------------------------
        DogeCard {
            DogeSectionTitle("Server inviti")
            Spacer(Modifier.height(10.dp))
            DogeInfoRow("Indirizzo", if (authPort > 0) "${hostIp ?: "127.0.0.1"}:$authPort" else "non attivo")
            DogeInfoRow("Mondo", "${hostIp ?: "127.0.0.1"}:$WORLD_PORT")
            DogeInfoRow("Validità codice", "5 minuti")
        }

        Spacer(Modifier.height(12.dp))

        // --- Aggiungi amico ----------------------------------------------
        DogeCard {
            DogeSectionTitle("Aggiungi amico")
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                DogeTextField(
                    value = nickname,
                    onValueChange = {
                        nickname = it
                        nicknameError = null
                    },
                    label = "Nickname",
                    placeholder = "Steve_01",
                    modifier = Modifier.weight(1f),
                    isError = nicknameError != null,
                    keyboardType = KeyboardType.Ascii,
                )
                Spacer(Modifier.width(10.dp))
                DogePrimaryButton(
                    text = "Aggiungi",
                    enabled = !busy && social != null,
                    onClick = { addFriend() },
                )
            }
            if (nicknameError != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    nicknameError ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = DogeDanger,
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        DogePrimaryButton(
            text = "Invita nel mondo",
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy && social != null,
            onClick = { createInvite() },
        )

        Spacer(Modifier.height(18.dp))

        // --- Lista amici --------------------------------------------------
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "I tuoi amici",
                style = MaterialTheme.typography.titleLarge,
                color = DogeTextPrimary,
                modifier = Modifier.weight(1f),
            )
            DogePill(
                text = "${friends.count { it.online }}/${friends.size} ONLINE",
                color = DogePrimary,
                dot = false,
            )
        }

        Spacer(Modifier.height(10.dp))

        if (friends.isEmpty()) {
            DogeCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(DogePrimary.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Person,
                            contentDescription = null,
                            tint = DogePrimary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            "Nessun amico salvato",
                            style = MaterialTheme.typography.titleMedium,
                            color = DogeTextPrimary,
                        )
                        Text(
                            "Aggiungi un nickname qui sopra per iniziare.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = DogeTextSecondary,
                        )
                    }
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                friends.forEach { friend ->
                    FriendRow(
                        friend = friend,
                        onLongPress = { removeTarget = friend.name },
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Text(
            "Tieni premuto un amico per rimuoverlo.",
            style = MaterialTheme.typography.bodyMedium,
            color = DogeTextSecondary,
            fontSize = 12.sp,
        )

        Spacer(Modifier.height(24.dp))
    }

    // --- Dialoghi dark ----------------------------------------------------
    val currentInvite = invite
    if (currentInvite != null) {
        val left = (currentInvite.expiresAt - now).coerceAtLeast(0L)
        val minutes = left / 60_000
        val seconds = (left % 60_000) / 1000
        val expired = left <= 0
        val copyAction: (() -> Unit)? = if (expired) {
            null
        } else {
            {
                copyToClipboard(ctx, currentInvite.code)
                toast(ctx, "Codice copiato")
            }
        }

        DogeDialog(
            title = "Invita nel mondo",
            onDismiss = { invite = null },
            confirmText = if (expired) null else "Copia codice",
            onConfirm = copyAction,
            dismissText = "Chiudi",
        ) {
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    currentInvite.code,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 7.sp,
                    color = if (expired) DogeTextSecondary else DogePrimary,
                )
                Spacer(Modifier.height(14.dp))
                DogeInfoRow("Mondo", "${currentInvite.host}:$WORLD_PORT")
                DogeInfoRow("Auth", "${currentInvite.host}:${currentInvite.authPort}")
                DogeInfoRow(
                    "Scade tra",
                    if (expired) "scaduto" else String.format(Locale.US, "%02d:%02d", minutes, seconds),
                    if (expired) DogeDanger else DogePrimary,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    if (expired) {
                        "Codice scaduto: generane un altro."
                    } else {
                        "Condividi il codice: vale per un solo ingresso."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = DogeTextSecondary,
                    fontSize = 12.sp,
                )
            }
        }
    }

    val target = removeTarget
    if (target != null) {
        DogeDialog(
            title = "Rimuovere $target?",
            onDismiss = { removeTarget = null },
            confirmText = "Rimuovi",
            onConfirm = {
                removeTarget = null
                val bridge = social
                if (bridge != null) {
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching { bridge.removeFriend(target) }
                        }
                        result.onFailure {
                            toast(ctx, "Rimozione fallita: ${it.message}")
                        }
                    }
                }
            },
            dismissText = "Annulla",
        ) {
            Text(
                "L'amico verrà rimosso dalla lista locale. Potrai aggiungerlo di nuovo in seguito.",
                style = MaterialTheme.typography.bodyMedium,
                color = DogeTextSecondary,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FriendRow(friend: FriendEntry, onLongPress: () -> Unit) {
    DogeCard(
        padding = 12.dp,
        modifier = Modifier.combinedClickable(
            onClick = {},
            onLongClick = onLongPress,
        ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(DogePrimary.copy(alpha = 0.10f))
                    .border(1.dp, DogePrimary.copy(alpha = 0.25f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) {
                DogeAvatar(friend.name, size = 32.dp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    friend.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = DogeTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (friend.online) "Nel mondo" else "Offline",
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (friend.online) DogeSuccess else DogeTextSecondary,
                )
            }
            DogePill(
                text = if (friend.online) "ONLINE" else "OFFLINE",
                color = if (friend.online) DogeSuccess else DogeTextSecondary,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Utility
// ---------------------------------------------------------------------------
private fun toast(ctx: Context, message: String) {
    Toast.makeText(ctx, message, Toast.LENGTH_LONG).show()
}

private fun copyToClipboard(ctx: Context, code: String) {
    val clipboard = ctx.getSystemService(ClipboardManager::class.java)
    clipboard?.setPrimaryClip(ClipData.newPlainText("Codice invito Doge Client", code))
}

/** IP IPv4 della rete Wi-Fi corrente (null se offline). */
private fun localIpv4(): String? {
    return try {
        Collections.list(NetworkInterface.getNetworkInterfaces())
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { Collections.list(it.inetAddresses).asSequence() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    } catch (_: Exception) {
        null
    }
}
