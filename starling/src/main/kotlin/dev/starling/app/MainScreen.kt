package dev.starling.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.Intent
import dev.starling.app.FriendsActivity
import dev.starling.app.SettingsActivity

// ---------------------------------------------------------------------------
// Schermate di navigazione
// ---------------------------------------------------------------------------
enum class Screen(val id: Int) {
    Home(0),
    Mods(1),
    Friends(2),
    Settings(3)
}

// ---------------------------------------------------------------------------
// Bottom Navigation in stile launcher gaming (Lunar/After Client style)
// ---------------------------------------------------------------------------
@Composable
private fun DogeBottomNavigation(
    selectedScreen: Screen,
    onItemClick: (Screen) -> Unit
) {
    BottomNavigation(
        backgroundColor = DogeSurface,
        containerColor = DogeSurface
    ) {
        BottomNavigationItem(
            icon = { Icon(Icons.Default.Home, contentDescription = null) },
            label = { Text(stringResource(R.string.nav_home)) },
            selected = selectedScreen == Screen.Home,
            onClick = { onItemClick(Screen.Home) },
            colors = BottomNavigationDefaults.colors(
                unselectedIconColor = DogeTertiary.copy(alpha = 0.6f),
                selectedIconColor = DogePrimary,
                unselectedLabelColor = DogeTertiary.copy(alpha = 0.6f),
                selectedLabelColor = DogePrimary,
            )
        )
        BottomNavigationItem(
            icon = { Icon(Icons.Default.List, contentDescription = null) },
            label = { Text(stringResource(R.string.nav_mods)) },
            selected = selectedScreen == Screen.Mods,
            onClick = { onItemClick(Screen.Mods) },
            colors = BottomNavigationDefaults.colors(
                unselectedIconColor = DogeTertiary.copy(alpha = 0.6f),
                selectedIconColor = DogePrimary,
                unselectedLabelColor = DogeTertiary.copy(alpha = 0.6f),
                selectedLabelColor = DogePrimary,
            )
        )
        BottomNavigationItem(
            icon = { Icon(Icons.Default.People, contentDescription = null) },
            label = { Text(stringResource(R.string.nav_friends)) },
            selected = selectedScreen == Screen.Friends,
            onClick = { onItemClick(Screen.Friends) },
            colors = BottomNavigationDefaults.colors(
                unselectedIconColor = DogeTertiary.copy(alpha = 0.6f),
                selectedIconColor = DogePrimary,
                unselectedLabelColor = DogeTertiary.copy(alpha = 0.6f),
                selectedLabelColor = DogePrimary,
            )
        )
        BottomNavigationItem(
            icon = { Icon(Icons.Default.Settings, contentDescription = null) },
            label = { Text(stringResource(R.string.nav_settings)) },
            selected = selectedScreen == Screen.Settings,
            onClick = { onItemClick(Screen.Settings) },
            colors = BottomNavigationDefaults.colors(
                unselectedIconColor = DogeTertiary.copy(alpha = 0.6f),
                selectedIconColor = DogePrimary,
                unselectedLabelColor = DogeTertiary.copy(alpha = 0.6f),
                selectedLabelColor = DogePrimary,
            )
        )
    }
}

// ---------------------------------------------------------------------------
// MainScreen — Home del launcher Doge Client
// ---------------------------------------------------------------------------
@Composable
fun MainScreen(
    onStartGame: () -> Unit = { /* Placeholder: collegare a nativeStartGame() */ },
    onNavigateToMods: () -> Unit = { },
    onNavigateToFriends: () -> Unit = { },
    onNavigateToSettings: () -> Unit = { },
) {
    val context = LocalContext.current

    // Stato di navigazione: Home rimane attiva; Amici/Impostazioni aprono
    // le Activity esistenti (invariato) tramite Intent, senza riscriverle.
    var selectedScreen by rememberSaveable { mutableStateOf(Screen.Home) }

    Scaffold(
        bottomNavigation = {
            DogeBottomNavigation(
                selectedScreen = selectedScreen,
                onItemClick = { screen ->
                    selectedScreen = screen
                    when (screen) {
                        Screen.Home -> { /* già in home */ }
                        Screen.Mods -> onNavigateToMods()
                        Screen.Friends -> {
                            // Schermata Amici esistente (Fase 6): lista amici, token,
                            // LAN invite. Invocata così com'è, nessun rischio di regressione.
                            context.startActivity(Intent(context, FriendsActivity::class.java))
                        }
                        Screen.Settings -> {
                            // Schermata Impostazioni esistente (Fase 7): toggle AI Locale,
                            // endpoint, conversione JAR. Invocata così com'è.
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
            // Header: logo a sinistra, avatar a destra (stile arcade/gaming)
            // -----------------------------------------------------------------
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp, horizontal = 20.dp),
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
                        .clickable { /* apri profilo utente */ }
                ) {
                    Text(
                        text = "🐶",
                        fontSize = 26.sp,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }

            Spacer(modifier = Modifier.weight(0.4f))

            // -----------------------------------------------------------------
            // Centro: pulsante Play circolare con glow/ombra gialla
            // -----------------------------------------------------------------
            Column(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Contenitore per l'effetto glow attorno al pulsante
                Box(
                    modifier = Modifier.size(128.dp),
                    contentAlignment = Alignment.Center
                ) {
                    // Anello glow giallo traslucido dietro il pulsante
                    Box(
                        modifier = Modifier
                            .size(112.dp)
                            .clip(CircleShape)
                            .background(DogePrimary.copy(alpha = 0.18))
                    )
                }

                // Pulsante Play vero e proprio
                Button(
                    onClick = onStartGame,
                    modifier = Modifier
                        .size(84.dp)
                        .clip(CircleShape)
                        .border(4.dp, DogePrimary, CircleShape)
                        .background(Color(0xFF121212)),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = DogePrimary,
                        contentColor = Color.BLACK
                    ),
                    shape = CircleShape,
                    elevation = 8.dp
                ) {
                    Text(
                        text = "🦴",
                        fontSize = 36.sp,
                        modifier = Modifier.align(Alignment.Center)
                    )
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
