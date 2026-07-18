package dev.jaspreet.printserver.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import dev.jaspreet.printserver.ui.theme.AndroidGreen
import dev.jaspreet.printserver.ui.theme.DarkNavy
import dev.jaspreet.printserver.ui.theme.SlateBlue

@Composable
fun UsbConnectionIllustration(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(160.dp),
        contentAlignment = Alignment.Center
    ) {
        // We draw the connecting USB cable in the background
        Canvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width
            val height = size.height

            // Coordinates for phone bottom center and printer left-middle
            // Phone is centered around X = width * 0.25f, bottom is at Y = height * 0.7f
            // Printer is centered around X = width * 0.72f, left side is at X = width * 0.58f, Y = height * 0.65f
            val phoneBottomX = width * 0.28f
            val phoneBottomY = height * 0.70f

            val printerConnectX = width * 0.58f
            val printerConnectY = height * 0.65f

            // Draw a beautiful curved USB cable path
            val path = Path().apply {
                moveTo(phoneBottomX, phoneBottomY)
                // Curve down, go right, and then curve up into the printer
                cubicTo(
                    phoneBottomX, phoneBottomY + 60f,
                    printerConnectX - 50f, phoneBottomY + 60f,
                    printerConnectX - 20f, printerConnectY + 20f
                )
                lineTo(printerConnectX, printerConnectY)
            }

            drawPath(
                path = path,
                color = DarkNavy,
                style = Stroke(width = 6f, cap = StrokeCap.Round)
            )

            // Draw USB Plug head near printer
            drawRect(
                color = SlateBlue,
                topLeft = Offset(printerConnectX - 18f, printerConnectY - 8f),
                size = androidx.compose.ui.geometry.Size(18f, 16f)
            )
        }

        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left: Phone representation
            Box(
                modifier = Modifier
                    .width(55.dp)
                    .height(105.dp)
                    .background(Color.White, RoundedCornerShape(8.dp))
                    .border(3.dp, DarkNavy, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                // Speaker/Camera Notch
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 6.dp)
                        .width(16.dp)
                        .height(4.dp)
                        .background(DarkNavy, RoundedCornerShape(2.dp))
                )

                // Android Mascot Icon
                Icon(
                    imageVector = Icons.Default.Android,
                    contentDescription = null,
                    tint = AndroidGreen,
                    modifier = Modifier.size(32.dp)
                )

                // Home button indicator line
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 6.dp)
                        .width(24.dp)
                        .height(3.dp)
                        .background(DarkNavy.copy(alpha = 0.3f), RoundedCornerShape(1.5.dp))
                )
            }

            // Right: Printer representation
            Box(
                modifier = Modifier
                    .width(130.dp)
                    .height(115.dp),
                contentAlignment = Alignment.BottomCenter
            ) {
                // Stack items to represent paper feeder and printer body
                
                // 1. Paper sticking out from the top
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(bottom = 50.dp)
                        .width(65.dp)
                        .height(55.dp)
                        .background(Color.White, RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                        .border(
                            2.5.dp,
                            DarkNavy,
                            RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)
                        )
                )

                // 2. Printer Main Body
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(72.dp)
                        .background(Color.White, RoundedCornerShape(8.dp))
                        .border(3.dp, DarkNavy, RoundedCornerShape(8.dp))
                ) {
                    // Control panel buttons/lights
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(top = 10.dp, start = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(Color(0xFF4CAF50), RoundedCornerShape(3.dp))
                        )
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(Color(0xFFFFC107), RoundedCornerShape(3.dp))
                        )
                    }

                    // Paper Output Tray slot
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 12.dp)
                            .width(86.dp)
                            .height(16.dp)
                            .background(Color.LightGray.copy(alpha = 0.3f), RoundedCornerShape(2.dp))
                            .border(2.dp, DarkNavy, RoundedCornerShape(2.dp))
                    )

                    // Sticking out printed paper
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 2.dp)
                            .width(76.dp)
                            .height(14.dp)
                            .background(Color.White)
                            .border(2.dp, DarkNavy, RoundedCornerShape(1.dp))
                    )
                }
            }
        }
    }
}

@Composable
fun WirelessSharingIllustration(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(160.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left: Printer
            Box(
                modifier = Modifier
                    .width(110.dp)
                    .height(95.dp),
                contentAlignment = Alignment.BottomCenter
            ) {
                // Paper sticking out from top
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(bottom = 40.dp)
                        .width(55.dp)
                        .height(45.dp)
                        .background(Color.White, RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                        .border(
                            2.dp,
                            DarkNavy,
                            RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)
                        )
                )

                // Printer Main Body
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(60.dp)
                        .background(Color.White, RoundedCornerShape(6.dp))
                        .border(2.5.dp, DarkNavy, RoundedCornerShape(6.dp))
                ) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 10.dp)
                            .width(70.dp)
                            .height(12.dp)
                            .background(Color.LightGray.copy(alpha = 0.3f), RoundedCornerShape(1.dp))
                            .border(1.5.dp, DarkNavy, RoundedCornerShape(1.dp))
                    )
                }
            }

            // Center: Wi-Fi Wireless Waves
            Canvas(
                modifier = Modifier
                    .width(80.dp)
                    .height(80.dp)
            ) {
                val width = size.width
                val height = size.height
                val centerX = width / 2f
                val centerY = height / 2f

                // Draw central dot
                drawCircle(
                    color = SlateBlue,
                    radius = 6f,
                    center = Offset(centerX, centerY)
                )

                // Draw concentric wireless wave arcs (left and right)
                for (i in 1..3) {
                    val radius = 18f * i
                    // Right-facing waves
                    drawArc(
                        color = SlateBlue.copy(alpha = 1.0f - (i - 1) * 0.25f),
                        startAngle = -45f,
                        sweepAngle = 90f,
                        useCenter = false,
                        topLeft = Offset(centerX - radius, centerY - radius),
                        size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2),
                        style = Stroke(width = 5f, cap = StrokeCap.Round)
                    )

                    // Left-facing waves
                    drawArc(
                        color = SlateBlue.copy(alpha = 1.0f - (i - 1) * 0.25f),
                        startAngle = 135f,
                        sweepAngle = 90f,
                        useCenter = false,
                        topLeft = Offset(centerX - radius, centerY - radius),
                        size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2),
                        style = Stroke(width = 5f, cap = StrokeCap.Round)
                    )
                }
            }

            // Right: Phone
            Box(
                modifier = Modifier
                    .width(50.dp)
                    .height(95.dp)
                    .background(Color.White, RoundedCornerShape(8.dp))
                    .border(2.5.dp, DarkNavy, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                // Notch
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 5.dp)
                        .width(14.dp)
                        .height(3.dp)
                        .background(DarkNavy, RoundedCornerShape(1.5.dp))
                )

                // Android Mascot
                Icon(
                    imageVector = Icons.Default.Android,
                    contentDescription = null,
                    tint = AndroidGreen,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    }
}
