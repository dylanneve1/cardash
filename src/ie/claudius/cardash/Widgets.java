package ie.claudius.cardash;

import android.content.Context;
import android.graphics.Bitmap;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import ie.claudius.cardash.car.Compass;
import ie.claudius.cardash.dash.Speedo;
import ie.claudius.cardash.dash.Trip;
import ie.claudius.cardash.layout.HomeLayout.Item;
import ie.claudius.cardash.layout.Kind;
import ie.claudius.cardash.media.NowPlaying;
import ie.claudius.cardash.vehicle.Readiness;
import ie.claudius.cardash.vehicle.VehicleField;
import ie.claudius.cardash.vehicle.VehicleState;

/**
 * Builds the view for one placed item and keeps it fed. The home screen
 * used to build three fixed columns; now every kind of thing is a
 * widget here and the layout model says where each one goes. Live data
 * (clock, now playing, weather, speed, trip, vehicle) is held once in
 * this class and pushed to every instance, so two clocks or three
 * vehicle gauges just work.
 */
final class Widgets {

    static final int CLOCK = 1, NP = 2, WX = 4, VEH = 8, TRIP = 16, SPEED = 32, HEADING = 64;

    /** A built widget that can redraw itself from the shared live state. */
    private abstract static class Live {
        final int mask;
        Live(int mask) { this.mask = mask; }
        abstract void refresh();
    }

    /** What a widget needs from the screen that hosts it. */
    interface Host {
        void openApps();
        void openSettings();
        void openMenu();
        void enterEdit();
        void pickApp(Item tile);
        NowPlaying nowPlaying();
        Trip trip();
    }

    private final Context ctx;
    private final Host host;
    private final float d;
    private M3 m3;
    private final List<Live> lives = new ArrayList<>();

    // Last known state from each source, so a rebuilt screen is filled in at once.
    String npTitle, npArtist;
    Bitmap npArt;
    boolean npPlaying, npKnown;
    int wxTempC;
    String wxDesc;
    boolean wxKnown;
    VehicleState lastVehicle;
    float lastKph;
    boolean lastFix;
    /** Direction of travel, or negative while unknown. */
    float heading = -1f;

    Widgets(Context ctx, Host host) {
        this.ctx = ctx;
        this.host = host;
        this.d = ctx.getResources().getDisplayMetrics().density;
    }

    void reset(M3 m3) {
        this.m3 = m3;
        lives.clear();
    }

    void refresh(int mask) {
        for (Live l : lives) if ((l.mask & mask) != 0) l.refresh();
    }

    private int dp(float v) { return Math.round(v * d); }

    private Live add(Live l) {
        lives.add(l);
        return l;
    }

    // ---- dispatch -----------------------------------------------------------------

    /**
     * @param w,h     the item's size in px
     * @param appIdx  how many app tiles came before this one (rotates tile colours and shapes)
     */
    View build(Item it, int w, int h, int appIdx, boolean editing) {
        View v;
        switch (it.type) {
            case Kind.CLOCK: v = clock(it, w, h); break;
            case Kind.DATE: v = date(it, w, h); break;
            case Kind.NOWPLAYING: v = nowPlaying(it, w, h); break;
            case Kind.SPEEDO: v = speedo(it); break;
            case Kind.TRIP: v = trip(); break;
            case Kind.LIST: v = list(it); break;
            case Kind.WEATHER: v = weather(w, h); break;
            case Kind.VEHICLE: v = vehicle(it); break;
            case Kind.DOORS: v = car(); break;
            case Kind.ALLAPPS: v = allApps(w, h); break;
            case Kind.SETTINGS: v = settingsButton(w, h); break;
            case Kind.CHIPS: v = chips(editing); break;
            default: v = appTile(it, w, h, appIdx); break;
        }
        if (v.getTag() == null) v.setTag(it);
        // Any item: long-press to edit the home screen (the clock offers a menu first).
        if (!Kind.CLOCK.equals(it.type)) {
            v.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View view) {
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    host.enterEdit();
                    return true;
                }
            });
        }
        return v;
    }

    // ---- the classic cluster page ---------------------------------------------------

    View classicCluster(boolean editing) {
        final ClassicCluster v = new ClassicCluster(ctx,
                editing ? ctx.getString(R.string.classic_edit_note) : null);
        add(new Live(CLOCK | HEADING | VEH | TRIP) {
            @Override void refresh() {
                Motion.setText(v.time, new SimpleDateFormat(Prefs.clockPattern(ctx), Locale.getDefault())
                        .format(new Date()));
                Motion.setText(v.heading, heading < 0 ? "—" : Compass.point(heading));
                VehicleState s = lastVehicle != null ? lastVehicle : new VehicleState();
                v.car.setThreeDoor(Prefs.carProfile(ctx).threeDoor());
                v.car.setState(s);
                int u = VehicleState.UNKNOWN_INT;
                String t = s.outsideC != u ? temperature(s.outsideC)
                        : s.intakeC != u ? temperature(s.intakeC) + " " + ctx.getString(R.string.intake_label)
                        : "—";
                Motion.setText(v.temperature, "\u2744\uFE0E  " + t);
                boolean miles = Prefs.mph(ctx);
                String unit = miles ? " mi" : " km";
                Motion.setText(v.odometer, "Odo " + (s.odometerKm == u ? "—"
                        : (miles ? Math.round(s.odometerKm / 1.609344f) : s.odometerKm) + unit));
                Trip tr = host.trip();
                Motion.setText(v.trip, "Trip " + (tr == null ? "—" : Trip.distance(tr.metres(), miles) + unit));
                // The same default limits as the gauges.
                StringBuilder warn = new StringBuilder();
                if (s.coolantC != u && s.coolantC >= Kind.vehicleRange("coolant")[2]) {
                    warn.append("Coolant ").append(temperature(s.coolantC)).append("   ");
                }
                if (s.rpm != u && s.rpm >= Kind.vehicleRange("rpm")[2]) warn.append(s.rpm).append(" rpm");
                Motion.setText(v.warning, warn.toString().trim());
                v.warning.setVisibility(warn.length() == 0 ? View.GONE : View.VISIBLE);
            }
        }).refresh();
        v.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View view) {
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                host.enterEdit();
                return true;
            }
        });
        return v;
    }

    // ---- clock and date -----------------------------------------------------------

    private View clock(final Item it, int w, int h) {
        final FitText t = new FitText(ctx);
        t.setTextColor(m3.primary());
        t.setTypeface(Fonts.hero(ctx));
        t.setLetterSpacing(-0.03f);
        t.setGravity(Gravity.CENTER);
        t.setCentreInk(true);
        t.setPadding(dp(20), dp(8), dp(20), dp(8));
        t.setRange(dp(24), dp(220));
        t.setBackground(Shapes.round(d, m3.surfaceContainer(), 36));
        final String pattern = clockPattern(it);
        t.setSample(widest(pattern));
        // The diagnostics instrument, settings and the editor hide behind the clock:
        // needed rarely, but needed from the car, without a keyboard.
        t.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                host.openMenu();
                return true;
            }
        });
        Live live = new Live(CLOCK) {
            @Override void refresh() {
                // No fade on the clock: at full size the minute's fade reads as a flash.
                String s = new SimpleDateFormat(pattern, Locale.getDefault()).format(new Date());
                if (!s.contentEquals(t.getText())) t.setText(s);
            }
        };
        add(live).refresh();
        return t;
    }

    private String clockPattern(Item it) {
        String f = it.get("format", "default");
        if ("12".equals(f)) return "h:mm";
        if ("24".equals(f)) return "HH:mm";
        return Prefs.clockPattern(ctx);
    }

    /** "88:88" for "h:mm" / "HH:mm": the widest the time can get. */
    static String widest(String pattern) {
        return pattern.replaceAll("[hHmsk]+", "88");
    }

    private View date(final Item it, int w, int h) {
        final FitText t = new FitText(ctx);
        t.setTypeface(Fonts.body(ctx));
        t.setTextColor(m3.onSurfaceVariant());
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(20), dp(6), dp(20), dp(6));
        t.setRange(dp(12), dp(34));
        t.setBackground(Shapes.round(d, m3.surfaceContainer(), 28));
        String f = it.get("format", "default");
        final String pattern = "short".equals(f) ? "EEE d MMM"
                : "full".equals(f) ? "EEEE d MMMM"
                : (Prefs.datePattern(ctx) != null ? Prefs.datePattern(ctx) : "EEEE d MMMM");
        t.setSample("Wednesday 30 September");
        add(new Live(CLOCK) {
            @Override void refresh() {
                Motion.swapText(t, new SimpleDateFormat(pattern, Locale.getDefault()).format(new Date()));
            }
        }).refresh();
        return t;
    }

    // ---- buttons ------------------------------------------------------------------

    private View allApps(int w, int h) {
        TextView b = new TextView(ctx);
        b.setText(R.string.all_apps);
        b.setTypeface(Fonts.display(ctx));
        b.setTextColor(m3.onPrimaryContainer());
        b.setTextSize(18);
        b.setGravity(Gravity.CENTER);
        b.setBackground(Shapes.pill(d, m3.primaryContainer(), M3.withAlpha(m3.onPrimaryContainer(), 0x40)));
        b.setPadding(dp(16), dp(8), dp(16), dp(8));
        b.setClickable(true);
        Shapes.springy(b);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { host.openApps(); }
        });
        return b;
    }

    private View settingsButton(int w, int h) {
        ImageView gear = new ImageView(ctx);
        gear.setImageDrawable(new Glyph(Glyph.Kind.GEAR, m3.onSurfaceVariant()));
        gear.setBackground(Shapes.pill(d, m3.surfaceContainerHigh(), M3.withAlpha(m3.onSurface(), 0x40)));
        gear.setClickable(true);
        gear.setContentDescription(ctx.getString(R.string.settings));
        Shapes.springy(gear);
        int p = Math.round(Math.min(w, h) * 0.22f);
        gear.setPadding(p, p, p, p);
        gear.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { host.openSettings(); }
        });
        return gear;
    }

    // ---- app tile -----------------------------------------------------------------

    /** The package a tile launches: its own choice, else the legacy slot assignment. */
    String tilePackage(Item it) {
        String pkg = it.settings.get("pkg");
        if (pkg != null) return pkg;
        String slot = it.settings.get("slot");
        if (slot != null) {
            try { return Apps.tile(ctx, Integer.parseInt(slot)); } catch (NumberFormatException ignored) { }
        }
        return null;
    }

    private View appTile(final Item it, int w, int h, int idx) {
        final String pkg = tilePackage(it);
        Apps.Entry entry = Apps.byPackage(ctx, pkg);

        // Rotate through the container roles so the grid has rhythm
        // instead of eight identical boxes.
        int fill, onFill;
        switch (idx % 4) {
            case 0: fill = m3.primaryContainer();   onFill = m3.onPrimaryContainer();   break;
            case 1: fill = m3.secondaryContainer(); onFill = m3.onSecondaryContainer(); break;
            case 2: fill = m3.tertiaryContainer();  onFill = m3.onTertiaryContainer();  break;
            default: fill = m3.surfaceContainerHigh(); onFill = m3.onSurface();         break;
        }
        boolean showLabel = !"hide".equals(it.get("label", "show"));
        boolean wide = h < dp(130) || w > h * 1.9f;   // short and wide: icon beside the label

        LinearLayout tile = new LinearLayout(ctx);
        tile.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER);
        tile.setBackground(Shapes.tile(d, fill, M3.withAlpha(onFill, 0x40), idx));
        tile.setClickable(true);
        tile.setPadding(dp(8), dp(8), dp(8), dp(8));
        Shapes.springy(tile);

        FrameLayout holder = new FrameLayout(ctx);
        holder.setBackground(new MaterialShape(MaterialShape.forIndex(idx), M3.withAlpha(onFill, 0x30), idx * 11f));
        int hp = dp(11);
        holder.setPadding(hp, hp, hp, hp);

        ImageView icon = new ImageView(ctx);
        if (entry != null) {
            icon.setImageDrawable(entry.icon);
        } else {
            icon.setImageResource(android.R.drawable.ic_input_add);
            icon.setColorFilter(onFill);
        }
        int avail = Math.min(w, h) - dp(16) - (showLabel && !wide ? dp(30) : 0);
        int isz = Math.max(dp(28), Math.min(dp(64), Math.round(avail * (wide ? 0.85f : 0.9f))));
        holder.addView(icon, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        tile.addView(holder, new LinearLayout.LayoutParams(isz, isz));

        if (showLabel) {
            TextView label = new TextView(ctx);
            label.setText(entry != null ? entry.label : ctx.getString(R.string.empty_tile));
            label.setTextColor(onFill);
            label.setTypeface(Fonts.display(ctx));
            label.setTextSize(wide || w > dp(220) ? 18 : 15);
            label.setMaxLines(1);
            label.setEllipsize(TextUtils.TruncateAt.END);
            label.setGravity(Gravity.CENTER);
            label.setPadding(wide ? dp(12) : 0, wide ? 0 : dp(8), 0, 0);
            tile.addView(label, wide
                    ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        tile.setContentDescription(entry != null ? entry.label : ctx.getString(R.string.empty_tile));
        tile.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (pkg == null || !Apps.launch(ctx, pkg)) host.pickApp(it);
            }
        });
        return tile;
    }

    // ---- speed, trip, weather ------------------------------------------------------

    private View speedo(Item it) {
        LinearLayout gauge = new LinearLayout(ctx);
        gauge.setGravity(Gravity.CENTER);
        gauge.setBackground(Shapes.round(d, m3.surfaceContainer(), 36));
        final Speedo s = new Speedo(ctx);
        s.setColors(M3.withAlpha(m3.onSurface(), 0x1F), m3.primary(), m3.onSurface(), m3.onSurfaceVariant());
        s.setTypefaces(Fonts.hero(ctx), Fonts.body(ctx));
        s.setUnit(Prefs.mph(ctx));
        String scale = it.get("scale", "default");
        s.setScale("default".equals(scale) ? Prefs.gaugeMax(ctx) : Prefs.gaugeMax(ctx, scale));
        String g = it.get("gauge", "default");
        s.setDigitsOnly("default".equals(g) ? Prefs.digitsGauge(ctx) : "digits".equals(g));
        gauge.addView(s, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        add(new Live(SPEED) {
            @Override void refresh() { s.setSpeed(lastKph, lastFix); }
        }).refresh();
        return gauge;
    }

    private View weather(int w, int h) {
        final boolean tall = h >= dp(190);
        LinearLayout wx = new LinearLayout(ctx);
        wx.setOrientation(LinearLayout.VERTICAL);
        wx.setGravity(Gravity.CENTER);
        wx.setBackground(Shapes.round(d, m3.secondaryContainer(), tall ? 36 : 28));
        int p = dp(14);
        wx.setPadding(p, p, p, p);

        final TextView temp = new TextView(ctx);
        temp.setTypeface(tall ? Fonts.hero(ctx) : Fonts.display(ctx));
        temp.setTextColor(m3.onSecondaryContainer());
        temp.setTextSize(tall ? 64 : 30);
        temp.setIncludeFontPadding(false);
        temp.setText("--");
        wx.addView(temp);

        final TextView desc = new TextView(ctx);
        desc.setTypeface(Fonts.body(ctx));
        desc.setTextColor(M3.withAlpha(m3.onSecondaryContainer(), 0xCC));
        desc.setTextSize(tall ? 18 : 14);
        desc.setPadding(0, dp(tall ? 8 : 4), 0, 0);
        desc.setText(R.string.weather_waiting);
        wx.addView(desc);
        add(new Live(WX) {
            @Override void refresh() {
                if (!wxKnown) return;
                int shown = Prefs.fahrenheit(ctx) ? Math.round(wxTempC * 9f / 5f + 32f) : wxTempC;
                Motion.swapText(temp, shown + "°");
                Motion.swapText(desc, wxDesc);
            }
        }).refresh();
        return wx;
    }

    /**
     * Trip meter: distance, moving time, moving average, as a pill like the
     * other values: a row when short, stacked when taller. Tap to reset
     * (after asking).
     */
    private View trip() {
        ValueView v = pill(m3.surfaceContainer());
        tripPill(v);
        return v;
    }

    /** {distance, its unit, time and moving average}, or null with no trip meter. */
    private String[] tripText() {
        Trip tr = host.trip();
        if (tr == null) return null;
        boolean miles = Prefs.mph(ctx);
        float m = tr.metres();
        long ms = tr.movingMs();
        int avg = Trip.average(m, ms, miles);
        String text = Trip.duration(ms);
        if (avg >= 0) text += " · " + avg + (miles ? " mph " : " km/h ") + ctx.getString(R.string.trip_avg);
        return new String[] { Trip.distance(m, miles), miles ? "mi" : "km", text };
    }

    private void tripPill(final ValueView v) {
        v.configure(ctx.getString(R.string.trip), "", false, false, 0, 0, 0, "never");
        resetOnTap(v);
        add(new Live(TRIP) {
            @Override void refresh() {
                String[] t = tripText();
                if (t != null) v.setInfo(t[0], t[1], t[2]);
            }
        }).refresh();
    }

    /** Long-press means "edit the home screen" like everywhere else, so a reset is a tap and a confirmation. */
    private void resetOnTap(View v) {
        v.setClickable(true);
        Shapes.springy(v);
        v.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(final View v) {
                Pop.confirm(ctx, m3, ctx.getString(R.string.trip_reset_q), ctx.getString(R.string.s_reset_action),
                        new Runnable() {
                            @Override public void run() {
                                host.trip().reset();
                                Motion.pop(v);
                            }
                        });
            }
        });
    }

    // ---- vehicle ------------------------------------------------------------------

    /** A pill in this palette on a track of colour {@code track}. */
    private ValueView pill(int track) {
        ValueView v = new ValueView(ctx);
        v.setPillColors(track, m3.primaryContainer(), m3.errorContainer(), m3.onSurface(), m3.onSurfaceVariant(),
                m3.onPrimaryContainer(), m3.onErrorContainer());
        return v;
    }

    private View vehicle(final Item it) {
        final VehicleField field = Kind.field(it.get("field", "coolant"));
        boolean gauge = "gauge".equals(it.get("style", "bar"));
        final ValueView v = pill(m3.surfaceContainer());
        v.setGaugeColors(M3.withAlpha(m3.onSurface(), 0x26), m3.primary(), m3.onSurface(), m3.onSurfaceVariant(),
                Shapes.round(d, m3.surfaceContainer(), 28), Shapes.round(d, m3.errorContainer(), 28),
                m3.error(), m3.onErrorContainer());
        configure(v, field, gauge, it.getInt("min", field.min), it.getInt("max", field.max),
                it.getInt("warn", field.warn), it.get("warndir", field.warnDir));
        if (field.type == VehicleField.Type.STATUS) monitorsOnTap(v);
        add(new Live(VEH) {
            @Override void refresh() { feed(v, field, true); }
        }).refresh();
        return v;
    }

    /** Range and warning are given in metric, as stored; the pill gets them in the units shown. */
    private void configure(ValueView v, VehicleField f, boolean gauge, int min, int max, int warn, String dir) {
        boolean fahr = Prefs.fahrenheit(ctx), miles = Prefs.mph(ctx);
        String unit = f.unit(fahr, miles);
        v.configure(f.label(miles), unit, gauge, f.bar, f.convert(min, fahr, miles), f.convert(max, fahr, miles),
                f.convert(warn, fahr, miles), dir);
    }

    /**
     * Give a pill or gauge the latest reading of its field; false while there is none.
     *
     * @param intakeStandsIn an unknown outside temperature may show the intake air instead
     */
    private boolean feed(ValueView v, VehicleField field, boolean intakeStandsIn) {
        VehicleState s = lastVehicle;
        boolean miles = Prefs.mph(ctx);
        if (field.section == VehicleField.Section.LAMBDA) v.setLabel(field.label(s, miles));
        if (field.type == VehicleField.Type.STATUS && s != null && s.readiness != null) {
            Readiness r = s.readiness;
            v.setStatus(r.mil ? 1f : 0f, r.headline(), r.light());
            return true;
        }
        Float val = field.value(s);
        String note = s != null && "fuel".equals(field.id) && s.fuelUnsupported
                ? ctx.getString(R.string.unavailable) : null;
        if (intakeStandsIn && "outside".equals(field.id)) {
            // Intake air stands in only when nothing reports outside air, and says so.
            boolean intake = val == null && s != null && s.intakeC != VehicleState.UNKNOWN_INT;
            if (intake) val = Float.valueOf(s.intakeC);
            v.setLabel(ctx.getString(intake ? R.string.intake_label : R.string.outside_label));
        }
        if (val == null) {
            v.setValue(false, 0f, "", note);
            return false;
        }
        float shown = field.convert(val, Prefs.fahrenheit(ctx), miles);
        v.setValue(true, shown, field.format(shown), note);
        return true;
    }

    /**
     * Several values as pills in one card: the car page's fuel, outside
     * temperature, battery and trip, or everything the engine reports
     * ({@link #engineList}). Fuel and outside temperature are always
     * there; the rest appear once the car has given a value.
     */
    private View list(Item it) {
        if (Kind.LIST_ENGINE.equals(it.get("set", Kind.LIST_CAR))) return engineList();
        final ValueList card = new ValueList(ctx, m3, ctx.getString(R.string.engine_waiting));
        final List<VehicleField> fields = new ArrayList<>();
        final List<ValueView> pills = new ArrayList<>();
        for (String id : new String[] { "fuel", "outside", "voltage" }) fields.add(VehicleField.of(id));
        for (VehicleField f : fields) {
            ValueView v = pill(m3.surfaceContainerHigh());
            configure(v, f, false, f.min, f.max, f.warn, f.warnDir);
            if ("voltage".equals(f.id)) v.setVisibility(View.GONE);
            pills.add(v);
            card.add(v);
        }
        add(new Live(VEH) {
            @Override void refresh() {
                for (int i = 0; i < pills.size(); i++) {
                    VehicleField f = fields.get(i);
                    boolean known = feed(pills.get(i), f, true);
                    if ("voltage".equals(f.id)) card.show(pills.get(i), known);
                }
            }
        }).refresh();
        ValueView t = pill(m3.surfaceContainerHigh());
        card.add(t);
        tripPill(t);
        t.setOnLongClickListener(editOnHold());
        return card;
    }

    /** Every value the car has given, in sections, with the fault-code button. */
    private View engineList() {
        final SectionList card = new SectionList(ctx, m3, ctx.getString(R.string.engine_data),
                ctx.getString(R.string.engine_waiting), ctx.getString(R.string.codes_read), new Runnable() {
                    @Override public void run() { FaultCodes.show(ctx, m3); }
                });
        card.onHold(editOnHold());
        final List<VehicleField> fields = VehicleField.all();
        final List<ValueView> pills = new ArrayList<>();
        for (VehicleField f : fields) {
            ValueView v = pill(m3.surfaceContainerHigh());
            configure(v, f, false, f.min, f.max, f.warn, f.warnDir);
            v.setVisibility(View.GONE);
            if (f.type == VehicleField.Type.STATUS) {
                monitorsOnTap(v);
                v.setOnLongClickListener(editOnHold());
            }
            pills.add(v);
            card.add(f.section, v);
        }
        add(new Live(VEH) {
            @Override void refresh() {
                // The list has the intake air in its own row, so it never stands in for outside air.
                for (int i = 0; i < pills.size(); i++) {
                    card.show(pills.get(i), feed(pills.get(i), fields.get(i), false));
                }
            }
        }).refresh();
        return card;
    }

    private View.OnLongClickListener editOnHold() {
        return new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View view) {
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                host.enterEdit();
                return true;
            }
        };
    }

    /** Tapping the emissions readiness lists the self-tests one by one. */
    private void monitorsOnTap(View v) {
        v.setClickable(true);
        Shapes.springy(v);
        v.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Readiness r = lastVehicle == null ? null : lastVehicle.readiness;
                if (r == null) return;
                Pop pop = new Pop(ctx, m3, ctx.getString(R.string.readiness_title));
                pop.note(r.light());
                for (Readiness.Monitor m : r.monitors) {
                    pop.note(m.name + ": " + ctx.getString(m.complete ? R.string.monitor_complete
                            : R.string.monitor_incomplete));
                }
                pop.row(ctx.getString(R.string.codes_read), new Runnable() {
                    @Override public void run() { FaultCodes.show(ctx, m3); }
                });
                pop.button(ctx.getString(android.R.string.ok), null, true).show();
            }
        });
    }

    private View car() {
        final CarView v = new CarView(ctx, m3.surfaceContainer());
        v.setBackground(Shapes.round(d, m3.surfaceContainer(), 28));
        add(new Live(VEH) {
            @Override void refresh() {
                v.setThreeDoor(Prefs.carProfile(ctx).threeDoor());
                v.setState(lastVehicle);
            }
        }).refresh();
        return v;
    }

    /**
     * Fuel / coolant / rpm / doors as pills. Nothing plugged in means
     * nothing to show — an empty strip of dashes looks broken, so the
     * strip stays empty until a source reports something.
     *
     * Every chip is made once, in a fixed order, and only its text and
     * visibility change: live data arrives several times a second, so
     * nothing here fades, moves or is rebuilt. The strip and the gauges
     * redraw from the same state in the same pass, so they always agree.
     */
    private View chips(final boolean editing) {
        final HorizontalScrollView scroll = new HorizontalScrollView(ctx);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout bar = new LinearLayout(ctx);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        scroll.addView(bar, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        scroll.setFillViewport(true);
        // An open door comes first: it must not be the chip that scrolls out of sight.
        final TextView doors = chip(bar), engine = chip(bar), fuel = chip(bar), coolant = chip(bar), rpm = chip(bar), sample = chip(bar);
        add(new Live(VEH) {
            @Override void refresh() {
                VehicleState s = lastVehicle;
                if (s == null) s = new VehicleState();
                boolean lowFuel = s.fuelPercent != VehicleState.UNKNOWN_INT && s.fuelPercent <= 15;
                show(fuel, s.fuelPercent != VehicleState.UNKNOWN_INT ? "Fuel " + s.fuelPercent + "%" : null,
                        lowFuel ? m3.tertiaryContainer() : m3.surfaceContainerHigh(),
                        lowFuel ? m3.onTertiaryContainer() : m3.onSurface());
                show(coolant, s.coolantC != VehicleState.UNKNOWN_INT ? s.coolantC + "°C" : null,
                        m3.surfaceContainerHigh(), m3.onSurface());
                show(rpm, s.rpm != VehicleState.UNKNOWN_INT && s.rpm > 0 ? s.rpm + " rpm" : null,
                        m3.surfaceContainerHigh(), m3.onSurface());
                int open = s.openDoors();
                show(doors, open > 0 ? (open == 1 ? "Door open" : open + " doors open") : null,
                        m3.primaryContainer(), m3.onPrimaryContainer());
                boolean light = s.readiness != null && s.readiness.mil;
                show(engine, light ? ctx.getString(R.string.check_engine) : null,
                        m3.errorContainer(), m3.onErrorContainer());
                boolean empty = !light && fuel.getVisibility() != View.VISIBLE && coolant.getVisibility() != View.VISIBLE
                        && rpm.getVisibility() != View.VISIBLE && doors.getVisibility() != View.VISIBLE;
                show(sample, editing && empty ? "Vehicle strip" : null,
                        m3.surfaceContainerHigh(), m3.onSurfaceVariant());
            }
        }).refresh();
        return scroll;
    }

    private static int toF(int c) {
        return Math.round(c * 9f / 5f + 32f);
    }

    private String temperature(int c) {
        return Prefs.fahrenheit(ctx) ? toF(c) + "°F" : c + "°C";
    }

    private TextView chip(LinearLayout bar) {
        TextView t = new TextView(ctx);
        t.setTypeface(Fonts.body(ctx));
        t.setFontFeatureSettings("tnum");   // digits of one width, so a changing number doesn't jiggle
        t.setTextSize(15);
        t.setPadding(dp(14), dp(8), dp(14), dp(8));
        t.setVisibility(View.GONE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(8);
        bar.addView(t, lp);
        return t;
    }

    private void show(TextView t, String text, int fill, int onFill) {
        if (text == null) {
            if (t.getVisibility() != View.GONE) t.setVisibility(View.GONE);
            return;
        }
        Motion.setText(t, text);
        // Restyle only on a role change (fuel turning low), so the ripple isn't rebuilt on every update.
        Integer styled = (Integer) t.getTag();
        if (styled == null || styled != fill) {
            t.setTextColor(onFill);
            t.setBackground(Shapes.pill(d, fill, M3.withAlpha(onFill, 0x33)));
            t.setTag(fill);
        }
        if (t.getVisibility() != View.VISIBLE) t.setVisibility(View.VISIBLE);
    }

    // ---- now playing --------------------------------------------------------------

    /**
     * Now playing: art, title, artist, transport. Compact drops the
     * transport row to a single play/pause beside the text. It goes
     * compact by itself when the card is too short for the full one.
     */
    private View nowPlaying(Item it, int w, int h) {
        String style = it.get("style", "default");
        boolean compact = "default".equals(style) ? "compact".equals(Prefs.media(ctx)) : "compact".equals(style);
        if (h < dp(150)) compact = true;
        final boolean fcompact = compact;

        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(Shapes.round(d, m3.surfaceContainerHigh(), 28));
        int p = dp(compact ? 10 : 14);
        card.setPadding(p, p, p, p);

        LinearLayout top = new LinearLayout(ctx);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        int artSize = dp(compact ? 48 : 64);
        final ImageView art = new ImageView(ctx);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        art.setBackground(new MaterialShape(MaterialShape.Kind.SQUIRCLE, M3.withAlpha(m3.onSurface(), 0x26)));
        art.setClipToOutline(false);
        top.addView(art, new LinearLayout.LayoutParams(artSize, artSize));

        LinearLayout text = new LinearLayout(ctx);
        text.setOrientation(LinearLayout.VERTICAL);
        text.setPadding(dp(12), 0, 0, 0);

        final TextView title = new TextView(ctx);
        title.setTypeface(Fonts.display(ctx));
        title.setTextColor(m3.onSurface());
        title.setTextSize(compact ? 15 : 17);
        title.setMaxLines(1);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setText(R.string.nothing_playing);
        text.addView(title);

        final TextView artist = new TextView(ctx);
        artist.setTypeface(Fonts.body(ctx));
        artist.setTextColor(m3.onSurfaceVariant());
        artist.setTextSize(compact ? 13 : 14);
        artist.setMaxLines(1);
        artist.setEllipsize(TextUtils.TruncateAt.END);
        artist.setVisibility(View.GONE);
        text.addView(artist);

        top.addView(text, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        card.addView(top);

        final ImageView play;
        if (compact) {
            play = (ImageView) transport(Glyph.Kind.PLAY, 1, true, dp(44));
            top.addView(play);
        } else {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER);
            row.setPadding(0, dp(12), 0, 0);
            row.addView(transport(Glyph.Kind.PREV, 0, false, dp(48)));
            play = (ImageView) transport(Glyph.Kind.PLAY, 1, true, dp(60));
            row.addView(play);
            row.addView(transport(Glyph.Kind.NEXT, 2, false, dp(48)));
            card.addView(row);
        }
        add(new Live(NP) {
            boolean shownPlaying;
            @Override void refresh() {
                if (!npKnown) return;
                boolean known = npTitle != null && !npTitle.trim().isEmpty();
                String t = known ? npTitle : ctx.getString(R.string.nothing_playing);
                boolean trackChanged = !t.contentEquals(title.getText());
                Motion.swapText(title, t);
                String a = npArtist == null ? "" : npArtist;
                Motion.swapText(artist, a);
                artist.setVisibility(a.isEmpty() ? View.GONE : View.VISIBLE);
                if (npArt != null) {
                    boolean hadArt = art.getDrawable() != null;
                    art.setImageBitmap(npArt);
                    // Only a new track earns the reveal; the same bitmap comes back on every poll.
                    if (trackChanged || !hadArt) Motion.reveal(art);
                } else {
                    art.setImageDrawable(null);
                }
                if (npPlaying != shownPlaying) {
                    shownPlaying = npPlaying;
                    play.setImageDrawable(new Glyph(npPlaying ? Glyph.Kind.PAUSE : Glyph.Kind.PLAY, m3.onPrimary()));
                    Motion.pop(play);
                }
            }
        }).refresh();
        return card;
    }

    private View transport(Glyph.Kind kind, final int action, boolean big, int size) {
        ImageView b = new ImageView(ctx);
        int fill = big ? m3.primary() : m3.surfaceContainer();
        int on = big ? m3.onPrimary() : m3.onSurface();
        b.setImageDrawable(new Glyph(kind, on));
        b.setBackground(Shapes.pill(d, fill, M3.withAlpha(on, 0x40)));
        b.setClickable(true);
        Shapes.springy(b);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final NowPlaying np = host.nowPlaying();
                if (np == null) return;
                if (action == 0) np.previous();
                else if (action == 1) np.playPause();
                else np.next();
                // The session reports back asynchronously; nudge it so the button
                // flips even on a player that is slow to publish its new state.
                v.postDelayed(new Runnable() {
                    @Override public void run() { np.refresh(); }
                }, 350);
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.leftMargin = dp(6);
        lp.rightMargin = dp(6);
        b.setLayoutParams(lp);
        int pad = Math.round(size * 0.29f);
        b.setPadding(pad, pad, pad, pad);
        return b;
    }
}
