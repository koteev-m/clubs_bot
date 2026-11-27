# CLB-INVITATION-AND-NAVIGATION-UX

Кандидат для Draft PR от `8587abf7c510b98c4906f44d145933f177c7526e`,
ветка `codex/clb-invitation-and-navigation-ux`. По сообщению владельца продукта
на устройстве проверена модель владельца бизнеса и управляющих в Stage V081.
Проверки этой ветки выполняются на одноразовых данных.

## Сценарии и совместимость

| Кто создаёт приглашение | Что принимает получатель |
|---|---|
| Владелец платформы | Клиентский кабинет организации. Указанный лимит действует при последующем создании заведений подтверждённым владельцем бизнеса. |
| Владелец бизнеса | Управление выбранным клубом или управление сетью в явно перечисленных клубах. Для каждого клуба сервер создаёт отдельное назначение. |
| Управляющий сети | Роль управляющего клуба в пределах действующего назначения отправителя. |
| Создатель клуба, в котором ещё доступна прежняя модель сотрудников | Поддерживаемый пакет конкретного клуба. При приглашении ответственного за подготовку сервер связывает задачу с принявшим сотрудником. Переход на подтверждённую модель бизнеса закрывает прежнюю выдачу. |

Отправитель указывает имя, выбирает доступную роль и проверяет заведения.
Готовая карточка содержит срок действия, копирование и отправку через выбор
чата в Telegram. Получатель открывает предварительный просмотр, проверяет
организацию, роль и заведения, затем нажимает «Принять приглашение».
Кабинет с одним назначенным клубом открывает этот клуб. При нескольких клубах
открывается организация с доступными заведениями.

V082 сохраняет режим выдачи приглашения. У прежних служебных ссылок остаётся
подтверждение, включая привязку и код ранее заявленного кандидата. Явный
перевыпуск клиентской ссылки для пустого кабинета сохраняет привязку к
кандидату, если она существовала. У новых непривязанных ссылок полномочия
получает первый пользователь с проверенной Telegram identity и действительным
токеном. Предварительный просмотр не назначает роль. Срок, исходный отправитель,
версия, допустимая роль и полный список клубов проверяются на сервере.
Назначения, использование токена, квитанция команды и аудит фиксируются
в одной транзакции. Отзыв до принятия отменяет ссылку на все выбранные клубы,
а после принятия можно отозвать отдельное назначение.

Политика NETWORK_MANAGER v2 добавляет настройку профиля, планировки, календаря,
правил бронирования, финансовых шаблонов и модулей назначенного клуба.
Денежные операции, ownership и назначение другого управляющего сети остаются
за границей роли. V083 регистрирует обновление прав подходящих активных
назначений v1 в аудите и сохраняет исходные хеши. Ожидавшее на момент миграции
приглашение v1 при последующем подтверждении выдаёт исходный пакет.
Контракт согласован и для будущих служебных ролей. [Продуктовая спецификация](../product/PRODUCT_SPEC.md#service-invitations)

Встроенная кнопка и Telegram-кнопка «Назад» используют один обработчик. В клиентском
кабинете у формы управляющего родитель — список управляющих, у раздела конфигурации —
настройки выбранного клуба, у настроек и списка управляющих — кабинет клуба.
Платформенная карточка клиента возвращает к списку клиентов. При выходе с
черновиком действует предупреждение, а локальная незавершённая команда
сохраняет исходный ключ для повторной попытки. Успешное создание заведения
и принятие ссылки заменяют завершённую форму в истории.

## Мобильная проверка

Проверки выполнялись при мобильном размере экрана в Chromium и WebKit.
Проверены выбор текста поля, событие копирования, отказ копирования после
успешной попытки, ручной fallback и передача URL в тестовый мост Telegram SDK.
Владелец продукта сообщил, что 27.11.2026 ручная приёмка на Stage пройдена
без замечаний, блокирующих дальнейшую разработку. Принята ревизия
`5befde028b0e8c20da65c84b8a80bfdef0cedb5a` (V083).
Кнопка отправки использует официальный `https://t.me/share/url` через
`openTelegramLink`. Пользователь сам выбирает чат и отправляет сообщение.
[Telegram Sharing Button](https://core.telegram.org/widgets/share) · [Telegram Mini Apps](https://core.telegram.org/bots/webapps#initializing-mini-apps)

## Проверки кандидата до итогового ревью

| Проверка | Результат |
|---|---|
| Полный набор JVM и H2 | `./gradlew --offline --no-configuration-cache test detektGate ktlintCheck` прошёл. Из 1 768 тестов прошли 1 767, один уже отключён в исходном main (`GuestFlowStartTest`, команда `/start`). |
| Ktor и PostgreSQL 16, восемь затронутых групп маршрутов | 36 из 36. Обновление с исторической схемы на уже открытом соединении прошло в том числе для назначений, прежних сотрудников и политики V083. |
| Слой данных, PostgreSQL 16 | 168 из 168. Приглашения и транзакции, конкуренция, отзыв доступа, границы прежней модели и области полномочий. |
| Vitest | 496 из 496 в 56 файлах. |
| TypeScript и ESLint | `tsc --noEmit` и `npm run lint:ci` с нулём допустимых предупреждений прошли. |
| Мобильные Chromium и WebKit на собранном `dist` | 96 проверок кабинетов, 42 проверки настроек и незавершённых команд, 20 проверок входа и клиентского кабинета. Сценарий копирования и Telegram share повторно прошёл в обоих движках с окончательной сборкой. |
| Мобильные Chromium и WebKit с реальным Ktor и одноразовой H2 V083 | 14 из 14. Проверены в том числе приём клиентской ссылки, перевыпуск, приглашение сотрудника и цепочка назначений владельца и управляющих. |
| Сборка Mini App | Чистая сборка Vite побайтово совпала с `miniapp/dist`. Для обоих каталогов прошёл `scripts/validate-miniapp-dist.sh`. |

Независимое ревью выявило два случая, которые исправлены и покрыты
регрессиями. Квитанция принятого приглашения исключает название клуба после
отзыва доступа к нему. Возврат с платформенной карточки защищает незавершённую
команду от потери при переходе по истории.

## Итоговое независимое ревью

Серверные маршруты получают Telegram identity из проверенного запроса. Предварительный
просмотр не выдаёт роль. Подтверждена проверка исходного отправителя и его роли,
организации и каждого выбранного клуба при принятии. Конкурирующие команды
сериализуются блокировкой клиента. Отзыв одного назначения сохраняет доступ
по независимому действующему назначению.

Ревью выявило и исправило следующие случаи.

- Срок старого приглашения сотрудника мог истечь во время записи. Теперь в
  общей операции назначения есть проверка времени перед фиксацией транзакции.
  Регрессия проверяет откат прав, аудита и квитанции на границе срока.
- Выход из решения по заявке, редактирования доступа участника или выбора роли
  сотрудника мог сбросить несохранённые поля. Добавлено предупреждение при возврате.
- Навигация после выдачи ссылки и до ответа на создание клуба могла вернуть
  пользователя в завершённую форму. Переход после выдачи использует адрес
  квитанции без токена. Явная новая выдача начинает пустую форму. До первого
  ответа сервера форма создания клуба защищена от ухода.

Переход проверен с заполненной V081. В Ktor-тесте одно назначение активно, а
другое приглашение уже привязано к кандидату. После применения V082 и V083
сохраняются кандидат, код, версии и исходные хеши. Новые права действуют в
назначенном клубе. При последующем подтверждении ожидающего приглашения
применяется v1. Все 159 отслеживаемых файлов прежних миграций совпали с базой.

| Проверка после исправлений | Результат |
|---|---|
| H2 и PostgreSQL 16 | 8 из 8. Переход V081 → V083, истечение срока перед commit, действующий и исторический сценарии назначения сотрудника. Detekt и ktlint затронутых модулей прошли. |
| Vitest | 54 из 54 в шести затронутых группах. |
| Мобильные Chromium и WebKit | 22 из 22. Восемь сквозных проверок прошли с локальным Ktor и H2, ещё 14 на собранном Mini App с тестовым транспортом. |
| Сборка и публикация | TypeScript и ESLint прошли. Чистая сборка совпала с отслеживаемым dist побайтово, оба каталога прошли валидатор. Сообщение коммита прошло commitlint. |

Доказательство миграции получено локально на схеме репозитория V081 и
синтетических записях. Сведения о версии Stage предоставил владелец продукта.

## Изменённые файлы

В список входят 69 путей, включая заменённые файлы сборки.

### Ktor и серверные регрессии

- [BusinessOwnershipRoutesTest.kt](../../app-bot/src/test/kotlin/com/example/bot/routes/BusinessOwnershipRoutesTest.kt)
- [ClientBookingRulesRoutesDataTest.kt](../../app-bot/src/test/kotlin/com/example/bot/routes/ClientBookingRulesRoutesDataTest.kt)
- [ClientCalendarRoutesDataTest.kt](../../app-bot/src/test/kotlin/com/example/bot/routes/ClientCalendarRoutesDataTest.kt)
- [ClientManagerRoutesDataTest.kt](../../app-bot/src/test/kotlin/com/example/bot/routes/ClientManagerRoutesDataTest.kt)
- [ClientNightEventLinksRoutesDataTest.kt](../../app-bot/src/test/kotlin/com/example/bot/routes/ClientNightEventLinksRoutesDataTest.kt)
- [ClientOperationalNightsRoutesDataTest.kt](../../app-bot/src/test/kotlin/com/example/bot/routes/ClientOperationalNightsRoutesDataTest.kt)
- [LegacyStaffAdoptionContract.kt](../../app-bot/src/test/kotlin/com/example/bot/routes/LegacyStaffAdoptionContract.kt)
- [NetworkManagerPolicyContract.kt](../../app-bot/src/test/kotlin/com/example/bot/routes/NetworkManagerPolicyContract.kt)

### Данные, полномочия и приглашения

- [AssignmentSnapshot.kt](../../core-data/src/main/kotlin/com/example/bot/data/client/assignments/AssignmentSnapshot.kt)
- [ManagerPolicies.kt](../../core-data/src/main/kotlin/com/example/bot/data/client/assignments/ManagerPolicies.kt)
- [ClubManagers.kt](../../core-data/src/main/kotlin/com/example/bot/data/client/onboarding/ClubManagers.kt)
- [ClubPreparationOperations.kt](../../core-data/src/main/kotlin/com/example/bot/data/client/onboarding/ClubPreparationOperations.kt)
- [ClubTeamOperations.kt](../../core-data/src/main/kotlin/com/example/bot/data/client/onboarding/ClubTeamOperations.kt)
- [InvitationOperations.kt](../../core-data/src/main/kotlin/com/example/bot/data/client/onboarding/InvitationOperations.kt)
- [JdbcClientOnboardingRepository.kt](../../core-data/src/main/kotlin/com/example/bot/data/client/onboarding/JdbcClientOnboardingRepository.kt)
- [LegacyStaffAssignments.kt](../../core-data/src/main/kotlin/com/example/bot/data/client/onboarding/LegacyStaffAssignments.kt)
- [ManagerAssignmentWriter.kt](../../core-data/src/main/kotlin/com/example/bot/data/client/onboarding/ManagerAssignmentWriter.kt)
- [ManagerInvitationScopes.kt](../../core-data/src/main/kotlin/com/example/bot/data/client/onboarding/ManagerInvitationScopes.kt)
- [OnboardingSql.kt](../../core-data/src/main/kotlin/com/example/bot/data/client/onboarding/OnboardingSql.kt)
- [StaffInvitationOperations.kt](../../core-data/src/main/kotlin/com/example/bot/data/client/onboarding/StaffInvitationOperations.kt)

### Миграции

- [h2/V082__direct_service_invitations.sql](../../core-data/src/main/resources/db/migration/h2/V082__direct_service_invitations.sql)
- [h2/V083__network_manager_settings_policy.sql](../../core-data/src/main/resources/db/migration/h2/V083__network_manager_settings_policy.sql)
- [postgresql/V082__direct_service_invitations.sql](../../core-data/src/main/resources/db/migration/postgresql/V082__direct_service_invitations.sql)
- [postgresql/V083__network_manager_settings_policy.sql](../../core-data/src/main/resources/db/migration/postgresql/V083__network_manager_settings_policy.sql)

### Проверки слоя данных

- [CanonicalBookingH2Test.kt](../../core-data/src/test/kotlin/com/example/bot/data/booking/core/CanonicalBookingH2Test.kt)
- [PlatformRoleRevokeFixture.kt](../../core-data/src/test/kotlin/com/example/bot/data/client/assignments/PlatformRoleRevokeFixture.kt)
- [ControlHeadContract.kt](../../core-data/src/test/kotlin/com/example/bot/data/client/bindings/ControlHeadContract.kt)
- [ClubStaffContract.kt](../../core-data/src/test/kotlin/com/example/bot/data/client/onboarding/ClubStaffContract.kt)
- [LegacyAuthorityWriteFenceTest.kt](../../core-data/src/test/kotlin/com/example/bot/data/client/onboarding/LegacyAuthorityWriteFenceTest.kt)
- [SimplifiedOnboardingContract.kt](../../core-data/src/test/kotlin/com/example/bot/data/client/onboarding/SimplifiedOnboardingContract.kt)

### Документация

- `CLB-INVITATION-AND-NAVIGATION-UX.md`
- [PROJECT_STATUS.md](PROJECT_STATUS.md)
- [owner-team-contract.md](owner-team-contract.md)
- [OPEN_DECISIONS.md](../product/OPEN_DECISIONS.md)
- [PRODUCT_SPEC.md](../product/PRODUCT_SPEC.md)
- [ROLE_ASSIGNMENT_CONTRACT_PROPOSED.md](../product/ROLE_ASSIGNMENT_CONTRACT_PROPOSED.md)
- [ROLE_WORKSPACES_PROPOSED.md](../product/ROLE_WORKSPACES_PROPOSED.md)
- [README.md](../../miniapp/README.md)

### Собранный Mini App

- [PlatformOwnerScreens-BH75HJP1.js](../../miniapp/dist/assets/PlatformOwnerScreens-BH75HJP1.js)
- `PlatformOwnerScreens-BpcUrj9t.js` (удалён)
- `index-DaVPrQQI.js` (удалён)
- [index-mQZ1saMU.js](../../miniapp/dist/assets/index-mQZ1saMU.js)
- [index.html](../../miniapp/dist/index.html)

### Экраны и типы Mini App

- [ClientAccess.tsx](../../miniapp/src/modules/client/ClientAccess.tsx)
- [ClubCabinet.tsx](../../miniapp/src/modules/client/ClubCabinet.tsx)
- [ClubManagers.tsx](../../miniapp/src/modules/client/ClubManagers.tsx)
- [ClubModules.tsx](../../miniapp/src/modules/client/ClubModules.tsx)
- [ClubPreparation.tsx](../../miniapp/src/modules/client/ClubPreparation.tsx)
- [ClubTeam.tsx](../../miniapp/src/modules/client/ClubTeam.tsx)
- [InvitationDelivery.tsx](../../miniapp/src/modules/client/InvitationDelivery.tsx)
- [NewClub.tsx](../../miniapp/src/modules/client/NewClub.tsx)
- [OnboardingShared.tsx](../../miniapp/src/modules/client/OnboardingShared.tsx)
- [PlatformOnboarding.tsx](../../miniapp/src/modules/client/PlatformOnboarding.tsx)
- [onboarding.types.ts](../../miniapp/src/modules/client/onboarding.types.ts)
- [preparation.types.ts](../../miniapp/src/modules/client/preparation.types.ts)

### Vitest и мобильные сценарии

- [invitation.test.tsx](../../miniapp/src/modules/client/invitation.test.tsx)
- [managers.test.tsx](../../miniapp/src/modules/client/managers.test.tsx)
- [onboarding.test.tsx](../../miniapp/src/modules/client/onboarding.test.tsx)
- [preparation.test.tsx](../../miniapp/src/modules/client/preparation.test.tsx)
- [simplified-onboarding.test.tsx](../../miniapp/src/modules/client/simplified-onboarding.test.tsx)
- [staff-invitation.test.tsx](../../miniapp/src/modules/client/staff-invitation.test.tsx)
- [team.test.tsx](../../miniapp/src/modules/client/team.test.tsx)
- [client-guest-booking-preview.spec.ts](../../miniapp/tests/client-guest-booking-preview.spec.ts)
- [onboarding-integrated.spec.ts](../../miniapp/tests/onboarding-integrated.spec.ts)
- [onboarding-production.spec.ts](../../miniapp/tests/onboarding-production.spec.ts)
- [owner-manager-integrated.spec.ts](../../miniapp/tests/owner-manager-integrated.spec.ts)
- [owner-setup.spec.ts](../../miniapp/tests/owner-setup.spec.ts)
- [owner-team-integrated.spec.ts](../../miniapp/tests/owner-team-integrated.spec.ts)
- [owner-ux-map.spec.ts](../../miniapp/tests/owner-ux-map.spec.ts)
