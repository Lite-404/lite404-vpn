<div align="center">

# Lite 404 VPN

VPN-клиент для Android, Windows, macOS и Linux

<img src="https://img.shields.io/badge/Android-9%2B-3DDC84?style=for-the-badge&logo=android&logoColor=white" alt="Android 9+">
<img src="https://img.shields.io/badge/Kotlin-Compose-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white" alt="Kotlin Compose">
<img src="https://img.shields.io/badge/License-GPLv3-blue?style=for-the-badge" alt="GPLv3">

[Скачать](https://github.com/Lite-404/lite404-vpn/releases/latest) · [Бот @lite404bot](https://t.me/lite404bot)

</div>

## Что умеет

- **Обычный VPN** на ядре Xray. Серверы приходят вместе с подпиской, ничего настраивать вручную не нужно.
- **Резервный VPN** (Android): туннель через звонки VK — включайте, когда другие VPN не подключаются.
- **Умная маршрутизация.** Российские сайты идут напрямую, реклама и торренты блокируются, остальное — через VPN. Правила приходят с сервера. Можно выключить — тогда всё через VPN.
- **Локальная сеть напрямую.** Роутер, принтер, NAS (192.168.x.x и другие частные адреса) доступны при включённом VPN. Настраивается отдельно для основного и резервного VPN.
- **Выбор приложений.** Можно пустить через VPN все приложения, только выбранные или все, кроме выбранных.
- **Вход по токену.** Токен выдаёт бот [@lite404bot](https://t.me/lite404bot). Там же можно управлять устройствами и продлевать подписку.
- **15 языков:** русский, английский, украинский, казахский, узбекский, турецкий, фарси, арабский, китайский, испанский, португальский, немецкий, французский, хинди и индонезийский. Язык берётся из телефона или выбирается в настройках. Для фарси и арабского интерфейс переворачивается справа налево.

## Установка

1. Откройте [последний релиз](https://github.com/Lite-404/lite404-vpn/releases/latest) и скачайте APK:
   - `arm64-v8a` — для большинства современных телефонов;
   - `armeabi-v7a` — для старых 32-битных телефонов;
   - `universal` — если не знаете, какой выбрать.
2. Установите APK. Если телефон попросит, разрешите установку из неизвестных источников.
3. Получите токен в боте [@lite404bot](https://t.me/lite404bot), вставьте его в приложение и нажмите «Войти».

## Компьютер (Windows, macOS, Linux)

В [релизе](https://github.com/Lite-404/lite404-vpn/releases/latest) — архивы `windows-x64`, `macos-apple-silicon`, `macos-intel`, `linux-x64`, `linux-arm64`. Программа открывает окно в браузере, вход — тем же токеном. При подключении включается системный прокси; для остальных программ — HTTP `127.0.0.1:10809` и SOCKS5 `127.0.0.1:10808`. Резервного VPN на компьютере пока нет. Подробности — в `README.txt` внутри архива.

Сборка (Go 1.27, исходники в `desktop/`): `cd desktop && go run ./pack 0.7.0` — архивы для всех систем появятся в `desktop/dist/`.

## Сборка из исходников (Android)

Понадобятся JDK 17 и Android SDK 35.

1. Скачайте `libv2ray.aar` со страницы [Releases](https://github.com/Lite-404/lite404-vpn/releases) и положите его в `app/libs/`. Из-за размера файл не хранится в репозитории.
2. Создайте `local.properties` в корне проекта:
   ```properties
   sdk.dir=/путь/к/android/sdk
   KEYSTORE_FILE=путь/к/вашему.keystore
   KEYSTORE_PASSWORD=...
   KEY_ALIAS=...
   KEY_PASSWORD=...
   ```
   Без ключа APK соберётся с отладочной подписью.
3. Запустите сборку:
   ```bash
   ./gradlew assembleRelease
   ```
   Готовые APK появятся в `app/build/outputs/apk/release/`.

Исходники Go-клиента WDTT лежат в `core/wdtt-client`, скрипты его сборки — в `scripts/`. Уже собранные библиотеки лежат в `app/src/main/jniLibs`.

## Благодарности

- [WDTT](https://github.com/amurcanov/proxy-turn-vk-android) от amurcanov — основа аварийного туннеля.
- [Xray-core](https://github.com/XTLS/Xray-core) — ядро обычного VPN.
- [WireGuard](https://www.wireguard.com/).

## Лицензия

[GNU General Public License v3.0](LICENSE). Проект основан на WDTT, который тоже распространяется под GPLv3, поэтому исходный код открыт на тех же условиях.
