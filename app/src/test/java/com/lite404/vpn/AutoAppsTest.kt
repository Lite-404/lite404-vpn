package com.lite404.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoAppsTest {
    @Test
    fun matchesRussianServices() {
        for ((pkg, label) in listOf(
            "ru.yandex.yandexmaps" to "Яндекс Карты", "ru.yandex.taxi" to "Yandex Go", "com.vkontakte.android" to "VK",
            "ru.ok.android" to "OK", "ru.oneme.app" to "MAX", "com.wildberries.ru" to "Wildberries", "ru.ozon.app.android" to "Ozon",
            "ru.beeline.services" to "Мой Билайн", "ru.yota.android" to "Yota", "ru.rostel" to "Госуслуги",
            "com.example.x5" to "Пятёрочка: доставка продуктов", "com.example.p" to "Перекрёсток",
            "com.example.r" to "Rostic's", "com.example.v" to "Вкусно — и точка", "ru.rzd.pass" to "РЖД Пассажирам",
        )) assertTrue("$pkg $label", AutoApps.matches(pkg, label))
    }

    @Test
    fun skipsBrowsersAndOthers() {
        for ((pkg, label) in listOf(
            "com.yandex.browser" to "Яндекс Браузер", "ru.yandex.searchplugin" to "Яндекс с Алисой", "org.telegram.messenger" to "Telegram",
            "com.google.android.youtube" to "YouTube", "com.example.radio" to "Магнитола FM", "com.android.chrome" to "Chrome",
            "com.whatsapp" to "WhatsApp",
        )) assertFalse("$pkg $label", AutoApps.matches(pkg, label))
    }
}
