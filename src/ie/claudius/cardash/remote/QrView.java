package ie.claudius.cardash.remote;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/**
 * Draws a {@link QrCode} as dark modules on a light square with the
 * 4-module quiet zone scanners need. Phone cameras read dark-on-light
 * reliably; inverted codes still trip some of them, so this one small
 * square stays light even on the dark UI.
 */
public final class QrView extends View {

    private static final int QUIET = 4;

    private final Paint dark = new Paint();
    private final Paint light = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean[][] modules;
    private float corner;

    public QrView(Context context) {
        super(context);
        dark.setStyle(Paint.Style.FILL);
        light.setStyle(Paint.Style.FILL);
    }

    /** Returns false (and draws nothing) if the text doesn't fit a QR code. */
    public boolean set(String text, int darkColor, int lightColor, float cornerPx) {
        modules = QrCode.encode(text);
        dark.setColor(darkColor);
        light.setColor(lightColor);
        corner = cornerPx;
        setContentDescription(text);
        invalidate();
        return modules != null;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (modules == null) return;
        int n = modules.length, total = n + QUIET * 2;
        float side = Math.min(getWidth(), getHeight());
        // Whole pixels per module so edges stay crisp at any density.
        int cell = (int) Math.floor(side / total);
        if (cell < 1) return;
        float size = cell * total;
        float left = (getWidth() - size) / 2f, top = (getHeight() - size) / 2f;
        canvas.drawRoundRect(left, top, left + size, top + size, corner, corner, light);
        float ox = left + cell * QUIET, oy = top + cell * QUIET;
        for (int y = 0; y < n; y++) {
            for (int x = 0; x < n; x++) {
                if (!modules[y][x]) continue;
                canvas.drawRect(ox + x * cell, oy + y * cell, ox + (x + 1) * cell, oy + (y + 1) * cell, dark);
            }
        }
    }
}
