package dev.starling.app

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import dev.starling.social.SocialBridge
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

/**
 * Schermata "Amici" del launcher Starling (Fase 6).
 *
 * - lista amici persistente (nickname, UUID locale, online/offline);
 * - campo per aggiungere un amico tramite nickname;
 * - pulsante "Invita nel mondo" che genera un codice monouso di 8 caratteri,
 *   valido 5 minuti, con countdown e copia negli appunti;
 * - mini-server auth su porta dedicata (separata dal bridge a 8 bit).
 *
 * Layer aggiuntivo: non tocca l'autenticazione Microsoft. Per il join offline
 * il client usa il codice; il server inietta il profilo con l'UUID locale e
 * `online_mode=false` solo per quel giocatore.
 *
 * Packaging Android: includere `libstarling_social.so` in
 * `src/main/jniLibs/arm64-v8a/` (prodotta da
 * `cargo ndk ... build -p starling-social --features jni`) e la classe
 * `dev.starling.social.SocialBridge` nell'APK.
 */
class FriendsActivity : ComponentActivity() {

    companion object {
        /** Porta del mondo host (il mondo che si invita). */
        const val WORLD_PORT = 25565
        /** Porta dedicata del mini-server auth (separata dal bridge a 8 bit). */
        const val AUTH_PORT = 25566
        private const val FRIENDS_FILE = "social/friends.txt"
    }

    private lateinit var social: SocialBridge
    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var listView: ListView
    private lateinit var nicknameInput: EditText
    private lateinit var statusView: TextView

    private val display = mutableListOf<String>()
    private val nicknames = mutableListOf<String>()
    private val handler = Handler(Looper.getMainLooper())
    private var authPort = 0

    private val refreshTask = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val friendsFile = File(getExternalFilesDir(null), FRIENDS_FILE).apply {
            parentFile?.mkdirs()
        }
        social = SocialBridge(friendsFile.absolutePath)
        setContentView(buildUi())

        // Porta fissa se libera, altrimenti effimera (mostrata nella status).
        authPort = try {
            social.startAuthServer(AUTH_PORT)
        } catch (e: IllegalStateException) {
            try {
                social.startAuthServer(0)
            } catch (e2: IllegalStateException) {
                0
            }
        }
        handler.post(refreshTask)
    }

    private fun buildUi(): LinearLayout {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 24)
        }

        root.addView(TextView(this).apply {
            text = "Amici"
            textSize = 26f
        })

        val addRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        nicknameInput = EditText(this).apply {
            hint = "Nickname (3-16: A-Z, 0-9, _)"
            inputType = InputType.TYPE_CLASS_TEXT
            layoutParams = LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
            )
            setSingleLine()
        }
        addRow.addView(nicknameInput)
        addRow.addView(Button(this).apply {
            text = "Aggiungi"
            setOnClickListener { addFriend() }
        })
        root.addView(addRow)

        root.addView(Button(this).apply {
            text = "Invita nel mondo"
            setOnClickListener { invite() }
        })

        statusView = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.GRAY)
        }
        root.addView(statusView)

        listView = ListView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
            setOnItemLongClickListener { _, _, position, _ ->
                confirmRemove(position)
                true
            }
        }
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, display)
        listView.adapter = adapter
        root.addView(listView)

        root.addView(TextView(this).apply {
            text = "Tieni premuto un amico per rimuoverlo."
            textSize = 12f
            setTextColor(Color.GRAY)
        })

        return root
    }

    /** Ricarica la lista dal layer nativo (stato online aggiornato dal server). */
    private fun refresh() {
        runCatching { social.listFriends() }
            .onSuccess { entries ->
                display.clear()
                nicknames.clear()
                for (entry in entries) {
                    val parts = entry.split('|', limit = 3)
                    if (parts.size != 3) continue
                    val name = parts[0]
                    val online = parts[2] == "1"
                    nicknames.add(name)
                    display.add(
                        (if (online) "● " else "○ ") + name +
                            if (online) "   (online)" else "   (offline)"
                    )
                }
                adapter.notifyDataSetChanged()
                val ip = localIpv4() ?: "127.0.0.1"
                statusView.text = if (authPort > 0) {
                    "Server inviti: $ip:$authPort · codice valido 5 minuti"
                } else {
                    "Server inviti non attivo (porta occupata?)"
                }
            }
            .onFailure { statusView.text = "Errore lista amici: ${it.message}" }
    }

    private fun addFriend() {
        val name = nicknameInput.text.toString().trim()
        if (name.isEmpty()) {
            toast("Inserisci un nickname")
            return
        }
        try {
            social.addFriend(name)
            nicknameInput.setText("")
            refresh()
            toast("Amico aggiunto: $name")
        } catch (e: IllegalStateException) {
            toast("Impossibile aggiungere: ${e.message}")
        }
    }

    private fun invite() {
        val ip = localIpv4()
        if (ip == null) {
            toast("Wi-Fi non connesso: impossibile generare l'invito")
            return
        }
        try {
            val code = social.generateInvite(ip, WORLD_PORT)
            showInviteDialog(code, ip)
        } catch (e: IllegalStateException) {
            toast("Impossibile generare il codice: ${e.message}")
        }
    }

    private fun showInviteDialog(code: String, host: String) {
        val expiresAt = System.currentTimeMillis() +
            SocialBridge.tokenTtlSeconds() * 1000L

        val message = TextView(this).apply {
            setPadding(48, 32, 48, 16)
            textSize = 16f
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Invita nel mondo")
            .setView(message)
            .setPositiveButton("Copia codice", null)
            .setNegativeButton("Chiudi", null)
            .create()

        val tick = object : Runnable {
            override fun run() {
                val left = expiresAt - System.currentTimeMillis()
                if (left <= 0) {
                    message.text = "Codice scaduto. Generane un altro."
                    return
                }
                val minutes = left / 60_000
                val seconds = (left % 60_000) / 1000
                message.text = buildString {
                    append("Codice: ").append(code).append("\n\n")
                    append("Mondo: ").append(host).append(':').append(WORLD_PORT).append('\n')
                    append("Auth: ").append(localIpv4() ?: host).append(':').append(authPort).append('\n')
                    append("Scade tra %02d:%02d".format(minutes, seconds))
                }
                handler.postDelayed(this, 500)
            }
        }

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                copyToClipboard(code)
                toast("Codice copiato")
            }
        }
        dialog.setOnDismissListener { handler.removeCallbacks(tick) }
        dialog.show()
        handler.post(tick)
    }

    private fun confirmRemove(position: Int) {
        val name = nicknames.getOrNull(position) ?: return
        AlertDialog.Builder(this)
            .setTitle("Rimuovere $name?")
            .setPositiveButton("Rimuovi") { _, _ ->
                try {
                    social.removeFriend(name)
                    refresh()
                } catch (e: IllegalStateException) {
                    toast("Rimozione fallita: ${e.message}")
                }
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun copyToClipboard(code: String) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("Codice invito Starling", code))
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
        } catch (e: Exception) {
            null
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        runCatching { social.stopAuthServer() }
        runCatching { social.close() }
        super.onDestroy()
    }
}
