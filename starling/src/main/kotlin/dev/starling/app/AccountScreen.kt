package dev.starling.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.net.URLEncoder

// ---------------------------------------------------------------------------
// Modello account persistito
// ---------------------------------------------------------------------------
data class Account(
    val nickname: String = "Giocatore",
    val microsoft: Boolean = false,
)

/**
 * Client ID pubblico usato dai launcher Minecraft per la pagina di login
 * Microsoft: l'utente non deve registrare nulla su Azure.
 */
const val MICROSOFT_CLIENT_ID = "00000000402b5328"

object AccountStore {
    private const val PREFS = "starling_account"
    private const val KEY_NICK = "nickname"
    private const val KEY_MS = "microsoft"
    private const val KEY_CLIENT_ID = "ms_client_id"

    private val NICKNAME_REGEX = Regex("^[A-Za-z0-9_]{3,16}$")

    fun isValidNickname(value: String): Boolean = NICKNAME_REGEX.matches(value)

    fun load(ctx: Context): Account {
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Account(
            nickname = prefs.getString(KEY_NICK, "Giocatore") ?: "Giocatore",
            microsoft = prefs.getBoolean(KEY_MS, false),
        )
    }

    fun save(ctx: Context, account: Account) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_NICK, account.nickname)
            .putBoolean(KEY_MS, account.microsoft)
            .apply()
    }

    fun clientId(ctx: Context): String =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CLIENT_ID, MICROSOFT_CLIENT_ID)
            ?.ifBlank { MICROSOFT_CLIENT_ID }
            ?: MICROSOFT_CLIENT_ID

    fun setClientId(ctx: Context, value: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_CLIENT_ID, value.trim()).apply()
    }
}

// ---------------------------------------------------------------------------
// Schermata Account (overlay full-screen)
// ---------------------------------------------------------------------------
@Composable
fun AccountScreen(
    account: Account,
    onDismiss: () -> Unit,
    onSave: (Account) -> Unit,
) {
    val ctx = LocalContext.current
    var draft by rememberSaveable { mutableStateOf(account.nickname) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var msDialog by rememberSaveable { mutableStateOf(false) }

    DogeScreenBackground {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Filled.ArrowBack,
                        contentDescription = "Indietro",
                        tint = DogeTextPrimary,
                    )
                }
                Text(
                    "Account",
                    style = MaterialTheme.typography.headlineLarge,
                    color = DogeTextPrimary,
                )
            }

            // --- Anteprima profilo -------------------------------------
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(contentAlignment = Alignment.BottomEnd) {
                    Box(
                        Modifier
                            .size(112.dp)
                            .clip(CircleShape)
                            .background(
                                Brush.linearGradient(
                                    listOf(DogePrimary.copy(alpha = 0.25f), Color.Transparent)
                                )
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        DogeAvatar(
                            nickname = draft.ifBlank { account.nickname },
                            size = 96.dp,
                        )
                    }
                    if (account.microsoft) {
                        Box(
                            Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(DogeSuccess)
                                .border(3.dp, DogeBackground, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = null,
                                tint = Color(0xFF06210F),
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    draft.ifBlank { account.nickname },
                    style = MaterialTheme.typography.titleLarge,
                    color = DogeTextPrimary,
                )
                Spacer(Modifier.height(6.dp))
                DogePill(
                    text = if (account.microsoft) "MICROSOFT" else "OFFLINE",
                    color = if (account.microsoft) DogeSuccess else DogePrimary,
                    dot = false,
                )
            }

            Spacer(Modifier.height(24.dp))

            // --- Profilo offline --------------------------------------
            DogeCard {
                DogeSectionTitle("Profilo offline")
                Spacer(Modifier.height(12.dp))
                DogeTextField(
                    value = draft,
                    onValueChange = {
                        draft = it
                        error = null
                    },
                    label = "Nickname",
                    placeholder = "3-16 caratteri: A-Z, 0-9, _",
                    isError = error != null,
                    keyboardType = KeyboardType.Ascii,
                )
                if (error != null) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        error!!,
                        style = MaterialTheme.typography.bodyMedium,
                        color = DogeDanger,
                    )
                }
                Spacer(Modifier.height(12.dp))
                DogePrimaryButton(
                    text = "Salva profilo",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        val name = draft.trim()
                        if (!AccountStore.isValidNickname(name)) {
                            error = "Nickname non valido: 3-16 caratteri tra A-Z, 0-9 e _."
                        } else {
                            onSave(Account(name, account.microsoft))
                        }
                    },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "La skin viene caricata automaticamente dal nickname " +
                        "(servizio pubblico mc-heads.net).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = DogeTextSecondary,
                    fontSize = 12.sp,
                )
            }

            Spacer(Modifier.height(16.dp))

            // --- Microsoft --------------------------------------------
            DogeCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Email,
                        contentDescription = null,
                        tint = DogePrimary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    DogeSectionTitle("Account Microsoft")
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    "Ti porta alla pagina di login Microsoft: accedi con la tua email " +
                        "e password, poi torna qui. Nessuna configurazione richiesta.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = DogeTextSecondary,
                )
                Spacer(Modifier.height(14.dp))
                DogePrimaryButton(
                    text = "Accedi con Microsoft",
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        openMicrosoftLogin(ctx, AccountStore.clientId(ctx))
                        msDialog = true
                    },
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Il login avviene sul sito Microsoft: il launcher non vede né " +
                        "salva la tua password.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = DogeTextSecondary,
                    fontSize = 12.sp,
                )
            }

            Spacer(Modifier.height(16.dp))

            DogeCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.Lock,
                        contentDescription = null,
                        tint = DogeTextSecondary,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Nessuna credenziale viene inviata a terzi: il nickname e il " +
                            "Client ID restano sul dispositivo.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = DogeTextSecondary,
                        fontSize = 12.sp,
                    )
                }
            }

            Spacer(Modifier.height(28.dp))
        }

        if (msDialog) {
            DogeDialog(
                title = "Accesso Microsoft",
                onDismiss = { msDialog = false },
                confirmText = "Ho completato",
                onConfirm = {
                    msDialog = false
                    val name = draft.trim()
                    val finalName = if (AccountStore.isValidNickname(name)) name else account.nickname
                    onSave(Account(finalName, microsoft = true))
                },
                dismissText = "Annulla",
            ) {
                Text(
                    "Completa il login nella pagina Microsoft appena aperta. " +
                        "Al ritorno conferma per impostare il profilo Microsoft locale.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = DogeTextSecondary,
                )
            }
        }
    }
}

private fun openMicrosoftLogin(ctx: Context, clientId: String) {
    val redirect = "https://login.live.com/oauth20_desktop.srf"
    val url = buildString {
        append("https://login.live.com/oauth20_authorize.srf")
        append("?client_id=").append(Uri.encode(clientId))
        append("&response_type=code")
        append("&scope=").append(URLEncoder.encode("XboxLive.signin offline_access", "UTF-8"))
        append("&redirect_uri=").append(Uri.encode(redirect))
        append("&prompt=select_account")
    }
    runCatching {
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    }
}
