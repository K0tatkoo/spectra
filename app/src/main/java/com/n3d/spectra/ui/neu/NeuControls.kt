package com.n3d.spectra.ui.neu

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
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
 * The sites' `.btn` on a touch screen: raised at rest, pressed into the surface
 * while a finger is on it, raised again when it lifts. Nothing squashes or
 * bounces. The press used to shrink the button and wobble it back on a "jelly"
 * overshoot; every site dropped that for this one motion on 2026-09-18, and
 * Spectra followed on 2026-10-06 so the family moves alike.
 *
 * A primary button is one flat violet (`Palette.accentFill`), never a gradient,
 * and stays violet while pressed: only its shadows move inside.
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
    val down = pressed && enabled

    Box(
        modifier
            // One draw modifier in every state, so the chain keeps its shape and
            // the click handler after it is never rebuilt mid-press.
            .then(
                when {
                    primary -> Modifier.neuFilled(palette.accentFill.toComposeColor(), pressed = down, radius = radius)
                    down -> Modifier.neuInset(radius, Neumorph.DepthSm)
                    else -> Modifier.neuRaised(radius)
                },
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

/**
 * A square button with one of the sites' line icons ([NeuIcons]). Pressed in
 * while held, and kept pressed in while [active], the way a latching switch on
 * a desk would be.
 */
@Composable
fun NeuIconButton(
    onClick: () -> Unit,
    icon: ImageVector,
    contentDescription: String,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    size: Dp = 46.dp,
) {
    val palette = LocalPalette.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier
            .size(size)
            .then(
                if (pressed || active) Modifier.neuInset(Neumorph.RadiusMd, Neumorph.DepthSm)
                else Modifier.neuRaised(Neumorph.RadiusMd, Neumorph.DepthSm),
            )
            .clickableNoRipple(interaction, true, onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = if (active) palette.accent.toComposeColor() else palette.textDim.toComposeColor(),
            modifier = Modifier.size(ICON_SIZE),
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
 *
 * Touching it does nothing. Almost every slider lives on a scrolling settings
 * page, and a finger that lands on one on its way down the page is a scroll,
 * not a setting — the old version set the value on touch-down and then held
 * the gesture, so scrolling past a slider quietly changed it. Now only a
 * sideways drag moves it, and relatively: the knob travels as far as the
 * finger does, from where it was, never jumping to where the finger landed.
 * With a [default], a double tap puts it back, and a small mark on the track
 * shows where that is.
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
    /** Where a double tap puts it back to, in the same units as [value]. */
    default: Float? = null,
) {
    val palette = LocalPalette.current
    val density = LocalDensity.current
    val trackHeight = 30.dp
    val thumbSize = 26.dp
    var widthPx by remember { mutableIntStateOf(0) }
    var dragging by remember { mutableStateOf(false) }
    var lastTapAt by remember { mutableLongStateOf(0L) }

    val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f
    val fraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)
    // Read inside the gesture, which outlives any one recomposition.
    val currentFraction by rememberUpdatedState(fraction)
    val report by rememberUpdatedState(onValueChange)
    val resetTo by rememberUpdatedState(default)
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
                        fun emit(t: Float) {
                            var f = t.coerceIn(0f, 1f)
                            if (steps > 0) f = (f * steps).roundToInt() / steps.toFloat()
                            report(valueRange.start + f * span)
                        }
                        val down = awaitFirstDown(requireUnconsumed = false)
                        // Sideways past the touch slop makes this a drag. The page's
                        // scroll claims vertical movement past the same slop, so
                        // whichever direction the finger really goes wins.
                        val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { change, _ ->
                            change.consume()
                        }
                        if (drag == null) {
                            // Lifted without moving (a tap), or the page took it.
                            val up = currentEvent.changes.firstOrNull { it.id == down.id }
                            val target = resetTo
                            if (up != null && !up.pressed && !up.isConsumed && target != null) {
                                if (up.uptimeMillis - lastTapAt <= DOUBLE_TAP_MS) {
                                    report(target)
                                    lastTapAt = 0L
                                } else {
                                    lastTapAt = up.uptimeMillis
                                }
                            }
                            return@awaitEachGesture
                        }
                        dragging = true
                        var t = (currentFraction + (drag.position.x - down.position.x) / usable).coerceIn(0f, 1f)
                        emit(t)
                        horizontalDrag(drag.id) { change ->
                            t = (t + change.positionChange().x / usable).coerceIn(0f, 1f)
                            emit(t)
                            change.consume()
                        }
                        dragging = false
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(Modifier.fillMaxWidth().height(trackHeight).neuInset(Neumorph.RadiusPill, 3.dp))

            // Filled portion: one flat violet, never a gradient. Clipped to the
            // track so it never spills past the rounded end.
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
                        .background(palette.accentFill.toComposeColor()),
                )
            }

            // Where the default is: a small mark under the knob's path.
            if (default != null) {
                val defaultFraction = ((default - valueRange.start) / span).coerceIn(0f, 1f)
                val markOffset = with(density) {
                    ((widthPx - thumbSize.toPx()) * defaultFraction + thumbSize.toPx() / 2f - 1.dp.toPx()).toDp()
                }
                Box(
                    Modifier
                        .offset(x = markOffset)
                        .size(width = 2.dp, height = 12.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(palette.text.toComposeColor().copy(alpha = 0.35f)),
                )
            }

            val thumbOffset = with(density) {
                ((widthPx - thumbSize.toPx()) * animatedFraction).toDp()
            }
            // The knob grows a little under the finger, so it shows round the
            // fingertip; on the sites' button ease, without a wobble.
            val thumbScale by animateFloatAsState(
                targetValue = if (dragging) 1.12f else 1f,
                animationSpec = tween(Motion.T, easing = Motion.Ease),
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
        // The sites' sliding tab tile: .38s on the shared button ease.
        val pillOffset by animateDpAsState(
            targetValue = slot * index,
            animationSpec = tween(Motion.TILE, easing = Motion.Ease),
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
                Icon(
                    NeuIcons.ChevronDown,
                    contentDescription = null,
                    tint = palette.textFaint.toComposeColor(),
                    modifier = Modifier.size(18.dp),
                )
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
    Box(
        modifier
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

/** A bare icon that behaves like a button, for dismissals: a ghost, no surface. */
@Composable
fun NeuIconAction(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color? = null,
) {
    val palette = LocalPalette.current
    val interaction = remember { MutableInteractionSource() }
    Icon(
        icon,
        contentDescription = contentDescription,
        tint = color ?: palette.textDim.toComposeColor(),
        modifier = modifier
            .clickableNoRipple(interaction, true, onClick)
            .padding(6.dp)
            .size(16.dp),
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

/** Two taps closer together than this reset a slider to its default. */
private const val DOUBLE_TAP_MS = 350L

/** The sites' `.ico`: 1.15em of a 1.1rem icon button, about 20 px. */
private val ICON_SIZE = 20.dp
