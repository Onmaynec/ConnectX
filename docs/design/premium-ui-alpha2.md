# ConnectX v0.3.0-alpha.2 — Premium UI redesign

## Источники дизайна

Редизайн выполнен по переданному пакету `connectx-premium-ui.zip` и приложенной PNG-дизайн-системе.

Пакет определяет:

- тёмную палитру `#07090D`, `#0E1117`, `#131720`;
- фиолетовые акценты `#8B5CF6` и `#7C3AED`;
- карточки с тонкой границей и радиусом 14–18 dp;
- нижнюю навигацию: Главная, Маршруты, Статистика, Логи, Настройки;
- отдельные экраны режима, стратегии, диагностики, быстрых действий и информации о приложении;
- честное позиционирование ConnectX как локального инструмента обработки трафика, а не удалённого VPN-сервиса.

## Реально подключено

Следующие элементы используют существующие production-модели и callbacks:

- состояния `OFF`, `PERMISSION_REQUIRED`, `STARTING`, `LOCAL_TUN_ACTIVE`, `STOPPING`, `ERROR`;
- главная кнопка запуска и остановки через `onToggle`;
- native bridge availability, version и ABI;
- TCP, UDP и DNS TEST-NET probes;
- TLS ClientHello write-split Lab;
- A/B/A strategy evaluation и rollback diagnostics;
- реальные byte, latency и relay counters последней Lab-проверки;
- безопасная сводка `ConnectionUiState` на экране логов.

## UI-ready, но пока не подключено к engine/repository

Следующие элементы явно помечены в интерфейсе как UI preview:

- режимы Smart, Balanced, Speed, Stability и Custom;
- выбор Auto / Strategy A / Strategy B / Strategy C;
- per-app маршруты Telegram, YouTube, ChatGPT и других приложений;
- доменные правила;
- статистика обычного пользовательского трафика;
- протокольное распределение TLS / HTTP/2 / QUIC;
- persistent log storage и экспорт;
- автозапуск, настройки уведомлений, выбор акцента и сохранение параметров.

Preview-состояния не передаются в native service, не сохраняются как рабочая конфигурация и не выдаются за реализованный обход.

## Ограничение текущего сетевого ядра

`v0.3.0-alpha.2` сохраняет текущую safety boundary:

- только локальный TEST-NET стенд `192.0.2.0/24`;
- ordinary application traffic не перехватывается;
- нет default routes `0.0.0.0/0` и `::/0`;
- нет удалённых ConnectX-серверов;
- нет MITM, расшифровки HTTPS или пользовательских CA;
- успешные Lab-проверки не являются доказательством обхода блокировок YouTube, Telegram или ChatGPT в реальной сети.

## Compose-структура

- `HomeScreen.kt` — navigation shell и real callback wiring;
- `PremiumUiModels.kt` — immutable UI models, palette и отображение реальных diagnostics;
- `PremiumComponents.kt` — карточки, navigation, rows, selectors и reusable components;
- `DashboardScreen.kt` — главная, power control и реальные последние метрики;
- `RoutesStatisticsScreens.kt` — маршруты preview и честная статистика;
- `LogsSettingsScreens.kt` — безопасная сводка логов, настройки и selection screens;
- `DiagnosticsAboutScreens.kt` — реальные Lab actions, quick actions и About;
- `PremiumPreviews.kt` — OFF, ACTIVE и ERROR previews;
- `ConnectXTheme.kt` — премиальная палитра и системная sans-serif typography.
