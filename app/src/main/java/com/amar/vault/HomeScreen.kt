package com.amar.vault

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.amar.vault.ui.theme.AgenticBlue
import com.amar.vault.ui.theme.AgenticPink
import com.amar.vault.ui.theme.CharcoalSoft
import com.amar.vault.ui.theme.ChevronGray
import com.amar.vault.ui.theme.Cream
import com.amar.vault.ui.theme.CreamLight
import com.amar.vault.ui.theme.IconBgLight
import com.amar.vault.ui.theme.WarmBrown

// ═══════════════════════════════════════════════════════════════════════
// HomeScreen — the warm, minimal landing page
// ═══════════════════════════════════════════════════════════════════════

@Composable
fun HomeScreen(
    onAgenticClick: () -> Unit,
    onPhotosClick: () -> Unit,
    onDocumentsClick: () -> Unit,
    onSettingsClick: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Cream)
            .padding(horizontal = 24.dp)
    ) {
        Spacer(Modifier.height(80.dp))

        // ── Title ───────────────────────────────────────────────────
        Text(
            text = "Home",
            fontSize = 38.sp,
            fontWeight = FontWeight.SemiBold,
            color = WarmBrown,
            letterSpacing = (-0.5).sp,
        )

        Spacer(Modifier.height(36.dp))

        // ── Cards ───────────────────────────────────────────────────
        HomeCard(
            icon = { AgenticIcon() },
            label = "Agentic",
            onClick = onAgenticClick,
        )

        Spacer(Modifier.height(16.dp))

        HomeCard(
            icon = { PhotosIcon() },
            label = "Photos",
            onClick = onPhotosClick,
        )

        Spacer(Modifier.height(16.dp))

        HomeCard(
            icon = { DocumentsIcon() },
            label = "Documents",
            onClick = onDocumentsClick,
        )

        Spacer(Modifier.height(16.dp))

        HomeCard(
            icon = { SettingsIcon() },
            label = "Settings",
            onClick = onSettingsClick,
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Card component
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun HomeCard(
    icon: @Composable () -> Unit,
    label: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(
                elevation = 4.dp,
                shape = RoundedCornerShape(20.dp),
                ambientColor = Color(0x0D000000),
                spotColor = Color(0x1A000000),
            )
            .clip(RoundedCornerShape(20.dp))
            .background(CreamLight)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            // Icon
            icon()

            Spacer(Modifier.width(16.dp))

            // Label
            Text(
                text = label,
                fontSize = 20.sp,
                fontWeight = FontWeight.Medium,
                color = CharcoalSoft,
                modifier = Modifier.weight(1f),
            )

            // Chevron
            Text(
                text = "›",
                fontSize = 24.sp,
                fontWeight = FontWeight.Light,
                color = ChevronGray,
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════
// Icons — each matches the reference design
// ═══════════════════════════════════════════════════════════════════════

/** Gradient circle — pink/blue like the Siri orb */
@Composable
private fun AgenticIcon() {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(
                Brush.linearGradient(
                    colors = listOf(AgenticPink, AgenticBlue)
                )
            )
    )
}

/** Photos icon — mountain/sun landscape symbol */
@Composable
private fun PhotosIcon() {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(IconBgLight),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "🖼",
            fontSize = 22.sp,
        )
    }
}

/** Documents icon — page symbol */
@Composable
private fun DocumentsIcon() {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(IconBgLight),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "📄",
            fontSize = 22.sp,
        )
    }
}

/** Settings icon — gear symbol */
@Composable
private fun SettingsIcon() {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(IconBgLight),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "⚙️",
            fontSize = 22.sp,
        )
    }
}