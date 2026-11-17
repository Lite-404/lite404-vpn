package com.lite404.vpn

import android.content.Context
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.io.IOException
import java.util.Locale

private const val FirstRegionalIndicator = 0x1F1E6
private const val LastRegionalIndicator = 0x1F1FF
private val FlagCache = LruCache<String, ImageBitmap>(32)

private data class FlagMarker(val offset: Int, val country: String)

private fun flagMarker(name: String): FlagMarker? {
    var offset = 0
    while (offset + 3 < name.length) {
        val first = name.codePointAt(offset)
        if (first in FirstRegionalIndicator..LastRegionalIndicator) {
            val second = name.codePointAt(offset + 2)
            if (second in FirstRegionalIndicator..LastRegionalIndicator) {
                val country = charArrayOf(
                    'a' + (first - FirstRegionalIndicator),
                    'a' + (second - FirstRegionalIndicator)
                ).concatToString()
                return FlagMarker(offset, country)
            }
        }
        offset += Character.charCount(first)
    }
    return null
}

internal fun serverLabel(name: String): String {
    val marker = flagMarker(name) ?: return name.trim()
    return name.removeRange(marker.offset, marker.offset + 4).trim().ifEmpty {
        Locale("", marker.country.uppercase(Locale.ROOT)).getDisplayCountry(Locale.forLanguageTag(I18n.lang))
    }
}

private val CountryNames by lazy {
    buildList {
        for (region in Locale.getISOCountries()) {
            val locale = Locale("", region)
            val code = region.lowercase(Locale.ROOT)
            for (language in listOf(Locale.ENGLISH, Locale.forLanguageTag("ru"))) {
                add(locale.getDisplayCountry(language) to code)
            }
        }
        addAll(listOf("USA" to "us", "США" to "us", "UK" to "gb", "ОАЭ" to "ae",
            "UAE" to "ae", "Южная Корея" to "kr", "South Korea" to "kr", "Turkey" to "tr"))
    }.sortedByDescending { it.first.length }
}

private fun flagCountry(name: String): String? {
    flagMarker(name)?.let { return it.country }
    val label = name.trimStart()
    return CountryNames.firstOrNull { (prefix, _) ->
        label.startsWith(prefix, ignoreCase = true) &&
            label.getOrNull(prefix.length)?.isLetterOrDigit() != true
    }?.second
}

private fun loadFlag(context: Context, country: String): ImageBitmap? {
    FlagCache.get(country)?.let { return it }
    val bitmap = try {
        context.assets.open("flags/$country.webp").use { BitmapFactory.decodeStream(it) }
    } catch (_: IOException) {
        null
    } ?: return null
    return bitmap.asImageBitmap().also { FlagCache.put(country, it) }
}

@Composable
internal fun ServerFlag(name: String, diameter: Dp) {
    val context = LocalContext.current
    val bitmap = remember(name, context) { flagCountry(name)?.let { loadFlag(context, it) } }
    Box(Modifier.size(diameter).clip(CircleShape).background(Color(0xFF2B2B2F)), contentAlignment = Alignment.Center) {
        if (bitmap != null) {
            Image(bitmap, contentDescription = null, modifier = Modifier.matchParentSize(), contentScale = ContentScale.Crop)
        } else {
            Canvas(Modifier.size(diameter * .55f)) {
                val color = Color(0xFFB0B0B5)
                val stroke = Stroke(1.1.dp.toPx())
                drawCircle(color, radius = size.minDimension * .46f, style = stroke)
                drawOval(color, topLeft = Offset(size.width * .29f, size.height * .04f),
                    size = Size(size.width * .42f, size.height * .92f), style = stroke)
                drawLine(color, Offset(size.width * .05f, center.y), Offset(size.width * .95f, center.y),
                    strokeWidth = stroke.width)
            }
        }
    }
}
