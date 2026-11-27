package ie.claudius.cardash;

import android.text.TextUtils;
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
 * Edit mode: the toolbar and the dialogs. There is one toolbar along the
 * bottom: the page actions, or — while an item is selected — what can be
 * done to that item. Taps and drags on the grid live in {@link GridPage};
 * this class owns the working copy of the layout, adds and removes
 * things, and keeps Cancel honest — nothing is saved until Done.
 */
final class HomeEditor implements GridPage.Edit {

    /** The height the screen keeps free under the grid for the toolbar, in dp. */
    static final int BAR_SPACE_DP = 80;
    private static final int BUTTON_W_DP = 72, BUTTON_H_DP = 56;

    /** The selection; shared with the editor that replaces this one when the screen is rebuilt. */
    static final class State {
        Item selected;
        boolean resizing;
    }

    private final HomeActivity act;
    private final M3 m3;
    private final float d;
    final HomeLayout working;
    private final String original;
    String originalJson() { return original; }
    final State state;
    private LinearLayout bar;

    /** @param originalJson what "unchanged" means; null = the layout as it is now (a fresh edit) */
    HomeEditor(HomeActivity act, M3 m3, HomeLayout working, String originalJson, State state) {
        this.act = act;
        this.m3 = m3;
        this.d = act.getResources().getDisplayMetrics().density;
        this.working = working;
        this.original = originalJson != null ? originalJson : working.toJson();
        this.state = state;
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
        bar.setBackground(Shapes.round(d, M3.withAlpha(m3.surfaceContainerHigh(), 0xF2), 34));
        bar.setPadding(dp(10), dp(6), dp(10), dp(6));
        bar.setElevation(dp(8));
        fill();
        // A narrow screen scrolls the toolbar instead of cutting "Done" off.
        HorizontalScrollView scroll = new HorizontalScrollView(act);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.addView(bar);
        return scroll;
    }

    /** The buttons for what is selected now. */
    private void fill() {
        if (bar == null) return;
        bar.removeAllViews();
        Item it = selected();
        if (it == null) mainButtons();
        else itemButtons(it);
    }

    private void mainButtons() {
        add(Glyph.Kind.CLOSE, act.getString(android.R.string.cancel), new Runnable() {
            @Override public void run() { cancel(); }
        });
        add(Glyph.Kind.PLUS, act.getString(R.string.edit_add), new Runnable() {
            @Override public void run() { addPicker(); }
        });
        divider();
        int at = act.pageIndex(), n = working.pages.size();
        enable(add(Glyph.Kind.LEFT, act.getString(R.string.edit_prev), new Runnable() {
            @Override public void run() { act.goToPage(act.pageIndex() - 1); }
        }), at > 0);
        bar.addView(caption((at + 1) + " / " + n, 16, m3.onSurface()),
                new LinearLayout.LayoutParams(dp(56), dp(BUTTON_H_DP)));
        enable(add(Glyph.Kind.RIGHT, act.getString(R.string.edit_next), new Runnable() {
            @Override public void run() { act.goToPage(act.pageIndex() + 1); }
        }), at < n - 1);
        add(Glyph.Kind.PAGES, act.getString(R.string.edit_pages), new Runnable() {
            @Override public void run() { pagesMenu(); }
        });
        add(Glyph.Kind.STYLE, act.getString(R.string.edit_style), new Runnable() {
            @Override public void run() { stylePicker(); }
        });
        divider();
        add(Glyph.Kind.RESET, act.getString(R.string.edit_reset), new Runnable() {
            @Override public void run() { reset(); }
        });
        add(Glyph.Kind.GEAR, act.getString(R.string.settings), new Runnable() {
            @Override public void run() { act.openSettings(); }
        });
        button(Glyph.Kind.CHECK, act.getString(R.string.done), true, new Runnable() {
            @Override public void run() { act.exitEdit(true); }
        });
    }

    private void itemButtons(final Item it) {
        Kind k = Kind.of(it.type);
        String name = Kind.VEHICLE.equals(it.type) ? Kind.vehicleLabel(it.get("field", "coolant"))
                : k == null ? it.type : k.label;
        TextView title = caption(name, 14, m3.primary());
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        bar.addView(title, new LinearLayout.LayoutParams(dp(96), dp(BUTTON_H_DP)));
        mode(add(Glyph.Kind.MOVE, act.getString(R.string.edit_move), new Runnable() {
            @Override public void run() { setResizing(false); }
        }), !state.resizing);
        mode(add(Glyph.Kind.RESIZE, act.getString(R.string.resize), new Runnable() {
            @Override public void run() { setResizing(true); }
        }), state.resizing);
        enable(add(Glyph.Kind.GEAR, act.getString(R.string.settings), new Runnable() {
            @Override public void run() { gear(it); }
        }), k != null && !k.options.isEmpty());
        add(Glyph.Kind.TRASH, act.getString(R.string.remove), new Runnable() {
            @Override public void run() { remove(it); }
        });
        divider();
        if (state.resizing) {
            size(Glyph.Kind.MINUS, R.string.edit_narrower, it, -1, 0);
            size(Glyph.Kind.PLUS, R.string.edit_wider, it, 1, 0);
            size(Glyph.Kind.MINUS, R.string.edit_shorter, it, 0, -1);
            size(Glyph.Kind.PLUS, R.string.edit_taller, it, 0, 1);
        } else {
            arrow(Glyph.Kind.LEFT, R.string.edit_left, it, -1, 0);
            arrow(Glyph.Kind.UP, R.string.edit_up, it, 0, -1);
            arrow(Glyph.Kind.DOWN, R.string.edit_down, it, 0, 1);
            arrow(Glyph.Kind.RIGHT, R.string.edit_right, it, 1, 0);
        }
        divider();
        button(Glyph.Kind.CHECK, act.getString(R.string.edit_deselect), true, new Runnable() {
            @Override public void run() { select(null); }
        });
    }

    private void arrow(Glyph.Kind glyph, int label, final Item it, final int dx, final int dy) {
        enable(add(glyph, act.getString(label), new Runnable() {
            @Override public void run() {
                int[] to = Arrange.nudge(working, page(), it, dx, dy);
                if (to == null) return;
                it.x = to[0];
                it.y = to[1];
                changed(false);
            }
        }), Arrange.nudge(working, page(), it, dx, dy) != null);
    }

    private void size(Glyph.Kind glyph, int label, final Item it, final int dw, final int dh) {
        enable(add(glyph, act.getString(label), new Runnable() {
            @Override public void run() {
                int[] to = Arrange.step(working, page(), it, dw, dh);
                if (to == null) return;
                it.w = to[0];
                it.h = to[1];
                changed(true);
            }
        }), Arrange.step(working, page(), it, dw, dh) != null);
    }

    private View add(Glyph.Kind glyph, String label, Runnable run) {
        return button(glyph, label, false, run);
    }

    /** An icon over a short label, at least 48dp each way. */
    private View button(Glyph.Kind glyph, String label, boolean primary, final Runnable run) {
        LinearLayout b = new LinearLayout(act);
        b.setOrientation(LinearLayout.VERTICAL);
        b.setGravity(Gravity.CENTER);
        int fill = primary ? m3.primary() : M3.withAlpha(m3.surfaceContainer(), 0);
        int on = primary ? m3.onPrimary() : m3.onSurface();
        b.setBackground(Shapes.pill(d, fill, M3.withAlpha(on, 0x40)));
        ImageView icon = new ImageView(act);
        icon.setImageDrawable(new Glyph(glyph, on));
        b.addView(icon, new LinearLayout.LayoutParams(dp(30), dp(30)));
        TextView t = caption(label, 12, on);
        t.setSingleLine(true);
        b.addView(t);
        b.setContentDescription(label);
        b.setClickable(true);
        Shapes.springy(b);
        b.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { run.run(); }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(BUTTON_W_DP), dp(BUTTON_H_DP));
        lp.leftMargin = dp(1);
        lp.rightMargin = dp(1);
        bar.addView(b, lp);
        return b;
    }

    /** Move or Resize: the one in use is filled in. */
    private void mode(View b, boolean on) {
        if (on) {
            b.setBackground(Shapes.pill(d, m3.primaryContainer(), M3.withAlpha(m3.onPrimaryContainer(), 0x40)));
            ViewGroup g = (ViewGroup) b;
            ((ImageView) g.getChildAt(0)).setColorFilter(m3.onPrimaryContainer());
            ((TextView) g.getChildAt(1)).setTextColor(m3.onPrimaryContainer());
        }
        b.setSelected(on);
    }

    private static void enable(View b, boolean on) {
        b.setEnabled(on);
        b.setAlpha(on ? 1f : 0.35f);
    }

    private TextView caption(String s, int sp, int color) {
        TextView t = new TextView(act);
        t.setText(s);
        t.setTypeface(Fonts.display(act));
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private void divider() {
        View v = new View(act);
        v.setBackgroundColor(M3.withAlpha(m3.onSurface(), 0x33));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Math.max(1, dp(1)), dp(36));
        lp.leftMargin = dp(6);
        lp.rightMargin = dp(6);
        bar.addView(v, lp);
    }

    // ---- selection --------------------------------------------------------------

    @Override
    public Item selected() {
        return state.selected != null && page().items.contains(state.selected) ? state.selected : null;
    }

    @Override
    public boolean resizing() {
        return state.resizing;
    }

    @Override
    public void select(Item it) {
        if (it != selected()) state.resizing = false;
        state.selected = it;
        refresh();
    }

    private void setResizing(boolean on) {
        state.resizing = on;
        refresh();
    }

    private void refresh() {
        act.refreshSelection();
        fill();
    }

    /** Another page is showing: nothing on it is selected yet. */
    void pageChanged() {
        select(null);
    }

    @Override
    public void addHere() {
        addPicker();
    }

    // ---- GridPage.Edit --------------------------------------------------------------

    private void remove(Item it) {
        page().items.remove(it);
        state.selected = null;
        state.resizing = false;
        act.rebuildEditing();
    }

    @Override
    public void changed(boolean structural) {
        if (structural) {
            act.rebuildEditing();
        } else {
            act.relayoutEditing();
            fill();   // which arrows still have somewhere to go
        }
    }

    @Override
    public void dragging(boolean on) {
        if (bar != null) bar.animate().alpha(on ? 0.15f : 1f).setDuration(120).start();
    }

    private void gear(final Item it) {
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
        added(it);
    }

    /** A new item comes up selected, ready to be put where it belongs. */
    private void added(Item it) {
        state.selected = it;
        state.resizing = false;
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
        added(it);
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

    private void pagesMenu() {
        new Pop(act, m3, act.getString(R.string.edit_pages))
                .row(act.getString(R.string.edit_page_add), new Runnable() {
                    @Override public void run() { addPage(); }
                })
                .row(act.getString(R.string.edit_page_remove), new Runnable() {
                    @Override public void run() { removePage(); }
                })
                .button(act.getString(android.R.string.cancel), null, false)
                .show();
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

    /**
     * Back button: drop the selection first; then leave quietly if nothing
     * changed, else say how to keep or drop the changes.
     */
    void back() {
        if (selected() != null) select(null);
        else if (!dirty()) act.exitEdit(false);
        else Toast.makeText(act, R.string.edit_back_hint, Toast.LENGTH_SHORT).show();
    }
}
