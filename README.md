# HavocCalendar

A December Advent Calendar for **Paper 1.21.x** (Java 21). Players open one door per day from December 1 to 25 and get the rewards you configure for each day.

## Build

```bash
mvn clean package
# -> target/HavocCalendar-1.0.0.jar
```
Drop the jar in `plugins/`, start the server, then edit `config.yml` and `rewards.yml`.

## Commands

| Command | Permission | Description |
|---|---|---|
| `/calendar`, `/advent`, `/havoccalendar` | `havoccalendar.use` (default: everyone) | Open the calendar |
| `/havoccalendar reload` | `havoccalendar.admin` (default: op) | Reload config.yml + rewards.yml |
| `/havoccalendar reset <player> [day]` | `havoccalendar.admin` | Reset one day, or all days, for this year |
| `/havoccalendar setday <1-31\|off>` | `havoccalendar.admin` | Pretend it is that day in December (for testing) |
| `/havoccalendar info` | `havoccalendar.admin` | Show the date, time zone and override status |

## Project layout

```
src/main/java/com/havoc/havoccalendar/
├── HavocCalendarMain.java        entry point, tasks, messages, settings
├── gui/CalendarGUI.java          layout pattern, item building, state resolution
├── gui/CalendarHolder.java       custom InventoryHolder used to recognise the GUI
├── listener/CalendarListener.java click/drag protection, claiming, join reminder
├── data/DataManager.java         data.yml storage (per UUID, per year), async + atomic saves
├── command/CommandManager.java   executor + tab completer
├── reward/RewardManager.java     rewards.yml loader + reward actions
└── util/DateUtils.java, TextUtil.java, SoundUtil.java
src/main/resources/plugin.yml, config.yml, rewards.yml
```

## Notes

- **Colours:** all text accepts `&a`, `&#RRGGBB`, `&x&R&R…` and MiniMessage tags, even mixed in one line.
- **Yearly reset:** claims are saved under the year, so the calendar starts fresh every December and old years stay in `data.yml`.
- **States:** available (glowing), claimed (`MINECART`, crossed out), locked (`RED_STAINED_GLASS_PANE`) and missed (`COAL`, only shown when `allow-retroactive-claims: false`).
- **Layout:** `gui.pattern` is a 6×9 grid of characters. It must contain exactly 25 `D` slots.
- **`/setday`** is kept in memory only. Use `testing.override-day` in config.yml to keep it across restarts.
- **Custom heads:** set `item-material: PLAYER_HEAD` and `head-texture:` to a base64 value, a textures.minecraft.net URL or a texture hash.
