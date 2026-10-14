# Консольный агент — уровень 9

Учебный Kotlin/JVM-проект: модель выбирает инструменты, а собственный runtime
проверяет policy, исполняет действия, сохраняет состояние и ждёт подтверждений.
Включены agent loop, Bash, файловые инструменты, safe/auto, восстановление, Plan Mode, бюджеты и задачи с зависимостями.
Журнал/откат уровня 10 не реализован.

## Требования и сборка

- JDK 17 или новее; для компиляции Gradle должен находить JDK 17.
- Bash и Python 3 для контрольной задачи. Windows: запускайте проект в WSL.
- При первой сборке нужен доступ к Gradle Plugin Portal и Maven Central.
- Для настоящего агента — доступный OpenAI-compatible Chat Completions API с tool calling.

Зафиксированы Kotlin 2.2.20, Gradle Wrapper 9.7.1, kotlinx.serialization-json 1.9.0
и JUnit 6.0.0. Для стиля подключены ktlint 1.8.0 и Gradle-плагин 14.2.0.
Готовых агентских фреймворков нет.

```bash
./gradlew ktlintCheck test installDist
build/install/ai-agents/bin/ai-agents help
```

Если Gradle запущен другой Java и не находит JDK 17, задайте `JAVA_HOME` или
укажите установленный JDK 17 в Gradle JVM/toolchain настройках IntelliJ IDEA.
Откройте эту папку как Gradle-проект; исходники находятся в `src/main/kotlin`.

## Настоящая модель

Задайте параметры своего доступного сервиса. Ключ не записывается в state и
не передаётся процессам Bash. Не добавляйте настоящий ключ в Git.

```bash
export OPENAI_BASE_URL='https://YOUR_PROVIDER/v1/'
export OPENAI_API_KEY='YOUR_KEY'
export OPENAI_MODEL='YOUR_TOOL_CALLING_MODEL'
build/install/ai-agents/bin/ai-agents start --mode auto \
  --task 'Исправь расчёт итоговой стоимости заказа, чтобы тесты проходили. Тесты не меняй. Сам найди нужные файлы, внеси исправление и запусти тесты.'
```

По умолчанию используются `./workspace`, `./policy.json`, `./.runs`, режим `safe`.
Можно задать `--workspace PATH --policy PATH --state-dir PATH`.
Базовый URL должен заканчиваться префиксом API (например `/v1/`), клиент
добавляет `/chat/completions`. Он передаёт `tools`, `tool_choice: auto`, JSON Schema,
сообщения `role: tool` и исходный `tool_call_id`.
Автоматический повтор HTTP-запросов не выполняется. После транспортной ошибки
история сохранена; команда `resume` повторяет запрос к модели.

Если нет другого доступа, запасной вариант из задания — Ollama:

```bash
ollama pull qwen3:4b
ollama serve
```

В другом терминале:

```bash
export OPENAI_BASE_URL='http://localhost:11434/v1/'
export OPENAI_API_KEY='ollama'
export OPENAI_MODEL='qwen3:4b'
build/install/ai-agents/bin/ai-agents start --mode auto
```

Настоящие внешние модели не участвуют в автоматических тестах. HTTP-контракт
проверяется локальным тестовым сервером; работа конкретного провайдера зависит
от его доступности и поддержки этого протокола.

## Запуск с локальной GPT-OSS

Откройте Ollama и скачайте модель, если она ещё не загружена:

```bash
ollama pull gpt-oss:20b
```

Запустите самого Kotlin-агента напрямую через Gradle:

```bash
./gradlew runLocal
```

Задача Gradle сама задаёт локальный URL, модель gpt-oss:20b, ключ-заглушку ollama
и reasoning_effort=low. Python-обёртки для запуска нет. По умолчанию используется
safe: агент останавливается перед записью или Bash и печатает run_id.

```bash
./gradlew runLocal --args="approve RUN_ID"
./gradlew runLocal --args="reject RUN_ID --reason 'Не изменять файл'"
./gradlew runLocal --args="resume RUN_ID"
./gradlew runLocal --args="show RUN_ID"
```

Для Plan Mode или исполнения auto:

```bash
./gradlew runLocal --args="start --plan"
./gradlew runLocal --args="approve-plan RUN_ID"
./gradlew runLocal --args="start --mode auto"
```

Для другого workspace используйте `--workspace PATH --policy PATH --state-dir PATH`.
Для другой скачанной модели: `./gradlew runLocal -PlocalModel=qwen3:14b`.

Python 3 требуется для тестов контрольного проекта workspace/test_order.py,
которые агент запускает через Bash. Сам агент написан и запускается на Kotlin/JVM.

### Отдельная интеграционная проверка

Необязательный скрипт проверки с отчётом:

```bash
./gradlew ktlintCheck test installDist
python3 scripts/verify_local_model.py --model gpt-oss:20b
```

Этот скрипт готовит отдельную копию workspace и запускает собранный Kotlin-агент
в режиме auto. Он проверяет исправление order.py, фактический запуск Bash,
неизменность test_order.py и собирает выборку памяти `/api/ps`.
Отчёт: `build/reports/local-model-verification.md`.

### Запуск собранного приложения

```bash
./gradlew installDist
export OPENAI_BASE_URL='http://127.0.0.1:11434/v1/'
export OPENAI_API_KEY='ollama'
export OPENAI_MODEL='gpt-oss:20b'
export OPENAI_REASONING_EFFORT='low'
build/install/ai-agents/bin/ai-agents start --mode safe --plan
```

`OPENAI_REASONING_EFFORT` необязателен; если не задан, поле не отправляется API.
Для GPT-OSS клиент сохраняет возвращённое reasoning и передаёт его с предыдущим
сообщением assistant в следующем запросе.

## Воспроизводимая проверка без настоящей модели

`python3 scripts/verify_cli.py` запускает локальный HTTP-сервер с заранее
заданными ответами и направляет на него обычный API-клиент CLI.
Подставная реализация ModelClient находится только в `src/test`.
Файловые операции и Bash в этих проверках настоящие.

## Подтверждения и продолжение

В `safe` чтение доступно сразу, запись и Bash требуют отдельного разрешения.
В `auto` запись и Bash выполняются сразу после проверок. Удаление требует
подтверждения в обоих режимах. Подтверждение — явная отдельная CLI-команда;
процесс сохраняет состояние и заканчивается, не читая интерактивный ответ.

```bash
build/install/ai-agents/bin/ai-agents start --mode safe
build/install/ai-agents/bin/ai-agents show RUN_ID
build/install/ai-agents/bin/ai-agents approve RUN_ID --reason 'Разрешаю запись'
build/install/ai-agents/bin/ai-agents approve RUN_ID --reason 'Разрешаю запуск тестов'
```

Замените `RUN_ID` значением, напечатанным при запуске. Если используется свой
`--state-dir`, передавайте его во всех последующих командах.

```bash
build/install/ai-agents/bin/ai-agents reject RUN_ID --reason 'Не изменять этот файл'
build/install/ai-agents/bin/ai-agents resume RUN_ID
```

Отказ возвращается модели как ошибочный результат инструмента с причиной.
Она может выбрать другой шаг или завершить ответ. `resume` не разрешает ожидающее
действие и не повторяет завершённое. Перед разрешённым исполнением policy
перечитывается: прежнее подтверждение не обходит изменившиеся ограничения.

## Policy и инструменты

`policy.json` находится вне `workspace`. Каталог state тоже обязан находиться
вне workspace. Файловые инструменты принимают только относительные пути,
запрещают абсолютные пути, компоненты `..` и любые симлинки, включая ведущие
внутрь workspace. Для записи родительская директория должна уже существовать.

| Инструмент | Аргументы | Назначение |
|---|---|---|
| `list_files` | `path` | Дочерние пути директории; запрещённые пути и симлинки пропускаются |
| `read_file` | `path` | Чтение UTF-8, слишком большой результат помечается `truncated` |
| `write_file` | `path`, `content` | Создание/замена файла через временный файл и atomic move |
| `delete_file` | `path` | Удаление одного обычного файла после подтверждения |
| `Bash` | `command`, `cwd` | Разрешённая команда, stdout/stderr/exit_code и признаки таймаута/обрезки |
| `submit_plan` | `goal`, `steps`, `risks`, `assumptions`, `done_when` | Структурированный план; у каждого шага есть `description` и `tools` |

Каждый инструмент имеет JSON Schema. `Schemas` проверяет типы, обязательные
поля, вложенные структуры и запрет дополнительных полей для фиксированного
набора схем проекта; это не универсальная реализация всего стандарта JSON Schema.

Правила `commands.allow`/`deny` — точные полные строки, не префиксы и не glob.
Директории в примере задаются относительно workspace и переводятся в канонические
пути; можно указать абсолютные канонические пути внутри workspace. Запрет имеет
приоритет, всё не разрешённое отклоняется. Пример разрешает корень и запрещает
`private`, разрешает `python3 -B -m unittest -v` и запрещает `rm -rf .`.

Вдобавок runtime разрешает только слова из латинских букв, цифр, `_`, `-`,
разделённые одиночными пробелами. Пути в аргументах, кавычки, подстановки,
конвейеры, перенаправления, вложенные shell-команды и аргументы `-c`/`-e`
отклоняются, даже если строка оказалась в allow. Это намеренно узкий учебный
поднабор Bash. Например, `python3 -B -m unittest -v` допустим, а
`python3 -m unittest test_order.py` — нет из-за точки в аргументе.

Bash запускается без startup-файлов с очищенным окружением, фиксированным PATH,
лимитом времени и общим лимитом захваченных байтов stdout/stderr. Эти потоки
читаются одновременно, чтобы не блокироваться на заполненной трубе.
Настройки: `bashTimeoutMillis`, `maxOutputBytes`, `maxFileBytes`.

**Граница безопасности:** проверки не являются OS sandbox. Разрешённая программа,
включая код тестов Python, работает с правами пользователя и технически может
обращаться к другим файлам/процессам. Работайте с учебной копией без секретов.
Внешние одновременные изменения workspace, включая намеренные гонки путей,
не изолированы этим учебным runtime. Ограничения проверяются перед действием,
но откат и обнаружение посторонних правок относятся к уровню 10.

## Plan Mode

```bash
build/install/ai-agents/bin/ai-agents start --mode safe --plan
build/install/ai-agents/bin/ai-agents show RUN_ID
build/install/ai-agents/bin/ai-agents approve-plan RUN_ID
build/install/ai-agents/bin/ai-agents approve RUN_ID --reason 'Разрешаю запись'
build/install/ai-agents/bin/ai-agents approve RUN_ID --reason 'Разрешаю Bash'
```

До утверждения доступны только `list_files`, `read_file`, `submit_plan`;
любые предложенные запись, удаление и Bash возвращают ошибку. Runtime проверяет
это независимо от того, какие инструменты были объявлены модели. Вызовы из той
же пачки, что и `submit_plan`, тоже обрабатываются в фазе PLAN.
План сохраняется с run_id. `approve-plan` меняет фазу на EXECUTE, но не разрешает
опасные действия в safe заранее. `reject-plan RUN_ID --reason TEXT` оставляет
агента в PLAN и просит модель пересмотреть план. Текстовый ответ без структурированного
плана завершает фазу с ошибкой, а не выдаётся за утверждённый план.

## Восстановление после сбоя

CLI использует `SqliteRunRepository` через интерфейс `RunRepository`.
База находится в `.runs/agent.db`; другой каталог задаётся `--state-dir PATH`.
SQLite JDBC 3.53.4.0 подключён через Gradle, отдельный сервер не нужен.

В одной транзакции сохраняются полный снимок RunState и таблицы `runs`,
`budgets`, `tasks`, `tool_calls`, `messages`, `decisions`. Полный `state_json`
в `runs` — источник восстановления; остальные таблицы дают отдельные записи
для запросов и просмотра данных. Ошибка любой записи откатывает снимок,
бюджет и все компоненты вместе. Установлены WAL, synchronous FULL, foreign keys
и busy_timeout 5000 мс. Версия схемы хранится в user_version; неизвестная
новая версия отклоняется без изменения схемы.

Межпроцессный file lock по run_id остаётся в каталоге состояния: он удерживается
на весь `resume`/подтверждение, включая обращение к модели. SQLite-транзакции
при этом короткие и не остаются открытыми во время модели или Bash.
После завершения/сбоя процесса файловая блокировка освобождается ОС.

Старые `.runs/RUN_ID.json` автоматически импортируются при первом `show`,
`budget`, `tasks` или `resume` этого запуска. Исходный JSON не изменяется
и остаётся резервной копией. После импорта SQLite имеет приоритет: прежний
JSON не перезаписывает более новое состояние в базе. `JsonRunRepository`
сохранён для совместимости и отдельных проверок файлового формата.

Транзакция базы не включает изменение файлов workspace или выполнение Bash.
Поэтому правила неопределённого вызова и явного восстановления остаются ниже.

Состояния вызова: `PENDING → WAITING_CONFIRMATION → STARTED → DONE`
(подтверждение требуется не всегда). `STARTED` сохраняется **до** исполнения,
`DONE` и результат — **после**. Если после перезапуска найден STARTED,
runtime останавливается в `NEEDS_RECOVERY`: молча повторять нельзя.

```bash
build/install/ai-agents/bin/ai-agents start --mode auto --crash-after write_file
# Команда завершится с кодом 86; run_id напечатан до исполнения.
build/install/ai-agents/bin/ai-agents resume RUN_ID
build/install/ai-agents/bin/ai-agents show RUN_ID
# Осмотрите файл и выберите ровно один вариант:
build/install/ai-agents/bin/ai-agents recover RUN_ID --choice retry --reason 'Осмотрел файл, разрешаю повтор'
build/install/ai-agents/bin/ai-agents recover RUN_ID --choice fail --reason 'Считать шаг неуспешным'
build/install/ai-agents/bin/ai-agents recover RUN_ID --choice abort --reason 'Прекратить запуск'
```

`retry` создаёт новую попытку того же tool call: растёт `attempts`, сохраняется
решение пользователя, заново проверяется policy. Чтение и полная замена файла
считаются повторяемыми: повторная замена тем же содержимым даёт тот же файл.
Пользователь должен осмотреть файл и согласиться с повторной заменой, особенно
если его кто-то изменял после сбоя. Bash и удаление консервативно считаются
неповторяемыми: для них можно выбрать `fail` или `abort`.

Повтор tool_call_id с тем же именем/аргументами возвращает сохранённый результат.
С изменённым именем/аргументами возвращается ошибка протокола. Если DONE сохранился,
а сообщение tool ещё нет, сообщение восстанавливается без нового исполнения.
Обрабатываются также несколько вызовов в одном ответе модели, по порядку.

## Как объяснить устройство на защите

1. `ModelClient` получает историю и определения инструментов; возвращает предложения.
2. `AgentLoop` сохраняет ответ модели и ещё не выполненную пачку вызовов.
3. `Schemas` и `Policy` проверяют вызов. Ошибку получает модель, а не весь процесс.
4. Runtime либо ждёт решения пользователя, либо сохраняет STARTED и запускает `ToolExecutor`.
5. Сохраняются результат, DONE и сообщение tool с исходным id.
6. Цикл снова обращается к модели; текстовый ответ завершает исполнение.

Расположение кода:

```text
edu.hse.agent/
├── cli/            Main, CommandParser, CliArguments, CommandContext, CommandResult, ConsoleRenderer
│   └── commands/   Command и конкретные команды CLI
├── runtime/        AgentLoop, SimulatedCrash
├── budget/         BudgetManager, BudgetExceeded, ResultLimiter
├── tasks/          TaskManager
├── texts/          Prompts, PromptTemplate, TextResources
├── llm/            ModelClient, CompatibleModelClient
├── tools/          Tool, ToolRegistry, ToolExecutor, Schemas, SchemaBuilders, ToolResults
│   └── implementations/  ListFilesTool, ReadFileTool, WriteFileTool, DeleteFileTool, BashTool
├── policy/         Policy
├── storage/        RunRepository, SqliteRunRepository, JsonRunRepository, RunFileLock
├── serialization/  JsonSupport
└── models/         модели данных и enum
```

Тесты повторяют пакеты основных компонентов: `runtime/AgentTest.kt` и
`runtime/BudgetTest.kt`, `runtime/TaskGraphTest.kt`, `tasks/TaskManagerTest.kt`,
`llm/ModelClientTest.kt` и `cli/CommandParserTest.kt`.

- [AgentLoop.kt](src/main/kotlin/edu/hse/agent/runtime/AgentLoop.kt): цикл, решения, восстановление.
- [RunRepository.kt](src/main/kotlin/edu/hse/agent/storage/RunRepository.kt): контракт сохранения, загрузки и блокировки.
- [SqliteRunRepository.kt](src/main/kotlin/edu/hse/agent/storage/SqliteRunRepository.kt): транзакционное SQLite-хранилище CLI.
- [JsonRunRepository.kt](src/main/kotlin/edu/hse/agent/storage/JsonRunRepository.kt): прежнее JSON-хранилище для совместимости.
- [models](src/main/kotlin/edu/hse/agent/models): модели данных и enum, каждый в отдельном файле.
- [Policy.kt](src/main/kotlin/edu/hse/agent/policy/Policy.kt): проверки команд, директорий, режимов.
- [Schemas.kt](src/main/kotlin/edu/hse/agent/tools/Schemas.kt): инструменты и проверка аргументов.
- [Tool.kt](src/main/kotlin/edu/hse/agent/tools/Tool.kt): общий интерфейс инструмента с описанием и схемой аргументов.
- [ToolRegistry.kt](src/main/kotlin/edu/hse/agent/tools/ToolRegistry.kt): поиск реализации по имени инструмента.
- [ToolExecutor.kt](src/main/kotlin/edu/hse/agent/tools/ToolExecutor.kt): проверка аргументов и вызов реализации.
- [implementations](src/main/kotlin/edu/hse/agent/tools/implementations): отдельные реализации файловых инструментов и Bash.
- [ModelClient.kt](src/main/kotlin/edu/hse/agent/llm/ModelClient.kt): интерфейс модели.
- [CompatibleModelClient.kt](src/main/kotlin/edu/hse/agent/llm/CompatibleModelClient.kt): HTTP-клиент.
- [Main.kt](src/main/kotlin/edu/hse/agent/cli/Main.kt): точка входа и обработка ошибок.
- [CommandParser.kt](src/main/kotlin/edu/hse/agent/cli/CommandParser.kt): преобразование аргументов в команды с проверкой параметров.
- [commands](src/main/kotlin/edu/hse/agent/cli/commands): интерфейс Command и выполнение команд.
- [CommandContext.kt](src/main/kotlin/edu/hse/agent/cli/CommandContext.kt): создание хранилища и runtime по необходимости.
- [ConsoleRenderer.kt](src/main/kotlin/edu/hse/agent/cli/ConsoleRenderer.kt): вывод результата и код завершения.

## Задачи с зависимостями — уровень 9

Включите `--tasks`: runtime начинает с планирования и ждёт утверждения.
Существующий запуск без этого флага использует обычный режим уровней 7–8.

```bash
./gradlew runLocal --args="start --tasks --mode auto --budget budget.json"
./gradlew runLocal --args="tasks RUN_ID"
./gradlew runLocal --args="approve-plan RUN_ID"
```

В safe запись и Bash после утверждения плана по-прежнему подтверждаются отдельно.
Команды `tasks`, `show`, `resume`, `approve`, `reject` и `recover` принимают
`--state-dir PATH`, если состояние находится не в `.runs`.

В `submit_plan` каждый элемент `steps` должен содержать:

```json
{
  "id": "test",
  "description": "Запустить тесты",
  "depends_on": ["modify"],
  "tools": ["Bash"],
  "verification_tool": "Bash"
}
```

У независимого шага `depends_on: []`. Runtime отклоняет повторяющиеся id,
неизвестные зависимости, ссылки на себя, циклы и неизвестные инструменты.
`verification_tool` должен присутствовать среди разрешённых инструментов задачи.

Runtime последовательно выбирает задачу, все зависимости которой имеют
статус COMPLETED, и передаёт модели описание текущей задачи. Модель не выбирает
`task_id` файловых вызовов: runtime сам фиксирует его в CallRecord и сохраняет
`batchTaskId`. Вызовы планирования имеют phase PLAN, taskId null и отдельный
список `planningCallIds`. Завершённые задачи и фактические результаты сохраняются
в state и не запускаются заново при resume.

Завершение — отдельный вызов `complete_task(task_id, call_id)`, единственный
в своей пачке. Runtime проверяет, что evidence вызван текущей задачей,
действительно выполнен, сохранён как DONE, соответствует verification_tool,
имеет `ok: true` и не обрезан. Для Bash нужны `exit_code: 0` и `timed_out: false`.
Отказы, ошибки, чужие результаты, неопределённые вызовы и текстовое заявление
модели не подтверждают результат. В TaskEvidence сохраняются call_id, инструмент
и фактический результат. Если модель завершает работу текстом до проверки всех
задач, текущая задача получает FAILED, ожидающие задачи BLOCKED, запуск FAILED.

Чеклист выводится при изменениях runtime и в конце команды; `tasks RUN_ID`
показывает его без вызова модели. Метки: `[ ]` ожидает, `[>]` выполняется,
`[x]` завершена с проверкой, `[!]` неуспешна, `[-]` заблокирована.
В JSON команды `show` доступен полный результат проверки.

`runtime/TaskGraphTest.kt` выполняет три задачи на настоящем контрольном
проекте и Bash, проверяет зависимости, неизменность тестов, остановку до
утверждения, отказ, недостоверное завершение и восстановление после сбоя.
`tasks/TaskManagerTest.kt` проверяет граф и недопустимые доказательства.

## Бюджеты запуска

Каждый новый запуск получает единый бюджет. Лимиты по умолчанию и пример
конфигурации находятся в `budget.json`: 30 запросов к модели, 100 tool calls,
20 попыток действий с побочными эффектами и 1048576 байт результатов.
Чтобы задать свои лимиты, передайте `--budget PATH` при `start`:

```bash
./gradlew runLocal --args="start --budget budget.json"
./gradlew runLocal --args="budget RUN_ID"
```

Все лимиты — неотрицательные целые числа. Ноль запрещает соответствующий расход.
Файл читается один раз при создании запуска; значения сохраняются в state.
`resume`, утверждение/отклонение плана, подтверждение действия и `recover`
используют сохранённые лимиты и счётчики, изменение файла их не меняет.

В консоли перед запросом модели и выполнением инструмента, после результата
инструмента и при завершении печатаются четыре счётчика `потрачено/лимит`
и остатки. Команда `budget RUN_ID` показывает их без обращения к модели;
`show RUN_ID` включает те же данные в JSON.

Правила списания:

- Каждый запрос к модели списывается до обращения к API, включая неудачные
  запросы и повтор после сбоя. Флаг незавершённого запроса сохраняется в state.
- Вся пачка tool calls проверяется до первого вызова. Принятая пачка целиком
  списывается вместе с её сохранением; учитываются также ошибочные и повторные
  предложения модели. Явный `recover --choice retry` списывает ещё один вызов.
- Побочные эффекты — попытки `write_file`, `delete_file` и `Bash`. Их счётчик
  увеличивается атомарно с `STARTED` перед выполнением. Отказ пользователя и
  отклонение policy не расходуют этот счётчик. Неопределённая попытка после
  сбоя остаётся потраченной; явный повтор требует нового остатка.
- Размер результатов — UTF-8 байты всего сохранённого JSON, включая метаданные,
  ошибки и результаты отказов. Списание сохраняется вместе с результатом;
  выдача уже сохранённого результата после resume не списывает его снова.

Если пачка не помещается по числу вызовов или потенциальных побочных эффектов,
не выполняется ни один вызов. Для результатов заранее резервируется минимум
18 байт на каждый вызов — размер `{"truncated":true}`. Остаток передаётся
инструменту; большой результат обрезается до допустимого размера с
`truncated: true`, сохраняя корректный JSON. Место для минимальных результатов
остальных вызовов пачки удерживается до их выполнения. При очень маленьком
остатке ответ содержит только признак обрезки и не доказывает успех инструмента.

Исчерпание останавливает запуск со статусом `BUDGET_EXHAUSTED` и кодом 1.
Повторный `resume` не обновляет лимиты и не запускает остановленные действия.

`runtime/BudgetTest.kt` проверяет каждый лимит, пачки, UTF-8 результаты,
Bash, сохранение расхода между фазами и управляемый сбой после списания,
а также расход новой попытки после восстановления.

## Тексты и промпты

Тексты для модели находятся в `src/main/resources/prompts`:

- `system.txt` — основные инструкции и ограничения из policy;
- `default-task.txt` — задача при отсутствии `--task`;
- `planning.txt`, `execution.txt`, `task-mode.txt` — инструкции режимов;
- `plan-approved.txt`, `plan-rejected.txt` — решения по плану;
- `current-task.txt`, `tasks-completed.txt` — инструкции текущей задачи и завершения;
- `tool-descriptions.json` — описания инструментов, передаваемые модели.

CLI help находится в `src/main/resources/cli/help.txt`.
`Prompts` загружает файлы из classpath в UTF-8. В шаблонах используются
`{{phase_instruction}}`, `{{allowed_commands}}`, `{{denied_directories}}`,
`{{reason}}` и `{{task_json}}`. Значения вставляются один раз как обычный текст;
содержимое подставленных значений повторно не интерпретируется как шаблон.
Отсутствие файла или требуемого значения приводит к явной ошибке.

Ресурсы включаются в JAR и дистрибутив. После изменения файлов для запуска
через `build/install/...` выполните `./gradlew installDist`; `runLocal` использует
обновлённые ресурсы через задачу classes. Загрузка не зависит от текущей папки.
Системные сообщения созданного запуска сохраняются в его истории; правка файлов
не заменяет уже сохранённые сообщения при resume.

## Единый стиль Kotlin

Настройки ktlint official заданы в `.editorconfig` для `.kt` и `.kts`.

```bash
./gradlew ktlintFormat
./gradlew ktlintCheck
```

Модели данных и enum находятся в `edu.hse.agent.models`, каждая в отдельном файле.

## Автоматические проверки

```bash
./gradlew test
```

После `installDist` дополнительно выполните `python3 scripts/verify_cli.py`:
он проверяет auto, safe + plan, сбой/продолжение, бюджеты и задачи отдельными JVM-процессами.
Отчёт CLI: `build/reports/cli-verification.md`.

Отчёт: `build/reports/tests/test/index.html`.
Дополнительные наборы: `storage/JsonRunRepositoryTest.kt`, `storage/SqliteRunRepositoryTest.kt`, `budget/ResultLimiterTest.kt`,
`llm/ModelClientResponseTest.kt`, `cli/BudgetConfigTest.kt`. Они проверяют
откат транзакции при ошибке SQL, импорт прежнего JSON, повреждение состояния и блокировки, некорректные HTTP-ответы, чтение конфигурации
до создания клиента модели и обрезку вложенных UTF-8 результатов. Тесты используют временные
workspace/state/policy, подставные ответы модели, настоящий Bash и локальный HTTP-сервер.
Они покрывают контрольную задачу и неизменность тестов, ошибки/пачки вызовов,
подтверждения/отказы, policy и симлинки, таймаут/обрезку, повторы id,
сбои/восстановление, Plan Mode и HTTP-протокол. API-ключи для них не нужны.

Для сдачи создайте GitHub-репозиторий с этим проектом и предоставьте доступ
проверяющему, если он закрытый. Не добавляйте `.runs`, `.env` и секреты.
