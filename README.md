# ConnectX

ConnectX — Android-приложение для локальной обработки сетевого трафика с целью повышения устойчивости соединений к DPI-фильтрации.

## Важное отличие от удалённого VPN

ConnectX не является сервисом удалённого VPN и не перенаправляет пользовательский трафик через сервер разработчика.

На Android без root системный `VpnService` используется только как разрешённый ОС механизм создания локального TUN-интерфейса. Исходящие relay-соединения открываются напрямую через сокеты, исключённые из TUN методом `VpnService.protect()`.

ConnectX не выполняет MITM, не расшифровывает HTTPS, не устанавливает пользовательские сертификаты и не записывает содержимое трафика.

## Текущая версия разработки: v0.3.0-alpha.1

Alpha.1 начинает линию подключаемых стратегий и добавляет ограниченный TLS write-split Lab. Обычный пользовательский трафик этой стратегией не обрабатывается.

### Strategy API

Добавлен отдельный pure Kotlin модуль `:strategy:api`:

- типизированный `BypassStrategy` API;
- capability model для TCP, UDP, IPv4, IPv6, TLS, QUIC и root;
- immutable registry стратегий с уникальными идентификаторами;
- глобальный feature gate, выключенный по умолчанию;
- отдельные `LAB_ONLY` и `USER_TRAFFIC` scopes;
- строгие причины отказа вместо скрытых fallback-заглушек.

Первая стратегия `tls-clienthello-split-v1` принимает только ограниченный синтетический TLS ClientHello. Bounded inspector проверяет TLS record header, тип ClientHello, версии и длины record/handshake, максимальный размер и отсутствие trailing data. План обязан восстанавливать исходный payload байт-в-байт.

### TLS write-split Lab

После отдельного действия пользователя приложение выполняет следующий диагностический путь:

```text
Synthetic TLS ClientHello
  → two ordered Android socket write() calls
  → exact endpoint 192.0.2.1:18443
  → TEST-NET TUN
  → Go/gVisor + tun2socks
  → authenticated local SOCKS5 relay
  → protected loopback echo endpoint
```

Между двумя вызовами `write()` используется ограниченная пауза. Loopback endpoint возвращает собранный TCP stream, после чего приложение проверяет его полное байтовое совпадение с исходным ClientHello. В diagnostics отображаются strategy id, число write-сегментов, split offset, задержка, байты и relay connections.

**Два вызова `write()` не гарантируют два TCP-сегмента или два IP-пакета на проводе.** Ядро ОС и userspace stack могут объединить данные. Alpha.1 подтверждает корректность planner, интеграцию с TUN и сохранность TCP stream, но не заявляет доказанную сетевую сегментацию или рабочий обход DPI.

### Проверенные ограничения planner

- feature gate выключен по умолчанию;
- `USER_TRAFFIC` scope отклоняется;
- повторное планирование уже обработанного payload отклоняется;
- UDP, IPv6, QUIC и неизвестные application protocols отклоняются;
- non-TLS, non-ClientHello, truncated, malformed, trailing и oversized payload отклоняются;
- сегменты хранятся защитными копиями, а reconstructed payload должен совпадать с исходным.

Существующие TCP, UDP и DNS diagnostic paths из `v0.2.0-alpha.6` сохраняются без расширения маршрутов.

### Важное ограничение

TUN перехватывает только зарезервированную сеть `192.0.2.0/24`. TLS strategy override разрешает только точную пару `192.0.2.1:18443` и перенаправляет её на process-local endpoint `127.0.0.1`.

`0.0.0.0/0` и `::/0` не добавляются. Системные DNS-запросы и обычный интернет-трафик телефона не направляются в ConnectX. Внешний DNS resolver, IPv6, QUIC и DPI-модификация реальных соединений не включены.

## Модули

```text
:app                 Compose UI, permission flow и Android instrumentation gates
:core:model          состояния, diagnostics и reducer
:core:designsystem   Material 3 theme
:strategy:api        strategy contracts, capability model, feature gate и lab planner
:vpn:api             межмодульный контракт tunnel lifecycle
:vpn:relay           authenticated SOCKS5 relay, bounded TCP/UDP/DNS endpoints
:vpn:nativebridge    JNI API и Android native payload
:vpn:service         VpnService, TUN, native/probe/strategy-lab lifecycle
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

GitHub Actions запускает Android 35 x86_64 emulator и выполняет отдельные gates:

1. strategy planner внутри установленного APK;
2. TLS write-split Lab через foreground `VpnService` и TEST-NET TUN;
3. JNI lifecycle;
4. TCP path;
5. UDP path;
6. DNS path.

Между сетевыми gates процесс приложения и VPN app-op сбрасываются, а каждый набор instrumentation-результатов сохраняется отдельно.

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
- нет записи содержимого пакетов, TLS payload или DNS query names;
- SOCKS credentials создаются временно и не выводятся в diagnostics;
- strategy feature gate выключен по умолчанию;
- lab strategy не разрешена для user-traffic scope;
- root не является обязательным.

## Статус релиза

Тег `v0.3.0-alpha.1` и GitHub prerelease могут быть созданы только после успешной проверки Go lock, strategy/JVM tests, lint, APK payload и всех изолированных Android gates на точном merge commit.
