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
 * Friends screen of the Starling launcher (Phase 6).
 *
 * - persistent friend list (nickname, local UUID, online/offline);
 * - field to add a friend via nickname;
 * - "Invite to world" button that generates a one-time code of 8 characters,
 *   valid for 5 minutes, with countdown and copy to clipboard;
 * - mini auth server on a dedicated port (separate from the 8-bit bridge).
 *
 * Additional layer: it does not touch Microsoft authentication. For the offline
 * join the client uses the code; the server injects the profile with the local
 * UUID and `online_mode=false` only for that player.
 *
 * Android packaging: include `libstarling_social.so` in
 * `src/main/jniLibs/arm64-v8a/` (produced by
 * `cargo ndk ... build --package starling-social --features jni`) and the
 * `dev.starling.social.SocialBridge` class in the APK.
 */
class FriendsActivity : ComponentActivity() {

    companion object {
        /** Host world port (the world being invited). */
        const val WORLD_PORT = 25565
        /** Port of the dedicated auth server (separate from the 8-bit bridge). */
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

        // Fixed port if free, otherwise ephemeral (shown in status).
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
            text = "Friends"
            textSize = 26f
        })

        val addRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        nicknameInput = EditText(this).apply {
            hint = "Nickname (3-16 chars: A-Z, 0-9, _)"
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
            text = "Add"
            setOnClickListener { addFriend() }
        })
        root.addView(addRow)

        root.addView(Button(this).apply {
            text = "Invite to world"
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
            text = "Hold a friend to remove them."
            textSize = 12f
            setTextColor(Color.GRAY)
        })

        return root
    }

    /** Refreshes the list from the native layer (online state updated by server). */
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
                    "Invite server: $ip:$authPort · code valid for 5 minutes"
                } else {
                    "Invite server not active (port busy?)"
                }
            }
            .onFailure { statusView.text = "Friends list error: ${it.message}" }
    }

    private fun addFriend() {
        val name = nicknameInput.text.toString().trim()
        if (name.isEmpty()) {
            toast("Enter a nickname")
            return
        }
        try {
            social.addFriend(name)
            nicknameInput.setText("")
            refresh()
            toast("Friend added: $name")
        } catch (e: IllegalStateException) {
            toast("Unable to add friend: ${e.message}")
        }
    }

    private fun invite() {
        val ip = localIpv4()
        if (ip == null) {
            toast("Wi-Fi not connected: unable to generate invite")
            return
        }
        try {
            val code = social.generateInvite(ip, WORLD_PORT)
            showInviteDialog(code, ip)
        } catch (e: IllegalStateException) {
            toast("Unable to generate the code: ${e.message}")
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
            .setTitle("Invite to world")
            .setView(message)
            .setPositiveButton("Copy code", null)
            .setNegativeButton("Close", null)
            .create()

        val tick = object : Runnable {
            override fun run() {
                val left = expiresAt - System.currentTimeMillis()
                if (left <= 0) {
                    message.text = "Code expired. Generate a new one."
                    return
                }
                val minutes = left / 60_000
                val seconds = (left % 60_000) / 1000
                message.text = buildString {
                    append("Code: ").append(code).append("\n\n")
                    append("World: ").append(host).append(':').append(WORLD_PORT).append('\n')
                    append("Auth: ").append(localIpv4() ?: host).append(':').append(authPort).append('\n')
                    append("Expires in %02d:%02d".format(minutes, seconds))
                }
                handler.postDelayed(this, 500)
            }
        }

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                copyToClipboard(code)
                toast("Code copied")
            }
        }
        dialog.setOnDismissListener { handler.removeCallbacks(tick) }
        dialog.show()
        handler.post(tick)
    }

    private fun confirmRemove(position: Int) {
        val name = nicknames.getOrNull(position) ?: return
        AlertDialog.Builder(this)
            .setTitle("Remove $name?")
            .setPositiveButton("Remove") { _, _ ->
                try {
                    social.removeFriend(name)
                    refresh()
                } catch (e: IllegalStateException) {
                    toast("Removal failed: ${e.message}")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun copyToClipboard(code: String) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("Starling invite code", code))
    }

    /** Current IPv4 address of the Wi-Fi network (null if offline). */
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
