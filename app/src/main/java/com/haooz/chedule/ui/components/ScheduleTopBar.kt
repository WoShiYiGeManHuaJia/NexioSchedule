package com.haooz.chedule.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.InfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.haooz.chedule.ui.basic.CollapsibleTopAppBar
import com.haooz.chedule.ui.basic.CollapsibleTopAppBarDefaults.CollapsedHeight
import com.haooz.chedule.ui.basic.LiquidTopBarButton
import com.haooz.chedule.ui.basic.ProgressiveBlurTopBar
import com.haooz.chedule.ui.basic.SharedScrollBehavior
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.FastForward
import top.yukonga.miuix.kmp.icon.extended.Background
import top.yukonga.miuix.kmp.icon.extended.ConvertFile
import top.yukonga.miuix.kmp.icon.extended.Reset
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

private val DAY_NAMES = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
private val MM_DD_FORMATTER = DateTimeFormatter.ofPattern("MM/dd")

// 不用 Scaffold paddingValues：随当前 tab 顶栏高度变化，切页时内容位移，
// 叠加 SharedBlur 层 draw 阶段写入滞后一帧，表现为顶部慢一帧就位
internal fun scheduleContentTopPadding(statusBarHeight: Dp): Dp {
    val appBar = CollapsedHeight
    val blurHeight = if (statusBarHeight > 0.dp) 120.dp + statusBarHeight else 160.dp
    val weekRowBottom = statusBarHeight + appBar +
            (if (statusBarHeight > 0.dp) 0.dp else 40.dp) + 40.dp
    val topBar = maxOf(blurHeight, appBar + statusBarHeight, weekRowBottom)
    return (appBar + topBar - 78.dp).coerceAtLeast(0.dp)
}

@Composable
internal fun ScheduleTopBar(
    visible: Boolean,
    pagerCurrentPage: Int,
    currentWeek: Int,
    isHoliday: Boolean,
    isViewingCurrentWeek: Boolean,
    dayRange: List<Int>,
    currentDayOfWeek: Int,
    isCurrentWeek: Boolean,
    weekDates: List<LocalDate>,
    isReorganized: Boolean,
    onBackToCurrentWeek: () -> Unit,
    onOpenSwitchSchedule: () -> Unit,
    onJumpWeek: () -> Unit = {},
    onEnterCustomize: () -> Unit = {},
    // 单人自用版：左上角真刷新按钮
    onRefresh: () -> Unit = {},
    isRefreshing: Boolean = false,
    isTablet: Boolean = false,
    isShiftMode: Boolean = false,
    liquidGlassBackdrop: com.kyant.backdrop.Backdrop?,
    scrollBehavior: SharedScrollBehavior? = null,
    blurResampleKey: Int = 0,
    blurSampleTrack: () -> Float = { 0f },
    // 布局期上报「更多」占位槽的真实位置，供下拉菜单按实际位置定位（避免首帧 inset 未到导致偏位）
    onMoreSlotTop: (Float) -> Unit = {},
    // 上报顶栏滚动材质透明度，供「更多」控件收起态同步渐显渐隐
    onMoreMaterial: (Float) -> Unit = {},
    /**
     * 渐变遮罩色。共享顶栏槽里三根顶栏叠在一起平移淡入淡出，外层主题跟的是
     * **当前 tab**（见 MainActivity 的 `forcedDark`），切页瞬间会变 —— 不显式锁色
     * 就会在「课程表深色 → 设置页浅色」时遮罩整体跳浅。由调用方传入本页锁定的 surface。
     */
    gradientColorOverride: Color? = null,
) {
    if (!visible || liquidGlassBackdrop == null) return

    val titleText = when {
        isHoliday -> "放假中"
        currentWeek < 1 -> "学期未开始"
        else -> "第${pagerCurrentPage + 1}周"
    }

    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val topBarHeight = if (statusBarHeight > 0.dp) 120.dp + statusBarHeight else 160.dp
    // 组合期读 currentHeightPx 会拿到未测量的值，星期行会慢一帧就位；改布局期读
    ProgressiveBlurTopBar(
        backdrop = liquidGlassBackdrop,
        height = topBarHeight,
        resampleKey = blurResampleKey,
        sampleTrack = blurSampleTrack,
    ) {
        Box {
            // 平板：标题避让左侧侧栏后左对齐（手机仍居中）
            val titleRailPadding =
                if (isTablet) tabletNavRailStartPadding().padding(start = 12.dp) else Modifier
            CollapsibleTopAppBar(
                title = titleText,
                showLargeTitle = false,
                showGradientOverlay = true,
                gradientOverlayScrollTriggered = true,
                titleStartAligned = isTablet,
                titleModifier = titleRailPadding,
                modifier = Modifier.zIndex(1f),
                gradientMaskHeight = CollapsedHeight + 110.dp,
                gradientColorOverride = gradientColorOverride,
                scrollBehavior = scrollBehavior,
                // 平板左上角不放刷新按钮（侧栏已有入口）；手机端放真刷新
                startAction = if (isTablet) null else { backdropAlpha, shadowAlpha ->
                    RefreshTopBarButton(backdropAlpha = backdropAlpha)
                },
                onAlphaChanged = { backdrop, _ -> onMoreMaterial(backdrop) },
                endAction = { backdropAlpha, shadowAlpha ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isTablet) {
                            // pad：切换课表/课程管理已在侧栏，右上角直接放跳转周数 + 课表外观
                            LiquidTopBarButton(
                                onClick = onJumpWeek,
                                backdrop = liquidGlassBackdrop,
                                icon = MiuixIcons.Basic.FastForward,
                                contentDescription = "跳转周数",
                                iconSize = 23.dp,
                                backdropAlpha = backdropAlpha,
                                shadowAlpha = shadowAlpha,
                            )
                            LiquidTopBarButton(
                                onClick = onEnterCustomize,
                                backdrop = liquidGlassBackdrop,
                                icon = MiuixIcons.Background,
                                contentDescription = "课表外观",
                                iconSize = 23.dp,
                                backdropAlpha = backdropAlpha,
                                shadowAlpha = shadowAlpha,
                            )
                        } else {
                            if (!isShiftMode) {
                                LiquidTopBarButton(
                                    onClick = {
                                        onOpenSwitchSchedule()
                                    },
                                    backdrop = liquidGlassBackdrop,
                                    icon = MiuixIcons.Normal.ConvertFile,
                                    contentDescription = "课表切换",
                                    iconSize = 27.dp,
                                    backdropAlpha = backdropAlpha,
                                    shadowAlpha = shadowAlpha
                                )
                            }
                            // 「更多」按钮由下拉菜单组件自带（收起态即那颗按钮，唯一一份），这里只占位对齐
                            Spacer(
                                modifier = Modifier
                                    .size(42.dp)
                                    .onGloballyPositioned { onMoreSlotTop(it.positionInRoot().y) }
                            )
                        }
                    }
                }
            )
            DayOfWeekRow(
                dayRange = dayRange,
                currentDayOfWeek = currentDayOfWeek,
                isCurrentWeek = isCurrentWeek,
                weekDates = weekDates,
                isReorganized = isReorganized,
                isTablet = isTablet,
                modifier = Modifier.dayOfWeekTopPadding(statusBarHeight, scrollBehavior)
            )
        }
    }
}

@Composable
private fun DayOfWeekRow(
    dayRange: List<Int>,
    currentDayOfWeek: Int,
    isCurrentWeek: Boolean,
    weekDates: List<LocalDate>,
    isReorganized: Boolean,
    isTablet: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .then(
                if (isTablet) {
                    // 侧栏避让放在原 padding(start=) 位置，避免撑高 dayOfWeekTopPadding
                    Modifier
                        .then(tabletNavRailStartPadding())
                        .padding(horizontal = 24.dp)
                } else {
                    Modifier.padding(end = 2.dp)
                }
            )
    ) {
        Spacer(modifier = Modifier.width(if (isTablet) 56.dp else 36.dp))
        dayRange.forEach { dayOfWeek ->
            val index = dayOfWeek - 1
            val name = DAY_NAMES[index]
            val isToday = dayOfWeek == currentDayOfWeek && isCurrentWeek &&
                (!isReorganized || weekDates.getOrNull(index) == LocalDate.now())

            val todayHighlightColor = Color(0xFF3482FF)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = name,
                        style = MiuixTheme.textStyles.footnote1.copy(
                            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal
                        ),
                        color = if (isToday) todayHighlightColor
                        else MiuixTheme.colorScheme.onSurface
                    )
                    if (weekDates.isNotEmpty() && index < weekDates.size) {
                        val dateText = remember(weekDates[index]) {
                            weekDates[index].format(MM_DD_FORMATTER)
                        }
                        Text(
                            text = dateText,
                            style = MiuixTheme.textStyles.footnote2,
                            color = if (isToday) todayHighlightColor
                            else MiuixTheme.colorScheme.onSurfaceVariantActions
                        )
                    }
                }
            }
        }
    }
}

// 顶栏高度是测量后才写入的状态，组合期读会得 0；布局期读与测量同帧对齐。
// 未测量时用 CollapsedHeight+状态栏兜底（本顶栏 showLargeTitle=false，实测恒为该值）
private fun Modifier.dayOfWeekTopPadding(
    statusBarHeight: Dp,
    scrollBehavior: SharedScrollBehavior?,
): Modifier = layout { measurable, constraints ->
    val statusBarPx = statusBarHeight.roundToPx()
    val barHeightPx = scrollBehavior?.currentHeightPx ?: 0f
    val measuredBarPx = if (barHeightPx > 0f) {
        barHeightPx.roundToInt()
    } else {
        CollapsedHeight.roundToPx() + statusBarPx
    }
    val top = statusBarPx + measuredBarPx + if (statusBarPx > 0) 0 else 40.dp.roundToPx()
    val placeable = measurable.measure(constraints)
    layout(placeable.width, placeable.height + top) {
        placeable.place(0, top)
    }
}

/**
 * 单人自用版：左上角刷新按钮。
 * 图标用 miuix 自带的 Reset；刷新中持续匀速旋转，转完自然归位。
 */
@Composable
private fun RefreshTopBarButton(
    backdropAlpha: Float,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val tintColor = MiuixTheme.colorScheme.onSurface
    var isRefreshing by remember { mutableStateOf(false) }
    val onClick: () -> Unit = {
        if (!isRefreshing) {
            scope.launch {
                isRefreshing = true
                val result =
                    com.haooz.chedule.data.BuiltinCourseSync.sync(context)
                isRefreshing = false
                android.widget.Toast
                    .makeText(context, result.message, android.widget.Toast.LENGTH_SHORT)
                    .show()
            }
        }
    }
    // 不刷新时把一轮时长拉到极长，等于静止，几乎不产生动画开销
    val transition: InfiniteTransition = rememberInfiniteTransition(label = "refreshSpin")
    val spin by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (isRefreshing) 900 else 3_600_000, easing = LinearEasing),
        ),
        label = "refreshSpinAngle",
    )
    val rotation = if (isRefreshing) spin else 0f
    Box(
        modifier = Modifier
            .size(42.dp)
            .graphicsLayer { rotationZ = rotation }
            .clip(CircleShape)
            .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.10f + 0.08f * backdropAlpha))
            .clickable(enabled = !isRefreshing) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Canvas(
            modifier = Modifier.size(24.dp)
        ) {
            val tint = tintColor
            val sw = 2.6f.dp.toPx()
            val r = (size.minDimension - sw) / 2f
            val cx = size.width / 2f
            val cy = size.height / 2f
            val startAng = -60.0
            val sweep = 300.0
            // 主体：留缺口的圆环
            drawArc(
                color = tint,
                startAngle = startAng.toFloat(),
                sweepAngle = sweep.toFloat(),
                useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(cx - r, cy - r),
                size = androidx.compose.ui.geometry.Size(r * 2f, r * 2f),
                style = androidx.compose.ui.graphics.drawscope.Stroke(
                    width = sw,
                    cap = androidx.compose.ui.graphics.StrokeCap.Round
                )
            )
            // 箭头：位于弧末端，沿顺时针切线方向，尺寸放大到能一眼认出
            val endRad = Math.toRadians(startAng + sweep)
            val px = cx + r * Math.cos(endRad).toFloat()
            val py = cy + r * Math.sin(endRad).toFloat()
            val tanRad = Math.toRadians(startAng + sweep + 90.0)
            val tx = Math.cos(tanRad).toFloat()
            val ty = Math.sin(tanRad).toFloat()
            val nx = Math.cos(endRad).toFloat()
            val ny = Math.sin(endRad).toFloat()
            val len = 7.5f.dp.toPx()
            val wid = 4.0f.dp.toPx()
            val arrow = androidx.compose.ui.graphics.Path().apply {
                moveTo(px + tx * len, py + ty * len)
                lineTo(px - tx * len * 0.25f + nx * wid, py - ty * len * 0.25f + ny * wid)
                lineTo(px - tx * len * 0.25f - nx * wid, py - ty * len * 0.25f - ny * wid)
                close()
            }
            drawPath(arrow, tint)
        }
        }
    }
}
