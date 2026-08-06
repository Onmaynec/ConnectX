# ConnectX v0.3.0-alpha.5 scope

## Цель

Завершить alpha-линию первой стратегии безопасным и повторно используемым процессом выпуска. Alpha.5 не расширяет маршрутизацию и не подключает стратегию к обычному пользовательскому трафику.

## Реализуемый объём

- единый JSON release manifest;
- общий `workflow_run` publisher для exact successful Android CI push-run;
- ручной dry-run/preflight;
- строгая проверка workflow/event/branch/SHA;
- version consistency guard для приложения, native bridge, README и changelog;
- negative tests provenance и manifest validation;
- deterministic asset packaging, checksums и provenance;
- идемпотентная проверка существующего prerelease;
- удаление активных version-specific publisher workflows;
- versionCode 12 и `0.3.0-alpha.5`.

## Не входит

- default route;
- production passthrough;
- обработка обычного трафика;
- IPv6 и QUIC;
- автоматический выбор стратегии;
- MITM, расшифровка HTTPS или пользовательский CA;
- утверждение, что стратегия универсально обходит DPI.

## Gates

- release guard unit/negative tests;
- repository metadata consistency;
- Go lock и bridge tests;
- JVM tests;
- Android lint;
- debug APK и legal payload verification;
- Android 35 x86_64 runtime gates;
- exact main CI artifact provenance;
- prerelease target and byte identity verification.
