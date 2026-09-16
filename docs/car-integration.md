# Car integration: how to find out what a head unit can do

How CarDash gets real vehicle data out of a generic Android head unit,
and how to learn what the unit supports **before** buying a canbox.

## The units this was written for

Generic Chinese 9" units on the Rockchip **RK3326** (quad Cortex-A35,
32-bit only), Jancar ROM family (`JC` build prefix, vendor service
`com.jancar.services`). They claim Android 13 in the build props and are
actually **API 30, Android 11** — the Diagnostics screen shows both
numbers side by side so the lie is visible.

The car matters as much as the unit. Older Toyotas (XP90 Yaris and its
generation) put body electronics on BEAN / AVC-LAN rather than a modern
body CAN. Decoder boxes advertising door and seatbelt graphics are
aimed at 2014+ cars. Steering-wheel controls, reverse trigger and
illumination are winnable; treat doors, seatbelt and fuel as unproven
until someone demonstrates them on that generation.

## Where the data comes from

```
VehicleSource   isAvailable / status / lastUpdate / start / stop
 ├─ ObdSource      ELM327 over Bluetooth SPP: 010C RPM, 010D speed,
 │                 0105 coolant, 012F fuel. Keeps every raw reply.
 └─ JancarSource   Broadcasts from the vendor car service, which a canbox
                   feeds. Wired; the action names are candidates until a
                   box or the sniffer proves them.
VehicleHub      one per process — an ELM327 serves one socket. Screens
                attach listeners; sources run while anyone is attached.
                Merges; canbox wins over OBD; fuel clamped to 0-100 and
                remembered for a day; doors never remembered.
```

## Discover the protocol without buying anything

An app can read any installed package's **compiled manifest** — public
API, no permission, no root:

```java
Context other = ctx.createPackageContext(pkg, 0);
XmlResourceParser p = other.getAssets().openXmlResourceParser("AndroidManifest.xml");
```

That yields the intent actions each receiver and service filters on,
which `PackageManager.GET_RECEIVERS` does not. Across the vendor suite,
the receivers' actions *are* the broadcasts that fly between them.
`Probe.sweep()` does this for every package and keeps the ones declaring
car-ish actions; the sniffer then listens on exactly that set.

If no package declares anything CAN-shaped, that is also an answer: the
ROM's car integration is a private binder or a UART the app can't reach,
and no canbox will feed CarDash regardless of which one is bought.

## What to ask a canbox seller

Hand them the Diagnostics export, then ask exactly these:

1. Which **protocol** does the box speak — Raise, Hiworld, Simple, Oudi,
   other? It must appear in the head unit's own factory-settings protocol
   list (type that list into the Diagnostics screen; it is in the export).
2. Does it support the **exact car, year and engine** — not "Toyota
   Yaris", which they will read as the 2014+ model.
3. On that generation, **which signals** does it output: steering
   buttons / reverse / illumination / doors / seatbelt / fuel? A list,
   not a yes.
4. Does the kit include the **vehicle-side harness**, and what connector
   does it present to the head unit?
5. Does it feed the head unit's **own car service**, or only the vendor's
   launcher? A box that only talks to a launcher you've replaced is
   useless unless it also broadcasts.

## Wireless Android Auto

Hard requirements, all queryable on API 30 and shown on the Diagnostics
screen: 5 GHz Wi-Fi, Wi-Fi Direct, **STA + AP concurrency**
(`isStaApConcurrencySupported`, new in API 30), Bluetooth for the
handshake. Cheap RK3326 units most often fail 5 GHz or concurrency, and
no app fixes either. Built-in projection (Zlink / AutoKit / Jancar) and
Headunit Reloaded are different debugging exercises; HUR's wireless mode
is usually a configuration problem.

## Still open

- Whether the steering-wheel buttons are **resistive** (KEY1/KEY2, two
  wires, no canbox) or **digital** (needs the box). The key-capture panel
  answers what arrives today.
- The factory-settings protocol list has to be read off the unit's own
  menu; nothing programmatic exposes it.
