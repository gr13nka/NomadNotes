# Исследование рынка — NomadNotes

Обновлено: 2026-09-18. Рабочие заметки, не единица ALPS; материал для problem_analysis, brief и будущего market_analysis.

## Главное

- **Идея не свободна от прецедентов, и штатное приложение движется навстречу.** Прошивка Boox V4.2 (апрель 2026) добавила в штатный Notes стабилизацию штриха и лассо, которое само возвращает прежнюю кисть [A4, A13]. Жест «два пальца держат — перо обводит» официально есть у Supernote [A1]; undo/redo свайпом двух пальцев было в стороннем Boox-блокноте `notable` [A2]; активный open-source `notate` уже собирает лассо, безлимитный undo и низкую задержку на Onyx [A3]. Сочетания «автосглаживание без настроек + undo/redo касаниями + лассо на удержании» на Boox не найдено.
- **Самая подтверждённая боль в открытых источниках — не жесты, а задержка пера в сторонних приложениях** [B6, B2]: многолетняя жалоба владельцев Boox; низкую задержку Onyx по умолчанию даёт белому списку (OneNote, Evernote, WPS).
- **Жалобы именно на undo и лассо — редкие и старые** (MobileRead 2017, 2020–2021; удалённые страницы поддержки Boox) [B7, B8, B12]. Свежих формулировок «нет быстрого жеста отмены» не найдено. Reddit в этом заходе технически не открылся — это дыра в покрытии, не нулевой результат.
- **Есть и молчание у совпадающего носителя**: автор обзора на vc.ru пишет от руки для работы, выбрал Boox ради приложения заметок и не жалуется [B3]. Альтернативы штатному Notes ищут, но чаще ради синхронизации и Markdown, а не ради жестов [B5].
- **Носители достижимы**: r/Onyx_Boox ≈ 68 тыс. подписчиков, +37 %/год [C6]; 4PDA-темы по моделям, MobileRead-подфорум [C7, C8]. Единого хаба нет, собственный форум Onyx закрыт с 2020 [C1].
- **Право — в пользу (2026-09-21).** Поддержка Boox письменно подтвердила автору: Pen SDK под BSD 3-Clause, бесплатно, closed-source и раздача через Google Play и APK разрешены, отдельная коммерческая лицензия не нужна; оговорка — сканер безопасности Google Play может ругаться из-за reflection в SDK [E5]. Это снимает D4–D7 и опровергает вывод, что канала к Onyx нет.
- **Деньги — против.** Boox-специфичные альтернативы бесплатны и живут на донатах [D2, D14]; Concepts: «About 85% of our users are free» [D1]. StarNote от Onyx (~30 % платящих, $5.99 разово) — приложение для iPhone и Android-телефонов, не для Boox [C12, E1]. Публичного пути к коммерческой лицензии Onyx SDK нет [D4, D5, D6].
- **Жест отмены пальцами в бесплатных Boox-блокнотах уже есть** (точечная проверка 2026-09-18): `notate` — двойной тап двумя пальцами, `Ethran/notable` — двойной тап одним пальцем, выделение удержанием; у обоих не найдено redo пальцами и сглаживания [E3, E4]. В штатном Notes жеста отмены пальцами нет [E2].

## Проверка утверждений из интервью

| Claim | Было | Стало | Основание |
|---|---|---|---|
| C1. Владельцы Boox, много пишущие/рисующие, недовольны штатным Notes и ищут альтернативу | допущение | косвенный сигнал, смешанный: альтернативы ищут и пишут сами, но мотив часто иной (синхронизация) | B5, B13, A20, B3 |
| C2. Нет быстрого жеста undo на холсте; без кнопки на стилусе — через панель | выстрадано (у автора); у других — допущение | у других — допущение: свежих жалоб не найдено, старые — про поломки undo, не про жест | B8, B15, A17 |
| C3. Лассо / перемещение неудобно и замедляет | выстрадано (у автора); у других — допущение | слабый косвенный сигнал; V4.2 уже улучшило лассо в штатном Notes | B7, B12, A13 |
| C4. Сторонние приложения на Boox лагают и мерцают | выстрадано (Concepts, OneNote) | косвенный сигнал, сильный; уточнение: OneNote в белом списке и может работать быстро | B6, B2, B1, A10 |
| C5. Никто не делает на Boox жесты пальцами + автосглаживание | допущение | частично опровергнуто: undo пальцами есть у `notate` и `Ethran/notable`, лассо на удержании двух пальцев — у Supernote; redo пальцами и автосглаживание на Boox не найдены | A1, A2, A3, E3, E4 |
| C6. Автосглаживание ценно пишущим на e-ink | выстрадано | слабый косвенный сигнал; штатный Notes V4.2 добавил ручную стабилизацию | B10, B11, A4 |
| C7. Без Onyx SDK сторонние приложения не получают быстрое перо | допущение | косвенный сигнал, подтверждено с уточнением: белый список + EinkWise Code в V4.2 | B2, A5, A6, A7 |
| C8. Владельцев много, они в достижимых сообществах | допущение | косвенный сигнал: да, но без единого хаба | C6, C7, C8, C1 |
| C9. Рынок перьевого e-ink растёт | допущение | смешанно: выручка Onyx растёт, штучные отгрузки перьевых Boox стоят | C10, C11, C3, C13 |
| C10. Владельцы Boox готовы платить за стороннее приложение заметок | допущение | противоречит: примеров платных Boox-блокнотов не найдено | D1, D2, D3, D16 |
| C11. Onyx SDK можно использовать в платном приложении | допущение | разрешено: BSD 3-Clause, бесплатно, closed-source и Google Play допускаются — письмо поддержки Boox автору | E5 |

## Альтернативы и конкуренты

| # | Находка | Источник | Дата | Уверенность |
|---|---|---|---|---|
| A1 | Supernote: «нажми и держи два пальца… затем обведи пером» — ластик/лассо без панели | https://support.supernote.com/en_US/Tools-Features/gesture-eraser | обновл. 2025-08-21 | high |
| A2 | `olup/notable` (архивный, ~300 звёзд) — Boox-блокнот с undo/redo свайпом двух пальцев и скрытием панели двойным тапом | https://github.com/olup/notable | архивирован | medium |
| A3 | `alexdremov/notate` (активный, 51 звезда) — Boox-нативный блокнот: безлимитный undo/redo, лассо, «hold-to-snap» фигуры, низкая задержка через `EpdController`; undo — двойной тап двумя пальцами (см. E4) | https://github.com/alexdremov/notate | ~2026-08 | medium |
| A4 | Прошивка V4.2: стабилизация штриха и чувствительность нажима в штатном Notes (ручная настройка) | https://shop.boox.com/blogs/news/firmware-v4-2-update | 2026-04-21 | medium |
| A5 | Onyx публикует Pen SDK и Flutter-обёртку `onyxsdk_pen`; функциональность урезана, только для устройств Onyx | https://github.com/onyx-intl/OnyxAndroidDemo/blob/master/doc/Onyx-Pen-SDK.md ; https://pub.dev/packages/onyxsdk_pen | ~2026-05 | medium |
| A6 | V4.2: «EinkWise Code» — коды сообщества для настройки обновления экрана в сторонних приложениях | https://shop.boox.com/blogs/news/firmware-v4-2-update | 2026-04-21 | medium |
| A7 | `boox-rapid-draw` (270 звёзд) — оверлей для быстрого пера в сторонних приложениях, «early prototype» | https://github.com/sergeylappo/boox-rapid-draw ; https://blog.the-ebook-reader.com/2024/10/11/boox-rapid-draw-can-decrease-writing-latency-on-3rd-party-apps/ | 2024-10-11 | medium |
| A8 | Запрос в поддержку Boox «universal writing (no writing lag in third party app)» | https://help.boox.com/hc/en-us/community/posts/4403886400020-FEATURE-REQUEST-universal-writing-no-writing-lag-in-third-party-app | неизвестно; не открылась | low |
| A10 | Xournal++ Mobile: «Input lag is very bad on Onyx Note Air» | https://gitlab.com/TheOneWithTheBraid/xournalpp_mobile/-/issues/6 | неизвестно | medium |
| A12 | «Boox Note Optimizer»: штатный Notes пишет избыточные точки пера, файлы раздуваются, сжатие в 5–10 раз | https://nrontsis.github.io/boox-note-optimizer/ | 2026-02-18 | medium |
| A13 | V4.2: лассо в штатном Notes само возвращает прежнюю кисть; перенос выделения между слоями | https://shop.boox.com/blogs/news/firmware-v4-2-update | 2026-04-21 | medium |
| A14 | Тред в поддержке Boox «Request for lasso tool bug fix and some suggestion» | https://help.boox.com/hc/en-us/community/posts/360063092691-Request-for-lasso-tool-bug-fix-and-some-suggestion | неизвестно; не открылась | low |
| A16 | Supernote Nomad: undo/redo — свайп одним пальцем по боковой рамке; двойной тап двумя пальцами — показать/скрыть тулбар | https://ib.supernote.com/Nomad_V3.15.27_EN.pdf | V3.15.27 | medium |
| A17 | Kindle Scribe: undo/redo только кнопками тулбара | https://www.amazon.com/gp/help/customer/display.html?nodeId=Tfdlx4BpnhTtmr0Q3r | неизвестно | medium |
| A18 | reMarkable 2: undo/redo — свайп одним пальцем в верхней средней зоне | https://support.remarkable.com/s/article/Navigating-on-your-reMarkable | неизвестно | medium |
| A20 | Форки `notable` «designed specifically for BOOX e-ink devices» (включая активный `Ethran/notable`) | https://github.com/ivanlee1999/notable ; https://github.com/rettier-claudi/notable ; https://github.com/Ethran/notable | неизвестно | low |
| A21 | MyScript: Nebo «doesn't work with Android e-ink tablets like Onyx and Boox» | https://help.myscript.com/notes/overview/compatible-devices/ | неизвестно | low |

**Три ближайшие альтернативы и что о них должен ответить автор.**
1. **Штатный Boox Notes на прошивке V4.2** — стабилизация штриха и возврат кисти после лассо уже есть. Что остаётся у NomadNotes, если носитель обновит прошивку?
2. **`notate` и `Ethran/notable`** — бесплатные Boox-блокноты с лассо, undo и низкой задержкой; у `notable` были жесты пальцами. Чем NomadNotes отличается и почему к нему уйдут?
3. **Supernote** — жест лассо на удержании двух пальцев уже есть, но на другом устройстве. Это подтверждение ценности паттерна, а не конкурент на Boox; стоит ли ссылаться на него в демо?

## Боль в открытых источниках

| # | Находка | Источник | Дата | Уверенность |
|---|---|---|---|---|
| B1 | Android Police: «OneNote is just as good as the Boox default app for handwriting. It's fast, responsive» (Palma 2 Pro) | https://www.androidpolice.com/i-found-the-best-notes-app-for-every-device/ | 2025-12-01 | medium |
| B2 | Низкая задержка пера «only available for OneNote, Evernote and WPS… other apps don't» — остальным нужен рут | https://gist.github.com/calliecameron/b3c62c601d255630468bd493380e3b7e | неизвестно | medium |
| B3 | vc.ru: пишет от руки для работы и дневников, выбрал Boox Go Color 7 Gen II за приложение заметок, «отклик стилуса довольно быстрый»; жалоб на undo/лассо/сглаживание нет | https://vc.ru/tech/2050439-obzor-elektronnoj-knigi-onyx-boox-go-color-7-gen-ii | 2025-06-18 | medium |
| B4 | Habr: в штатном приложении «никакого ощущения запаздывания нет» | https://habr.com/ru/articles/478952/ | 2019-12-06 | low |
| B5 | «Best note apps for Boox devices?» — ищут Joplin, Markor, Obsidian, Nebo, Notion, Xodo ради синхронизации/Markdown; сторонние (Squid) лагают и мерцают | https://www.ereadersforum.com/threads/best-note-apps-for-boox-devices.3538/ | 2024-12-31 | medium |
| B6 | the-ebook-reader: «One of the biggest complaints people have about Boox's eNotes going back many years is the fact 3rd party note apps have too much lag» | https://blog.the-ebook-reader.com/2024/10/11/boox-rapid-draw-can-decrease-writing-latency-on-3rd-party-apps/ | 2024-10-11 | high |
| B7 | MobileRead, лассо в Note Pro v3.0: «UNABLE to resize or move at all»; «sometimes it works well and sometimes it doesn't» | https://www.mobileread.com/forums/showthread.php?p=4066309 | 2020-12-07 / 2021-01-25 | medium |
| B8 | MobileRead, Max Carta: «the Note app will undo or delete way too much» | https://www.mobileread.com/forums/showthread.php?t=290933 | 2017-10-02 | low |
| B10 | eReadersForum: у GoodNotes 6 «strong handwriting refinement (stroke stabilization)», у Boox Notes аналог не упомянут | https://www.ereadersforum.com/threads/goodnotes-6-vs-boox-notes-app-in-depth-software-comparison.7453/ | 2025-07-02 | low-medium |
| B11 | Запрос в поддержку Boox на авто-сглаживание («look more straight and not so wiggly»), передан разработчикам; страница удалена, виден только кэш поиска | https://help.boox.com/hc/en-us/community/posts/10985833064852 | неизвестно | low |
| B12 | Запрос в поддержку Boox о баге лассо (выделение частично удаляется «about half the time»); страница удалена | https://help.boox.com/hc/en-us/community/posts/360063092691 | неизвестно | low |
| B13 | Независимые open-source замены штатному приложению под Boox, включая «capture surface for Obsidian» | https://github.com/olup/notable ; https://github.com/jdkruzr/aragonite | неизвестно | medium |
| B15 | Молчание: нигде не найдено «I'd pay for a two-finger undo gesture» или аналога | — | — | — |

Вывод трека: устойчиво подтверждена задержка пера в сторонних приложениях; боль undo и лассо реальна, но фрагментирована и стара; сглаживание просили как минимум раз; есть совпадающий носитель без боли. Reddit не проверен.

## Размер и сегменты

| # | Находка | Источник | Дата | Уверенность |
|---|---|---|---|---|
| C1 | Onyx закрыл собственный форум в 2020, пользователей отправили в Facebook и Reddit | https://goodereader.com/blog/onyx-boox/onyx-boox-closes-down-their-forum | 2020-08-25 | medium |
| C2 | В китайском рейтинге 2026 Onyx/文石 — 6-е место дома; отраслевой обзор — «второй в мире» | https://web.phb123.com/pinpai/top72954.html ; https://eu.36kr.com/en/p/3655565320528005 | 2026 | low-medium |
| C3 | Kindle Scribe: ~500–3000 шт/мес в США по SKU, падает | https://blog.the-ebook-reader.com/2025/02/14/wow-practically-nobody-is-buying-the-kindle-scribe-lately/ ; https://blog.the-ebook-reader.com/2026/04/10/previous-gen-kindle-scribe-selling-better-than-the-new-scribe/ | 2025-02-14, 2026-04-10 | medium |
| C4 | Отчёты о «рынке e-ink планшетов» ($1.2–2.2+ млрд, CAGR 7.6–18.5 %) без методологии и привязки к перьевому Boox — не использовать | https://marketintelo.com/report/e-ink-tablet-market ; https://www.verifiedmarketresearch.com/product/e-ink-tablet-market/ | доступ 2026-09-18 | low |
| C5 | Не все Boox сертифицированы Google Play Protect | https://help.boox.com/hc/en-us/articles/360034655212-Why-does-my-Boox-device-require-Google-Play-certification | неизвестно | medium |
| C6 | r/Onyx_Boox ≈ 68 000 подписчиков, +37 %/год | https://gummysearch.com/r/Onyx_Boox/ | 2026-09-13 | medium |
| C7 | MobileRead: подфорум Onyx Boox (форум — 261 072 участника) | https://www.mobileread.com/forums/forumdisplay.php?f=220 | 2026-09-18 | medium |
| C8 | 4PDA: темы по каждой модели Boox, до 7000+ сообщений | https://4pda.to/forum/index.php?showtopic=1073047&st=7000 ; https://4pda.to/forum/index.php?showforum=507 | 2026-09-18 | medium |
| C9 | Telegram @onyxbooxrussia — 2 265 подписчиков (новости бренда) | https://t.me/onyxbooxrussia | 2026-09-18 | medium |
| C10 | Выручка Onyx 2024 — 1.02 млрд юаней (+26.6 %), зарубежные продажи 59.4 %; 9 мес. 2025: выручка +10 %, прибыль −4 % | https://eu.36kr.com/en/p/3655565320528005 | данные до 9 мес. 2025 | high |
| C11 | Отгрузки перьевых («продуктивных») Boox: 224 тыс. (2023) → 216 тыс. (2024) | https://eu.36kr.com/en/p/3655565320528005 | — | high |
| C12 | StarNote (собственное приложение Onyx): >1 млн скачиваний к 2025-09, ~30 % платящих среди зарегистрированных. Уточнение E1: StarNote — приложение для iPhone/iPad и Android-телефонов, не для Boox; о платящих владельцах Boox ничего не говорит | https://eu.36kr.com/en/p/3655565320528005 | 2025-09 | medium |
| C13 | reMarkable: выручка $433.8 млн (2024, +28 %), ~600 тыс. платящих подписчиков | https://www.digi.no/artikler/voldsom-vekst-for-remarkable/511094 | ~2025-04 | high |
| C15 | Boox Note Air6 C / Note Mini C / Palma 3 на Android 16 с Google Play из коробки | https://9to5google.com/2026/09/15/boox-announced-android-16-tablets/ | 2026-09-15 | high |
| C18 | Обзоры описывают топовые Boox для «PhDs, researchers, students» и «students and artists» | https://www.ereadersforum.com/threads/boox-note-max-in-depth-review-best-device-for-phds-researchers-students-and-knowledge-seekers.4405/ ; https://www.androidpolice.com/onyx-boox-note-air4-c-review/ | неизвестно | medium |
| C19 | Facebook boox.global — 75 630 отметок | https://www.facebook.com/boox.global/ | 2026-09-18 | low-medium |

Вывод трека: накопленная база перьевых Boox — «неизвестно»; опора — ~216–224 тыс. отгрузок в год. Сегменты с острейшей болью по источникам — студенты, исследователи, рисующие; прямых данных о боли по сегментам нет.

## Цены и модели

| # | Находка | Источник | Дата | Уверенность |
|---|---|---|---|---|
| D1 | Concepts: «About 85% of our users are free» | https://concepts.app/en/stories/subscription-isnt-for-everybody/ | неизвестно | medium |
| D2 | Boox-специфичные `notable` (Ethran, olup) — бесплатные, донаты, сборки вне Google Play | https://github.com/Ethran/notable/blob/main/readme.md ; https://github.com/olup/notable | неизвестно | medium |
| D3 | MobileRead: владельцы Boox обсуждают бесплатные приложения; платят разве что за ридер Moon+ Reader Pro | https://www.mobileread.com/forums/showthread.php?t=334817&page=3 | неизвестно | low |
| D4 | Форум разработчиков Onyx не резолвится | http://bbs.onyx-international.com/t/developer-support/478 | 2026-09-18 | high |
| D5 | Страница Boox Help Center о SDK — 404 | https://help.boox.com/hc/en-us/community/posts/35402854517524-SDK-GO-10-3-update | 2026-09-18 | high |
| D6 | Единственная явная лицензия — Apache-2.0 у демо-репозитория; бинарники SDK без лицензии | https://github.com/onyx-intl/OnyxAndroidDemo | неизвестно | medium |
| D7 | Зеркало Boox SDK на GitHub без лицензии | https://api.github.com/repos/stevezuo/booxsdk | неизвестно | medium |
| D10 | Squid: Premium $1/мес или $10/год, IAP $2.99–4.99 | https://play.google.com/store/apps/details?id=com.steadfastinnovation.android.projectpapyrus&hl=en_US | неизвестно | low |
| D11 | Noteshelf на Android — разовая покупка ~$9.99 | https://www.noteshelf.net/noteshelf-android.html | неизвестно | low-medium |
| D12 | Notewise: «Classic Unlimited — $16.99» разово, подписки $28.99–129.99/год | https://notewise.dev/pricing | неизвестно | medium |
| D13 | GoodNotes на Android: Essential $11.99/год, Pro $35.99/год | https://support.goodnotes.com/hc/en-us/articles/13651365090575-Changes-to-Android-Windows-Yearly-Plan | 2025-09 | medium |
| D14 | Saber: офлайн бесплатно, донат за облако | https://github.com/saber-notes/saber/blob/main/pricing.md | неизвестно | medium |
| D16 | Подборка e-ink-приложений для Palma 2 Pro: ни одного платного блокнота | https://www.pocket-lint.com/e-ink-friendly-apps/ | неизвестно | medium |
| D17 | Tools for Boox — бесплатно | https://play.google.com/store/apps/details?id=com.toolsboox&hl=en_US | неизвестно | low |

Правдоподобные модели (без выдуманных цен): (a) донаты, как у Saber и `notable` — ближе всего к поведению Boox-ниши; (b) разовая покупка — паттерн Noteshelf и Notewise на Android, $10–17 по их прайсам; (c) freemium с дешёвым IAP, как у Squid, — примеров выручки на Boox-аудитории нет. Выручка Boox-блокнотов — неизвестно.

## Точечная проверка летальных звеньев (2026-09-18)

| # | Находка | Источник | Дата | Уверенность |
|---|---|---|---|---|
| E1 | StarNote — отдельный продукт Onyx для iPhone/iPad и Android-телефонов, не штатный Notes на Boox; бесплатно + IAP, Lifetime PRO $5.99 разово | https://apps.apple.com/app/id6751570915 ; https://play.google.com/store/apps/details?id=com.onyx.galaxy.global.note | проверено 2026-09-18 | medium |
| E2 | Официальные changelog'и V4.0 и V4.2 описывают жесты только навигационные (углы, края); undo/redo пальцами в штатном Notes не найдено | https://shop.boox.com/blogs/news/boox-firmware-v4-0 ; https://shop.boox.com/blogs/news/firmware-v4-2-update | 2025-01-21; 2026-04-21 | medium |
| E3 | `Ethran/notable`: обновлён 2026-09-17, 258 звёзд, только GitHub Releases; undo — двойной тап одним пальцем, выделение удержанием и протягиванием, скрытие панели — свайп тремя пальцами; redo пальцами и сглаживание не найдены | https://github.com/Ethran/notable ; https://github.com/Ethran/notable/releases | 2026-09-17 | medium |
| E5 | Письмо поддержки Boox автору (2026-09-21): onyxsdk-pen / -device / -base под BSD 3-Clause; Apache-2.0 относится только к демо-коду; можно упаковывать в закрытое приложение и раздавать через Google Play и APK; бесплатно, отдельная коммерческая лицензия не нужна; хранение артефактов в приватном репозитории разрешено; Google Play может выдавать ошибки сканера из-за reflection | письмо BOOX Support Team, у автора; текст лицензии https://opensource.org/licenses/BSD-3-Clause | 2026-09-21 | high (первичный источник — вендор) |
| E4 | `alexdremov/notate`: обновлён 2026-08-29, 51 звезда; undo — двойной тап двумя пальцами, лассо/прямоугольник через долгое нажатие; redo пальцами и сглаживание не найдены | https://github.com/alexdremov/notate | 2026-08-29 | medium |

## Не найдено / открытые вопросы

- Reddit (r/Onyx_Boox, r/eink) недоступен машинно: 2026-09-21 проверено 47 попытками — old.reddit требует логин, `search.json` отдаёт 403, все 10 проверенных зеркал redlib/libreddit мертвы или за анти-бот защитой, поисковики не дают ссылок на reddit.com. Это не молчание источника, а отсутствие доступа. Живые цитаты по C2/C3 может собрать только автор вручную из браузера с логином. Официальный community.boox.com тоже недоступен.
- Страницы сообщества поддержки Boox (help.boox.com/community) удалены — первоисточники B11, B12, A8, A14 не открыты.
- Жеста undo пальцами в changelog'ах V4.0/V4.2 нет [E2]; по одному треду help.boox.com в Notes есть настройка «Tap/Long press» для лассо пальцем (точный URL не зафиксирован) — проверить на устройстве автора.
- Лицензия Onyx SDK — закрыто письмом вендора [E5]. Осталось неизвестным: пройдёт ли сборка сканер Google Play с reflection в SDK.
- Накопленная база владельцев перьевых Boox — неизвестно.
