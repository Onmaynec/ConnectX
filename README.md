# ConnectX

ConnectX — Android-приложение для локальной обработки сетевого трафика с целью повышения устойчивости соединений к DPI-фильтрации.

## Важное отличие от удалённого VPN

ConnectX не является сервисом удалённого VPN и не перенаправляет пользовательский трафик через сервер разработчика.

На Android без root системный `VpnService` используется только как разрешённый ОС механизм создания локального TUN-интерфейса. Исходящие relay-соединения открываются напрямую через сокеты, исключённые из TUN методом `VpnService.protect()`.

ConnectX не выполняет MITM, не расшифровывает HTTPS, не устанавливает пользовательские сертификаты и не записывает содержимое трафика.

## Текущая версия разработки: v0.3.0-alpha.1

Alpha.1 начинает линию подключаемых стратегий, но **ещё не включает DPI-модификацию пользовательского трафика**.

Добавлен отдельный pure Kotlin модуль `:strategy:api`:

- типизированный `BypassStrategy` API;
- capability model для TCP, UDP, IPv4, IPv6, TLS, QUIC и root;
- immutable registry стратегий с уникальными идентификаторами;
- глобальный feature gate, выключенный по умолчанию;
- запрет применения lab-стратегии к обычному пользовательскому трафику;
- строгие причины отказа вместо скрытых fallback-заглушек.

Первая встроенная lab-стратегия `tls-clienthello-split-v1` только строит план двух последовательных socket writes внутри синтетического TLS ClientHello. Bounded inspector проверяет TLS record header, тип ClientHello, длины record/handshake, максимальный размер и отсутствие trailing data. План обязан восстанавливать исходный payload байт-в-байт.

### Что эта версия подтверждает

- strategy API собирается как отдельный модуль;
- feature flag действительно выключен по умолчанию;
- malformed, truncated, oversized, non-TLS и non-ClientHello payload отклоняются;
- user-traffic scope отклоняется даже при ошибочно включённом общем флаге;
- одинаковый planner проходит JVM tests и отдельный Android instrumentation gate внутри APK.

### Что эта версия не подтверждает

Разделение вызовов `write()` не гарантирует отдельные TCP-пакеты: это зависит от сетевого стека и транспорта. Alpha.1 не заявляет, что ClientHello реально сегментирован на проводе, и не заявляет работающий обход DPI. Подключение стратегии к ограниченному TEST-NET TUN probe и проверка фактического transport behavior будут отдельным следующим gate.

Существующие TCP, UDP и DNS diagnostic paths из `v0.2.0-alpha.6` сохраняются без расширения маршрутов.

### Важное ограничение

TUN по-прежнему перехватывает только зарезервированную сеть `192.0.2.0/24`.

`0.0.0.0/0` и `::/0` не добавляются. Системные DNS-запросы и обычный интернет-трафик телефона не направляются в ConnectX. Внешний DNS resolver, IPv6, QUIC и активная DPI-обфускация не включены.

## Модули

```text
:app                 Compose UI, permission flow и Android instrumentation gates
:core:model          состояния, diagnostics и reducer
:core:designsystem   Material 3 theme
:strategy:api        strategy contracts, capability model, feature gate и lab planner
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

GitHub Actions дополнительно запускает Android 35 x86_64 emulator и выполняет отдельные strategy-foundation, JNI, TCP, UDP и DNS gates. Между сетевыми gates процесс приложения останавливается, а каждый набор instrumentation-результатов сохраняется отдельно.

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
