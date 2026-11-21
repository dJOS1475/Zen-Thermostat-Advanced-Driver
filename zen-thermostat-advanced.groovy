/**
 *  Zen Thermostat Advanced (Zigbee) - driver for Hubitat Elevation
 *
 *  Works with battery-only and C-wire (24 VAC) powered Zen thermostats.
 *
 *  Why this driver exists:
 *   - On battery power the Zen is a *sleepy* end device. Its parent router only buffers an
 *     outgoing message for ~7.5 s, so commands sent at a random moment are usually lost.
 *     This driver uses the Zigbee Poll Control cluster (0x0020): the thermostat checks in on a
 *     schedule, the hub tells it to "fast poll", and queued commands are delivered while it is
 *     awake. Every command is verified by reading the value back, and retried a configurable
 *     number of times (default 3) before being reported as failed.
 *   - Supported thermostat / fan modes can be set manually per unit (or auto-detected), and
 *     unsupported modes are refused instead of being sent to the device.
 *
 *  Device: Zen Within Zen-01 (firmware 1158-0003-00000220), endpoint 01, ZigBee HA 1.2.
 *
 *  Licensed under the Apache License, Version 2.0
 *
 *  Changelog:
 *   2.4.0  Setpoints sent after a mode change confirms; re-send if thermostat overrides a setpoint; exact setpoint matching
 *   2.3.2  Settings page shows only the options for the chosen modes source
 *   2.3.1  Discovery finishes reliably; no false "battery alarm cleared" on first reading
 *   2.3.0  Display units and touchscreen lock restored; C-wire detection uses MainsVoltage only
 *   2.2.1  Discovery checks Zen manufacturer cluster 0xFE11; LQI/RSSI attributes
 *   2.2.0  Discovery reads cluster list; Alarms cluster logging; unhandled messages logged
 *   2.1.1  Discovery progress kept in memory (no duplicate lines)
 *   2.1.0  Zen battery thresholds and batteryAlarm; C-wire detection; Poll Control limits respected
 *   2.0.1  Removed programmingMode
 *   2.0.0  Renamed "Advanced": Zen config code modes, power source, running stage, calibration, attribute discovery
 *   1.0.9  Quieter check-in refusal logs; lastCheckin updated at most every 15 min
 *   1.0.8  Cooling setpoint hidden on heat-only units; battery voltage shown to 1 decimal
 *   1.0.7  Battery type setting with per-type discharge curves and voltage offset
 *   1.0.6  Revert guard: re-send if the thermostat drops back to the old value
 *   1.0.5  Thread-safe command queue; write acks matched to the right command
 *   1.0.4  Log banners for driver updates and settings saves
 *   1.0.3  Commands confirmed by the thermostat's write-accepted reply
 *   1.0.2  Battery % calibration; decoded default responses; quieter info logs
 *   1.0.1  Longer awake window while configuration is pending
 *   1.0.0  Initial release: Poll Control delivery, verified retries, manual supported modes
 */

import groovy.json.JsonOutput
import groovy.transform.Field

@Field static final String DRIVER_VERSION = "2.4.0"

// Cluster IDs
@Field static final int CL_BASIC      = 0x0000
@Field static final int CL_POWER      = 0x0001
@Field static final int CL_POLL       = 0x0020
@Field static final int CL_THERMOSTAT = 0x0201
@Field static final int CL_FAN        = 0x0202
@Field static final int CL_ALARMS     = 0x0009
@Field static final int CL_UI         = 0x0204   // Thermostat UI Configuration (listed by the Zen, but ignores discovery)
@Field static final int CL_DIAG       = 0x0B05   // Diagnostics: last message LQI / RSSI
// Clusters already handled (or of no interest) - any others found in the cluster list are discovered automatically
@Field static final List KNOWN_CLUSTERS = [0x0000, 0x0001, 0x0003, 0x0004, 0x0005, 0x0020, 0x0201, 0x0202]
@Field static final String ZEN_MFG_CODE = "1158"  // Zen Within manufacturer code (from firmware ID 1158-...)

// Thermostat attributes
@Field static final int AT_LOCAL_TEMP   = 0x0000
@Field static final int AT_ABS_MIN_HEAT = 0x0003
@Field static final int AT_ABS_MAX_HEAT = 0x0004
@Field static final int AT_ABS_MIN_COOL = 0x0005
@Field static final int AT_ABS_MAX_COOL = 0x0006
@Field static final int AT_COOL_SP      = 0x0011
@Field static final int AT_HEAT_SP      = 0x0012
@Field static final int AT_MIN_HEAT_LIM = 0x0015
@Field static final int AT_MAX_HEAT_LIM = 0x0016
@Field static final int AT_MIN_COOL_LIM = 0x0017
@Field static final int AT_MAX_COOL_LIM = 0x0018
@Field static final int AT_DEADBAND     = 0x0019
@Field static final int AT_CTRL_SEQ     = 0x001B
@Field static final int AT_SYSTEM_MODE  = 0x001C
@Field static final int AT_RUNNING_STATE = 0x0029
@Field static final int AT_CALIBRATION  = 0x0010

// Zigbee data types
@Field static final int DT_INT16  = 0x29
@Field static final int DT_ENUM8  = 0x30
@Field static final int DT_UINT16 = 0x21
@Field static final int DT_UINT32 = 0x23
@Field static final int DT_UINT8  = 0x20
@Field static final int DT_BMAP16 = 0x19
@Field static final int DT_INT8   = 0x28
@Field static final Map DT_NAMES = [0x10: "bool", 0x18: "bmap8", 0x19: "bmap16", 0x20: "uint8", 0x21: "uint16",
                                    0x23: "uint32", 0x28: "int8", 0x29: "int16", 0x30: "enum8", 0x31: "enum16",
                                    0x42: "string", 0xE2: "UTC"]

// Zen basic configuration code (installer manual p.20-21): <heating digit><cooling digit><letter A-P>
@Field static final Map ZEN_HEAT_TYPES = [0: "no heating", 1: "1-stage fuel (gas/oil)", 2: "1-stage electric",
                                          3: "1-stage heat pump", 4: "1-stage hydronic", 6: "2-stage fuel (gas/oil)",
                                          7: "2-stage electric", 8: "2-stage heat pump", 9: "2-stage hydronic"]
@Field static final Map ZEN_COOL_TYPES = [0: "no cooling", 1: "1-stage cooling", 6: "2-stage cooling"]

// Command queue lives in memory, shared by all threads for a device and guarded by a lock.
// Hubitat's `state` is loaded per execution and saved at the end, so a command and a parse() running
// at the same time overwrite each other's queue changes. `state.pending` is kept only as a mirror
// (for display and to restore the queue after a hub reboot or driver code save).
@Field static final java.util.concurrent.ConcurrentHashMap<String, Map> QUEUES = new java.util.concurrent.ConcurrentHashMap<String, Map>()
// Writes sent but not yet acknowledged, per device and cluster - used to match success-only write responses.
@Field static final java.util.concurrent.ConcurrentHashMap<String, Map> OUTSTANDING = new java.util.concurrent.ConcurrentHashMap<String, Map>()

// Recently confirmed commands, per device. The Zen has been seen to report a new value and then silently
// drop back to the old one a few seconds later; within this window that is treated as a revert and re-sent.
@Field static final java.util.concurrent.ConcurrentHashMap<String, Map> RECENT = new java.util.concurrent.ConcurrentHashMap<String, Map>()
@Field static final long REVERT_GUARD_MS = 60000L
// Within this time after confirming, ANY change away from the requested value is treated as the thermostat
// overriding it (e.g. the Zen applying its own auto-mode setpoints) and is re-sent. Nobody at the thermostat
// can produce values like 18.7 °C - its screen only steps in 0.5 °C.
@Field static final long OVERRIDE_GUARD_MS = 30000L
@Field static final String MODE_KEY = "0201_001C"

// Attribute discovery progress per device (in memory: concurrent parse() calls would overwrite each other in `state`)
@Field static final java.util.concurrent.ConcurrentHashMap<String, Set> DISC_OUT = new java.util.concurrent.ConcurrentHashMap<String, Set>()
@Field static final java.util.concurrent.ConcurrentHashMap<String, Set> DISC_LOGGED = new java.util.concurrent.ConcurrentHashMap<String, Set>()

// Per-cell discharge curves: [cell volts, percent], highest first. Applied to the corrected pack voltage / 4.
@Field static final Map BATTERY_CURVES = [
    // Regulated 1.5 V Li-ion (USB rechargeable): output is flat until the protection circuit cuts out,
    // so voltage gives little warning - % stays high, then falls quickly near the end.
    liion   : [[1.45, 100], [1.40, 50], [1.30, 20], [1.20, 5], [1.10, 0]],
    alkaline: [[1.60, 100], [1.50, 90], [1.40, 70], [1.30, 45], [1.20, 25], [1.10, 10], [1.00, 0]],
    // Lithium primary (Li-FeS2, e.g. Energizer Ultimate Lithium): high flat plateau, then a steep drop.
    lithium : [[1.75, 100], [1.60, 85], [1.50, 55], [1.40, 25], [1.30, 10], [1.20, 0]],
    nimh    : [[1.40, 100], [1.30, 85], [1.25, 65], [1.20, 40], [1.15, 20], [1.10, 8], [1.00, 0]]
]
@Field static final Map BATTERY_TYPE_NAMES = [liion: "Li-ion rechargeable 1.5 V (regulated)", alkaline: "Alkaline",
                                              lithium: "Lithium (non-rechargeable)", nimh: "NiMH rechargeable 1.2 V",
                                              custom: "Custom (use voltages below)"]

@Field static final Map SYSTEM_MODE_TO_NAME = [0: "off", 1: "auto", 3: "cool", 4: "heat", 5: "emergency heat"]
@Field static final Map NAME_TO_SYSTEM_MODE = ["off": 0, "auto": 1, "cool": 3, "heat": 4, "emergency heat": 5]
@Field static final Map FAN_MODE_TO_NAME = [4: "on", 5: "auto"]
@Field static final Map NAME_TO_FAN_MODE = ["on": 4, "auto": 5]

metadata {
    definition(name: "Zen Thermostat Advanced", namespace: "dJOS", author: "Derek Osborn",
               importUrl: "https://raw.githubusercontent.com/dJOS1475/Zen-Thermostat-Advanced-Driver/main/zen-thermostat-advanced.groovy") {
        capability "Actuator"
        capability "Sensor"
        capability "Thermostat"
        capability "ThermostatHeatingSetpoint"
        capability "ThermostatCoolingSetpoint"
        capability "ThermostatMode"
        capability "ThermostatFanMode"
        capability "ThermostatOperatingState"
        capability "TemperatureMeasurement"
        capability "Battery"
        capability "Configuration"
        capability "Refresh"
        capability "HealthCheck"
        capability "PowerSource"

        attribute "commandStatus", "string"
        attribute "pendingCommands", "number"
        attribute "lastCheckin", "string"
        attribute "healthStatus", "enum", ["online", "offline", "unknown"]
        attribute "controlSequence", "string"
        attribute "batteryVoltage", "number"
        attribute "runningStage", "string"
        attribute "lastAlarm", "string"
        attribute "lqi", "number"
        attribute "rssi", "number"
        attribute "batteryAlarm", "string"

        command "clearPendingCommands"
        command "discoverAttributes"

        fingerprint profileId: "0104", endpointId: "01",
                    inClusters: "0000,0001,0003,0004,0005,0020,0201,0202,0204,0B05",
                    outClusters: "000A,0019",
                    manufacturer: "Zen Within", model: "Zen-01",
                    deviceJoinName: "Zen Thermostat"
    }

    preferences {
        input name: "modeSource", type: "enum", title: "Supported modes source",
              options: ["manual": "Manual (use switches below)",
                        "code"  : "Zen config code (e.g. 31P)",
                        "auto"  : "Auto-detect from device (unreliable on Zen)"],
              defaultValue: "manual", required: true
        // Only the settings the chosen source uses are shown (the page refreshes after Save Preferences).
        // Hidden settings keep their values.
        String src = modeSource ?: "manual"
        if (src == "code") {
            input name: "zenConfigCode", type: "text", title: "Zen config code",
                  description: "The 3-character code set on the back of the thermostat (CONFIG button), e.g. 10A, 11B, 31N, 86P."
        }
        if (src == "manual") {
            input name: "allowHeat", type: "bool", title: "Supports Heat", defaultValue: true
            input name: "allowCool", type: "bool", title: "Supports Cool", defaultValue: true
        }
        if (src in ["manual", "auto"]) {
            input name: "allowEmergencyHeat", type: "bool", title: "Supports Emergency Heat", defaultValue: false,
                  description: src == "auto" ? "The thermostat can't report this, so it's set here." : ""
            input name: "fanModesSetting", type: "enum", title: src == "auto" ? "Fan modes (used if the thermostat doesn't report them)" : "Supported fan modes",
                  options: ["auto": "Auto only", "auto,on": "Auto + On"], defaultValue: "auto,on", required: true
        }
        // Auto mode only matters when the unit has both heating and cooling
        String code = (zenConfigCode ?: "").trim()
        boolean codeHeatCool = code.length() == 3 && code.charAt(0) != ('0' as char) && code.charAt(1) != ('0' as char)
        if ((src == "code" && codeHeatCool) || (src == "manual" && allowHeat != false && allowCool != false) || src == "auto") {
            input name: "allowAuto", type: "bool", title: "Auto (heat/cool) mode enabled on the thermostat", defaultValue: true,
                  description: "Zen's Auto mode must be switched on in its Advanced settings."
        }

        input name: "powerMode", type: "enum", title: "Power source",
              options: ["auto": "Auto-detect", "battery": "Batteries only", "cwire": "C-wire (24 VAC), batteries as backup"],
              defaultValue: "auto", required: true,
              description: "C-wire units are always listening, so failed commands are retried every 30 s instead of waiting for check-ins."

        input name: "maxRetries", type: "number", title: "Command retries before giving up",
              defaultValue: 3, range: "0..20", required: true
        input name: "retryInterval", type: "number", title: "Retry interval (seconds) if no check-in arrives",
              defaultValue: 120, range: "30..3600", required: true

        input name: "checkinInterval", type: "number", title: "Device check-in interval (minutes)",
              defaultValue: 2, range: "1..60", required: true,
              description: "How often the thermostat wakes to collect queued commands. Shorter = more responsive, slightly more battery."
        input name: "longPollInterval", type: "number", title: "Long poll interval (seconds)",
              defaultValue: 20, range: "5..3600", required: true
        input name: "fastPollTimeout", type: "number", title: "Fast poll window (seconds)",
              defaultValue: 10, range: "2..60", required: true

        input name: "tempReportMin", type: "number", title: "Temperature report min interval (s)", defaultValue: 60
        input name: "tempReportMax", type: "number", title: "Temperature report max interval (s)", defaultValue: 1800
        input name: "tempReportChange", type: "decimal", title: "Temperature report change threshold (°C)", defaultValue: 0.2

        input name: "tempCalibration", type: "decimal", title: "Temperature calibration (°C, -2.5 to +2.5)",
              defaultValue: 0, range: "-2.5..2.5",
              description: "Written to the thermostat (LocalTemperatureCalibration), so it also affects what it controls to. Ignored if the Zen doesn't support it."
        input name: "displayUnits", type: "enum", title: "Thermostat display units",
              options: ["nochange": "Don't change", "C": "Celsius", "F": "Fahrenheit"], defaultValue: "nochange"
        input name: "keypadLock", type: "enum", title: "Touchscreen lock",
              options: ["nochange": "Don't change", "0": "Unlocked", "1": "Locked"], defaultValue: "nochange"

        input name: "batteryType", type: "enum", title: "Battery type",
              options: BATTERY_TYPE_NAMES, defaultValue: "liion", required: true
        input name: "voltageOffset", type: "decimal", title: "Zen voltage reading offset (V)",
              defaultValue: 1.0,
              description: "Subtracted from the voltage the Zen reports before applying the battery curve. Your Zens read ~1.0 V high (7.0 V on 6.0 V regulated Li-ion cells)."
        input name: "batteryFullV", type: "decimal", title: "Custom type only: reported voltage shown as 100%",
              defaultValue: 7.0
        input name: "batteryEmptyV", type: "decimal", title: "Custom type only: reported voltage shown as 0%",
              defaultValue: 5.0

        input name: "logEnable", type: "bool", title: "Enable debug logging (auto-off after 30 min)", defaultValue: true
        input name: "txtEnable", type: "bool", title: "Enable description text logging", defaultValue: true
    }
}

/* =====================================================================================
 *  Lifecycle
 * ===================================================================================== */

void installed() {
    log.info "${device.displayName}: installed (driver ${DRIVER_VERSION})"
    state.driverVersion = DRIVER_VERSION
    initState()
    sendEvent(name: "healthStatus", value: "unknown")
    sendEvent(name: "commandStatus", value: "ok")
    sendEvent(name: "pendingCommands", value: 0)
    publishSupported()
}

void updated() {
    checkVersion()
    log.info "${device.displayName}: ════════ SETTINGS SAVED (driver v${DRIVER_VERSION}) ════════"
    log.info "${device.displayName}: modes=${supportedModesPreview()} fan=${supportedFanModes()} source=${modeSource ?: 'manual'}" +
             "${modeSource == 'code' ? ' code=' + (zenConfigCode ?: '?') : ''} | power=${powerMode ?: 'auto'}${isPowered() ? ' (powered)' : ''} | " +
             "retries=${safeInt(maxRetries, 3)} retryInterval=${retrySecs()}s | " +
             "checkin=${safeInt(checkinInterval, 2)}min longPoll=${safeInt(longPollInterval, 20)}s fastPoll=${safeInt(fastPollTimeout, 10)}s | " +
             "battery=${batteryType ?: 'liion'} offset=${voltageOffset ?: 1.0}V | debug=${logEnable} txt=${txtEnable}"
    initState()
    unschedule()
    if (logEnable) runIn(1800, "logsOff")
    ["ui_displayUnits", "ui_keypadLockout", "ui_scheduleProgrammingVisibility"].each { device.removeDataValue(it) }   // old probe data
    publishSupported()
    publishZenConfig()
    publishPowerSource()
    if (state.lastBattV != null && !state.hasBattPct) sendBattery(batteryPercent(state.lastBattV as BigDecimal))
    scheduleHealthCheck()
    configure()
    applyAdvancedSettings()
}

void configure() {
    checkVersion()
    logInfo "configure: binding clusters, setting up reporting and Poll Control. Wake the thermostat (touch the screen) to make sure it receives this."
    initState()
    publishSupported()
    state.configPending = true
    state.configAttempts = 0
    sendConfig()
    scheduleHealthCheck()
}

void refresh() {
    checkVersion()
    logDebug "refresh"
    state.refreshPending = true
    sendZigbee(refreshCmds())
}

void ping() {
    refresh()
}

void logsOff() {
    log.warn "${device.displayName}: debug logging disabled"
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}

/** Logs a banner the first time new driver code runs (saving driver code calls no method, so check lazily). */
private void checkVersion() {
    if (state.driverVersion != DRIVER_VERSION) {
        log.info "${device.displayName}: ════════ DRIVER UPDATED ${state.driverVersion ?: '(unknown)'} → v${DRIVER_VERSION} ════════"
        state.driverVersion = DRIVER_VERSION
    }
}

/** Mode list for the settings banner (same as supportedModes, safe before state exists). */
private List<String> supportedModesPreview() {
    try { return supportedModes() } catch (e) { return [] }
}

private void initState() {
    if (state.pending == null) state.pending = [:]
    if (state.confirmed == null) state.confirmed = [:]
    if (state.limits == null) state.limits = [:]
    if (state.detected == null) state.detected = [:]
}

private void scheduleHealthCheck() {
    unschedule("healthCheck")
    runEvery5Minutes("healthCheck")
}

private void sendConfig() {
    state.configAttempts = (state.configAttempts ?: 0) + 1
    state.lastConfigSent = now()
    sendZigbee(configCmds())
}

private List<String> configCmds() {
    String dni = device.deviceNetworkId
    String ep = device.endpointId ?: "01"
    String zid = device.zigbeeId
    List<String> cmds = []

    // Bindings so reports / check-ins come to the hub
    List<Integer> bindClusters = [CL_POWER, CL_POLL, CL_THERMOSTAT, CL_FAN]
    if (state.hasAlarms) bindClusters << CL_ALARMS   // so alarm notifications (e.g. filter) reach the hub
    bindClusters.each { cl ->
        cmds += "zdo bind 0x${dni} 0x${ep} 0x01 0x${hex4(cl)} {${zid}} {}"
        cmds += "delay 200"
    }
    if (state.hasAlarms) cmds += zigbee.readAttribute(CL_ALARMS, 0x0000, [:], 200)   // AlarmCount

    // Poll Control (sleepy-device delivery). Check-in and fast poll are writable attributes in quarter-seconds;
    // long poll interval is set with the Set Long Poll Interval command (0x02).
    long checkinQs = (safeInt(checkinInterval, 2) * 60L) * 4L
    long longPollQs = safeInt(longPollInterval, 20) * 4L
    Map lim = state.pollLimits ?: [:]
    if (lim.checkinMinQs && checkinQs < (lim.checkinMinQs as long)) {
        logInfo "check-in interval raised to the thermostat's minimum (${(lim.checkinMinQs as long) / 4} s)"
        checkinQs = lim.checkinMinQs as long
    }
    if (lim.longPollMinQs && longPollQs < (lim.longPollMinQs as long)) longPollQs = lim.longPollMinQs as long
    if (longPollQs > checkinQs) longPollQs = checkinQs
    int fastQs = fastPollQs()
    cmds += zigbee.writeAttribute(CL_POLL, 0x0000, DT_UINT32, (int) checkinQs, [:], 200)
    cmds += zigbee.writeAttribute(CL_POLL, 0x0003, DT_UINT16, fastQs, [:], 200)
    cmds += "he cmd 0x${dni} 0x${ep} 0x0020 0x02 {${leHex(longPollQs, 4)}}"
    cmds += "delay 200"

    // Reporting
    int tMin = safeInt(tempReportMin, 60)
    int tMax = safeInt(tempReportMax, 1800)
    int tChg = Math.max(1, (((tempReportChange ?: 0.2) as BigDecimal) * 100).setScale(0, BigDecimal.ROUND_HALF_UP).intValue())
    cmds += zigbee.configureReporting(CL_THERMOSTAT, AT_LOCAL_TEMP, DT_INT16, tMin, tMax, tChg, [:], 200)
    cmds += zigbee.configureReporting(CL_THERMOSTAT, AT_HEAT_SP, DT_INT16, 1, 3600, 10, [:], 200)
    cmds += zigbee.configureReporting(CL_THERMOSTAT, AT_COOL_SP, DT_INT16, 1, 3600, 10, [:], 200)
    cmds += zigbee.configureReporting(CL_THERMOSTAT, AT_SYSTEM_MODE, DT_ENUM8, 1, 3600, null, [:], 200)
    cmds += zigbee.configureReporting(CL_THERMOSTAT, AT_RUNNING_STATE, DT_BMAP16, 1, 3600, null, [:], 200)
    cmds += zigbee.configureReporting(CL_FAN, 0x0000, DT_ENUM8, 1, 3600, null, [:], 200)
    cmds += zigbee.configureReporting(CL_POWER, 0x0020, DT_UINT8, 3600, 21600, 1, [:], 200)
    cmds += zigbee.configureReporting(CL_POWER, 0x003E, 0x1B, 60, 21600, null, [:], 200)   // BatteryAlarmState (bmap32)

    // Read capabilities / limits once
    cmds += zigbee.readAttribute(CL_THERMOSTAT, [AT_CTRL_SEQ, AT_DEADBAND, AT_MIN_HEAT_LIM, AT_MAX_HEAT_LIM,
                                                 AT_MIN_COOL_LIM, AT_MAX_COOL_LIM], [:], 200)
    cmds += zigbee.readAttribute(CL_FAN, 0x0001, [:], 200)
    cmds += zigbee.readAttribute(CL_THERMOSTAT, AT_CALIBRATION, [:], 200)
    cmds += zigbee.readAttribute(CL_UI, [0x0000, 0x0001], [:], 200)
    // Zen's own battery thresholds (when its "Battery Low" icon appears), battery spec, and Poll Control limits
    cmds += zigbee.readAttribute(CL_POWER, [0x0031, 0x0033, 0x0036, 0x0037, 0x0038, 0x0039], [:], 200)
    cmds += zigbee.readAttribute(CL_POLL, [0x0004, 0x0005, 0x0006], [:], 200)
    cmds += refreshCmds()
    return cmds
}

private List<String> refreshCmds() {
    List<String> cmds = []
    cmds += zigbee.readAttribute(CL_THERMOSTAT, [AT_LOCAL_TEMP, AT_HEAT_SP, AT_COOL_SP, AT_SYSTEM_MODE, AT_RUNNING_STATE], [:], 200)
    cmds += zigbee.readAttribute(CL_FAN, 0x0000, [:], 200)
    cmds += zigbee.readAttribute(CL_POWER, [0x0020, 0x0021], [:], 200)
    cmds += zigbee.readAttribute(CL_POWER, [0x0000, 0x003E], [:], 200)   // MainsVoltage (C-wire), BatteryAlarmState
    cmds += zigbee.readAttribute(CL_BASIC, 0x0007, [:], 200)             // PowerSource
    cmds += zigbee.readAttribute(CL_DIAG, [0x011C, 0x011D], [:], 200)    // last message LQI / RSSI
    return cmds
}

/* =====================================================================================
 *  Supported modes
 * ===================================================================================== */

List<String> supportedModes() {
    List<String> modes = ["off"]
    boolean heat, cool, emer
    Map code = (modeSource == "code") ? parseZenCode(zenConfigCode) : null
    if (code) {
        heat = code.heat; cool = code.cool; emer = code.emergency
    } else if (modeSource == "auto" && state.detected?.ctrlSeq != null) {
        int cs = state.detected.ctrlSeq as int
        heat = cs in [2, 3, 4, 5]
        cool = cs in [0, 1, 4, 5]
        emer = allowEmergencyHeat == true
    } else {
        heat = allowHeat != false
        cool = allowCool != false
        emer = allowEmergencyHeat == true
    }
    if (heat) modes << "heat"
    if (cool) modes << "cool"
    if (heat && cool && allowAuto != false) modes << "auto"
    if (heat && emer) modes << "emergency heat"
    return modes
}

List<String> supportedFanModes() {
    Map code = (modeSource == "code") ? parseZenCode(zenConfigCode) : null
    if (code) return code.fan ? ["auto", "on"] : ["auto"]
    if (modeSource == "auto" && state.detected?.fanSeq != null) {
        return (state.detected.fanSeq as int) == 4 ? ["auto", "on"] : ["auto"]
    }
    return (fanModesSetting ?: "auto,on").tokenize(",")
}

/**
 * Decode a Zen basic configuration code (e.g. "31P") per the installer manual.
 * Letter A-P is a 4-bit value: bit0 fan control, bit1 emergency heat, bit2 changeover valve cools (O), bit3 has changeover valve.
 * Returns null if the code is missing or invalid.
 */
private Map parseZenCode(String raw) {
    String c = raw?.trim()?.toUpperCase()
    if (!c || !(c ==~ /[0-9][0-9][A-P]/)) return null
    int h = Character.getNumericValue(c.charAt(0))
    int k = Character.getNumericValue(c.charAt(1))
    if (!ZEN_HEAT_TYPES.containsKey(h) || !ZEN_COOL_TYPES.containsKey(k)) return null
    int bits = (int) c.charAt(2) - (int) ('A' as char)
    return [code: c, heat: h != 0, cool: k != 0, heatType: ZEN_HEAT_TYPES[h], coolType: ZEN_COOL_TYPES[k],
            heatStages: h == 0 ? 0 : (h >= 6 ? 2 : 1), coolStages: k == 0 ? 0 : (k == 6 ? 2 : 1),
            heatPump: h in [3, 8],
            fan: (bits & 1) != 0, emergency: (bits & 2) != 0 && h != 0,
            changeover: (bits & 8) != 0 ? ((bits & 4) != 0 ? "O (energised in cool)" : "B (energised in heat)") : "none"]
}

private void publishZenConfig() {
    if (modeSource != "code") { device.removeDataValue("zenConfig"); return }
    Map z = parseZenCode(zenConfigCode)
    if (!z) {
        log.warn "${device.displayName}: Zen config code '${zenConfigCode ?: ''}' is not valid (expected e.g. 10A, 31P) - using manual switches"
        device.removeDataValue("zenConfig")
        return
    }
    String desc = "${z.code}: ${z.heatType}, ${z.coolType}, fan control ${z.fan ? 'yes' : 'no'}, " +
                  "emergency heat ${z.emergency ? 'yes' : 'no'}, changeover valve ${z.changeover}"
    updateDataValue("zenConfig", desc)
    logInfo "Zen config ${desc}"
}

private boolean coolSupported() { return "cool" in supportedModes() }

/** True when the thermostat runs from a C-wire (always listening) rather than batteries alone. */
private boolean isPowered() {
    if (powerMode == "cwire") return true
    if (powerMode == "battery") return false
    return detectedSource() in ["mains", "dc"]
}

/** Auto-detected source, from the MainsVoltage reading (24 VAC C-wire). */
private String detectedSource() {
    // Only MainsVoltage counts: the Zen's PowerSource attribute is static ("mains with battery backup")
    // even on battery-only units, so it can't tell the two apart.
    if (state.mainsVolts != null) return ((state.mainsVolts as BigDecimal) >= 10) ? "mains" : "battery"
    return "unknown"   // treated as battery until a MainsVoltage reading arrives
}

/** C-wire units hear commands immediately, so retry quickly; battery units mostly retry at check-ins. */
private int retrySecs() {
    int s = safeInt(retryInterval, 120)
    return isPowered() ? Math.min(s, 30) : s
}

private void publishPowerSource() {
    String src = powerMode == "cwire" ? "mains" : (powerMode == "battery" ? "battery" : detectedSource())
    if (device.currentValue("powerSource") != src) logInfo "power source is ${src}"
    sendEvent(name: "powerSource", value: src)
}

/** Optional settings written to the thermostat through the verified command queue (retried, confirmed). */
private void applyAdvancedSettings() {
    // Temperature calibration: int8 in 0.1 °C, sent as an unsigned byte
    BigDecimal cal = (tempCalibration ?: 0) as BigDecimal
    int calRaw = (cal * 10).setScale(0, BigDecimal.ROUND_HALF_UP).intValue()
    calRaw = Math.max(-25, Math.min(25, calRaw)) & 0xFF
    def curCal = state.confirmed?.get(attrKey(CL_THERMOSTAT, AT_CALIBRATION))
    if ((curCal != null && (curCal as int) != calRaw) || (curCal == null && calRaw != 0)) {
        logInfo "setting temperature calibration to ${cal}°C"
        enqueue(CL_THERMOSTAT, AT_CALIBRATION, DT_INT8, calRaw, "temperatureCalibration", 0)
    }
    if (displayUnits in ["C", "F"]) {
        int u = displayUnits == "F" ? 1 : 0
        def cur = state.confirmed?.get(attrKey(CL_UI, 0x0000))
        if (cur == null || (cur as int) != u) {
            logInfo "setting thermostat display units to °${displayUnits}"
            enqueue(CL_UI, 0x0000, DT_ENUM8, u, "displayUnits", 0)
        }
    }
    if (keypadLock in ["0", "1"]) {
        int l = keypadLock as int
        def cur = state.confirmed?.get(attrKey(CL_UI, 0x0001))
        if (cur == null || (cur as int) != l) {
            logInfo "setting touchscreen lock to ${l ? 'locked' : 'unlocked'}"
            enqueue(CL_UI, 0x0001, DT_ENUM8, l, "keypadLock", 0)
        }
    }
}

private void publishSupported() {
    sendEvent(name: "supportedThermostatModes", value: JsonOutput.toJson(supportedModes()), isStateChange: true)
    sendEvent(name: "supportedThermostatFanModes", value: JsonOutput.toJson(supportedFanModes()), isStateChange: true)
    // Heat-only units: the Zen still stores a cooling setpoint, but nothing uses it - keep it off the device page.
    if (!coolSupported() && device.currentValue("coolingSetpoint") != null) device.deleteCurrentState("coolingSetpoint")
}

/* =====================================================================================
 *  Commands
 * ===================================================================================== */

void off()           { setThermostatMode("off") }
void heat()          { setThermostatMode("heat") }
void cool()          { setThermostatMode("cool") }
void auto()          { setThermostatMode("auto") }
void emergencyHeat() { setThermostatMode("emergency heat") }
void fanAuto()       { setThermostatFanMode("auto") }
void fanOn()         { setThermostatFanMode("on") }
void fanCirculate()  { setThermostatFanMode("circulate") }
void setSchedule(sched) { logDebug "setSchedule not supported" }

void setThermostatMode(String mode) {
    mode = mode?.toString()?.trim()?.toLowerCase()
    if (!(mode in supportedModes())) {
        log.warn "${device.displayName}: thermostat mode '${mode}' is not supported by this unit (supported: ${supportedModes()}) - ignored"
        return
    }
    logInfo "setting thermostat mode to ${mode}"
    enqueue(CL_THERMOSTAT, AT_SYSTEM_MODE, DT_ENUM8, NAME_TO_SYSTEM_MODE[mode], "thermostatMode", 0)
}

void setThermostatFanMode(String mode) {
    mode = mode?.toString()?.trim()?.toLowerCase()
    if (!(mode in supportedFanModes())) {
        log.warn "${device.displayName}: fan mode '${mode}' is not supported by this unit (supported: ${supportedFanModes()}) - ignored"
        return
    }
    logInfo "setting fan mode to ${mode}"
    enqueue(CL_FAN, 0x0000, DT_ENUM8, NAME_TO_FAN_MODE[mode], "thermostatFanMode", 0)
}

void setHeatingSetpoint(temp) {
    if (!("heat" in supportedModes())) {
        log.warn "${device.displayName}: heating not supported on this unit - setHeatingSetpoint ignored"
        return
    }
    int raw = clampRaw(toRawC(temp), state.limits?.minHeat, state.limits?.maxHeat)
    logInfo "setting heating setpoint to ${temp}°${scale()}"
    List extra = []
    if (coolSupported()) {
        Integer cool = currentRaw(AT_COOL_SP)
        int db = deadbandRaw()
        if (cool != null && raw + db > cool) {
            int newCool = clampRaw(raw + db, state.limits?.minCool, state.limits?.maxCool)
            log.warn "${device.displayName}: heating setpoint is within the ${db / 100}°C deadband of cooling - raising cooling setpoint"
            extra << newCool
        }
    }
    enqueue(CL_THERMOSTAT, AT_HEAT_SP, DT_INT16, raw, "heatingSetpoint", spTolerance())
    if (extra) enqueue(CL_THERMOSTAT, AT_COOL_SP, DT_INT16, extra[0] as int, "coolingSetpoint", spTolerance())
}

void setCoolingSetpoint(temp) {
    if (!coolSupported()) {
        logInfo "cooling not supported on this unit - setCoolingSetpoint(${temp}) ignored"
        return
    }
    int raw = clampRaw(toRawC(temp), state.limits?.minCool, state.limits?.maxCool)
    logInfo "setting cooling setpoint to ${temp}°${scale()}"
    List extra = []
    Integer heat = currentRaw(AT_HEAT_SP)
    int db = deadbandRaw()
    if (heat != null && heat + db > raw) {
        int newHeat = clampRaw(raw - db, state.limits?.minHeat, state.limits?.maxHeat)
        log.warn "${device.displayName}: cooling setpoint is within the ${db / 100}°C deadband of heating - lowering heating setpoint"
        extra << newHeat
    }
    enqueue(CL_THERMOSTAT, AT_COOL_SP, DT_INT16, raw, "coolingSetpoint", spTolerance())
    if (extra) enqueue(CL_THERMOSTAT, AT_HEAT_SP, DT_INT16, extra[0] as int, "heatingSetpoint", spTolerance())
}

void clearPendingCommands() {
    Map queue = q()
    synchronized (queue) {
        log.warn "${device.displayName}: clearing ${queue.size()} pending command(s)"
        queue.clear()
        outstanding().clear()
        recent().clear()
    }
    unschedule("retryCheck")
    mirrorQueue()
    updateQueueStatus()
}

/* =====================================================================================
 *  Command queue / delivery engine
 * ===================================================================================== */

private Map q() {
    String id = device.id.toString()
    Map m = QUEUES.get(id)
    if (m == null) {
        Map fresh = new java.util.concurrent.ConcurrentHashMap()
        (state.pending ?: [:]).each { k, v -> fresh.put(k, new LinkedHashMap(v as Map)) }   // restore after reboot / code save
        m = QUEUES.putIfAbsent(id, fresh) ?: fresh
    }
    return m
}

private Map outstanding() {
    String id = device.id.toString()
    Map m = OUTSTANDING.get(id)
    if (m == null) {
        Map fresh = new java.util.concurrent.ConcurrentHashMap()
        m = OUTSTANDING.putIfAbsent(id, fresh) ?: fresh
    }
    return m
}

private Map recent() {
    String id = device.id.toString()
    Map m = RECENT.get(id)
    if (m == null) {
        Map fresh = new java.util.concurrent.ConcurrentHashMap()
        m = RECENT.putIfAbsent(id, fresh) ?: fresh
    }
    return m
}

/** Remember a confirmed command so a quick revert by the device can be detected. */
private void rememberConfirmed(String key, Map p) {
    recent().put(key, new LinkedHashMap(p) + [confirmedAt: now()])
}

/**
 * Called when the device reports a value for an attribute with no pending command.
 * Returns true if it looks like the device reverted a recently confirmed command (and it was re-sent).
 */
private boolean checkRevert(String key, int raw) {
    Map r = recent().get(key)
    if (r == null) return false
    if (now() - (r.confirmedAt as long) > REVERT_GUARD_MS) { recent().remove(key); return false }
    int tol = r.tolerance as int
    if (Math.abs((r.value as int) - raw) <= tol) return false          // still holding our value
    long age = now() - (r.confirmedAt as long)
    boolean reverted = (r.prev != null) ? Math.abs((r.prev as int) - raw) <= tol : true
    boolean overridden = !reverted && age <= OVERRIDE_GUARD_MS
    if (!reverted && !overridden) { recent().remove(key); return false }   // later change to something else (e.g. at the thermostat)
    recent().remove(key)

    String what = reverted ? "reverted" : "changed"
    int maxAttempts = safeInt(maxRetries, 3) + 1
    long secs = age.intdiv(1000L)
    if ((r.attempts as int) >= maxAttempts) {
        reportFailure(r + [key: key], "device ${what} it to ${raw} ${secs}s after confirming, no retries left")
        updateQueueStatus()
        return false
    }
    log.warn "${device.displayName}: device ${what} ${r.label} to ${raw} ${secs}s after confirming ${r.value} - re-sending"
    Map entry = new LinkedHashMap(r)
    entry.remove("confirmedAt")
    Map queue = q()
    synchronized (queue) {
        if (!queue.containsKey(key)) queue.put(key, entry)
    }
    sendPending([key])
    runIn(retrySecs(), "retryCheck", [overwrite: true])
    return true
}

private void mirrorQueue() {
    Map queue = q()
    synchronized (queue) {
        state.pending = queue.collectEntries { k, v -> [(k): new LinkedHashMap(v as Map)] }
    }
}

/** Remove a queue entry (caller holds the lock); clears the cluster's outstanding-write count if nothing else is pending there. */
private Map removeEntry(String key) {
    Map queue = q()
    Map p = queue.remove(key)
    if (p != null && !queue.values().any { (it.cluster as int) == (p.cluster as int) }) outstanding().remove(p.cluster as int)
    return p
}

private void afterQueueChange() {
    mirrorQueue()
    updateQueueStatus()
    if (q().isEmpty()) {
        unschedule("retryCheck")
        stopFastPoll()
        return
    }
    // Release setpoints that were held for a mode change once it has gone through
    if (!q().containsKey(MODE_KEY)) {
        List<String> held
        synchronized (q()) { held = q().findAll { k, p -> (p.attempts as int) == 0 }.keySet().toList() }
        if (held) {
            logDebug "mode change done - sending held ${held.collect { q().get(it)?.label }.join(', ')}"
            sendPending(held)
        }
    }
}

private void enqueue(int cluster, int attr, int dataType, int value, String label, int tolerance) {
    checkVersion()
    initState()
    String key = attrKey(cluster, attr)
    def prev = state.confirmed?.get(key)
    Map entry = [cluster: cluster, attr: attr, dataType: dataType, value: value, label: label,
                 tolerance: tolerance, attempts: 0, lastSent: 0L, prev: prev]
    recent().remove(key)
    Map queue = q()
    synchronized (queue) {
        queue.put(key, entry)   // newest command for an attribute wins
    }
    state.lastFailure = null

    // Optimistic UI update (reverted if the command ultimately fails)
    emitFromRaw(cluster, attr, value, "(pending)")

    sendPending([key])
    runIn(retrySecs(), "retryCheck", [overwrite: true])
}

/** Send the given pending keys (or all). Each send counts as an attempt. */
private void sendPending(List<String> keys = null) {
    Map queue = q()
    List<String> cmds = []
    List<Map> failed = []
    int maxAttempts = safeInt(maxRetries, 3) + 1
    synchronized (queue) {
        boolean modePending = queue.containsKey(MODE_KEY)
        (keys ?: queue.keySet().toList()).each { String key ->
            Map p = queue.get(key)
            if (p == null) return
            // Setpoints wait until a pending mode change is confirmed: the Zen applies its own stored
            // setpoints when it changes mode, which would overwrite setpoints that arrive at the same time.
            if (modePending && key != MODE_KEY && (p.cluster as int) == CL_THERMOSTAT && (p.attr as int) in [AT_HEAT_SP, AT_COOL_SP]) {
                logDebug "holding ${p.label}=${p.value} until the mode change is confirmed"
                return
            }
            if ((p.attempts as int) >= maxAttempts) {
                failed << removeEntry(key) + [key: key]
                return
            }
            p.attempts = (p.attempts as int) + 1
            p.lastSent = now()
            int cl = p.cluster as int
            outstanding().put(cl, ((outstanding().get(cl) ?: 0) as int) + 1)
            cmds += zigbee.writeAttribute(cl, p.attr as int, p.dataType as int, p.value as int, [:], 200)
            cmds += zigbee.readAttribute(cl, p.attr as int, [:], 200)
            logDebug "sending ${p.label}=${p.value} (attempt ${p.attempts}/${maxAttempts})"
        }
    }
    failed.each { reportFailure(it, "no confirmation after ${safeInt(maxRetries, 3)} retries") }
    if (cmds) sendZigbee(cmds)
    afterQueueChange()
}

void retryCheck() {
    Map queue = q()
    if (queue.isEmpty()) return
    long interval = retrySecs() * 1000L
    List<String> due
    synchronized (queue) {
        due = queue.findAll { k, p -> now() - (p.lastSent as long) >= interval - 2000 }.keySet().toList()
    }
    if (due) {
        logDebug "retryCheck: ${due.size()} command(s) due (no check-in since last send)"
        sendPending(due)
    }
    if (!q().isEmpty()) runIn(retrySecs(), "retryCheck", [overwrite: true])
}

/** Confirm a pending command if the value the device reports matches it. */
private void confirm(String key, int reportedValue) {
    Map queue = q()
    Map p = null
    synchronized (queue) {
        Map cur = queue.get(key)
        if (cur != null && Math.abs((cur.value as int) - reportedValue) <= (cur.tolerance as int)) p = removeEntry(key)
    }
    if (p != null) {
        logInfo "confirmed ${p.label} (read-back, attempt ${p.attempts})"
        rememberConfirmed(key, p)
        afterQueueChange()
    }
}

private void failCommand(String key, String reason) {
    Map queue = q()
    Map p
    synchronized (queue) { p = removeEntry(key) }
    if (p == null) return
    reportFailure(p + [key: key], reason)
    afterQueueChange()
}

private void reportFailure(Map p, String reason) {
    log.warn "${device.displayName}: command ${p.label}=${p.value} FAILED - ${reason}"
    sendEvent(name: "commandStatus", value: "failed: ${p.label}", isStateChange: true,
              descriptionText: "${device.displayName} ${p.label} failed: ${reason}")
    // Revert to last confirmed value from the device
    def confirmedVal = state.confirmed?.get(p.key)
    if (confirmedVal != null) emitFromRaw(p.cluster as int, p.attr as int, confirmedVal as int, "(reverted)")
    state.lastFailure = p.label
}

private void updateQueueStatus() {
    List<Map> pending = new ArrayList(q().values())
    sendEvent(name: "pendingCommands", value: pending.size())
    String status = pending ? "pending: " + pending*.label.unique().join(", ")
                            : (state.lastFailure ? "failed: ${state.lastFailure}" : "ok")
    if (device.currentValue("commandStatus") != status) sendEvent(name: "commandStatus", value: status)
}

private void handleCheckin() {
    // Health uses every check-in (markOnline); the visible attribute is updated at most every 15 min
    // to avoid ~700 events/day per device in the Events table and hub database.
    if (now() - ((state.lastCheckinEvent ?: 0L) as long) >= 15 * 60000L) {
        sendEvent(name: "lastCheckin", value: new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone))
        state.lastCheckinEvent = now()
    }
    markOnline()
    String dni = device.deviceNetworkId
    String ep = device.endpointId ?: "01"
    boolean work = !q().isEmpty() || state.configPending || state.refreshPending || discoveryActive()
    state.lastCheckinWork = work
    // The Zen rejects fast poll timeouts above its FastPollTimeoutMax (status 0x85), so stay within it.
    int fastQs = fastPollQs()
    // Check-in Response (cmd 0x00): startFastPolling(bool) + fastPollTimeout(uint16, quarter-seconds)
    sendZigbee(["he cmd 0x${dni} 0x${ep} 0x0020 0x00 {${work ? '01' : '00'}${leHex(fastQs, 2)}}"])
    if (!work) return

    logDebug "check-in: device awake, delivering queued work"
    if (state.configPending) {
        if ((state.configAttempts ?: 0) <= safeInt(maxRetries, 3)) {
            sendConfig()
        } else {
            state.configPending = false
            log.warn "${device.displayName}: configuration could not be confirmed after retries"
        }
    }
    if (state.refreshPending) {
        state.refreshPending = false
        sendZigbee(refreshCmds())
    }
    if (discoveryActive()) {
        state.discoveryAttempts = (state.discoveryAttempts ?: 0) + 1
        if (state.discoveryAttempts > safeInt(maxRetries, 3) + 1) {
            log.warn "${device.displayName}: DISCOVERY finished - no answer for: ${discOut().join(', ')}"
            discOut().clear()
        } else {
            sendZigbee(discoveryCmds())
        }
    }
    if (!q().isEmpty()) sendPending(null)
}

/* =====================================================================================
 *  Attribute discovery (diagnostics - finds what each Zen supports, incl. Zen-specific attributes)
 * ===================================================================================== */

void discoverAttributes() {
    checkVersion()
    log.info "${device.displayName}: ════════ ATTRIBUTE DISCOVERY ════════ results appear as DISCOVERY lines (battery units: at the next check-in)"
    state.discoveryAttempts = 0
    discLogged().clear()
    Set out = discOut()
    out.clear()
    out << "endpoints"
    out << "clusters_${device.endpointId ?: '01'}"
    [CL_BASIC, CL_POWER, CL_POLL, CL_THERMOSTAT, CL_FAN].each { out << discoveryTarget(it, false) }
    [CL_BASIC, CL_POWER, CL_THERMOSTAT, CL_FAN].each { out << discoveryTarget(it, true) }
    [CL_BASIC, CL_POWER, CL_POLL, CL_THERMOSTAT, CL_FAN].each { device.removeDataValue("attrs_${hex4(it)}"); device.removeDataValue("attrs_${hex4(it)}_zen") }
    sendZigbee(discoveryCmds())
}

private Set discOut() {
    String id = device.id.toString()
    Set s = DISC_OUT.get(id)
    if (s == null) { Set fresh = java.util.concurrent.ConcurrentHashMap.newKeySet(); s = DISC_OUT.putIfAbsent(id, fresh) ?: fresh }
    return s
}

private Set discLogged() {
    String id = device.id.toString()
    Set s = DISC_LOGGED.get(id)
    if (s == null) { Set fresh = java.util.concurrent.ConcurrentHashMap.newKeySet(); s = DISC_LOGGED.putIfAbsent(id, fresh) ?: fresh }
    return s
}

private boolean discoveryActive() { return !discOut().isEmpty() }

private static String discoveryTarget(int cluster, boolean mfg) { return "${hex4(cluster)}${mfg ? '_zen' : ''}" }

/** Discovery requests for every cluster that hasn't answered yet (the device may sleep mid-batch). */
private List<String> discoveryCmds() {
    List<String> cmds = []
    new ArrayList(discOut()).each { String t ->
        if (t == "endpoints") { cmds += zdoActiveEndpointsCmd(); cmds += "delay 400"; return }
        if (t.startsWith("clusters_")) { cmds += zdoSimpleDescriptorCmd(t.substring(9)); cmds += "delay 400"; return }
        int cl = Integer.parseInt(t.substring(0, 4), 16)
        cmds += discoverCmd(cl, 0, t.endsWith("_zen"))
        cmds += "delay 400"
    }
    return cmds
}

private void discoveryDone(int cluster, boolean mfg) { finishTarget(discoveryTarget(cluster, mfg)) }

private void finishTarget(String target) {
    // remove() is atomic, so only the call that empties the set logs the finish line
    if (discOut().remove(target) && discOut().isEmpty()) {
        log.info "${device.displayName}: ════════ DISCOVERY finished ════════"
    }
}

private String swappedNwk() {
    String d = device.deviceNetworkId.padLeft(4, "0")
    return "${d.substring(2, 4)} ${d.substring(0, 2)}"
}

/** ZDO Active Endpoints request (0x0005) - which endpoints the thermostat has. */
private String zdoActiveEndpointsCmd() {
    return "he raw 0x${device.deviceNetworkId} 0 0 0x0005 {00 ${swappedNwk()}} {0x0000}"
}

/** ZDO Simple Descriptor request (0x0004) - the full list of clusters on an endpoint. */
private String zdoSimpleDescriptorCmd(String ep) {
    return "he raw 0x${device.deviceNetworkId} 0 0 0x0004 {00 ${swappedNwk()} ${ep}} {0x0000}"
}

/** Active Endpoints Response: [seq, status, nwkLo, nwkHi, count, endpoints...] */
private void handleActiveEndpoints(List data) {
    if (data.size() < 5 || data[1] != "00") {
        if (discLogged().add("endpoints:fail")) log.info "${device.displayName}: DISCOVERY endpoints: request failed (${data})"
        finishTarget("endpoints")
        return
    }
    int n = Integer.parseInt(data[4], 16)
    List<String> eps = (n > 0 && data.size() >= 5 + n) ? data.subList(5, 5 + n) : []
    updateDataValue("endpoints", eps.join(","))
    if (discLogged().add("endpoints:${eps.join(',')}")) log.info "${device.displayName}: DISCOVERY endpoints: ${eps.join(', ') ?: 'none'}"
    // Ask for the cluster list of any extra endpoints too (the main endpoint is already requested)
    int mainEp = Integer.parseInt(device.endpointId ?: "01", 16)
    eps.findAll { Integer.parseInt(it, 16) != mainEp }.each { String ep ->
        if (discLogged().add("clusters_req_${ep}") && !discOut().contains("clusters_${ep}")) {
            discOut() << "clusters_${ep}"
            sendZigbee([zdoSimpleDescriptorCmd(ep)])
        }
    }
    finishTarget("endpoints")
}

/**
 * Simple Descriptor Response: [seq, status, nwkLo, nwkHi, len, ep, profLo, profHi, devLo, devHi, ver,
 *                              inCount, in clusters (LE)..., outCount, out clusters (LE)...]
 */
private void handleSimpleDescriptor(List data) {
    if (data.size() < 12 || data[1] != "00") {
        if (discLogged().add("clusters:fail")) log.info "${device.displayName}: DISCOVERY cluster list: request failed (${data})"
        new ArrayList(discOut()).findAll { it.startsWith("clusters_") }.each { finishTarget(it) }
        return
    }
    String ep = data[5]
    String profile = data[7] + data[6]
    String devId = data[9] + data[8]
    int i = 11
    int inCount = Integer.parseInt(data[i++], 16)
    List<Integer> inCl = []
    for (int k = 0; k < inCount && i + 1 < data.size(); k++) { inCl << Integer.parseInt(data[i + 1] + data[i], 16); i += 2 }
    List<Integer> outCl = []
    if (i < data.size()) {
        int outCount = Integer.parseInt(data[i++], 16)
        for (int k = 0; k < outCount && i + 1 < data.size(); k++) { outCl << Integer.parseInt(data[i + 1] + data[i], 16); i += 2 }
    }
    String inTxt = inCl.collect { hex4(it) }.join(",")
    String outTxt = outCl.collect { hex4(it) }.join(",")
    boolean mainEp = Integer.parseInt(ep, 16) == Integer.parseInt(device.endpointId ?: "01", 16)
    updateDataValue(mainEp ? "inClusters" : "inClusters_${ep}", inTxt)
    updateDataValue(mainEp ? "outClusters" : "outClusters_${ep}", outTxt)
    if (discLogged().add("clusters_${ep}:${inTxt}|${outTxt}")) {
        log.info "${device.displayName}: DISCOVERY endpoint ${ep} (profile ${profile}, device type ${devId}): in ${inTxt ?: 'none'} | out ${outTxt ?: 'none'}"
    }

    if (mainEp && (CL_ALARMS in inCl)) {
        if (!state.hasAlarms) {
            log.info "${device.displayName}: thermostat has an Alarms cluster - binding it so alarms (such as filter change) are logged"
            state.hasAlarms = true
            sendZigbee(["zdo bind 0x${device.deviceNetworkId} 0x${ep} 0x01 0x${hex4(CL_ALARMS)} {${device.zigbeeId}} {}", "delay 200"] +
                       zigbee.readAttribute(CL_ALARMS, 0x0000, [:], 200))
        }
    } else if (mainEp) {
        state.hasAlarms = false
    }
    // Discover attributes on any cluster we don't already handle
    List<String> extra = []
    inCl.findAll { !(it in KNOWN_CLUSTERS) }.each { int cl ->
        List<Boolean> modes = cl >= 0xFC00 ? [false, true] : [false]   // manufacturer clusters: also ask with Zen's code
        modes.each { boolean mfg ->
            String t = discoveryTarget(cl, mfg)
            if (discLogged().add("queued_${t}")) { discOut() << t; extra << discoverCmd(cl, 0, mfg); extra << "delay 400" }
        }
    }

    if (extra) sendZigbee(extra)
    finishTarget("clusters_${ep}")
}

/** Alarm command (Alarms cluster, cmd 0x00): [alarmCode, clusterLo, clusterHi] */
private void handleAlarm(List data) {
    if (!data || data.size() < 3) { log.warn "${device.displayName}: ALARM from thermostat (unparsed): ${data}"; return }
    String code = data[0].toUpperCase()
    String cl = (data[2] + data[1]).toUpperCase()
    String text = "cluster 0x${cl} code 0x${code}"
    log.warn "${device.displayName}: ALARM from thermostat: ${text}"
    sendEvent(name: "lastAlarm", value: "${text} at ${new Date().format('yyyy-MM-dd HH:mm', location.timeZone)}",
              isStateChange: true, descriptionText: "${device.displayName} alarm ${text}")
}

/** Default Response to a Discover Attributes request: the cluster or manufacturer command isn't supported. */
private void handleDiscoverRefused(int cluster, boolean mfg, String status) {
    String why = ["C3": "cluster not supported", "82": "command not supported", "84": "manufacturer command not supported",
                  "81": "command not supported", "C4": "cluster not supported"][status?.toUpperCase()] ?: "status 0x${status}"
    updateDataValue("attrs_${discoveryTarget(cluster, mfg)}", "not supported (${why})")
    if (discLogged().add("${discoveryTarget(cluster, mfg)}:refused")) {
        log.info "${device.displayName}: DISCOVERY cluster 0x${hex4(cluster)}${mfg ? ' (Zen-specific)' : ''}: not supported (${why})"
    }
    discoveryDone(cluster, mfg)
}

/** ZCL Discover Attributes (global cmd 0x0C): start attribute id + max count, optionally manufacturer-specific. */
private String discoverCmd(int cluster, int startAttr, boolean mfg) {
    String start = leHex(startAttr, 2)
    String zcl = mfg ? "14 ${ZEN_MFG_CODE.substring(2, 4)} ${ZEN_MFG_CODE.substring(0, 2)} 00 0C ${start.substring(0, 2)} ${start.substring(2, 4)} 20"
                     : "10 00 0C ${start.substring(0, 2)} ${start.substring(2, 4)} 20"
    return "he raw 0x${device.deviceNetworkId} 1 0x${device.endpointId ?: '01'} 0x${hex4(cluster)} {${zcl}}"
}

private void handleDiscoverResponse(int cluster, List data, boolean mfg) {
    if (!data) return
    boolean complete = data[0] == "01"
    List<String> found = []
    int last = -1
    for (int i = 1; i + 2 < data.size(); i += 3) {
        int attr = Integer.parseInt(data[i + 1] + data[i], 16)
        int type = Integer.parseInt(data[i + 2], 16)
        found << "${hex4(attr)}:${DT_NAMES[type] ?: 'type 0x' + data[i + 2]}"
        last = attr
    }
    String key = "attrs_${discoveryTarget(cluster, mfg)}"
    String prev = device.getDataValue(key)
    // Duplicate responses happen (radio retries) - merge without repeating entries
    LinkedHashSet<String> all = new LinkedHashSet<String>()
    if (prev && !prev.startsWith("not supported") && prev != "none") all.addAll(prev.split(", ") as List)
    all.addAll(found)
    updateDataValue(key, all ? all.join(", ") : "none")
    // Radio retries deliver the same answer more than once - log each distinct answer only once
    if (discLogged().add("${discoveryTarget(cluster, mfg)}:${found.join(',')}")) {
        log.info "${device.displayName}: DISCOVERY cluster 0x${hex4(cluster)}${mfg ? ' (Zen-specific)' : ''}: " +
                 "${found ? found.join(', ') : 'none'}${complete ? '' : ' ...more'}"
    }
    if (!complete && last >= 0 && last < 0xFFFF && discOut().contains(discoveryTarget(cluster, mfg))) sendZigbee([discoverCmd(cluster, last + 1, mfg)])
    else if (complete) discoveryDone(cluster, mfg)
}

/** Configured fast poll window in quarter-seconds, capped at the thermostat's FastPollTimeoutMax if known. */
private int fastPollQs() {
    int qs = safeInt(fastPollTimeout, 10) * 4
    def max = state.pollLimits?.fastPollMaxQs
    return (max != null && (max as int) > 0) ? Math.min(qs, max as int) : qs
}

private void stopFastPoll() {
    sendZigbee(["he cmd 0x${device.deviceNetworkId} 0x${device.endpointId ?: '01'} 0x0020 0x01 {}"])
}

/* =====================================================================================
 *  Health
 * ===================================================================================== */

void healthCheck() {
    Long last = state.lastHeard as Long
    long limit = safeInt(checkinInterval, 2) * 60000L * 3 + 60000L
    if (last == null || now() - last > limit) {
        if (device.currentValue("healthStatus") != "offline") {
            log.warn "${device.displayName}: no contact for over ${limit / 60000} minutes - marking offline"
            sendEvent(name: "healthStatus", value: "offline")
        }
    }
}

private void markOnline() {
    state.lastHeard = now()
    if (device.currentValue("healthStatus") != "online") sendEvent(name: "healthStatus", value: "online")
}

/* =====================================================================================
 *  Parse
 * ===================================================================================== */

void parse(String description) {
    checkVersion()
    logDebug "parse: ${description}"
    initState()
    markOnline()
    Map msg = zigbee.parseDescriptionAsMap(description)
    if (!msg) return

    Integer cluster = msg.clusterInt ?: (msg.clusterId ? Integer.parseInt(msg.clusterId, 16) :
                                         (msg.cluster ? Integer.parseInt(msg.cluster, 16) : null))
    if (cluster == null) return

    // Cluster-specific / global commands (catchall)
    if (msg.clusterId && msg.command != null) {
        int cmd = Integer.parseInt(msg.command, 16)
        if (cluster == CL_POLL && cmd == 0x00 && msg.isClusterSpecific) {
            handleCheckin()
            return
        }
        if (cmd == 0x04 && !msg.isClusterSpecific) {   // Write Attributes Response
            handleWriteResponse(cluster, msg.data ?: [])
            return
        }
        if (cmd == 0x0D && !msg.isClusterSpecific) {   // Discover Attributes Response
            handleDiscoverResponse(cluster, msg.data ?: [], isMfg(msg))
            return
        }
        if (cmd == 0x07 && !msg.isClusterSpecific) {   // Configure Reporting Response
            handleConfigReportingResponse(cluster, msg.data ?: [])
            return
        }
        if (cluster == 0x8005) { handleActiveEndpoints(msg.data ?: []); return }    // ZDO Active Endpoints Response
        if (cluster == 0x8004) { handleSimpleDescriptor(msg.data ?: []); return }   // ZDO Simple Descriptor Response
        if (cluster == CL_ALARMS && cmd == 0x00 && msg.isClusterSpecific) {        // Alarm notification
            handleAlarm(msg.data ?: [])
            return
        }
        if (cluster == 0x8021) {                       // ZDO Bind Response: [seq, status]
            String st = msg.data?.size() > 1 ? msg.data[1] : "??"
            logDebug "bind response: ${st == '00' ? 'success' : 'status 0x' + st}"
            return
        }
        if (cmd == 0x0B && !msg.isClusterSpecific) {   // Default Response
            String dCmd = msg.data?.size() > 0 ? msg.data[0] : "??"
            String dSt = msg.data?.size() > 1 ? msg.data[1] : "??"
            if (dCmd?.toUpperCase() == "0C") {
                handleDiscoverRefused(cluster, isMfg(msg), dSt)
            } else if (dSt == "00") {
                logDebug "default response cluster 0x${hex4(cluster)} cmd 0x${dCmd}: success"
            } else {
                String why = ["7E": "not authorized / action denied", "81": "unsupported command",
                              "85": "invalid field", "87": "invalid value"][dSt?.toUpperCase()] ?: "status 0x${dSt}"
                String what = (cluster == CL_POLL) ? (["00": "Check-in Response", "01": "Fast Poll Stop",
                              "02": "Set Long Poll Interval"][dCmd] ?: "cmd 0x${dCmd}") : "cmd 0x${dCmd}"
                if (cluster == CL_POLL && dCmd == "00" && !state.lastCheckinWork) {
                    // Late/duplicate reply to a check-in that had nothing queued - harmless
                    logDebug "device refused ${what} (${why}) - nothing was queued, ignoring"
                } else {
                    log.warn "${device.displayName}: device refused ${what} on cluster 0x${hex4(cluster)} (${why})"
                }
            }
            return
        }
        if (cmd == 0x01 && !msg.isClusterSpecific && msg.attrId == null) {
            logDebug "read response (catchall) cluster 0x${hex4(cluster)}: ${msg.data}"
            return
        }
        // Anything else the thermostat sends on its own (HA profile) is logged, so unknown features show up
        if (msg.profileId == "0104" && msg.isClusterSpecific) {
            log.info "${device.displayName}: UNHANDLED message from thermostat: cluster 0x${hex4(cluster)} cmd 0x${msg.command} data ${msg.data}"
            return
        }
    }

    // Attribute reports / read responses
    if (msg.attrId != null) {
        handleAttribute(cluster, Integer.parseInt(msg.attrId, 16), msg.value, msg.encoding)
        msg.additionalAttrs?.each { Map a ->
            if (a.attrId != null) handleAttribute(cluster, Integer.parseInt(a.attrId, 16), a.value, a.encoding)
        }
    }

    // Opportunistic delivery: the device just transmitted, so it is awake right now.
    if (!q().isEmpty()) {
        List<String> due
        synchronized (q()) { due = q().findAll { k, p -> now() - (p.lastSent as long) > 15000 }.keySet().toList() }
        if (due) sendPending(due)
    }
}

private void handleAttribute(int cluster, int attr, String value, String encoding) {
    if (value == null) return
    String key = attrKey(cluster, attr)
    switch (cluster) {
        case CL_THERMOSTAT:
            switch (attr) {
                case AT_LOCAL_TEMP:
                    int tRaw = signed16(value)
                    if (tRaw == -32768) return   // invalid
                    sendTemp("temperature", tRaw)
                    break
                case AT_HEAT_SP:
                case AT_COOL_SP:
                case AT_SYSTEM_MODE:
                    int cRaw = (attr == AT_SYSTEM_MODE) ? Integer.parseInt(value, 16) : signed16(value)
                    deviceValue(cluster, attr, cRaw)
                    break
                case AT_RUNNING_STATE:
                    int bits = Integer.parseInt(value, 16)
                    String opState = "idle"
                    if (bits & 0x0009) opState = "heating"
                    else if (bits & 0x0012) opState = "cooling"
                    else if (bits & 0x0064) opState = "fan only"
                    if (device.currentValue("thermostatOperatingState") != opState) logInfo "operating state is ${opState}"
                    sendEvent(name: "thermostatOperatingState", value: opState,
                              descriptionText: "${device.displayName} operating state is ${opState}")
                    // Bits: 0 heat, 1 cool, 2 fan, 3 heat stage 2, 4 cool stage 2, 5 fan stage 2, 6 fan stage 3
                    String stage = "idle"
                    if (bits & 0x0001 && device.currentValue("thermostatMode") == "emergency heat") stage = "emergency heat"
                    else if (bits & 0x0008) stage = "heat stage 2"
                    else if (bits & 0x0001) stage = "heat stage 1"
                    else if (bits & 0x0010) stage = "cool stage 2"
                    else if (bits & 0x0002) stage = "cool stage 1"
                    else if (bits & 0x0064) stage = "fan"
                    if (device.currentValue("runningStage") != stage) logInfo "running stage is ${stage}"
                    sendEvent(name: "runningStage", value: stage)
                    break
                case AT_CALIBRATION:
                    deviceValue(cluster, attr, Integer.parseInt(value, 16) & 0xFF)
                    break

                case AT_CTRL_SEQ:
                    int cs = Integer.parseInt(value, 16)
                    state.detected.ctrlSeq = cs
                    String desc = [0: "cooling only", 1: "cooling w/ reheat", 2: "heating only", 3: "heating w/ reheat",
                                   4: "cooling and heating", 5: "cooling and heating w/ reheat"][cs] ?: "unknown"
                    sendEvent(name: "controlSequence", value: "${cs} (${desc})")
                    if (modeSource == "auto") publishSupported()
                    break
                case AT_DEADBAND:
                    state.limits.deadband = Integer.parseInt(value, 16) * 10   // 0.1°C -> 0.01°C
                    break
                case AT_MIN_HEAT_LIM: state.limits.minHeat = signed16(value); break
                case AT_MAX_HEAT_LIM: state.limits.maxHeat = signed16(value); break
                case AT_MIN_COOL_LIM: state.limits.minCool = signed16(value); break
                case AT_MAX_COOL_LIM: state.limits.maxCool = signed16(value); break
                case 0x001E:   // ThermostatRunningMode (what the device last ran: off/heat/cool)
                    updateDataValue("lastRunningMode", SYSTEM_MODE_TO_NAME[Integer.parseInt(value, 16)] ?: value)
                    break
                default:
                    logDebug "unhandled thermostat attribute 0x${hex4(attr)} = ${value}"
            }
            break
        case CL_FAN:
            if (attr == 0x0000) {
                deviceValue(cluster, attr, Integer.parseInt(value, 16))
            } else if (attr == 0x0001) {
                state.detected.fanSeq = Integer.parseInt(value, 16)
                if (modeSource == "auto") publishSupported()
            }
            break
        case CL_POWER:
            if (attr == 0x0000) {                       // MainsVoltage, 100 mV (24 VAC C-wire)
                int mv = Integer.parseInt(value, 16)
                if (mv != 0xFFFF) {
                    BigDecimal v = mv / 10.0
                    updateDataValue("mainsVoltage", "${v} V")
                    boolean wasPowered = isPowered()
                    state.mainsVolts = v
                    if (isPowered() != wasPowered) logInfo "C-wire ${isPowered() ? 'detected' : 'not detected'} (${v} V)"
                    publishPowerSource()
                }
            } else if (attr == 0x0031) {
                updateDataValue("batterySize", [1: "built-in", 2: "other", 3: "AA", 4: "AAA", 5: "C", 6: "D", 7: "CR2", 8: "CR123A"][Integer.parseInt(value, 16)] ?: value)
            } else if (attr == 0x0033) {
                updateDataValue("batteryQuantity", "${Integer.parseInt(value, 16)}")
            } else if (attr in [0x0036, 0x0037, 0x0038, 0x0039]) {
                if (state.battThresholds == null) state.battThresholds = [:]
                String name = [0x0036: "minimum", 0x0037: "threshold1", 0x0038: "threshold2", 0x0039: "threshold3"][attr]
                state.battThresholds[name] = Integer.parseInt(value, 16) / 10.0
                updateDataValue("zenBatteryThresholds", state.battThresholds.collect { k, v -> "${k} ${v} V" }.join(", "))
            } else if (attr == 0x003E) {                // BatteryAlarmState: bit0 below minimum, bits1-3 thresholds 1-3
                long bits = Long.parseLong(value, 16)
                String alarm = (bits & 0x1) ? "below minimum" : ((bits & 0xE) ? "low" : "none")
                String prevAlarm = device.currentValue("batteryAlarm")
                if (prevAlarm != alarm) {
                    if (alarm != "none") log.warn "${device.displayName}: thermostat reports battery ${alarm} - replace or recharge soon"
                    else if (prevAlarm != null) logInfo "battery alarm cleared"
                }
                sendEvent(name: "batteryAlarm", value: alarm)
            } else if (attr == 0x0021) {
                int pct = Math.min(100, (int) Math.round(Integer.parseInt(value, 16) / 2.0d))
                state.hasBattPct = true
                sendBattery(pct)
            } else if (attr == 0x0020) {
                BigDecimal volts = Integer.parseInt(value, 16) / 10.0
                sendEvent(name: "batteryVoltage", value: String.format("%.1f", volts), unit: "V")
                state.lastBattV = volts
                // Zen does not support BatteryPercentageRemaining (0x0021 -> status 0x86); map voltage instead
                if (!state.hasBattPct) sendBattery(batteryPercent(volts))
            }
            break
        case CL_POLL:
            if (attr in [0x0004, 0x0005, 0x0006]) {
                if (state.pollLimits == null) state.pollLimits = [:]
                long qs = Long.parseLong(value, 16)
                String name = [0x0004: "checkinMinQs", 0x0005: "longPollMinQs", 0x0006: "fastPollMaxQs"][attr]
                state.pollLimits[name] = qs
                updateDataValue("pollControlLimits", "check-in ≥ ${(state.pollLimits.checkinMinQs ?: 0) / 4} s, " +
                                "long poll ≥ ${(state.pollLimits.longPollMinQs ?: 0) / 4} s, " +
                                "fast poll ≤ ${state.pollLimits.fastPollMaxQs != null ? (state.pollLimits.fastPollMaxQs / 4) + ' s' : '?'}")
            } else {
                logDebug "poll control attribute 0x${hex4(attr)} = ${value}"
            }
            break
        case CL_BASIC:
            if (attr == 0x0007) {
                // Informational only - static on the Zen (reports mains + battery backup even on batteries)
                int ps = Integer.parseInt(value, 16)
                String src = [1: "mains", 2: "mains", 3: "battery", 4: "dc", 5: "mains", 6: "mains"][ps & 0x7F] ?: "unknown"
                updateDataValue("reportedPowerSource", "${src}${(ps & 0x80) ? ' + battery backup' : ''} (not used - static on Zen)")
                state.remove("detectedPower")
            }
            break
        default:
            if (cluster == CL_UI && attr in [0x0000, 0x0001]) {
                deviceValue(cluster, attr, Integer.parseInt(value, 16))
            } else if (cluster == CL_DIAG && attr in [0x011C, 0x011D]) {
                int v = Integer.parseInt(value, 16)
                if (attr == 0x011D && v > 127) v -= 256   // RSSI is int8 dBm
                sendEvent(name: attr == 0x011C ? "lqi" : "rssi", value: v, unit: attr == 0x011D ? "dBm" : null)
            } else if (cluster == CL_ALARMS && attr == 0x0000) {
                int n = Integer.parseInt(value, 16)
                updateDataValue("alarmCount", "${n}")
                logInfo "thermostat alarm table holds ${n} alarm(s)"
            } else {
                log.info "${device.displayName}: UNHANDLED attribute from thermostat: cluster 0x${hex4(cluster)} attr 0x${hex4(attr)} = ${value}"
            }
    }
}

/** A value the device reports for a commandable attribute. */
private void deviceValue(int cluster, int attr, int raw) {
    String key = attrKey(cluster, attr)
    state.confirmed[key] = raw
    Map p = q().get(key)
    if (p) {
        confirm(key, raw)
        if (q().containsKey(key)) {
            // Device still holds the old value; keep showing the requested value until confirmed or failed.
            logDebug "${p.label}: device reports ${raw}, waiting for ${p.value}"
            return
        }
    } else if (checkRevert(key, raw)) {
        return   // keep showing the requested value while it is re-sent
    }
    emitFromRaw(cluster, attr, raw, null)
}

private void handleWriteResponse(int cluster, List data) {
    if (!data || data[0] == "00") {
        // A success-only Write Attributes Response doesn't name the attribute. Count outstanding writes per
        // cluster: only when this ack is the LAST outstanding one and exactly one command for the cluster is
        // pending can we be sure it refers to that command. Otherwise wait for the read-back.
        Map queue = q()
        Map p = null
        String key = null
        int left
        int candidates = 0
        synchronized (queue) {
            Map out = outstanding()
            left = Math.max(0, ((out.get(cluster) ?: 0) as int) - 1)
            out.put(cluster, left)
            List<String> recent = queue.findAll { k, e ->
                (e.cluster as int) == cluster && now() - (e.lastSent as long) < 15000
            }.keySet().toList()
            candidates = recent.size()
            if (left == 0 && candidates == 1) {
                key = recent[0]
                p = removeEntry(key)
            }
        }
        if (p != null) {
            state.confirmed[key] = p.value
            logInfo "confirmed ${p.label} (device accepted write, attempt ${p.attempts})"
            rememberConfirmed(key, p)
            emitFromRaw(p.cluster as int, p.attr as int, p.value as int, null)
            afterQueueChange()
        } else if (candidates) {
            logDebug "write accepted on cluster 0x${hex4(cluster)} (${left} more write(s) in flight, ${candidates} pending - waiting)"
        }
        return
    }
    // Failure records: status, attrId (LE) repeated
    for (int i = 0; i + 2 < data.size(); i += 3) {
        String status = data[i]
        int attr = Integer.parseInt(data[i + 2] + data[i + 1], 16)
        String key = attrKey(cluster, attr)
        String reason = [ "86": "unsupported attribute", "87": "invalid value", "88": "read only",
                          "8D": "invalid data type" ][status?.toUpperCase()] ?: "status 0x${status}"
        if (q().containsKey(key)) {
            failCommand(key, "device rejected write (${reason})")
        } else {
            log.warn "${device.displayName}: write to 0x${hex4(cluster)}/0x${hex4(attr)} rejected (${reason})"
        }
    }
}

private void handleConfigReportingResponse(int cluster, List data) {
    boolean ok = !data || data[0] == "00"
    logDebug "configure reporting response cluster 0x${hex4(cluster)}: ${ok ? 'success' : data}"
    if (ok && cluster == CL_THERMOSTAT && state.configPending) {
        state.configPending = false
        logInfo "configuration confirmed by device"
        if (q().isEmpty()) stopFastPoll()
    }
}

/* =====================================================================================
 *  Events
 * ===================================================================================== */

private void emitFromRaw(int cluster, int attr, int raw, String suffix) {
    String note = suffix ? " ${suffix}" : ""
    if (cluster == CL_THERMOSTAT && attr == AT_HEAT_SP) {
        sendTemp("heatingSetpoint", raw, note)
        updateThermostatSetpoint()
    } else if (cluster == CL_THERMOSTAT && attr == AT_COOL_SP) {
        if (coolSupported()) sendTemp("coolingSetpoint", raw, note)   // tracked in state.confirmed either way
        updateThermostatSetpoint()
    } else if (cluster == CL_THERMOSTAT && attr == AT_SYSTEM_MODE) {
        String mode = SYSTEM_MODE_TO_NAME[raw] ?: "off"
        if (raw == 7) logInfo "device reports 'fan only' system mode"
        if (device.currentValue("thermostatMode") != mode) logInfo "mode is ${mode}${note}"
        sendEvent(name: "thermostatMode", value: mode, descriptionText: "${device.displayName} mode is ${mode}${note}")
        updateThermostatSetpoint()
    } else if (cluster == CL_UI && attr == 0x0000) {
        updateDataValue("displayUnits", raw == 1 ? "°F" : "°C")
    } else if (cluster == CL_UI && attr == 0x0001) {
        updateDataValue("touchscreenLock", raw == 0 ? "unlocked" : "locked (level ${raw})")
    } else if (cluster == CL_THERMOSTAT && attr == AT_CALIBRATION) {
        int signed = raw > 127 ? raw - 256 : raw
        updateDataValue("temperatureCalibration", "${signed / 10.0}°C")
    } else if (cluster == CL_FAN && attr == 0x0000) {
        String fan = FAN_MODE_TO_NAME[raw] ?: "auto"
        if (device.currentValue("thermostatFanMode") != fan) logInfo "fan mode is ${fan}${note}"
        sendEvent(name: "thermostatFanMode", value: fan, descriptionText: "${device.displayName} fan mode is ${fan}${note}")
    }
}

private void updateThermostatSetpoint() {
    String mode = device.currentValue("thermostatMode")
    def heat = device.currentValue("heatingSetpoint")
    def cool = device.currentValue("coolingSetpoint")
    def sp = (mode == "cool" && coolSupported()) ? cool : heat
    if (sp != null) sendEvent(name: "thermostatSetpoint", value: sp, unit: "°${scale()}")
}

private void sendTemp(String name, int rawC, String note = "") {
    BigDecimal v = fromRawC(rawC)
    String msg = "${name} is ${v}°${scale()}${note ?: ''}"
    def prev = device.currentValue(name)
    sendEvent(name: name, value: v, unit: "°${scale()}", descriptionText: "${device.displayName} ${msg}")
    if (!note && (prev == null || (prev as BigDecimal) != v)) logInfo msg
}

/** Reported pack voltage -> percent, using the selected battery type's curve. */
private int batteryPercent(BigDecimal reported) {
    String type = batteryType ?: "liion"
    if (type == "custom") {
        BigDecimal full = (batteryFullV ?: 7.0) as BigDecimal
        BigDecimal empty = (batteryEmptyV ?: 5.0) as BigDecimal
        if (full <= empty) full = empty + 1
        return Math.max(0, Math.min(100, ((reported - empty) / (full - empty) * 100).setScale(0, BigDecimal.ROUND_HALF_UP).intValue()))
    }
    BigDecimal cell = (reported - ((voltageOffset != null ? voltageOffset : 1.0) as BigDecimal)) / 4
    List curve = BATTERY_CURVES[type] ?: BATTERY_CURVES.liion
    if (cell >= (curve[0][0] as BigDecimal)) return 100
    for (int i = 1; i < curve.size(); i++) {
        BigDecimal vHi = curve[i - 1][0] as BigDecimal, vLo = curve[i][0] as BigDecimal
        int pHi = curve[i - 1][1] as int, pLo = curve[i][1] as int
        if (cell >= vLo) {
            return (pLo + (cell - vLo) / (vHi - vLo) * (pHi - pLo)).setScale(0, BigDecimal.ROUND_HALF_UP).intValue()
        }
    }
    return 0
}

private void sendBattery(int pct) {
    if (pct <= 20 && !state.lowBattWarned) {
        log.warn "${device.displayName}: battery low (${pct}%, ${state.lastBattV ?: '?'} V reported) - replace or recharge soon"
        state.lowBattWarned = true
    } else if (pct > 50) {
        state.lowBattWarned = false
    }
    if (device.currentValue("battery") == pct) { sendEvent(name: "battery", value: pct, unit: "%"); return }
    sendEvent(name: "battery", value: pct, unit: "%", descriptionText: "${device.displayName} battery is ${pct}%")
    logInfo "battery is ${pct}%"
}

/* =====================================================================================
 *  Helpers
 * ===================================================================================== */

private String scale() { return location?.temperatureScale ?: "C" }

/** Hub-scale temperature -> 0.01 °C, rounded to the Zen's resolution (0.5 °C or 1 °F). */
private int toRawC(temp) {
    BigDecimal t = new BigDecimal(temp.toString())
    BigDecimal c
    if (scale() == "F") {
        t = t.setScale(0, BigDecimal.ROUND_HALF_UP)
        c = (t - 32) * 5 / 9
    } else {
        c = (t * 2).setScale(0, BigDecimal.ROUND_HALF_UP) / 2
    }
    return (c * 100).setScale(0, BigDecimal.ROUND_HALF_UP) as int
}

private BigDecimal fromRawC(int raw) {
    BigDecimal c = raw / 100.0
    if (scale() == "F") return (c * 9 / 5 + 32).setScale(0, BigDecimal.ROUND_HALF_UP)
    return c.setScale(1, BigDecimal.ROUND_HALF_UP)
}

private Integer currentRaw(int attr) {
    def pendingVal = q().get(attrKey(CL_THERMOSTAT, attr))?.value
    if (pendingVal != null) return pendingVal as int
    def c = state.confirmed?.get(attrKey(CL_THERMOSTAT, attr))
    return c != null ? c as int : null
}

/** Setpoint match tolerance in 0.01 °C: just under 0.1 °C for °C hubs, just under 0.5 °F for °F hubs. */
private int spTolerance() { return scale() == "F" ? 27 : 9 }

private int deadbandRaw() { return (state.limits?.deadband ?: 200) as int }   // default 2.0 °C

private int clampRaw(int raw, lo, hi) {
    if (lo != null && raw < (lo as int)) { log.warn "${device.displayName}: setpoint raised to device minimum"; raw = lo as int }
    if (hi != null && raw > (hi as int)) { log.warn "${device.displayName}: setpoint lowered to device maximum"; raw = hi as int }
    return raw
}

private static int signed16(String hex) {
    int v = Integer.parseInt(hex, 16)
    return v > 32767 ? v - 65536 : v
}

private static boolean isMfg(Map msg) { return msg?.isManufacturerSpecific in [true, "true", "1", "01"] }

private static String hex4(int v) { return String.format("%04X", v) }

private static String attrKey(int cluster, int attr) { return "${hex4(cluster)}_${hex4(attr)}" }

/** Little-endian hex for a payload of the given byte length. */
private static String leHex(long value, int bytes) {
    StringBuilder sb = new StringBuilder()
    for (int i = 0; i < bytes; i++) sb.append(String.format("%02X", (value >> (8 * i)) & 0xFF))
    return sb.toString()
}

private static int safeInt(val, int dflt) {
    try { return val == null ? dflt : (val as BigDecimal).intValue() } catch (e) { return dflt }
}

private void sendZigbee(List<String> cmds) {
    if (!cmds) return
    sendHubCommand(new hubitat.device.HubMultiAction(cmds, hubitat.device.Protocol.ZIGBEE))
}

private void logDebug(String msg) { if (logEnable) log.debug "${device.displayName}: ${msg}" }
private void logInfo(String msg)  { if (txtEnable) log.info "${device.displayName}: ${msg}" }
