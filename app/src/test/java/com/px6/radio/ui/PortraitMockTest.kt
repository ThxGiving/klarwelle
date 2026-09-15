package com.px6.radio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.takahirom.roborazzi.captureRoboImage
import com.px6.radio.ui.theme.ModernDarkSkin
import com.px6.radio.ui.theme.Px6RadioTheme
import com.px6.radio.ui.theme.appColors
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * MOCK ONLY — not a frontend. Sketches what a portrait Klarwelle could look like (now-playing on
 * top, a 3×2 preset grid, the function bar at the bottom), using the real theme tokens so the
 * picture is honest about colours and proportions. 800×1280 dp = a 10" tablet held upright.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w800dp-h1280dp-port-mdpi")
class PortraitMockTest {

    @Test fun portraitMock() {
        captureRoboImage(filePath = "build/outputs/roborazzi/portrait_mock.png") {
            Px6RadioTheme(ModernDarkSkin) { Mock() }
        }
    }

    @Composable
    private fun Mock() {
        val c = appColors
        Column(Modifier.fillMaxSize().background(c.bg)) {
            // Status row: pills + clock
            Row(Modifier.fillMaxWidth().padding(16.dp, 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Pill("● DAB+", c.accent); Spacer(Modifier.size(8.dp)); Pill("● FM 98.8", c.muted); Spacer(Modifier.size(8.dp)); Pill("● Internet", c.muted)
                Spacer(Modifier.weight(1f))
                Text("20:51", color = c.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            }
            Hairline()
            // Now playing: logo, name, DLS, arrows
            Column(Modifier.fillMaxWidth().weight(1f).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Box(Modifier.size(220.dp).clip(RoundedCornerShape(28.dp)).background(c.panel), contentAlignment = Alignment.Center) {
                    Text("Dlf", color = c.text, fontSize = 64.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(28.dp))
                Text("Deutschlandfunk", color = c.text, fontSize = 40.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                Text("Deutschlandfunk — Nachrichten", color = c.muted, fontSize = 20.sp)
                Spacer(Modifier.height(32.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Arrow("‹"); Arrow("›")
                }
            }
            // Presets: 3 × 2
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                for (row in 0..1) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    for (col in 0..2) {
                        val n = row * 3 + col + 1
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                Modifier.fillMaxWidth().aspectRatio(1.35f).clip(RoundedCornerShape(18.dp))
                                    .background(c.panel).border(2.dp, if (n == 1) c.accent else c.line, RoundedCornerShape(18.dp)),
                                contentAlignment = Alignment.BottomEnd,
                            ) {
                                Text("$n", color = c.muted2, fontSize = 56.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(10.dp))
                                if (n == 1) Text("Dlf", color = c.text, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.Center))
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(if (n == 1) "Deutschlandfunk" else "—", color = if (n == 1) c.accent else c.muted, fontSize = 15.sp)
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Hairline(c.accent)
            // Function bar
            Row(Modifier.fillMaxWidth().background(c.transportBg).padding(vertical = 12.dp)) {
                for ((label, value) in listOf("Band" to "DAB+", "Stations" to "☰", "Manual" to "⌁", "View" to "▦", "Settings" to "⚙")) {
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(label, color = c.muted, fontSize = 13.sp)
                        Text(value, color = c.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }

    @Composable private fun Pill(t: String, color: androidx.compose.ui.graphics.Color) =
        Box(Modifier.border(1.dp, color, RoundedCornerShape(50)).padding(12.dp, 6.dp)) { Text(t, color = color, fontSize = 14.sp) }

    @Composable private fun Arrow(t: String) =
        Box(Modifier.size(72.dp).clip(RoundedCornerShape(16.dp)).background(appColors.panel), contentAlignment = Alignment.Center) {
            Text(t, color = appColors.text, fontSize = 30.sp)
        }

    @Composable private fun Hairline(color: androidx.compose.ui.graphics.Color = appColors.line) =
        Box(Modifier.fillMaxWidth().height(1.dp).background(color))
}
