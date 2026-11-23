# HCN head units (com.hcn.*)

Facts about how HCN units handle canbox data, worked out by reading the vendor
canbox app `com.hcn.autocan` (versionCode 42, APK sha256 `28a6d837…`) offline.
Nothing here has been checked on a running unit yet; "seen" below means seen in
the vendor code, not on a car. CarDash's implementation is
`vehicle/HcnSource.java` (Android) and `vehicle/HcnParse.java` (plain Java,
tested by `test/HcnParseTest.java`).

## Where the data lives

`com.hcn.autocan` runs as `android.uid.system`. Its `CanService` owns the canbox
UART, decodes the frames with a per-car decoder class, and mostly uses the
results itself: popups (doors, climate, parking radar) and its own "car info"
screens. Very little leaves the process:

| Route | What | Readable by CarDash? |
|---|---|---|
| Bound service, data callback (below) | declared, but **never invoked** in v42 | yes to bind; nothing arrives on v42 |
| System properties `persist.vendor.cem.cardoor1`…`5` | doors FL, FR, RL, RR, boot. `1` shut, `2` open, `0`/unset unknown | depends on the ROM's SELinux policy for `persist.vendor.*` |
| `Settings.System` `key_can_gear` | gear: 0 N, 1–10 gears, 100 D, 101 P, 102 R, 103 S, 104 L, -1 none | yes (target 30) |
| `Settings.System` `car_speed_value`, `key_can_speed_unit`, `key_can_average_speed` | speed, for cars whose decoder sets it | yes, but reset to 0 at service start, so a 0 is not a reading |
| Broadcast `hcn.air.outdoor_temp`, extra `hcn.air.extra.outdoor_temp` (String like `12.5℃`) | outside temperature | yes, but the VW Raise decoders never fill it |
| File `/tmp/caninfo/canbus.kv` | a copy of the door / radar / speed properties | probably not (path and permissions unknown) |

The door properties are only written on some SoCs (the door popup checks for
AC8257, AC8227L, MT8163, UIS8581, MT8321, SM6225, T527) and are cleared to "all
shut" when the user has turned the door popup off. They are `persist.`, so they
survive a reboot: a value read before the box has sent anything may be stale.
For the Polo decoder neither `key_can_gear` nor `car_speed_value` is written.

## The bound service

- Component: `com.hcn.autocan/com.hcn.autocan.service.CanService`, exported, no
  permission. Manifest intent-filter action `com.hcn.autocan.CanService`.
- `onBind` returns a different binder depending on the intent action:
  - action `com.hcn.autocan.action.DATA_CTRL_SERVICE` → the data binder
    (`ICustomDataServices`);
  - any other action → the climate binder (`IAirCtrlServices`).
- HCN's own client binds with an explicit component, that action and
  `BIND_AUTO_CREATE`, registers its callback in `onServiceConnected`, and
  rebinds when it receives the broadcast `action.hcn.initcan` (sent by
  `CanService` when it starts). `action.hcn.ubindcan` asks clients to unbind.

### `ICustomDataServices` (descriptor `com.hcn.autocan.aidl.ICustomDataServices`)

| Code | Method | Args | Notes |
|---|---|---|---|
| 1 | `dataCtrlString` | String | sends to the box. **CarDash never calls it.** (v42 only logs it.) |
| 2 | `dataCtrlByte` | byte[] | sends to the box. **CarDash never calls it.** (v42 only logs it.) |
| 3 | `registerDataCallBack` | strong binder | adds the callback to a map keyed by binder |
| 4 | `unRegisterDataCallBack` | strong binder | removes it |

All are two-way: `writeInterfaceToken(descriptor)`, args, then the reply carries
the usual AIDL exception header and nothing else.

### `ICustomDataCallBack` (descriptor `com.hcn.autocan.aidl.ICustomDataCallBack`)

| Code | Method | Arg |
|---|---|---|
| 1 | `CallBackDatatEventString` | String |
| 2 | `CallBackDatatEventByte` | byte[] |

`CanService` has two methods that loop over the registered callbacks
(`updateDataMsgString`, `updateDataMsgByte`), but **nothing in the v42 APK calls
either of them**. So the payload format is unknown. `HcnSource` registers anyway
(cheap, harmless, and a later build may fire it), keeps every payload verbatim
in Diagnostics, and if it is hex text or bytes that look like a Raise frame,
decodes it.

The climate binder (`IAirCtrlServices` / `IAirCallBack`) does fire, with HCN's
own air-conditioning bean; CarDash does not use it.

## The canbox protocol: Raise (RZC)

Wire format: `2E type len data[len] chk`, `chk = 0xFF - ((type + len + Σdata) & 0xFF)`.
The head unit ACKs each good frame with `FF`. HCN's decoders get the frame with
`2E` and `chk` removed: `type len data…`.

Which decoder runs is the "CAN type" chosen in HCN's factory settings. For
**Raise, VW, 2008–2018 Polo** the value is 6101 (`eCBT_RAISE_VW`), and its
decoder handles these box→head-unit types: 0x14 backlight, 0x20 steering-wheel
keys, 0x21 climate, 0x22/0x23 radar, 0x25 radar mute, 0x26 steering angle,
0x27 EQ, 0x30 version, 0x34 rear climate, 0x41 body info, 0x7D misc. Type 0x37,
seen on the wire on the Polo, is ignored by HCN.

The head unit requests 0x41 sub 0x02 every 10 s (`2E 90 02 41 02 2A`) and the
climate/version on start (`90 02 21 00`, `90 02 30 00`).

### 0x41 body info (data[0] is the sub-type)

**Sub 0x01, doors and warnings.** data[1] bits (1 = open / warning):

| bit | meaning |
|---|---|
| 0 | front left door |
| 1 | front right door |
| 2 | rear left door |
| 3 | rear right door |
| 4 | boot |
| 5 | handbrake (see below) |
| 6 | washer fluid low |
| 7 | seat belt unfastened |

The remaining bytes (four on the Polo) are not read by HCN.

Handbrake: HCN labels bit 5 = 1 as "未刹" (shown in English as "No brakes") and
0 as "Normal". The other bits in this byte are all "1 = warning", so CarDash
reads bit 5 = 1 as **handbrake on**. This is the least certain mapping here: the
tester should pull and release the handbrake and watch `handbrake-bit` in
Diagnostics; if it is inverted it is a one-line change in `HcnSource.publish`.

**Sub 0x02, driving data.** HCN only accepts it with len = 13 (sub + 12 bytes),
all big-endian:

| data[] | size | meaning |
|---|---|---|
| 1–2 | 2 | engine RPM |
| 3–4 | 2 | speed, 0.01 km/h |
| 5–6 | 2 | battery voltage, 0.01 V |
| 7–8 | 2 | outside temperature, signed, 0.1 °C (HCN shows "--" outside −50…77.5) |
| 9–11 | 3 | odometer, km |
| 12 | 1 | fuel remaining, litres |

**Sub 0x03, warnings.** data[1] bit 6 battery voltage warning, bit 7 low fuel.

Since 0x41/02 is polled every 10 s, speed and RPM through this route would be up
to 10 s old: fine for fuel and odometer, not a speedometer.

## Routes deliberately not used

- `dataCtrlByte` / `dataCtrlString`: they exist to send to the box.
- Broadcast `com.hcn.request.serialport` makes `CanService` close the canbox
  UART (it is meant for box firmware updates); `com.hcn.release.serialport`
  reopens it. Reading the UART ourselves would mean HCN's own UI goes dark and
  someone has to ACK each frame with `FF`, which is a write to the box. Not done.
- The climate binder and its callback.

## What CarDash does with it

`HcnSource` is available when `com.hcn.autocan` is installed. It binds the data
service and registers its callback (transactions 3 and 4 only), polls the five
door properties and `key_can_gear` once a second, and maps:

- doors and boot ← door properties, or 0x41/01 if callback frames ever arrive;
- handbrake ← 0x41/01 bit 5;
- reverse ← `key_can_gear` = 102 (not written for the Polo);
- RPM ← 0x41/02 (speed is decoded but not published: at a 10 s poll it would
  override the live GPS speed on the gauge);
- fuel % ← 0x41/02 litres ÷ 45 L (Polo 6R tank).

Outside temperature, battery voltage and odometer are decoded and shown in
Diagnostics, but `VehicleState` has no field for them yet. Coolant temperature
is not in any frame this decoder reads.

Diagnostics → Vehicle sources shows the HCN row, what has been decoded, and the
last 40 raw events (callback payloads, property changes, bind state).

## What to try on the car

1. Diagnostics → Vehicle sources: is the HCN row "bound, callback registered"?
   Any callback payloads at all?
2. Open each door and the boot with the HCN door popup enabled: do the
   `props doors(…)` lines change? If they stay `_____` (empty), the ROM blocks
   reading `persist.vendor.*` and doors need another route.
3. `adb shell getprop | grep cem` and `adb shell settings list system | grep -i can`
   to see what the unit really writes.
