# ConnectX

ConnectX — Android-приложение для локальной обработки сетевого трафика с целью повышения устойчивости соединений к DPI-фильтрации.

## Важное отличие от удалённого VPN

ConnectX не является сервисом удалённого VPN и не перенаправляет пользовательский трафик через сервер разработчика.

На Android без root системный `VpnService` используется только как разрешённый ОС механизм создания локального TUN-интерфейса. Исходящие relay-соединения открываются напрямую через сокеты, исключённые из TUN методом `VpnService.protect()`.

ConnectX не выполняет MITM, не расшифровывает HTTPS, не устанавливает пользовательские сертификаты и не записывает содержимое трафика.

## Текущая версия разработки: v0.2.0-alpha.6

Alpha.6 добавляет ограниченный DNS path probe поверх уже проверенного UDP transport. По явному действию пользователя приложение проверяет путь:

```text
Android IPv4 DatagramSocket
  → TEST-NET TUN
  → Go/gVisor + tun2socks
  → authenticated local SOCKS5 UDP relay
  → protected loopback DNS responder
```

Приложение формирует один строгий запрос `A/IN connectx.invalid` со случайным transaction ID и принимает только детерминированный ответ `192.0.2.42`. Локальный responder не выполняет рекурсию и никогда не пересылает запрос наружу.

В интерфейсе показываются задержка, переданные и полученные байты, число UDP associations/datagrams и счётчики DNS-запросов/ответов. После успеха, ошибки, остановки или отзыва VPN-разрешения client socket, native stack, TUN, relay и responder автоматически закрываются.

### Важное ограничение

TUN по-прежнему перехватывает только зарезервированную сеть `192.0.2.0/24`. DNS diagnostic override разрешает только точную пару `192.0.2.53:53` и перенаправляет её на process-local responder `127.0.0.1`.

`0.0.0.0/0` и `::/0` не добавляются. Системные DNS-запросы и обычный интернет-трафик телефона не направляются в ConnectX. Внешний DNS resolver, IPv6, QUIC и DPI-обфускация пока не включены, поэтому рабочий обход блокировок этой версией не заявляется.

## Модули

```text
:app                 Compose UI, permission flow и Android instrumentation gates
:core:model          состояния, diagnostics и reducer
:core:designsystem   Material 3 theme
:vpn:api             межмодульный контракт tunnel lifecycle
:vpn:relay           authenticated SOCKS5 relay, bounded TCP/UDP/DNS endpoints
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

GitHub Actions дополнительно запускает Android 35 x86_64 emulator, загружает реальную `.so` и выполняет изолированные JNI, TCP, UDP и DNS foreground `VpnService` gates. Между сетевыми gates процесс приложения принудительно останавливается, а каждый набор instrumentation-результатов сохраняется отдельно.

## Roadmap

Полный план версий находится в [`ROADMAP.md`](ROADMAP.md). Технические исследования, ADR и release notes находятся в каталоге [`docs/`](docs/).

## Безопасность и приватность

- нет регистрации и аккаунтов;
- нет рекламы и собственной аналитики;
- нет удалённого сервера ConnectX;
- нет внешнего DNS resolver в diagnostic probe;
- нет перехвата системных DNS-запросов;
- нет расшифровки HTTPS;
- нет установки сертификатов;
- нет записи содержимого пакетов или DNS query names;
- SOCKS credentials создаются временно и не выводятся в diagnostics;
- root не является обязательным.

## Статус релиза

Тег `v0.2.0-alpha.6` и GitHub prerelease могут быть созданы только после успешной проверки Go lock, unit tests, lint, APK payload и всех изолированных Android runtime gates на точном merge commit.
