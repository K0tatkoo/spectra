package com.n3d.spectra.ui.neu

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.n3d.spectra.ui.theme.LocalPalette
import com.n3d.spectra.ui.theme.Motion
import com.n3d.spectra.ui.theme.Neumorph
import com.n3d.spectra.ui.theme.toComposeColor
import kotlin.math.roundToInt

@Composable
fun NeuCard(
    modifier: Modifier = Modifier,
    radius: Dp = Neumorph.RadiusLg,
    depth: Dp = Neumorph.DepthMd,
    padding: Dp = 14.dp,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .neuRaised(radius, depth)
            .padding(padding),
        content = content,
    )
}

/** A well: the inset counterpart, used for anything that contains a graph. */
@Composable
fun NeuWell(
    modifier: Modifier = Modifier,
    radius: Dp = Neumorph.RadiusMd,
    depth: Dp = Neumorph.DepthSm,
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit,
) {
    Box(modifier.neuInset(radius, depth), content = content)
}

/**
 * Press animation copied from the site's `.btn`: a squash on the way down
 * (narrower than it is shorter), the shadows flipping to inset, then a jelly
 * overshoot on release. Without the overshoot the control feels dead.
 */
@Composable
fun NeuButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String,
    primary: Boolean = false,
    enabled: Boolean = true,
    radius: Dp = Neumorph.RadiusMd,
) {
    val palette = LocalPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.965f else 1f,
        animationSpec = tween(if (pressed) 110 else Motion.JELLY_MS, easing = if (pressed) Motion.Out else Motion.Jelly),
        label = "btnScale",
    )

    Box(
        modifier
            .scale(scale)
            .then(if (pressed && enabled) Modifier.neuInset(radius, Neumorph.DepthSm) else Modifier.neuRaised(radius))
            .then(
                if (primary) Modifier
                    .clip(RoundedCornerShape(radius))
                    .background(
                        Brush.linearGradient(
                            listOf(palette.gradA.toComposeColor(), palette.gradB.toComposeColor()),
                        ),
                    )
                else Modifier,
            )
            .clickableNoRipple(interaction, enabled, onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = when {
                !enabled -> palette.textFaint.toComposeColor()
                primary -> Color.White
                else -> palette.text.toComposeColor()
            },
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
fun NeuIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    glyph: String,
    active: Boolean = false,
    size: Dp = 46.dp,
) {
    val palette = LocalPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.94f else 1f,
        animationSpec = tween(if (pressed) 110 else Motion.JELLY_MS, easing = if (pressed) Motion.Out else Motion.Jelly),
        label = "iconScale",
    )
    Box(
        modifier
            .size(size)
            .scale(scale)
            .then(
                if (pressed || active) Modifier.neuInset(Neumorph.RadiusMd, Neumorph.DepthSm)
                else Modifier.neuRaised(Neumorph.RadiusMd, Neumorph.DepthSm),
            )
            .clickableNoRipple(interaction, true, onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            glyph,
            color = if (active) palette.accent.toComposeColor() else palette.textDim.toComposeColor(),
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * Continuous slider.
 *
 * The knob follows a short tween rather than the raw finger position: the value
 * itself updates immediately (so the DSP reacts on the next block), while the
 * drawn thumb eases in over 140 ms. That is the difference between a control
 * that feels sprung and one that feels like a pixel readout.
 */
@Composable
fun NeuSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    steps: Int = 0,
    label: String? = null,
    valueText: String? = null,
    enabled: Boolean = true,
) {
    val palette = LocalPalette.current
    val density = LocalDensity.current
    val trackHeight = 30.dp
    val thumbSize = 26.dp
    var widthPx by remember { mutableIntStateOf(0) }
    var dragging by remember { mutableStateOf(false) }

    val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f
    val fraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)
    val animatedFraction by animateFloatAsState(
        targetValue = fraction,
        animationSpec = tween(Motion.FAST, easing = Motion.Out),
        label = "sliderFraction",
    )

    Column(modifier) {
        if (label != null || valueText != null) {
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (label != null) {
                    Text(
                        label,
                        color = palette.textDim.toComposeColor(),
                        fontSize = 12.sp,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (valueText != null) {
                    Text(
                        valueText,
                        color = palette.text.toComposeColor(),
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(trackHeight)
                .onSizeChanged { widthPx = it.width }
                .pointerInput(enabled, valueRange, steps, widthPx) {
                    if (!enabled) return@pointerInput
                    awaitEachGesture {
                        val thumbPx = with(density) { thumbSize.toPx() }
                        val usable = (widthPx - thumbPx).coerceAtLeast(1f)
                        fun report(x: Float) {
                            var t = ((x - thumbPx / 2f) / usable).coerceIn(0f, 1f)
                            if (steps > 0) t = (t * steps).roundToInt() / steps.toFloat()
                            onValueChange(valueRange.start + t * span)
                        }
                        val down = awaitFirstDown(requireUnconsumed = false)
                        dragging = true
                        report(down.position.x)
                        horizontalDrag(down.id) { change ->
                            report(change.position.x)
                            change.consume()
                        }
                        dragging = false
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(Modifier.fillMaxWidth().height(trackHeight).neuInset(Neumorph.RadiusPill, 3.dp))

            // Filled portion. Clipped to the track so the accent never spills
            // past the rounded end.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(trackHeight)
                    .clip(RoundedCornerShape(percent = 50))
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(animatedFraction)
                        .background(
                            Brush.horizontalGradient(
                                listOf(palette.gradA.toComposeColor(), palette.accent.toComposeColor()),
                            ),
                        ),
                )
            }

            val thumbOffset = with(density) {
                ((widthPx - thumbSize.toPx()) * animatedFraction).toDp()
            }
            val thumbScale by animateFloatAsState(
                targetValue = if (dragging) 1.12f else 1f,
                animationSpec = tween(Motion.JELLY_MS, easing = Motion.Jelly),
                label = "thumbScale",
            )
            Box(
                Modifier
                    .offset(x = thumbOffset)
                    .size(thumbSize)
                    .scale(thumbScale)
                    .neuRaised(Neumorph.RadiusPill, 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            if (enabled) palette.accent.toComposeColor()
                            else palette.textFaint.toComposeColor(),
                        ),
                )
            }
        }
    }
}

@Composable
fun NeuSwitch(
    checked: Boolean,
    modifier: Modifier = Modifier,
    onCheckedChange: (Boolean) -> Unit,
) {
    val palette = LocalPalette.current
    val width = 52.dp
    val height = 30.dp
    val knob = 22.dp
    val offset by animateDpAsState(
        targetValue = if (checked) width - knob - 4.dp else 4.dp,
        animationSpec = tween(Motion.MID, easing = Motion.Spring),
        label = "switchKnob",
    )
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .width(width)
            .height(height)
            .neuInset(Neumorph.RadiusPill, 3.dp)
            .clickableNoRipple(interaction, true) { onCheckedChange(!checked) },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .offset(x = offset)
                .size(knob)
                .neuRaised(Neumorph.RadiusPill, 3.dp),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(
                        if (checked) palette.accent.toComposeColor()
                        else palette.textFaint.toComposeColor(),
                    ),
            )
        }
    }
}

/**
 * A segmented control whose selection pill slides. Good for up to four options;
 * beyond that the labels stop fitting and [NeuPicker] is the right control.
 */
@Composable
fun <T> NeuSegmented(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    labelOf: (T) -> String,
) {
    val palette = LocalPalette.current
    if (options.isEmpty()) return
    val index = options.indexOf(selected).coerceAtLeast(0)

    BoxWithConstraints(modifier.height(44.dp).neuInset(Neumorph.RadiusPill, 3.dp)) {
        val slot = maxWidth / options.size
        val pillOffset by animateDpAsState(
            targetValue = slot * index,
            animationSpec = tween(Motion.MID, easing = Motion.Spring),
            label = "segPill",
        )
        Box(
            Modifier
                .offset(x = pillOffset)
                .width(slot)
                .fillMaxHeight()
                .padding(4.dp)
                .neuRaised(Neumorph.RadiusPill, 3.dp),
        )
        Row(Modifier.fillMaxWidth().fillMaxHeight()) {
            options.forEach { option ->
                val isSelected = option == selected
                val interaction = remember(option) { MutableInteractionSource() }
                Box(
                    Modifier
                        .width(slot)
                        .fillMaxHeight()
                        .clickableNoRipple(interaction, true) { onSelect(option) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        labelOf(option),
                        color = if (isSelected) palette.accent.toComposeColor() else palette.textDim.toComposeColor(),
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** Dropdown for lists too long to segment — windows, colour maps, pages. */
@Composable
fun <T> NeuPicker(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    labelOf: (T) -> String,
) {
    val palette = LocalPalette.current
    var open by remember { mutableStateOf(false) }
    val interaction = remember { MutableInteractionSource() }

    Column(modifier) {
        if (label != null) {
            Text(
                label,
                color = palette.textDim.toComposeColor(),
                fontSize = 12.sp,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        Box {
            Row(
                Modifier
                    .fillMaxWidth()
                    .neuRaised(Neumorph.RadiusMd, Neumorph.DepthSm)
                    .clickableNoRipple(interaction, true) { open = true }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    labelOf(selected),
                    color = palette.text.toComposeColor(),
                    fontSize = 13.sp,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                Text("▾", color = palette.textFaint.toComposeColor(), fontSize = 13.sp)
            }
            DropdownMenu(
                expanded = open,
                onDismissRequest = { open = false },
                modifier = Modifier.background(palette.bg.toComposeColor()),
            ) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                labelOf(option),
                                color = if (option == selected) palette.accent.toComposeColor()
                                else palette.text.toComposeColor(),
                                fontSize = 13.sp,
                            )
                        },
                        onClick = {
                            onSelect(option)
                            open = false
                        },
                    )
                }
            }
        }
    }
}

/** A pill that is raised when idle and pressed-in when selected. */
@Composable
fun NeuChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = tween(if (pressed) 110 else Motion.JELLY_MS, easing = if (pressed) Motion.Out else Motion.Jelly),
        label = "chipScale",
    )
    Box(
        modifier
            .scale(scale)
            .then(
                if (selected || pressed) Modifier.neuInset(Neumorph.RadiusPill, 3.dp)
                else Modifier.neuRaised(Neumorph.RadiusPill, 3.dp),
            )
            .clickableNoRipple(interaction, true, onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (selected) palette.accent.toComposeColor() else palette.textDim.toComposeColor(),
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
        )
    }
}

/** Bare text that behaves like a button, for dismissals and inline links. */
@Composable
fun NeuTextAction(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color? = null,
) {
    val palette = LocalPalette.current
    val interaction = remember { MutableInteractionSource() }
    Text(
        label,
        color = color ?: palette.textDim.toComposeColor(),
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier
            .clickableNoRipple(interaction, true, onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

@Composable
fun SettingRow(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit,
) {
    val palette = LocalPalette.current
    Row(
        modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = palette.text.toComposeColor(), fontSize = 14.sp)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(subtitle, color = palette.textFaint.toComposeColor(), fontSize = 11.sp, lineHeight = 14.sp)
            }
        }
        trailing()
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    Text(
        text.uppercase(),
        color = palette.textFaint.toComposeColor(),
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.4.sp,
        modifier = modifier.padding(start = 4.dp, top = 18.dp, bottom = 8.dp),
    )
}

/**
 * Neumorphism has no room for a Material ripple — the whole point is that the
 * surface is the same colour as its background, and a ripple paints a grey
 * splash across it. The press state is carried by the shadows instead.
 */
@Composable
private fun Modifier.clickableNoRipple(
    interaction: MutableInteractionSource,
    enabled: Boolean,
    onClick: () -> Unit,
): Modifier = clickable(
    interactionSource = interaction,
    indication = null,
    enabled = enabled,
    onClick = onClick,
)
