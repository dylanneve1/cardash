package ie.claudius.cardash;

import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

import ie.claudius.cardash.layout.Arrange;
import ie.claudius.cardash.layout.HomeLayout;
import ie.claudius.cardash.layout.HomeLayout.Item;
import ie.claudius.cardash.layout.Kind;

/**
 * Edit mode: the toolbar and the dialogs. The grid-level gestures
 * (drag, resize handle, gear, X) live in {@link GridPage}; this class
 * owns the working copy of the layout, adds and removes things, and
 * keeps Cancel honest — nothing is saved until Done.
 */
final class HomeEditor implements GridPage.Edit {

    private final HomeActivity act;
    private final M3 m3;
    private final float d;
    final HomeLayout working;
    private final String original;
    String originalJson() { return original; }
    private LinearLayout bar;
    private TextView pageLabel;

    /** @param originalJson what "unchanged" means; null = the layout as it is now (a fresh edit) */
    HomeEditor(HomeActivity act, M3 m3, HomeLayout working, String originalJson) {
        this.act = act;
        this.m3 = m3;
        this.d = act.getResources().getDisplayMetrics().density;
        this.working = working;
        this.original = originalJson != null ? originalJson : working.toJson();
    }

    private int dp(float v) { return Math.round(v * d); }

    boolean dirty() { return !original.equals(working.toJson()); }

    HomeLayout.Page page() {
        return working.pages.get(Math.max(0, Math.min(act.pageIndex(), working.pages.size() - 1)));
    }

    // ---- toolbar ----------------------------------------------------------------

    View toolbar() {
        bar = new LinearLayout(act);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(Shapes.round(d, M3.withAlpha(m3.surfaceContainerHigh(), 0xF2), 36));
        bar.setPadding(dp(10), dp(6), dp(10), dp(6));
        bar.setElevation(dp(8));

        bar.addView(text(act.getString(android.R.string.cancel), false, new Runnable() {
            @Override public void run() { cancel(); }
        }));
        bar.addView(icon(Glyph.Kind.GEAR, act.getString(R.string.settings), new Runnable() {
            @Override public void run() { act.openSettings(); }
        }));
        bar.addView(text(act.getString(R.string.edit_add), false, new Runnable() {
            @Override public void run() { addPicker(); }
        }));
        bar.addView(text("<", false, new Runnable() {
            @Override public void run() { act.goToPage(act.pageIndex() - 1); label(); }
        }));
        pageLabel = new TextView(act);
        pageLabel.setTypeface(Fonts.display(act));
        pageLabel.setTextColor(m3.onSurface());
        pageLabel.setTextSize(16);
        pageLabel.setGravity(Gravity.CENTER);
        bar.addView(pageLabel, new LinearLayout.LayoutParams(dp(64), dp(48)));
        bar.addView(text(">", false, new Runnable() {
            @Override public void run() { act.goToPage(act.pageIndex() + 1); label(); }
        }));
        bar.addView(text(act.getString(R.string.edit_style), false, new Runnable() {
            @Override public void run() { stylePicker(); }
        }));
        bar.addView(text(act.getString(R.string.edit_page_add), false, new Runnable() {
            @Override public void run() { addPage(); }
        }));
        bar.addView(text(act.getString(R.string.edit_page_remove), false, new Runnable() {
            @Override public void run() { removePage(); }
        }));
        bar.addView(text(act.getString(R.string.edit_reset), false, new Runnable() {
            @Override public void run() { reset(); }
        }));
        bar.addView(text(act.getString(R.string.done), true, new Runnable() {
            @Override public void run() { act.exitEdit(true); }
        }));
        label();
        // A narrow screen scrolls the toolbar instead of cutting "Done" off.
        HorizontalScrollView scroll = new HorizontalScrollView(act);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(bar);
        return scroll;
    }

    void label() {
        if (pageLabel != null) pageLabel.setText((act.pageIndex() + 1) + " / " + working.pages.size());
    }

    private TextView text(String s, boolean primary, final Runnable run) {
        TextView t = new TextView(act);
        t.setText(s);
        t.setTypeface(Fonts.display(act));
        t.setTextSize(16);
        t.setGravity(Gravity.CENTER);
        t.setMinHeight(dp(48));
        t.setMinWidth(dp(48));
        t.setPadding(dp(14), dp(6), dp(14), dp(6));
        int fill = primary ? m3.primary() : M3.withAlpha(m3.surfaceContainer(), 0);
        int on = primary ? m3.onPrimary() : m3.onSurface();
        t.setTextColor(on);
        t.setBackground(Shapes.pill(d, fill, M3.withAlpha(on, 0x40)));
        t.setClickable(true);
        Shapes.springy(t);
        t.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { run.run(); }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(48));
        lp.leftMargin = dp(2);
        lp.rightMargin = dp(2);
        t.setLayoutParams(lp);
        return t;
    }

    private View icon(Glyph.Kind kind, String description, final Runnable run) {
        ImageView b = new ImageView(act);
        b.setImageDrawable(new Glyph(kind, m3.onSurface()));
        b.setBackground(Shapes.pill(d, M3.withAlpha(m3.surfaceContainer(), 0), M3.withAlpha(m3.onSurface(), 0x40)));
        b.setContentDescription(description);
        b.setPadding(dp(11), dp(11), dp(11), dp(11));
        b.setClickable(true);
        Shapes.springy(b);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { run.run(); }
        });
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
        return b;
    }

    // ---- GridPage.Edit --------------------------------------------------------------

    @Override
    public void remove(Item it) {
        page().items.remove(it);
        act.rebuildEditing();
    }

    @Override
    public void changed(boolean structural) {
        if (structural) act.rebuildEditing();
    }

    @Override
    public void dragging(boolean on) {
        if (bar != null) bar.animate().alpha(on ? 0.15f : 1f).setDuration(120).start();
    }

    @Override
    public void gear(final Item it) {
        final Kind k = Kind.of(it.type);
        if (k == null) return;
        final Pop pop = new Pop(act, m3, k.label);
        if (Kind.APP.equals(it.type)) {
            String pkg = act.tilePackage(it);
            Apps.Entry e = Apps.byPackage(act, pkg);
            pop.note(e != null ? e.label : act.getString(R.string.empty_tile));
            pop.row(act.getString(R.string.edit_choose_app), new Runnable() {
                @Override public void run() { act.pickApp(it); }
            });
        }
        for (final Kind.Option o : k.options) {
            if (o.isChoice()) {
                pop.choices(o.label, o.choices, it.get(o.key, o.def), new Pop.Choice() {
                    @Override public void on(String v) {
                        it.settings.put(o.key, v);
                        if (Kind.VEHICLE.equals(it.type) && "field".equals(o.key)) {
                            // A new value brings its own usual range and warning.
                            String style = it.get("style", "bar");
                            it.settings.putAll(Kind.vehicleDefaults(v));
                            it.settings.put("style", style);
                            pop.dismiss();   // rebuilds; then reopen with the new field's numbers
                            gear(it);
                        }
                    }
                });
            } else {
                int step = Kind.VEHICLE.equals(it.type) ? Kind.vehicleStep(it.get("field", "coolant")) : o.step;
                pop.stepper(o.label, it.getInt(o.key, Integer.parseInt(o.def)), o.min, o.max, step,
                        new Pop.Count() {
                            @Override public void on(int v) { it.settings.put(o.key, String.valueOf(v)); }
                        });
            }
        }
        pop.button(act.getString(R.string.done), null, true);
        pop.onDismiss(new Runnable() {
            @Override public void run() { act.rebuildEditing(); }
        });
        pop.show();
    }

    // ---- add ---------------------------------------------------------------------------

    private static final class Entry {
        final String label, kind, field;
        Entry(String label, String kind, String field) { this.label = label; this.kind = kind; this.field = field; }
    }

    void addPicker() {
        final List<Entry> entries = new ArrayList<>();
        entries.add(new Entry(act.getString(R.string.edit_app), Kind.APP, null));
        for (Kind k : Kind.all()) {
            if (Kind.APP.equals(k.id)) continue;
            if (Kind.VEHICLE.equals(k.id)) {
                for (String f : new String[] { "coolant", "fuel", "rpm", "speed", "outside" }) {
                    entries.add(new Entry(Kind.vehicleLabel(f), k.id, f));
                }
            } else {
                entries.add(new Entry(k.label, k.id, null));
            }
        }
        final Pop pop = new Pop(act, m3, act.getString(R.string.edit_add_title));
        LinearLayout row = null;
        for (int i = 0; i < entries.size(); i++) {
            if (i % 3 == 0) {
                row = new LinearLayout(act);
                row.setOrientation(LinearLayout.HORIZONTAL);
                pop.body.addView(row);
            }
            final Entry e = entries.get(i);
            TextView b = pop.pill(e.label, false);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { pop.dismiss(); add(e); }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(56), 1f);
            lp.setMargins(dp(4), dp(4), dp(4), dp(4));
            row.addView(b, lp);
        }
        pop.button(act.getString(android.R.string.cancel), null, false);
        pop.show();
    }

    /** A classic cluster page shows no widgets, so nothing is added to it unseen. */
    private boolean classicPage() {
        if (!page().classic(Prefs.carTheme(act))) return false;
        Toast.makeText(act, R.string.classic_edit_note, Toast.LENGTH_LONG).show();
        return true;
    }

    private void add(Entry e) {
        if (classicPage()) return;
        if (Kind.APP.equals(e.kind)) {
            act.pickApp(null);   // the result adds the tile
            return;
        }
        Kind k = Kind.of(e.kind);
        Item it = Arrange.place(working, page(), k);
        if (it == null) {
            Toast.makeText(act, R.string.edit_no_room, Toast.LENGTH_SHORT).show();
            return;
        }
        if (e.field != null) it.settings.putAll(Kind.vehicleDefaults(e.field));
        act.rebuildEditing();
    }

    /** The app picker returned for a new tile. */
    void addApp(String pkg) {
        Item it = Arrange.place(working, page(), Kind.of(Kind.APP));
        if (it == null) {
            Toast.makeText(act, R.string.edit_no_room, Toast.LENGTH_SHORT).show();
            return;
        }
        it.settings.put("pkg", pkg);
        act.rebuildEditing();
    }

    // ---- pages -------------------------------------------------------------------------

    /** How this page looks: its widgets, or the classic cluster screen. */
    private void stylePicker() {
        final HomeLayout.Page p = page();
        String current = p.classic(Prefs.carTheme(act)) ? HomeLayout.CLASSIC : HomeLayout.MODERN;
        final Pop pop = new Pop(act, m3, act.getString(R.string.edit_style_title));
        pop.row(mark(act.getString(R.string.s_car_theme_modern), HomeLayout.MODERN.equals(current)),
                style(p, HomeLayout.MODERN));
        pop.row(mark(act.getString(R.string.s_car_theme_classic), HomeLayout.CLASSIC.equals(current)),
                style(p, HomeLayout.CLASSIC));
        pop.button(act.getString(android.R.string.cancel), null, false).show();
    }

    private static String mark(String s, boolean selected) {
        return selected ? s + "  ✓" : s;
    }

    private Runnable style(final HomeLayout.Page p, final String theme) {
        return new Runnable() {
            @Override public void run() {
                p.theme = theme;
                act.rebuildEditing();
            }
        };
    }

    private void addPage() {
        if (working.pages.size() >= HomeLayout.MAX_PAGES) {
            Toast.makeText(act, R.string.edit_no_room, Toast.LENGTH_SHORT).show();
            return;
        }
        new Pop(act, m3, act.getString(R.string.edit_page_add))
                .row(act.getString(R.string.edit_page_empty), new Runnable() {
                    @Override public void run() { insertPage(new HomeLayout.Page()); }
                })
                .row(act.getString(R.string.edit_page_vehicle), new Runnable() {
                    @Override public void run() { insertPage(LayoutStore.carPage(act)); }
                })
                .button(act.getString(android.R.string.cancel), null, false)
                .show();
    }

    private void insertPage(HomeLayout.Page p) {
        int at = Math.min(act.pageIndex() + 1, working.pages.size());
        working.insertPage(at, p);
        act.setPageIndex(at);
        act.rebuildEditing();
    }

    private void removePage() {
        if (working.pages.size() <= 1) {
            // The last page is emptied rather than removed: there is always a home screen.
            Pop.confirm(act, m3, act.getString(R.string.edit_page_clear_q), act.getString(R.string.edit_page_clear),
                    new Runnable() {
                        @Override public void run() {
                            page().items.clear();
                            act.rebuildEditing();
                        }
                    });
            return;
        }
        Pop.confirm(act, m3, act.getString(R.string.edit_page_remove_q), act.getString(R.string.edit_page_remove),
                new Runnable() {
                    @Override public void run() {
                        int at = act.pageIndex();
                        working.removePage(at);
                        act.setPageIndex(Math.max(0, at - 1));
                        act.rebuildEditing();
                    }
                });
    }

    private void reset() {
        Pop.confirm(act, m3, act.getString(R.string.edit_reset_q), act.getString(R.string.edit_reset),
                new Runnable() {
                    @Override public void run() {
                        HomeLayout fresh = LayoutStore.defaults(act);
                        working.pages.clear();
                        working.pages.addAll(fresh.pages);
                        working.cols = fresh.cols;
                        working.rows = fresh.rows;
                        working.start = fresh.start;
                        act.setPageIndex(fresh.start);
                        act.rebuildEditing();
                    }
                });
    }

    private void cancel() {
        if (!dirty()) { act.exitEdit(false); return; }
        Pop.confirm(act, m3, act.getString(R.string.edit_discard_q), act.getString(R.string.edit_discard),
                new Runnable() {
                    @Override public void run() { act.exitEdit(false); }
                });
    }

    /** Back button: leave quietly if nothing changed, else say how to keep or drop the changes. */
    void back() {
        if (!dirty()) act.exitEdit(false);
        else Toast.makeText(act, R.string.edit_back_hint, Toast.LENGTH_SHORT).show();
    }
}
