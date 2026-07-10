package com.mink.companion

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The companion's speech bubble. Shows a [CompanionUtterance] and, when the
 * utterance carries one, a single action that routes into the app.
 *
 * Styled for the overlay window rather than the app theme, so it reads clearly
 * on top of any background without depending on the host activity's colours.
 */
@Composable
fun CompanionBubble(
    utterance: CompanionUtterance,
    onAction: (route: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .widthIn(max = 240.dp)
            .background(BubbleBackground, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = utterance.text,
            color = BubbleText,
            fontSize = 13.sp,
            lineHeight = 18.sp,
        )

        val label = utterance.actionLabel
        val route = utterance.actionRoute
        if (label != null && route != null) {
            TextButton(
                onClick = { onAction(route) },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 0.dp,
                    vertical = 0.dp,
                ),
            ) {
                Text(
                    text = label,
                    color = BubbleAction,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

private val BubbleBackground = Color(0xFFEEF3FF)
private val BubbleText = Color(0xFF0A0E17)
private val BubbleAction = Color(0xFF2E6BFF)
