# ConnectX

ConnectX — Android-приложение для локальной обработки сетевого трафика с целью повышения устойчивости соединений к DPI-фильтрации.

## Важное отличие от удалённого VPN

ConnectX не является сервисом удалённого VPN и не перенаправляет пользовательский трафик через сервер разработчика.

На Android без root системный `VpnService` используется только как разрешённый ОС механизм создания локального TUN-интерфейса. Исходящие relay-соединения открываются напрямую через сокеты, исключённые из TUN методом `VpnService.protect()`.

ConnectX не выполняет MITM, не расшифровывает HTTPS, не устанавливает пользовательские сертификаты и не записывает содержимое трафика.

## Текущая версия: v0.2.0-alpha.4

Alpha.4 добавляет ограниченный end-to-end TCP probe. По явному действию пользователя приложение проверяет путь:

```text
Android socket
  → TEST-NET TUN
  → Go/gVisor + tun2socks
  → authenticated local SOCKS5 relay
  → loopback echo endpoint
```

Probe отправляет случайный 64-байтовый nonce, проверяет точный echo-ответ и показывает задержку, число переданных байтов и relay-соединений. После успеха, ошибки, остановки или отзыва VPN-разрешения все ресурсы автоматически закрываются.

### Важное ограничение

TUN по-прежнему перехватывает только зарезервированную сеть `192.0.2.0/24`. Диагностический relay override действует только для `192.0.2.1:18080` и перенаправляет его на process-local endpoint `127.0.0.1`.

`0.0.0.0/0` и `::/0` не добавляются. Обычный интернет-трафик телефона не направляется в ConnectX. DNS, UDP, IPv6, QUIC и DPI-обфускация пока не реализованы, поэтому рабочий обход блокировок этой версией не заявляется.

## Модули

```text
:app                 Compose UI, permission flow и Android instrumentation probe
:core:model          состояния, diagnostics и reducer
:core:designsystem   Material 3 theme
:vpn:api             межмодульный контракт tunnel lifecycle
:vpn:relay           authenticated SOCKS5 relay и bounded echo endpoint
:vpn:nativebridge    JNI API и Android native payload
:vpn:service         VpnService, TUN, native/probe lifecycle
engine/go            source-built tun2socks/gVisor bridge
```

## Сборка

Требования:

- JDK 17;
- Go 1.26.3;
- Android SDK 36;
- Android NDK `28.0.13004108`;
- Gradle 8.13.

```bash
gradle --no-daemon test
gradle --no-daemon lintDebug
gradle --no-daemon :app:assembleDebug
```

Native bridge собирается скриптом:

```bash
engine/go/build-android.sh "$ANDROID_SDK_ROOT/ndk/28.0.13004108"
```

APK будет находиться в:

```text
app/build/outputs/apk/debug/app-debug.apk
```

GitHub Actions дополнительно запускает Android 35 x86_64 emulator, загружает реальную `.so`, выполняет JNI smoke-test и полный `VpnService` TCP probe.

## Roadmap

Полный план версий находится в [`ROADMAP.md`](ROADMAP.md). Технические исследования, ADR и release notes находятся в каталоге [`docs/`](docs/).

## Безопасность и приватность

- нет регистрации и аккаунтов;
- нет рекламы и собственной аналитики;
- нет удалённого сервера ConnectX;
- нет расшифровки HTTPS;
- нет установки сертификатов;
- нет записи содержимого пакетов;
- SOCKS credentials создаются временно и не выводятся в diagnostics;
- root не является обязательным.

## Статус релиза

Тег `v0.2.0-alpha.4` и GitHub prerelease создаются только после успешной проверки Go lock, unit tests, lint, APK payload и полного Android runtime probe на точном merge commit.
