package com.hydrobuddy.bt

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

private val GradientStart = Color(0xFF90CDF3)
private val GradientMiddle = Color(0xFF71AED5)
private val GradientEnd = Color(0xFF338AB9)
private val BarFill = Color(0xFF8DC3E5)
private val BarBackground = Color(0xFFE6EFF5)
private val ButtonColor = Color(0xFF338AB8)
private val MainText = Color(0xFF1B4F72)
private val ScreenBackground = Color(0xFFF2F5F8)
private val MutedLine = Color(0xFFD8E4ED)
private val TimeText = Color(0x70144360)
private val ParticleColor = Color(0xFF8DC3E5)

enum class TopTab { Buddy, History }

@Composable
fun HydroBuddyHomeScreen(
    onOpenSettings: () -> Unit,
    entries: List<LogEntry>,
    snapshot: BuddySnapshot,
    feedbackTick: Int,
    lastGain: Int,
    onSip: () -> Unit,
    onPreset: (PresetDrink) -> Unit,
    onEditEntry: (entryId: String, type: LogEntryType, sipCount: Int?, preset: PresetDrink?) -> Unit,
    onDeleteEntry: (String) -> Unit
) {
    var selectedTab by remember { mutableStateOf(TopTab.Buddy) }
    var editTarget by remember { mutableStateOf<LogEntry?>(null) }

    Surface(modifier = Modifier.fillMaxSize(), color = ScreenBackground) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = ScreenHorizontalPadding, vertical = ScreenTopPadding),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Header(title = "HydroBuddy", onSettingsClick = onOpenSettings)

            TopTabSwitcher(selectedTab = selectedTab, onSelect = { selectedTab = it })

            if (selectedTab == TopTab.Buddy) {
                BuddyTab(
                    snapshot = snapshot,
                    feedbackTick = feedbackTick,
                    lastGain = lastGain,
                    onSip = onSip,
                    onPreset = onPreset
                )
            } else {
                HistoryTab(entries = entries, onTapEntry = { editTarget = it })
            }
        }
    }

    val target = editTarget
    if (target != null) {
        EntryEditSheet(
            entry = target,
            onDismiss = { editTarget = null },
            onSave = { type, sipCount, preset ->
                onEditEntry(target.id, type, sipCount, preset)
                editTarget = null
            },
            onDelete = {
                onDeleteEntry(target.id)
                editTarget = null
            }
        )
    }
}

@Composable
private fun Header(title: String, onSettingsClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = buildAnnotatedString {
                withStyle(
                    style = SpanStyle(
                        brush = Brush.horizontalGradient(
                            colors = listOf(GradientStart, GradientMiddle, GradientEnd)
                        )
                    )
                ) { append(title) }
            },
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold)
        )
        Box(
            modifier = Modifier
                .size(HeaderIconSize)
                .clip(CircleShape)
                .clickable(onClick = onSettingsClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Outlined.Settings, contentDescription = "Settings", tint = GradientEnd)
        }
    }
}

@Composable
private fun TopTabSwitcher(selectedTab: TopTab, onSelect: (TopTab) -> Unit) {
    val gradient = Brush.horizontalGradient(listOf(GradientStart, GradientMiddle, GradientEnd))
    val density = LocalDensity.current
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(gradient)
            .padding(3.dp)
    ) {
        val maxOffsetPx = with(density) { (maxWidth / 2).toPx() - 3.dp.toPx() }
        val selectedOffsetPx = if (selectedTab == TopTab.Buddy) 0f else maxOffsetPx
        var dragOffsetPx by remember(selectedTab, maxOffsetPx) { mutableStateOf(selectedOffsetPx) }
        val animatedOffsetPx by animateFloatAsState(
            targetValue = dragOffsetPx,
            animationSpec = tween(220),
            label = "tab_offset"
        )

        Box(modifier = Modifier.matchParentSize()) {
            Box(
                modifier = Modifier
                    .offset { IntOffset(animatedOffsetPx.roundToInt(), 0) }
                    .fillMaxWidth(0.5f)
                    .height(42.dp)
                    .clip(RoundedCornerShape(999.dp))
                    .background(Color.White)
                    .draggable(
                        orientation = Orientation.Horizontal,
                        state = rememberDraggableState { delta ->
                            dragOffsetPx = (dragOffsetPx + delta).coerceIn(0f, maxOffsetPx)
                        },
                        onDragStopped = {
                            val toHistory = dragOffsetPx > (maxOffsetPx / 2f)
                            val next = if (toHistory) TopTab.History else TopTab.Buddy
                            onSelect(next)
                            dragOffsetPx = if (next == TopTab.History) maxOffsetPx else 0f
                        }
                    )
            )
            Row(modifier = Modifier.fillMaxSize()) {
                TabLabel("Buddy", selectedTab == TopTab.Buddy, Modifier.weight(1f)) {
                    onSelect(TopTab.Buddy); dragOffsetPx = 0f
                }
                TabLabel("History", selectedTab == TopTab.History, Modifier.weight(1f)) {
                    onSelect(TopTab.History); dragOffsetPx = maxOffsetPx
                }
            }
        }
    }
}

@Composable
private fun TabLabel(text: String, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (active) GradientEnd else Color.White,
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 24.sp, fontWeight = FontWeight.Bold)
        )
    }
}

@Composable
private fun BuddyTab(
    snapshot: BuddySnapshot,
    feedbackTick: Int,
    lastGain: Int,
    onSip: () -> Unit,
    onPreset: (PresetDrink) -> Unit
) {
    Spacer(modifier = Modifier.height(8.dp))

    BuddyGauge(
        health = snapshot.health,
        healthFraction = snapshot.healthFraction,
        mood = snapshot.mood,
        feedbackTick = feedbackTick,
        lastGain = lastGain
    )

    Spacer(modifier = Modifier.height(4.dp))

    Button(
        onClick = onSip,
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .shadow(elevation = 12.dp, shape = RoundedCornerShape(999.dp)),
        shape = RoundedCornerShape(999.dp),
        colors = ButtonDefaults.buttonColors(containerColor = ButtonColor)
    ) {
        Text(
            "I took a sip",
            color = Color.White,
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold)
        )
    }

    Spacer(modifier = Modifier.height(4.dp))

    PresetGrid(onPreset = onPreset)
}

@Composable
private fun BuddyGauge(
    health: Int,
    healthFraction: Float,
    mood: BuddyMood,
    feedbackTick: Int,
    lastGain: Int
) {
    val animatedFraction by animateFloatAsState(
        targetValue = healthFraction,
        animationSpec = tween(durationMillis = 700, easing = FastOutSlowInEasing),
        label = "buddy_health_fill"
    )

    val bounce = remember { Animatable(1f) }
    val particleProgress = remember { Animatable(0f) }
    val labelProgress = remember { Animatable(0f) }
    var displayedGain by remember { mutableIntStateOf(0) }

    LaunchedEffect(feedbackTick) {
        if (feedbackTick == 0) return@LaunchedEffect
        displayedGain = lastGain
        bounce.snapTo(1f)
        particleProgress.snapTo(0f)
        labelProgress.snapTo(0f)
        coroutineScope {
            launch {
                bounce.animateTo(1.18f, tween(160, easing = FastOutSlowInEasing))
                bounce.animateTo(1f, tween(220, easing = FastOutSlowInEasing))
            }
            launch {
                particleProgress.animateTo(1f, tween(durationMillis = 750, easing = FastOutSlowInEasing))
            }
            launch {
                labelProgress.animateTo(1f, tween(durationMillis = 900, easing = FastOutSlowInEasing))
            }
        }
    }

    val context = LocalContext.current
    val mascotName = buddyMascotState(health)
    val mascotResId = remember(mascotName) {
        context.resources.getIdentifier(mascotName, "drawable", context.packageName)
            .takeIf { it != 0 }
            ?: context.resources.getIdentifier("state1", "drawable", context.packageName)
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(310.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(250.dp)) {
            val stroke = 34.dp.toPx()
            drawArc(
                color = BarBackground,
                startAngle = 140f,
                sweepAngle = 260f,
                useCenter = false,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
            drawArc(
                color = BarFill,
                startAngle = 140f,
                sweepAngle = (260f * animatedFraction),
                useCenter = false,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
        }

        ParticleBurst(progress = particleProgress.value, modifier = Modifier.size(260.dp))

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (mascotResId != 0) {
                Image(
                    painter = painterResource(id = mascotResId),
                    contentDescription = "Buddy",
                    modifier = Modifier
                        .size(120.dp)
                        .scale(bounce.value)
                        .padding(bottom = 4.dp)
                )
            }
            Text(
                text = health.toString(),
                style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold, fontSize = 56.sp),
                color = MainText
            )
            Text(
                text = mood.label,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium),
                color = MainText.copy(alpha = 0.75f)
            )
        }

        if (displayedGain > 0 && labelProgress.value > 0f && labelProgress.value < 1f) {
            val alpha = (1f - labelProgress.value).coerceIn(0f, 1f)
            val yOffsetDp = (-40 - (labelProgress.value * 60f)).dp
            Text(
                text = "+$displayedGain health",
                color = Color(0xFF2E7D32).copy(alpha = alpha),
                style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold),
                modifier = Modifier.offset(y = yOffsetDp)
            )
        }
    }
}

@Composable
private fun ParticleBurst(progress: Float, modifier: Modifier = Modifier) {
    if (progress <= 0f || progress >= 1f) {
        Box(modifier = modifier)
        return
    }
    val seed = remember {
        List(12) { idx ->
            val angle = (idx * (360f / 12f)) + (idx % 3) * 7f
            val distance = 70f + (idx % 4) * 18f
            Triple(angle, distance, 4f + (idx % 3) * 2f)
        }
    }
    Canvas(modifier = modifier) {
        val centerX = size.width / 2f
        val centerY = size.height / 2f
        seed.forEach { (angle, distance, baseSize) ->
            val rad = Math.toRadians(angle.toDouble())
            val dx = (cos(rad) * distance * progress).toFloat()
            val dy = (sin(rad) * distance * progress).toFloat() - (progress * 12f)
            val alpha = (1f - progress).coerceIn(0f, 1f)
            val scale = 0.6f + progress * 0.7f
            drawCircle(
                color = ParticleColor.copy(alpha = alpha),
                radius = baseSize * scale,
                center = Offset(centerX + dx, centerY + dy)
            )
        }
    }
}

@Composable
private fun PresetGrid(onPreset: (PresetDrink) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        PresetDrink.entries.forEach { preset ->
            PresetButton(preset = preset, modifier = Modifier.weight(1f), onClick = { onPreset(preset) })
        }
    }
}

@Composable
private fun PresetButton(preset: PresetDrink, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFFE6EFF5))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp, horizontal = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = preset.label,
                color = MainText,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            )
            Text(
                text = "${preset.amountMl} mL",
                color = MainText.copy(alpha = 0.65f),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp)
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "+${preset.healthGain}",
                color = Color(0xFF2E7D32),
                style = MaterialTheme.typography.titleSmall.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold)
            )
        }
    }
}

@Composable
private fun HistoryTab(entries: List<LogEntry>, onTapEntry: (LogEntry) -> Unit) {
    val dayFormatter = remember { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM) }
    val timeFormatter = remember { DateTimeFormatter.ofPattern("HH:mm") }
    val grouped = entries
        .sortedByDescending { it.timestampMillis }
        .groupBy {
            Instant.ofEpochMilli(it.timestampMillis).atZone(ZoneId.systemDefault()).toLocalDate()
        }
        .toList()
        .sortedByDescending { it.first }

    if (grouped.isEmpty()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp),
            contentAlignment = Alignment.Center
        ) {
            Text("Nothing logged yet", color = GradientEnd, fontWeight = FontWeight.Medium)
        }
        return
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        grouped.forEach { (date, dayEntries) ->
            item(key = "header_$date") {
                Text(
                    text = formatHistoryDate(date, dayFormatter),
                    color = MainText,
                    style = MaterialTheme.typography.titleSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
                    modifier = Modifier.padding(top = 10.dp, bottom = 6.dp)
                )
            }
            items(dayEntries, key = { it.id }) { entry ->
                HistoryRow(
                    entry = entry,
                    timeText = Instant.ofEpochMilli(entry.timestampMillis)
                        .atZone(ZoneId.systemDefault())
                        .toLocalTime()
                        .format(timeFormatter),
                    onClick = { onTapEntry(entry) }
                )
            }
        }
    }
}

private fun formatHistoryDate(date: LocalDate, formatter: DateTimeFormatter): String {
    val today = LocalDate.now()
    return when {
        date == today -> "Today, ${date.format(formatter)}"
        date == today.minusDays(1) -> "Yesterday, ${date.format(formatter)}"
        else -> date.format(formatter)
    }
}

@Composable
private fun HistoryRow(entry: LogEntry, timeText: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entryTitle(entry),
                color = MainText,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            )
            Text(
                text = entrySubtitle(entry, timeText),
                color = TimeText,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.sp)
            )
        }
        Text(
            text = "+${entry.healthGain} health",
            color = Color(0xFF2E7D32),
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold)
        )
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MutedLine)
    )
}

private fun entryTitle(entry: LogEntry): String = when (entry.type) {
    LogEntryType.Sip -> {
        val count = entry.sipCount ?: 1
        if (count == 1) "1 sip" else "$count sips"
    }
    LogEntryType.Preset -> {
        val preset = entry.amountMl?.let { PresetDrink.fromAmount(it) }
        preset?.label ?: "Drink"
    }
}

private fun entrySubtitle(entry: LogEntry, timeText: String): String = when (entry.type) {
    LogEntryType.Sip -> timeText
    LogEntryType.Preset -> {
        val ml = entry.amountMl
        if (ml != null) "$ml mL · $timeText" else timeText
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntryEditSheet(
    entry: LogEntry,
    onDismiss: () -> Unit,
    onSave: (LogEntryType, Int?, PresetDrink?) -> Unit,
    onDelete: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var sipCount by remember(entry.id) { mutableIntStateOf(entry.sipCount ?: 1) }
    val initialPreset = entry.amountMl?.let { PresetDrink.fromAmount(it) }
    var selectedPreset by remember(entry.id) { mutableStateOf(initialPreset) }
    var selectedType by remember(entry.id) { mutableStateOf(entry.type) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFFF7FAFD)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Edit entry",
                color = MainText,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 14.sp, fontWeight = FontWeight.Bold),
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )

            SipStepperRow(
                count = sipCount,
                isActive = selectedType == LogEntryType.Sip,
                onActivate = {
                    selectedType = LogEntryType.Sip
                    selectedPreset = null
                },
                onDecrement = { sipCount = (sipCount - 1).coerceAtLeast(1) },
                onIncrement = { sipCount += 1 }
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                PresetDrink.entries.forEach { preset ->
                    val active = selectedType == LogEntryType.Preset && selectedPreset == preset
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (active) GradientEnd else Color(0xFFE6EFF5))
                            .clickable {
                                selectedType = LogEntryType.Preset
                                selectedPreset = preset
                            }
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "${preset.amountMl} mL",
                                color = if (active) Color.White else MainText,
                                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            )
                            Text(
                                text = "+${preset.healthGain}",
                                color = if (active) Color.White else Color(0xFF2E7D32),
                                style = MaterialTheme.typography.titleSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onDelete,
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp),
                    shape = RoundedCornerShape(999.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD95C5C))
                ) {
                    Text("Delete", color = Color.White)
                }
                Button(
                    onClick = {
                        val type = selectedType
                        if (type == LogEntryType.Preset && selectedPreset == null) return@Button
                        onSave(
                            type,
                            if (type == LogEntryType.Sip) sipCount else null,
                            if (type == LogEntryType.Preset) selectedPreset else null
                        )
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(46.dp),
                    shape = RoundedCornerShape(999.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = ButtonColor)
                ) {
                    Text("Save", color = Color.White)
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
        }
    }
}

@Composable
private fun SipStepperRow(
    count: Int,
    isActive: Boolean,
    onActivate: () -> Unit,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (isActive) GradientEnd else Color(0xFFE6EFF5))
            .clickable(onClick = onActivate)
            .padding(vertical = 10.dp, horizontal = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Sips",
            color = if (isActive) Color.White else MainText,
            style = MaterialTheme.typography.titleSmall.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            StepperButton(symbol = "−", enabled = isActive && count > 1, onClick = onDecrement, isActive = isActive)
            Text(
                text = count.toString(),
                color = if (isActive) Color.White else MainText,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold),
                textAlign = TextAlign.Center
            )
            StepperButton(symbol = "+", enabled = isActive, onClick = onIncrement, isActive = isActive)
        }
    }
}

@Composable
private fun StepperButton(symbol: String, enabled: Boolean, isActive: Boolean, onClick: () -> Unit) {
    val bg = when {
        !isActive -> Color(0xFFD9E5EE)
        enabled -> Color.White
        else -> Color.White.copy(alpha = 0.4f)
    }
    val fg = when {
        !isActive -> MainText.copy(alpha = 0.5f)
        enabled -> GradientEnd
        else -> GradientEnd.copy(alpha = 0.4f)
    }
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = symbol,
            color = fg,
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 16.sp, fontWeight = FontWeight.Bold)
        )
    }
}
