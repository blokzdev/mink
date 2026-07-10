package com.mink.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mink.core.model.Sensitivity

/**
 * A small pill labelling a category's sensitivity tier, tinted with the tier's
 * accent colour from [Sensitivity.tint].
 */
@Composable
fun TierChip(
    sensitivity: Sensitivity,
    modifier: Modifier = Modifier,
) {
    val tint = sensitivity.tint
    Text(
        text = sensitivity.shortTitle,
        color = tint,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(tint.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 3.dp),
    )
}

/** Colour for a guardian/alert emphasis; kept local so screens share one look. */
fun tierTint(sensitivity: Sensitivity): Color = sensitivity.tint
