package com.limelight.overlay;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * Lightweight overlay that renders performance and network stats on top of the video surface.
 * Supports three display modes: OFF, COMPACT (single line at top), FULL (multi-line box in top-right).
 */
public class StatsOverlay extends View {

    // Stats from server (received via control channel)
    private int serverBitrate;
    private int serverFecPct;
    private int serverThermalState; // 0=cool, 1=warm, 2=hot

    // Stats from client (measured locally)
    private float decodeTimeMs;
    private float renderTimeMs;
    private float networkLatencyMs;
    private int fps;
    private String codec = "";

    // WiFi info (from WifiMonitor)
    private int wifiQuality; // 0-4
    private int wifiRssi;
    private int wifiLinkSpeed;

    // Display modes
    public enum Mode { OFF, COMPACT, FULL }
    private Mode mode = Mode.OFF;

    private final Paint bgPaint;
    private final Paint textPaint;
    private final Paint warnPaint;
    private final Paint critPaint;
    private final RectF bgRect;

    private static final float TEXT_SIZE_SP = 12f;
    private static final float COMPACT_TEXT_SIZE_SP = 10f;
    private static final float PADDING_DP = 8f;
    private static final float LINE_SPACING_DP = 2f;
    private static final float CORNER_RADIUS_DP = 4f;

    /**
     * StringBuilder reused across redraws to avoid GC pressure from repeated `new StringBuilder()`.
     * Note that `.toString()` on a StringBuilder DOES allocate a String — total allocations per
     * redraw = number of distinct text labels. The win vs the naive approach is in avoiding the
     * auto-grow path of fresh builders (and the per-call Formatter+intermediate-String allocations
     * that String.format would incur), not in avoiding all allocation.
     */
    private final StringBuilder reusableSb = new StringBuilder(128);

    // Cached values used to detect "no actual change" so we can skip invalidate() on no-op updates.
    private boolean hasCachedClient;
    private float cachedDecodeMs = Float.NaN;
    private float cachedRenderMs = Float.NaN;
    private float cachedNetworkMs = Float.NaN;
    private int cachedFps = -1;
    private String cachedCodec = null;

    private boolean hasCachedServer;
    private int cachedBitrate = -1;
    private int cachedFecPct = -1;
    private int cachedThermal = -1;

    private boolean hasCachedWifi;
    private int cachedWifiQuality = -1;
    private int cachedWifiRssi = Integer.MIN_VALUE;
    private int cachedWifiLinkSpeed = -1;

    public StatsOverlay(Context context) {
        this(context, null);
    }

    public StatsOverlay(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public StatsOverlay(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        float density = context.getResources().getDisplayMetrics().density;

        bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setColor(Color.argb(180, 0, 0, 0));
        bgPaint.setStyle(Paint.Style.FILL);

        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(TEXT_SIZE_SP * density);

        warnPaint = new Paint(textPaint);
        warnPaint.setColor(Color.YELLOW);

        critPaint = new Paint(textPaint);
        critPaint.setColor(Color.RED);

        bgRect = new RectF();
    }

    /**
     * Cycle through OFF -> FULL -> COMPACT -> OFF
     */
    public void toggle() {
        switch (mode) {
            case OFF:
                mode = Mode.FULL;
                break;
            case FULL:
                mode = Mode.COMPACT;
                break;
            case COMPACT:
                mode = Mode.OFF;
                break;
        }
        // Mode change forces a repaint regardless of value-equality caches.
        clearDirtyCaches();
        invalidate();
    }

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
        clearDirtyCaches();
        invalidate();
    }

    /**
     * Reset the "last rendered values" caches. Called on mode transitions so that the next
     * stats update is treated as dirty even if the underlying values are unchanged.
     */
    private void clearDirtyCaches() {
        hasCachedClient = false;
        hasCachedServer = false;
        hasCachedWifi = false;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mode == Mode.OFF) {
            return;
        }

        float density = getResources().getDisplayMetrics().density;
        float padding = PADDING_DP * density;
        float cornerRadius = CORNER_RADIUS_DP * density;

        if (mode == Mode.COMPACT) {
            drawCompact(canvas, density, padding, cornerRadius);
        } else {
            drawFull(canvas, density, padding, cornerRadius);
        }
    }

    private void drawCompact(Canvas canvas, float density, float padding, float cornerRadius) {
        textPaint.setTextSize(COMPACT_TEXT_SIZE_SP * density);
        float textHeight = textPaint.descent() - textPaint.ascent();

        String line = buildCompactLine();

        float textWidth = textPaint.measureText(line);
        float boxWidth = textWidth + padding * 2;
        float boxHeight = textHeight + padding * 2;

        // Centered at top
        float left = (getWidth() - boxWidth) / 2f;
        bgRect.set(left, 0, left + boxWidth, boxHeight);
        canvas.drawRoundRect(bgRect, cornerRadius, cornerRadius, bgPaint);

        canvas.drawText(line, left + padding, padding - textPaint.ascent(), getTextPaintForQuality());
    }

    private void drawFull(Canvas canvas, float density, float padding, float cornerRadius) {
        textPaint.setTextSize(TEXT_SIZE_SP * density);
        float lineHeight = textPaint.descent() - textPaint.ascent() + LINE_SPACING_DP * density;

        String[] lines = buildFullLines();

        // Measure max width
        float maxWidth = 0;
        for (String line : lines) {
            float w = textPaint.measureText(line);
            if (w > maxWidth) maxWidth = w;
        }

        float boxWidth = maxWidth + padding * 2;
        float boxHeight = lineHeight * lines.length + padding * 2;

        // Top-right corner
        float left = getWidth() - boxWidth - padding;
        float top = padding;
        bgRect.set(left, top, left + boxWidth, top + boxHeight);
        canvas.drawRoundRect(bgRect, cornerRadius, cornerRadius, bgPaint);

        float y = top + padding - textPaint.ascent();
        for (String line : lines) {
            Paint paint = textPaint;
            if (line.startsWith("[!]")) {
                paint = warnPaint;
                line = line.substring(3);
            } else if (line.startsWith("[X]")) {
                paint = critPaint;
                line = line.substring(3);
            }
            canvas.drawText(line, left + padding, y, paint);
            y += lineHeight;
        }
    }

    /**
     * Append `v` formatted with one decimal place, followed by `suffix`. Avoids the
     * Formatter + intermediate-String allocations of String.format on the hot draw path.
     * Negative values are emitted with a leading '-'. Rounding is half-up at the tenths place.
     */
    private static void appendOneDecimal(StringBuilder sb, float v, String suffix) {
        if (Float.isNaN(v) || Float.isInfinite(v)) {
            sb.append(v).append(suffix);
            return;
        }
        if (v < 0) {
            sb.append('-');
            v = -v;
        }
        // Round half-up at the tenths place: scale * 10, +0.5, truncate.
        int scaled = (int)(v * 10f + 0.5f);
        int intPart = scaled / 10;
        int decPart = scaled % 10;
        sb.append(intPart).append('.').append(decPart).append(suffix);
    }

    private String buildCompactLine() {
        reusableSb.setLength(0);
        reusableSb.append(fps).append(" FPS");
        if (codec != null && !codec.isEmpty()) {
            reusableSb.append(" | ").append(codec);
        }
        reusableSb.append(" | ");
        appendOneDecimal(reusableSb, decodeTimeMs, "ms");
        reusableSb.append(" | WiFi ").append(getWifiQualityLabel());
        if (serverThermalState > 0) {
            reusableSb.append(" | ").append(getThermalLabel());
        }
        return reusableSb.toString();
    }

    private String[] buildFullLines() {
        String thermalPrefix = serverThermalState >= 2 ? "[X]" : serverThermalState == 1 ? "[!]" : "";
        String wifiPrefix = wifiQuality <= 1 ? (wifiQuality == 0 ? "[X]" : "[!]") : "";

        reusableSb.setLength(0);
        reusableSb.append("FPS: ").append(fps);
        if (codec != null && !codec.isEmpty()) {
            reusableSb.append(" (").append(codec).append(")");
        }
        String line0 = reusableSb.toString();

        reusableSb.setLength(0);
        reusableSb.append("Decode: ");
        appendOneDecimal(reusableSb, decodeTimeMs, " ms");
        String line1 = reusableSb.toString();

        reusableSb.setLength(0);
        reusableSb.append("Render: ");
        appendOneDecimal(reusableSb, renderTimeMs, " ms");
        String line2 = reusableSb.toString();

        reusableSb.setLength(0);
        reusableSb.append("Network: ");
        appendOneDecimal(reusableSb, networkLatencyMs, " ms");
        String line3 = reusableSb.toString();

        reusableSb.setLength(0);
        reusableSb.append("Bitrate: ");
        if (serverBitrate > 0) {
            reusableSb.append(serverBitrate).append(" kbps");
        } else {
            reusableSb.append("N/A");
        }
        String line4 = reusableSb.toString();

        reusableSb.setLength(0);
        reusableSb.append("FEC: ");
        if (serverBitrate > 0) {
            reusableSb.append(serverFecPct).append('%');
        } else {
            reusableSb.append("N/A");
        }
        String line5 = reusableSb.toString();

        reusableSb.setLength(0);
        reusableSb.append(thermalPrefix).append("Thermal: ").append(getThermalLabel());
        String line6 = reusableSb.toString();

        reusableSb.setLength(0);
        reusableSb.append(wifiPrefix).append("WiFi: ").append(getWifiQualityLabel())
                .append(" (").append(wifiRssi).append(" dBm, ").append(wifiLinkSpeed).append(" Mbps)");
        String line7 = reusableSb.toString();

        return new String[] { line0, line1, line2, line3, line4, line5, line6, line7 };
    }

    private String getWifiQualityLabel() {
        switch (wifiQuality) {
            case 4: return "Excellent";
            case 3: return "Good";
            case 2: return "Fair";
            case 1: return "Poor";
            case 0: return "Critical";
            default: return "Unknown";
        }
    }

    private String getThermalLabel() {
        switch (serverThermalState) {
            case 0: return "Cool";
            case 1: return "Warm";
            case 2: return "Hot";
            default: return "Unknown";
        }
    }

    private Paint getTextPaintForQuality() {
        if (wifiQuality == 0 || serverThermalState >= 2) {
            return critPaint;
        }
        if (wifiQuality == 1 || serverThermalState == 1) {
            return warnPaint;
        }
        return textPaint;
    }

    /**
     * Update stats received from the server via control channel.
     * Only invalidates if a value actually changed (avoids redundant redraws of unchanged frames).
     */
    public void updateServerStats(int bitrate, int fecPct, int thermal) {
        this.serverBitrate = bitrate;
        this.serverFecPct = fecPct;
        this.serverThermalState = thermal;
        if (mode == Mode.OFF) {
            return;
        }
        boolean dirty = !hasCachedServer
                || cachedBitrate != bitrate
                || cachedFecPct != fecPct
                || cachedThermal != thermal;
        if (!dirty) {
            return;
        }
        hasCachedServer = true;
        cachedBitrate = bitrate;
        cachedFecPct = fecPct;
        cachedThermal = thermal;
        invalidate();
    }

    /**
     * Update client-measured stats.
     * Only invalidates if a value actually changed.
     */
    public void updateClientStats(float decode, float render, float network, int fps, String codec) {
        this.decodeTimeMs = decode;
        this.renderTimeMs = render;
        this.networkLatencyMs = network;
        this.fps = fps;
        this.codec = codec;
        if (mode == Mode.OFF) {
            return;
        }
        // Compare floats by bits to handle NaN consistently and avoid `==` on NaN returning false forever.
        boolean dirty = !hasCachedClient
                || Float.floatToRawIntBits(cachedDecodeMs) != Float.floatToRawIntBits(decode)
                || Float.floatToRawIntBits(cachedRenderMs) != Float.floatToRawIntBits(render)
                || Float.floatToRawIntBits(cachedNetworkMs) != Float.floatToRawIntBits(network)
                || cachedFps != fps
                || (cachedCodec == null ? codec != null : !cachedCodec.equals(codec));
        if (!dirty) {
            return;
        }
        hasCachedClient = true;
        cachedDecodeMs = decode;
        cachedRenderMs = render;
        cachedNetworkMs = network;
        cachedFps = fps;
        cachedCodec = codec;
        invalidate();
    }

    /**
     * Update WiFi stats from WifiMonitor.
     * Only invalidates if a value actually changed.
     */
    public void updateWifiStats(int quality, int rssi, int linkSpeed) {
        this.wifiQuality = quality;
        this.wifiRssi = rssi;
        this.wifiLinkSpeed = linkSpeed;
        if (mode == Mode.OFF) {
            return;
        }
        boolean dirty = !hasCachedWifi
                || cachedWifiQuality != quality
                || cachedWifiRssi != rssi
                || cachedWifiLinkSpeed != linkSpeed;
        if (!dirty) {
            return;
        }
        hasCachedWifi = true;
        cachedWifiQuality = quality;
        cachedWifiRssi = rssi;
        cachedWifiLinkSpeed = linkSpeed;
        invalidate();
    }
}
