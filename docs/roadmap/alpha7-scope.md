# v0.3.0-alpha.7 — Physical Device Evidence Kit

## Цель

Перевести оставшиеся physical-device блокеры #11 и #22 из неформальной инструкции в воспроизводимый readiness workflow с bounded FD evidence.

## Входит

- broad environment profile без уникальных идентификаторов;
- FD before/after/delta после полного evidence teardown;
- typed FD budget assessment;
- report schema v3 и deterministic report ID;
- три последовательные Android TUN/native evidence-сессии;
- strict physical arm64 collector и bundle validator;
- runbook ручной restricted-network проверки.

## Не входит

- default route;
- обычный пользовательский трафик;
- автоматическое включение стратегии;
- удалённый сервер;
- HTTP, MITM или расшифровка HTTPS;
- автоматическое заявление о подтверждённом bypass;
- закрытие #11 или #22 без фактического physical-device результата.

## Release gate

Релиз публикуется только после полного Android CI на exact `main` commit. Physical collector поставляется и тестируется статически, но реальный physical arm64 bundle должен быть создан отдельно владельцем устройства.
