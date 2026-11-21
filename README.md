# Zen Thermostat Advanced: Hubitat driver

A replacement for the built-in Hubitat driver for the **Zen Within Zen-01** Zigbee thermostat. It works with every configuration the Zen supports, whether it runs on **batteries only** or from a **C-wire (24 VAC)**, and it's designed to deliver commands reliably even when the thermostat is on batteries.

## What it does

| Problem with the built-in driver | What this driver does |
|---|---|
| Commands get lost on battery units | Uses Zigbee **Poll Control**. The thermostat checks in on a schedule, the hub tells it to stay awake ("fast poll") and sends any queued commands while it's listening. |
| Retry doesn't work | Each command is **read back to confirm it took effect**. If it hasn't, it's sent again, up to *Command retries* times (default 3). Battery units are retried at their next check-in. C-wire units are retried every 30 s. |
| Thermostat sometimes reports a new value, then silently drops back to the old one | **Revert guard:** for 60 s after a command is confirmed, if the thermostat reports the *previous* value again, the driver logs `device reverted … - re-sending` and sends the command again (within the retry limit). One trade-off: if you change that same setting on the thermostat itself within those 60 s, back to what it was, the driver will undo your change once. |
| No way to tell when a command failed | `commandStatus` shows `ok`, `pending: <command>` or `failed: <command>`. On failure, the dashboard goes back to the thermostat's real value. |
| Wrong supported modes (every Zen reports "cooling and heating") | You set the supported modes from the **Zen config code**, or by hand. Unsupported modes are refused with a warning and never sent to the thermostat. |

## Install

1. In Hubitat, go to **Drivers Code → + New Driver** (or open the existing Zen driver if you're upgrading). Paste in `zen-thermostat-advanced.groovy` and click **Save**.
2. Open each thermostat's device page and change **Type** to *Zen Thermostat Advanced* (it's under the User section near the bottom of the list). Click **Save Device**.
3. Fill in the **Preferences** (see below) and click **Save Preferences**. Saving also runs Configure.
4. **Wake the thermostat straight away** by touching its screen, so it receives the configuration. If it misses it, the driver tries again at the next check-in.
5. In **Logs**, look for `configuration confirmed by device`.

To upgrade later, just paste in the new driver code and save it. You don't need to save each device's preferences again unless the release notes say so. A `DRIVER UPDATED` line appears in the log the first time the new code runs.

## Power: batteries or C-wire

The Zen always needs its 4 AA batteries fitted. With a C-wire connected, it runs from the HVAC system while it's on the wall, and only uses the batteries when the display is taken off the wall (installer manual, p.17).

**Power source** setting:
- **Auto-detect** (default): the driver reads the thermostat's MainsVoltage. A reading of 10 V or more means a C-wire is connected; the value appears in Data as `mainsVoltage`. Until there's a reading, the unit is treated as battery-powered and `powerSource` shows `unknown`. The Zen's own PowerSource value isn't used, because it reports "mains with battery backup" even on battery-only units; it's shown in Data as `reportedPowerSource` for reference. If the detected power source is wrong for your wiring, choose the setting yourself.
- **Batteries only:** commands are delivered at check-ins (Poll Control).
- **C-wire:** the thermostat is always listening, so commands go through straight away and failed ones are retried every 30 s. Check-ins carry on and are used for health monitoring.

## Supported modes

The Zen only shows the modes your wiring supports, but over Zigbee it reports "cooling and heating" whatever its real configuration. So set the modes using one of these options:

### Option 1: Zen config code (recommended)

Set **Supported modes source** to *Zen config code* and enter the 3-character code shown on the thermostat. Remove it from the wall, turn it over, and press **CONFIG** once to see the code. The driver decodes it using the tables in the installer manual (p.20–21):

| Position | Meaning |
|---|---|
| 1st digit: heating | 0 none · 1 one-stage fuel (gas/oil) · 2 one-stage electric · 3 one-stage heat pump · 4 one-stage hydronic · 6 two-stage fuel · 7 two-stage electric · 8 two-stage heat pump · 9 two-stage hydronic |
| 2nd digit: cooling | 0 none · 1 one-stage · 6 two-stage |
| Letter A–P | Fan control yes/no, emergency heat yes/no, and changeover valve type. B, D, F … P have fan control; C, D, G, H, K, L, O, P have emergency heat. |

For example:
- `10A` gives *off, heat*, fan *auto*.
- `11B` gives *off, heat, cool, auto*, fan *auto, on*.
- `31P` gives *off, heat, cool, auto, emergency heat*, fan *auto, on*.

The decoded result appears in the device's Data section as `zenConfig`.

**Auto mode:** the Zen only offers Auto if it has been enabled in the thermostat's own Advanced settings. Turn off *Auto (heat/cool) mode enabled on the thermostat* if yours hasn't been.

The settings page only shows the options the chosen source uses. After changing **Supported modes source**, click **Save Preferences** and the page updates. Hidden settings keep their values, so switching back restores them. The *Auto mode enabled on the thermostat* switch only appears when the unit has both heating and cooling.

### Option 2: Manual

Use the Heat, Cool, Emergency Heat and fan mode settings.

### Option 3: Auto-detect from device

This uses what the Zen reports about itself. It isn't recommended, because every Zen tested reports "cooling and heating".

## Advanced features

| Feature | Where | Notes |
|---|---|---|
| Running stage | `runningStage` attribute | `idle`, `heat stage 1`, `heat stage 2`, `cool stage 1`, `cool stage 2`, `emergency heat`, `fan`. Shows which stage a 2-stage system is running. |
| Temperature calibration | Preference, from −2.5 to +2.5 °C | Written to the thermostat, so it also changes what the thermostat controls to. It's verified and retried like any other command. |
| Display units | Preference: Don't change, °C or °F | Sets the units on the thermostat's own screen. The current value is shown in Data as `displayUnits`. |
| Touchscreen lock | Preference: Don't change, Unlocked or Locked | Child or tamper lock. The current value is shown in Data as `touchscreenLock`. |
| Discover Attributes | Command button | A diagnostic that lists every attribute the thermostat supports, including Zen's own manufacturer-specific ones. See below. |

If a Zen doesn't support one of these settings, it refuses the write. You'll see `failed: <setting>` with "device rejected write (unsupported attribute)" in the log, and nothing else is affected.

### Discover Attributes (diagnostics)

**Discover Attributes** lists every Zigbee attribute the thermostat supports. On Zen firmware `1158-0003-00000220` it found:

| Cluster | Attributes |
|---|---|
| Basic 0x0000 | 0000–0005, 0007 PowerSource |
| Power 0x0001 | 0000 MainsVoltage, 0020 BatteryVoltage, 0031 BatterySize, 0033 BatteryQuantity, 0035 alarm mask, 0036–0039 battery thresholds, 003E BatteryAlarmState |
| Poll Control 0x0020 | 0000–0006 |
| Thermostat 0x0201 | 0000, 0003–0006, 0010 calibration, 0011, 0012, 0015–0019, 001B, 001C, 001E, 0023 setpoint hold, 0029 running state |
| Fan 0x0202 | 0000 FanMode, 0001 FanModeSequence |
| UI configuration 0x0204 | 0000 display units, 0001 keypad lockout (answers reads; sometimes slow to answer discovery) |
| Diagnostics 0x0B05 | 011B average MAC retries, 011C last message LQI, 011D last message RSSI |
| 0xFE11 (Zen manufacturer cluster) | No attributes. Asking with Zen's manufacturer code is refused. |
| Alarms 0x0009 | not present |
| Zen manufacturer-specific | none: every cluster answers "manufacturer command not supported" |

No attribute holds the **Filter Change** or **Demand Response** status, so **they're shown only on the thermostat's screen and can't be read over Zigbee**. Scheduling, eco and setpoint hold are left to Hubitat.

Discovery also asks the thermostat for its **full cluster list** (ZDO Active Endpoints and Simple Descriptor). The list is saved in Data as `inClusters` / `outClusters`, and any cluster the driver doesn't already handle is discovered automatically. If the Zen has an **Alarms cluster (0x0009)**, the driver binds it. From then on, any alarm the thermostat sends, such as a filter-change alert, is logged as a warning (`ALARM from thermostat: cluster 0x… code 0x…`) and saved in the `lastAlarm` attribute, so you can trigger a Rule Machine notification from it. Anything else the thermostat sends that the driver doesn't recognise is logged as `UNHANDLED …`.

To run it on your own unit, click the button. Battery units answer over the next one or two check-ins, and anything that hasn't answered is asked again. Each cluster gets a single `DISCOVERY cluster 0x…` line in the log, and the run ends with a `DISCOVERY finished` line. The results are also saved in the device's Data section (`attrs_0201` and so on). A heat-only 2-wire unit and a heat/cool unit gave identical results, so the Zen's Zigbee interface doesn't change with how it's wired. If your firmware shows something different from the table, please share the log.

## Settings explained

- **Command retries** (default 3): how many times a command is sent again before it's marked `failed`.
- **Retry interval** (default 120 s, and at most 30 s on C-wire): how long to wait before sending again when the thermostat hasn't checked in.
- **Device check-in interval** (default 2 min): how often a battery-powered thermostat wakes up to collect commands. This is the trade-off between battery life and responsiveness; 2 to 5 minutes is sensible.
- **Long poll interval** (default 20 s): how often the thermostat asks its parent router for messages between check-ins.
- **Fast poll window** (default 10 s): how long the thermostat stays awake after a check-in when there's work for it.

The thermostat reports its own Poll Control limits: the shortest check-in and long poll intervals, and the longest fast poll window. The driver keeps your settings within them, and shows the limits in Data as `pollControlLimits`.

## Battery

`batteryVoltage` is the pack voltage **as the Zen reports it**. The Zen doesn't report a percentage, so the driver works out battery % from this voltage:

1. It subtracts the *Zen voltage reading offset*, 1.0 V by default. Zens tested so far read about 1.0 V high: around 7.0 V on regulated 1.5 V Li-ion cells, which really give 6.0 V.
2. It divides by 4 to get the voltage per cell.
3. It looks the result up on the discharge curve for the selected *Battery type*.

| Battery type | 100% at (per cell) | 0% at (per cell) | Notes |
|---|---|---|---|
| Li-ion rechargeable 1.5 V (regulated, the default) | ≥ 1.45 V | 1.10 V | The voltage stays flat until the cell's protection circuit cuts out, so % sits near 100 and then falls quickly. Treat the low-battery warning as "recharge now". |
| Alkaline | 1.60 V | 1.00 V | Declines steadily, so % is a fair guide. |
| Lithium (non-rechargeable, e.g. Energizer Ultimate) | 1.75 V | 1.20 V | Stays high for most of its life, then drops steeply. |
| NiMH rechargeable 1.2 V | 1.40 V | 1.00 V | Stays around 1.2–1.25 V for most of its life. |
| Custom | your setting | your setting | Straight line between the two *Custom type only* voltages, using the reported voltage with no offset. |

When battery % first drops to 20% or below, the driver logs a **low battery** warning. It only warns again after % has gone back above 50%, for example after you recharge.

The thermostat also has its own low-battery thresholds (the voltages at which it shows the **Battery Low** icon), which appear in Data as `zenBatteryThresholds`. It also has its own alarm flag, which the driver shows as the `batteryAlarm` attribute: `none`, `low` or `below minimum`. A warning is logged when that alarm goes on. Because this comes from the thermostat itself, it matches the icon on its screen whatever battery type you use.

To check the offset, look at `batteryVoltage` right after putting in fresh cells. If the reading is about 1.0 V higher than the true pack voltage (4 × the cell voltage), the offset is right. Otherwise, set the offset to that difference.

On a C-wire unit, the batteries are only used while the display is off the wall, so they should last a very long time.

## Notifying yourself when a command fails

In Rule Machine, set the trigger to *Custom Attribute → commandStatus* on the thermostat, with the condition "contains `failed`". Use a notification as the action. You can set up a low-battery alert the same way, using `battery` ≤ 20.

## Other attributes

- `commandStatus`, `pendingCommands`: the state of the delivery queue
- `lastCheckin`, `healthStatus`: `lastCheckin` is updated **at most every 15 minutes**, so the Events tab and the hub database don't fill up. `healthStatus` still uses every check-in, and turns `offline` after about 3 missed check-ins.
- `powerSource`: `battery`, `mains`, `dc` or `unknown`
- `batteryAlarm`: the thermostat's own low-battery alarm (`none`, `low`, `below minimum`)
- `lastAlarm`: the last alarm the thermostat sent (only if it has an Alarms cluster)
- `lqi`, `rssi`: radio link quality and signal strength (dBm) of the thermostat's last message, from its Diagnostics cluster. They're updated on Configure and Refresh. Useful if a battery unit misses commands: RSSI below about −80 dBm, or LQI below about 100, suggests adding a Zigbee repeater nearby.
- `controlSequence`: the raw wiring configuration the device reports, for diagnostics only

There's also a **Clear Pending Commands** button, which drops anything still in the queue.

## Testing checklist

1. On a heat-only unit, run `setThermostatMode("cool")` and then `heat()`. The first should log a warning and do nothing. The second should work.
2. Change the heating setpoint from a dashboard. `commandStatus` should go to `pending: heatingSetpoint`, then to `ok`, and the thermostat screen should match.
3. Failure path: take one battery out and change the setpoint. After the retries run out, `commandStatus` should show `failed: heatingSetpoint` and the setpoint should go back to its old value.
4. Over the next day, check that `lastCheckin` keeps updating and the battery level stays steady.
