package com.limelight.overlay;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.LinearInterpolator;

/**
 * Overlay shown during reconnection attempts. Displays a semi-transparent dark overlay
 * with pulsing dots animation and attempt counter over the frozen last frame.
 */
public class ReconnectOverlay extends View {

    private final Paint bgPaint;
    private final Paint textPaint;
    private final Paint dotPaint;

    private int currentAttempt;
    private int maxAttempts;
    private boolean active;

    private ValueAnimator pulseAnimator;
    private float pulsePhase; // 0..1 for dot animation

    private static final float DOT_RADIUS_DP = 6f;
    private static final float DOT_SPACING_DP = 20f;
    private static final int NUM_DOTS = 3;
    private static final float TEXT_SIZE_SP = 16f;
    private static final float COUNTER_SIZE_SP = 14f;

    private final StringBuilder reusableSb = new StringBuilder(16);

    public ReconnectOverlay(Context context) {
        this(context, null);
    }

    public ReconnectOverlay(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ReconnectOverlay(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);

        bgPaint = new Paint();
        bgPaint.setColor(Color.argb(160, 0, 0, 0));
        bgPaint.setStyle(Paint.Style.FILL);

        float density = context.getResources().getDisplayMetrics().density;

        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(TEXT_SIZE_SP * density);
        textPaint.setTextAlign(Paint.Align.CENTER);

        dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        dotPaint.setColor(Color.WHITE);
        dotPaint.setStyle(Paint.Style.FILL);

        setVisibility(GONE);
    }

    /**
     * Show the reconnect overlay and start the pulsing animation.
     */
    public void show(int maxAttempts) {
        this.maxAttempts = maxAttempts;
        // Intentional: counter is hidden until the first setAttempt(1) call;
        // brief visual pause is acceptable and matches reconnection semantics.
        this.currentAttempt = 0;
        this.active = true;
        setVisibility(VISIBLE);
        // Avoid race during onCreate: if not yet attached, the ValueAnimator's
        // invalidate() callbacks won't take effect. Defer until attach.
        if (isAttachedToWindow()) {
            startPulseAnimation();
        } else {
            post(this::startPulseAnimation);
        }
        invalidate();
    }

    /**
     * Hide the reconnect overlay and stop animation.
     */
    public void hide() {
        this.active = false;
        setVisibility(GONE);
        stopPulseAnimation();
    }

    /**
     * Update the current attempt number.
     */
    public void setAttempt(int attempt) {
        this.currentAttempt = attempt;
        invalidate();
    }

    public boolean isActive() {
        return active;
    }

    private void startPulseAnimation() {
        stopPulseAnimation();
        pulseAnimator = ValueAnimator.ofFloat(0f, 1f);
        pulseAnimator.setDuration(1200);
        pulseAnimator.setRepeatCount(ValueAnimator.INFINITE);
        pulseAnimator.setInterpolator(new LinearInterpolator());
        pulseAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                pulsePhase = (float) animation.getAnimatedValue();
                invalidate();
            }
        });
        pulseAnimator.start();
    }

    private void stopPulseAnimation() {
        if (pulseAnimator != null) {
            pulseAnimator.cancel();
            pulseAnimator = null;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!active) {
            return;
        }

        float density = getResources().getDisplayMetrics().density;

        // Semi-transparent background
        canvas.drawRect(0, 0, getWidth(), getHeight(), bgPaint);

        float centerX = getWidth() / 2f;
        float centerY = getHeight() / 2f;

        // Draw "Reconnecting..." text
        String label = "Reconnecting...";
        textPaint.setTextSize(TEXT_SIZE_SP * density);
        canvas.drawText(label, centerX, centerY - 30 * density, textPaint);

        // Draw pulsing dots
        float dotRadius = DOT_RADIUS_DP * density;
        float dotSpacing = DOT_SPACING_DP * density;
        float dotsStartX = centerX - (NUM_DOTS - 1) * dotSpacing / 2f;
        float dotsY = centerY;

        for (int i = 0; i < NUM_DOTS; i++) {
            // Phase offset for each dot
            float dotPhase = (pulsePhase + (float) i / NUM_DOTS) % 1f;
            // Pulsing alpha: sine wave
            float alpha = (float) (0.3 + 0.7 * Math.sin(dotPhase * Math.PI));
            dotPaint.setAlpha((int) (alpha * 255));

            float x = dotsStartX + i * dotSpacing;
            canvas.drawCircle(x, dotsY, dotRadius, dotPaint);
        }

        // Draw attempt counter
        if (currentAttempt > 0) {
            reusableSb.setLength(0);
            String counter = reusableSb.append(currentAttempt).append('/').append(maxAttempts).toString();
            textPaint.setTextSize(COUNTER_SIZE_SP * density);
            canvas.drawText(counter, centerX, centerY + 30 * density, textPaint);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stopPulseAnimation();
    }
}
