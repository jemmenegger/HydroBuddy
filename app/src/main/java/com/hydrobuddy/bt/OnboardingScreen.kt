package com.hydrobuddy.bt

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

private val OnboardingText = Color(0xFF67AEDA)

@Composable
fun OnboardingFlow(
    onComplete: (gender: String, heightCm: Int, weightKg: Int) -> Unit
) {
    var step by remember { mutableIntStateOf(1) }
    var selectedGender by remember { mutableStateOf("Male") }
    var height by remember { mutableFloatStateOf(165f) }
    var weight by remember { mutableFloatStateOf(90f) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .hydroBuddyScreenPadding(),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(44.dp)
        ) {
            OnboardingProgress(step = step)
            Spacer(modifier = Modifier.height(18.dp))

            when (step) {
                1 -> GenderStep(
                    selectedGender = selectedGender,
                    onSelect = { selectedGender = it }
                )

                2 -> SliderStep(
                    title = "Whats your\nHeight?",
                    valueText = "${height.roundToInt()} cm",
                    minLabel = "130 cm",
                    maxLabel = "200 cm",
                    value = height,
                    range = 130f..200f,
                    onValueChange = { height = it }
                )

                else -> SliderStep(
                    title = "Whats your\nWeight?",
                    valueText = "${weight.roundToInt()} kg",
                    minLabel = "30 Kg",
                    maxLabel = "150 kg",
                    value = weight,
                    range = 30f..150f,
                    onValueChange = { weight = it }
                )
            }
        }

        Button(
            onClick = {
                if (step < 3) {
                    step += 1
                } else {
                    onComplete(
                        selectedGender.lowercase(),
                        height.roundToInt(),
                        weight.roundToInt()
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(999.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.Transparent,
                contentColor = Color.White
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(999.dp))
                    .background(Brush.horizontalGradient(listOf(HydroBuddyColors.gradientStart, HydroBuddyColors.gradientEnd)))
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("Continue", style = MaterialTheme.typography.titleMedium.copy(fontSize = 22.sp))
            }
        }
    }
}

@Composable
private fun OnboardingProgress(step: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        repeat(3) { idx ->
            val targetFill = if (idx < step) 1f else 0f
            val animatedFill by animateFloatAsState(
                targetValue = targetFill,
                animationSpec = tween(durationMillis = 320),
                label = "onboarding_progress_$idx"
            )
            val stepColor = when (idx) {
                0 -> HydroBuddyColors.gradientStart
                1 -> HydroBuddyColors.gradientMiddle
                else -> HydroBuddyColors.gradientEnd
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(10.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color(0xFFE1E1E1))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(animatedFill)
                        .height(10.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(stepColor)
                )
            }
        }
    }
}

@Composable
private fun GenderStep(
    selectedGender: String,
    onSelect: (String) -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = buildAnnotatedString {
                withStyle(
                    style = SpanStyle(
                        brush = Brush.horizontalGradient(
                            colors = listOf(HydroBuddyColors.gradientStart, HydroBuddyColors.gradientMiddle, HydroBuddyColors.gradientEnd)
                        )
                    )
                ) {
                    append("Whats your\nGender?")
                }
            },
            style = MaterialTheme.typography.headlineSmall.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 36.sp
            ),
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(52.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(34.dp)) {
            GenderOption(
                symbol = "♂",
                label = "Male",
                selected = selectedGender == "Male",
                onClick = { onSelect("Male") }
            )
            GenderOption(
                symbol = "♀",
                label = "Female",
                selected = selectedGender == "Female",
                onClick = { onSelect("Female") }
            )
        }
    }
}

@Composable
private fun GenderOption(
    symbol: String,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(104.dp)
                .clip(CircleShape)
                .background(if (selected) HydroBuddyColors.gradientEnd else Color(0xFF7DBBE0))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Text(symbol, color = Color.White, fontSize = 46.sp)
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            label,
            color = Color(0xFF184B70),
            style = MaterialTheme.typography.headlineSmall.copy(
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold
            )
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SliderStep(
    title: String,
    valueText: String,
    minLabel: String,
    maxLabel: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit
) {
    val haptic = LocalHapticFeedback.current
    var lastRounded by remember { mutableIntStateOf(value.roundToInt()) }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = buildAnnotatedString {
                withStyle(
                    style = SpanStyle(
                        brush = Brush.horizontalGradient(
                            colors = listOf(HydroBuddyColors.gradientStart, HydroBuddyColors.gradientMiddle, HydroBuddyColors.gradientEnd)
                        )
                    )
                ) {
                    append(title)
                }
            },
            style = MaterialTheme.typography.headlineSmall.copy(
                fontWeight = FontWeight.Bold,
                fontSize = 36.sp
            ),
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(40.dp))
        Text(
            text = valueText,
            color = Color(0xFF184B70),
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, fontSize = 52.sp * 0.55f)
        )
        Spacer(modifier = Modifier.height(24.dp))
        Slider(
            value = value,
            onValueChange = {
                onValueChange(it)
                val rounded = it.roundToInt()
                if (rounded != lastRounded) {
                    lastRounded = rounded
                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
            },
            valueRange = range,
            modifier = Modifier.fillMaxWidth(),
            thumb = {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFF5F8FA))
                )
            },
            track = { sliderState ->
                SliderDefaults.Track(
                    sliderState = sliderState,
                    modifier = Modifier.height(30.dp),
                    colors = SliderDefaults.colors(
                        activeTrackColor = Color(0xFF7DBBE0),
                        inactiveTrackColor = Color(0xFFDCE7F1)
                    )
                )
            },
            colors = SliderDefaults.colors(
                thumbColor = Color(0xFFF8F9FA),
                activeTrackColor = Color(0xFF7DBBE0),
                inactiveTrackColor = Color(0xFFDCE7F1)
            )
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(minLabel, color = Color(0xFF184B70))
            Text(maxLabel, color = Color(0xFF184B70))
        }
    }
}
