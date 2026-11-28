package dev.starling.app

import android.content.Intent
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ---------------------------------------------------------------------------
// Navigation screens
// ---------------------------------------------------------------------------
enum class Screen(val id: Int) {
    Home(0),
    Mods(1),
    Friends(2),
    Settings(3)
}

// ---------------------------------------------------------------------------
// Bottom Navigation Material 3 in launcher gaming style
// ---------------------------------------------------------------------------
@Composable
private fun DogeBottomNavigation(
    selectedScreen: Screen,
    onItemClick: (Screen) -> Unit
) {
    val itemColors = NavigationBarItemDefaults.colors(
        selectedIconColor = DogePrimary,
        selectedTextColor = DogePrimary,
        indicatorColor = DogePrimary.copy(alpha = 0.18f),
        unselectedIconColor = DogeTertiary.copy(alpha = 0.6f),
        unselectedTextColor = DogeTertiary.copy(alpha = 0.6f),
    )

    NavigationBar(containerColor = DogeSurface) {
        NavigationBarItem(
            selected = selectedScreen == Screen.Home,
            onClick = { onItemClick(Screen.Home) },
            icon = { Text("🏠", fontSize = 20.sp) },
            label = { Text(stringResource(R.string.nav_home)) },
            colors = itemColors
        )
        NavigationBarItem(
            selected = selectedScreen == Screen.Mods,
            onClick = { onItemClick(Screen.Mods) },
            icon = { Text("🧩", fontSize = 20.sp) },
            label = { Text(stringResource(R.string.nav_mods)) },
            colors = itemColors
        )
        NavigationBarItem(
            selected = selectedScreen == Screen.Friends,
            onClick = { onItemClick(Screen.Friends) },
            icon = { Text("🐕", fontSize = 20.sp) },
            label = { Text(stringResource(R.string.nav_friends)) },
            colors = itemColors
        )
        NavigationBarItem(
            selected = selectedScreen == Screen.Settings,
            onClick = { onItemClick(Screen.Settings) },
            icon = { Text("⚙️", fontSize = 20.sp) },
            label = { Text(stringResource(R.string.nav_settings)) },
            colors = itemColors
        )
    }
}

// ---------------------------------------------------------------------------
// MainScreen — Home of the Doge Client launcher
// ---------------------------------------------------------------------------
@Composable
fun MainScreen(
    onStartGame: () -> Unit = { /* Placeholder: wire to nativeStartGame() */ },
    onNavigateToMods: () -> Unit = { },
    onNavigateToFriends: () -> Unit = { },
    onNavigateToSettings: () -> Unit = { },
) {
    val context = LocalContext.current

    // Home stays active; Friends/Settings open the existing Activities
    // (unchanged) via Intent, without rewriting them.
    var selectedScreen by rememberSaveable { mutableStateOf(Screen.Home) }

    Scaffold(
        containerColor = DogeBackground,
        bottomBar = {
            DogeBottomNavigation(
                selectedScreen = selectedScreen,
                onItemClick = { screen ->
                    selectedScreen = screen
                    when (screen) {
                        Screen.Home -> { /* already on Home */ }
                        Screen.Mods -> onNavigateToMods()
                        Screen.Friends -> {
                            // Existing Friends screen (Phase 6): friend list, token,
                            // LAN invite. Invoked as is, no regression.
                            context.startActivity(Intent(context, FriendsActivity::class.java))
                        }
                        Screen.Settings -> {
                            // Existing Settings screen (Phase 7): AI Local toggle,
                            // endpoint, JAR conversion. Invoked as is.
                            context.startActivity(Intent(context, SettingsActivity::class.java))
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(DogeBackground)
        ) {
            // -----------------------------------------------------------------
            // Header: logo on the left, avatar on the right (arcade/gaming style)
            // -----------------------------------------------------------------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp, start = 20.dp, end = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "DOGE CLIENT",
                    style = MaterialTheme.typography.displayLarge,
                    color = DogePrimary,
                    modifier = Modifier.weight(1f)
                )

                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(DogeSecondary)
                        .clickable { /* open user profile */ },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "🐶",
                        fontSize = 26.sp
                    )
                }
            }

            Spacer(modifier = Modifier.weight(0.4f))

            // -----------------------------------------------------------------
            // Center: circular Play button with glow/shadow
            // -----------------------------------------------------------------
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Container for the glow effect around the button
                Box(
                    modifier = Modifier.size(132.dp),
                    contentAlignment = Alignment.Center
                ) {
                    // Translucent yellow glow rings behind the button
                    Box(
                        modifier = Modifier
                            .size(124.dp)
                            .clip(CircleShape)
                            .background(DogePrimary.copy(alpha = 0.12f))
                    )
                    Box(
                        modifier = Modifier
                            .size(108.dp)
                            .clip(CircleShape)
                            .background(DogePrimary.copy(alpha = 0.22f))
                    )

                    // Pulsante Play vero e proprio
                    Button(
                        onClick = onStartGame,
                        modifier = Modifier
                            .size(88.dp)
                            .border(4.dp, DogePrimary, CircleShape),
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF121212),
                            contentColor = DogePrimary
                        ),
                        elevation = ButtonDefaults.buttonElevation(
                            defaultElevation = 8.dp,
                            pressedElevation = 2.dp
                        ),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text(text = "🦴", fontSize = 38.sp)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = stringResource(R.string.ready_to_play),
                    style = MaterialTheme.typography.bodyLarge,
                    color = DogeTertiary
                )
            }

            Spacer(modifier = Modifier.weight(0.6f))
        }
    }
}
