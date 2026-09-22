package com.baining.str.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.baining.str.R

/**
 * 字体策略：
 * - FontCn（春联行书）作全局主字体，覆盖所有 CJK + 部分 Latin
 * - Latin/数字由系统自动 fallback 到 Noto/Roboto（统一、不乱码）
 * - FontEn（Pwhalloween）字形不完整，仅用于 AppTitle 专用标题装饰
 */
val FontCn = FontFamily(
    Font(R.font.font_cn, FontWeight.Normal),
    Font(R.font.font_cn, FontWeight.Medium),
    Font(R.font.font_cn, FontWeight.SemiBold),
    Font(R.font.font_cn, FontWeight.Bold),
)

/**仅供大标题"白柠"装饰用，不要用于正文/标签 */
val FontEnDisplay = FontFamily(
    Font(R.font.font_en, FontWeight.Normal),
    Font(R.font.font_en, FontWeight.Bold),
)

val AppTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontCn,
        fontWeight = FontWeight.Bold,
        fontSize = 34.sp,
        lineHeight = 41.sp,
        letterSpacing = 0.37.sp
    ),
    displayMedium = TextStyle(
        fontFamily = FontCn,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
        letterSpacing = 0.36.sp
    ),
    displaySmall = TextStyle(
        fontFamily = FontCn,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.2.sp
    ),
    headlineLarge = TextStyle(
        fontFamily = FontCn,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.15.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontCn,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.1.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = FontCn,
        fontWeight = FontWeight.Medium,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        letterSpacing = 0.05.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontCn,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontCn,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontCn,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.05.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontCn,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.05.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontCn,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontCn,
        fontWeight = FontWeight.Normal,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.sp
    ),
)
