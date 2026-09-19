package com.baining.str.ui.theme

import androidx.compose.ui.graphics.Color

//── 流云织梦：附件图片提取的柔和彩色系统 ─────────────────────────────
// 主调四宫格：#BEDDF1 / #F9FEC7 / #F1D1EC / #CEC5EE
val DreamBlue      = Color(0xFFBEDDF1)
val DreamYellow    = Color(0xFFF9FEC7)
val DreamPink      = Color(0xFFF1D1EC)
val DreamPurple    = Color(0xFFCEC5EE)

// 交互与文字：在保留粉蓝梦幻感的同时保证按钮与文字可读
val DreamBlueInk   = Color(0xFF5FAFD0)
val DreamPinkInk   = Color(0xFFD88AC9)
val DreamPurpleInk = Color(0xFF998DCE)
val DreamInk       = Color(0xFF39435A)
val DreamInkStrong = Color(0xFF233044)
val DreamMuted     = Color(0xFF70778A)

// 兼容旧命名：旧界面仍引用 Ios*，实际已统一映射到附件配色
val IosBlue        = DreamBlue
val IosBlueLight   = Color(0xFFDFF1FB)
val IosBlueDark    = DreamBlueInk

// 功能色采用同一低饱和、糖霜质感
val IosGreen       = Color(0xFF83CDAE)
val IosRed         = Color(0xFFD86A92)
val IosOrange      = Color(0xFFE8B86D)
val IosYellow      = DreamYellow
val IosPurple      = DreamPurple
val IosTeal        = DreamBlueInk

// 灰度系改为蓝紫灰，避免与柔彩背景割裂
val IosGray1       = Color(0xFF7A8192)
val IosGray2       = Color(0xFF9AA1B2)
val IosGray3       = Color(0xFFC7CBD8)
val IosGray4       = Color(0xFFDDE2EC)
val IosGray5       = Color(0xFFEFF2F8)
val IosGray6       = Color(0xFFF8F7FC)

// 液态玻璃效果色
val GlassWhite     = Color(0xE6FFFFFF)
val GlassDark      = Color(0xD9272638)
val GlassStroke    = Color(0x66FFFFFF)
val GlassShadow    = Color(0x1F39435A)

// 背景渐变（深色模式）
val BgDark1        = Color(0xFF1B1B2A)
val BgDark2        = Color(0xFF25243A)
val BgDark3        = Color(0xFF30314B)

// 背景渐变（浅色模式）
val BgLight1       = Color(0xFFFFF8FE)
val BgLight2       = Color(0xFFF9FEC7)
val BgLight3       = Color(0xFFEAF7FF)

// 卡片
val CardDark       = Color(0xFF272638)
val CardLight      = Color(0xFFFFFDFF)
