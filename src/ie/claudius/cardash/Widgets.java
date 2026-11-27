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

import ie.claudius.cardash.dash.Speedo;
import ie.claudius.cardash.dash.Trip;
import ie.claudius.cardash.layout.HomeLayout.Item;
import ie.claudius.cardash.layout.Kind;
import ie.claudius.cardash.media.NowPlaying;
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

    static final int CLOCK = 1, NP = 2, WX = 4, VEH = 8, TRIP = 16, SPEED = 32;

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
            case Kind.TRIP: v = trip(w, h); break;
            case Kind.WEATHER: v = weather(w, h); break;
            case Kind.VEHICLE: v = vehicle(it); break;
            case Kind.DOORS: v = doors(); break;
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

    // ---- clock and date -----------------------------------------------------------

    private View clock(final Item it, int w, int h) {
        final FitText t = new FitText(ctx);
        t.setTextColor(m3.primary());
        t.setTypeface(Fonts.hero(ctx));
        t.setLetterSpacing(-0.03f);
        t.setGravity(Gravity.CENTER_VERTICAL);
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
        t.setGravity(Gravity.CENTER_VERTICAL);
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

    /** Trip meter: distance, moving time, moving average. Tap to reset (after asking). */
    private View trip(int w, int h) {
        final boolean tall = h >= dp(190);
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setBackground(Shapes.tile(d, m3.tertiaryContainer(), M3.withAlpha(m3.onTertiaryContainer(), 0x33), 0));
        int p = dp(14);
        card.setPadding(p, p, p, p);
        card.setClickable(true);
        Shapes.springy(card);

        TextView title = new TextView(ctx);
        title.setText(R.string.trip);
        title.setTypeface(Fonts.display(ctx));
        title.setTextColor(M3.withAlpha(m3.onTertiaryContainer(), 0xB3));
        title.setTextSize(tall ? 16 : 13);
        card.addView(title);

        final TextView dist = new TextView(ctx);
        dist.setTypeface(tall ? Fonts.hero(ctx) : Fonts.display(ctx));
        dist.setTextColor(m3.onTertiaryContainer());
        dist.setTextSize(tall ? 64 : 30);
        dist.setIncludeFontPadding(false);
        dist.setPadding(0, dp(tall ? 8 : 2), 0, 0);
        card.addView(dist);

        final TextView detail = new TextView(ctx);
        detail.setTypeface(Fonts.body(ctx));
        detail.setTextColor(M3.withAlpha(m3.onTertiaryContainer(), 0xCC));
        detail.setTextSize(tall ? 18 : 14);
        detail.setPadding(0, dp(tall ? 8 : 4), 0, 0);
        card.addView(detail);

        // Long-press now means "edit the home screen" like everywhere else,
        // so a reset is a tap and a confirmation.
        card.setOnClickListener(new View.OnClickListener() {
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
        add(new Live(TRIP) {
            @Override void refresh() {
                Trip tr = host.trip();
                if (tr == null) return;
                boolean miles = Prefs.mph(ctx);
                float m = tr.metres();
                long ms = tr.movingMs();
                Motion.setText(dist, Trip.distance(m, miles) + (miles ? " mi" : " km"));
                int avg = Trip.average(m, ms, miles);
                String text = Trip.duration(ms);
                if (avg >= 0) text += " · " + avg + (miles ? " mph " : " km/h ") + ctx.getString(R.string.trip_avg);
                Motion.setText(detail, text);
            }
        }).refresh();
        return card;
    }

    // ---- vehicle ------------------------------------------------------------------

    private View vehicle(final Item it) {
        final String field = it.get("field", "coolant");
        int[] def = Kind.vehicleRange(field);
        final ValueView v = new ValueView(ctx, M3.withAlpha(m3.onSurface(), 0x26), m3.primary(),
                m3.onSurface(), m3.onSurfaceVariant());
        v.setBoxes(Shapes.round(d, m3.surfaceContainer(), 28), Shapes.round(d, m3.errorContainer(), 28),
                m3.error(), m3.onErrorContainer());
        v.configure(Kind.vehicleLabel(field), Kind.vehicleUnit(field), "gauge".equals(it.get("style", "bar")),
                it.getInt("min", def[0]), it.getInt("max", def[1]), it.getInt("warn", def[2]),
                it.get("warndir", Kind.vehicleWarnDir(field)));
        final boolean outside = "outside".equals(field);
        final boolean f = outside && Prefs.fahrenheit(ctx);
        if (f) {
            v.configure(Kind.vehicleLabel(field), "°F", "gauge".equals(it.get("style", "bar")),
                    toF(it.getInt("min", def[0])), toF(it.getInt("max", def[1])), toF(it.getInt("warn", def[2])),
                    it.get("warndir", Kind.vehicleWarnDir(field)));
        }
        add(new Live(VEH) {
            @Override void refresh() {
                VehicleState s = lastVehicle;
                int val = VehicleState.UNKNOWN_INT;
                String note = null;
                if (s != null) {
                    if ("fuel".equals(field)) {
                        val = s.fuelPercent;
                        if (s.fuelUnsupported) note = ctx.getString(R.string.unavailable);
                    } else if ("rpm".equals(field)) val = s.rpm;
                    else if ("speed".equals(field)) val = s.speedKph;
                    else if (outside) val = s.outsideC;
                    else val = s.coolantC;
                }
                if (outside) {
                    // Intake air stands in only when nothing reports outside air, and says so.
                    boolean intake = val == VehicleState.UNKNOWN_INT && s != null
                            && s.intakeC != VehicleState.UNKNOWN_INT;
                    if (intake) val = s.intakeC;
                    v.setLabel(ctx.getString(intake ? R.string.intake_label : R.string.outside_label));
                }
                boolean known = val != VehicleState.UNKNOWN_INT;
                v.setValue(known, known && f ? toF(val) : val, note);
            }
        }).refresh();
        return v;
    }

    private static int toF(int c) {
        return Math.round(c * 9f / 5f + 32f);
    }

    private View doors() {
        final DoorsView v = new DoorsView(ctx, M3.withAlpha(m3.onSurface(), 0x22), m3.primary(),
                M3.withAlpha(m3.onSurfaceVariant(), 0x88), m3.onSurfaceVariant());
        v.setBackground(Shapes.round(d, m3.surfaceContainer(), 28));
        add(new Live(VEH) {
            @Override void refresh() { v.setState(lastVehicle); }
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
        final TextView fuel = chip(bar), coolant = chip(bar), rpm = chip(bar), outside = chip(bar), doors = chip(bar),
                sample = chip(bar);
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
                show(outside, s.outsideC != VehicleState.UNKNOWN_INT ? "Outside " + temperature(s.outsideC) : null,
                        m3.surfaceContainerHigh(), m3.onSurface());
                int open = s.openDoors();
                show(doors, open > 0 ? (open == 1 ? "Door open" : open + " doors open") : null,
                        m3.primaryContainer(), m3.onPrimaryContainer());
                boolean empty = fuel.getVisibility() != View.VISIBLE && coolant.getVisibility() != View.VISIBLE
                        && rpm.getVisibility() != View.VISIBLE && outside.getVisibility() != View.VISIBLE
                        && doors.getVisibility() != View.VISIBLE;
                show(sample, editing && empty ? "Vehicle strip" : null,
                        m3.surfaceContainerHigh(), m3.onSurfaceVariant());
            }
        }).refresh();
        return scroll;
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
