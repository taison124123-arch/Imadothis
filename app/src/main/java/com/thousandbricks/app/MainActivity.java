package com.thousandbricks.app;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window window = getWindow();
        window.setStatusBarColor(Color.rgb(16, 19, 28));
        window.setNavigationBarColor(Color.rgb(16, 19, 28));
        setContentView(new BricksView(this));
    }

    private static final class Brick {
        final float x, y, w, h;
        final int kind;
        Brick(float x, float y, float w, float h, int kind) {
            this.x = x; this.y = y; this.w = w; this.h = h; this.kind = kind;
        }
    }

    private static final class Confetti {
        float x, y, speed, size, phase;
        int color;
    }

    private static final class BricksView extends View {
        private static final String PREFS = "thousand_bricks_state";
        private static final String KEY_HISTORY = "history";
        private static final int MAX_BRICKS = 1000;
        private static final long DAY_MS = 86_400_000L;

        private final SharedPreferences prefs;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final SimpleDateFormat dayFormat = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        private final SimpleDateFormat displayDate = new SimpleDateFormat("d MMMM yyyy", new Locale("ru"));
        private final List<String> history = new ArrayList<>();
        private final Set<String> historySet = new HashSet<>();
        private final List<Brick> bricks = new ArrayList<>(MAX_BRICKS);
        private final List<Confetti> confetti = new ArrayList<>();

        private int tab = 0;
        private int brickCount = 0;
        private int currentStreak = 0;
        private int bestStreak = 0;
        private int missedDays = 0;
        private long newBrickAnimStart = 0L;
        private long milestoneUntil = 0L;
        private String milestoneMessage = "";
        private boolean finalCelebration = false;
        private long celebrationStart = 0L;

        private final RectF addButton = new RectF();
        private final RectF homeTab = new RectF();
        private final RectF historyTab = new RectF();

        BricksView(Context context) {
            super(context);
            setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            loadState();
            buildBrickMap();
            prepareConfetti();
            handler.post(ticker);
        }

        private final Runnable ticker = new Runnable() {
            @Override public void run() {
                invalidate();
                handler.postDelayed(this, 1000L);
            }
        };

        @Override protected void onDetachedFromWindow() {
            handler.removeCallbacks(ticker);
            super.onDetachedFromWindow();
        }

        private float dp(float v) {
            return v * getResources().getDisplayMetrics().density;
        }

        private void loadState() {
            String raw = prefs.getString(KEY_HISTORY, "");
            if (raw != null && !raw.trim().isEmpty()) {
                String[] parts = raw.split(",");
                for (String p : parts) {
                    String d = p.trim();
                    if (!d.isEmpty() && !historySet.contains(d)) {
                        history.add(d);
                        historySet.add(d);
                    }
                }
                Collections.sort(history);
            }
            if (history.size() > MAX_BRICKS) {
                history.subList(MAX_BRICKS, history.size()).clear();
                historySet.clear();
                historySet.addAll(history);
            }
            brickCount = history.size();
            recomputeStats();
        }

        private void saveState() {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < history.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(history.get(i));
            }
            prefs.edit().putString(KEY_HISTORY, sb.toString()).apply();
        }

        private long parseDay(String text) {
            try {
                Date d = dayFormat.parse(text);
                return d == null ? 0L : d.getTime();
            } catch (Exception e) {
                return 0L;
            }
        }

        private void recomputeStats() {
            currentStreak = 0;
            bestStreak = 0;
            if (!history.isEmpty()) {
                int run = 1;
                bestStreak = 1;
                for (int i = 1; i < history.size(); i++) {
                    long prev = parseDay(history.get(i - 1));
                    long cur = parseDay(history.get(i));
                    long diff = Math.round((cur - prev) / (double) DAY_MS);
                    if (diff == 1) run++;
                    else run = 1;
                    if (run > bestStreak) bestStreak = run;
                }
                String today = dayFormat.format(new Date());
                long last = parseDay(history.get(history.size() - 1));
                long now = parseDay(today);
                long since = Math.round((now - last) / (double) DAY_MS);
                if (since == 0) {
                    currentStreak = run;
                } else if (since == 1) {
                    currentStreak = run;
                } else {
                    currentStreak = 0;
                }
            }
            missedDays = 0;
            if (!history.isEmpty()) {
                long first = parseDay(history.get(0));
                long today = parseDay(dayFormat.format(new Date()));
                int elapsed = (int)Math.max(1, Math.round((today - first) / (double)DAY_MS) + 1);
                missedDays = Math.max(0, elapsed - history.size());
            }
        }

        private boolean canAddToday() {
            if (brickCount >= MAX_BRICKS) return false;
            if (history.isEmpty()) return true;
            String today = dayFormat.format(new Date());
            String last = history.get(history.size() - 1);
            return today.compareTo(last) > 0;
        }

        private boolean clockLooksRolledBack() {
            if (history.isEmpty()) return false;
            String today = dayFormat.format(new Date());
            String last = history.get(history.size() - 1);
            return today.compareTo(last) < 0;
        }

        private void addBrick() {
            if (!canAddToday()) return;
            String today = dayFormat.format(new Date());
            history.add(today);
            historySet.add(today);
            brickCount = history.size();
            recomputeStats();
            saveState();
            newBrickAnimStart = System.currentTimeMillis();
            performHapticFeedback(HapticFeedbackConstants.CONFIRM);
            checkMilestone();
            if (brickCount == MAX_BRICKS) {
                finalCelebration = true;
                celebrationStart = System.currentTimeMillis();
            }
            invalidate();
        }

        private void checkMilestone() {
            String m = null;
            switch (brickCount) {
                case 1: m = "Первый кирпич положен"; break;
                case 10: m = "Начало строительства · 10"; break;
                case 30: m = "Первый месяц · 30"; break;
                case 100: m = "100 кирпичей"; break;
                case 250: m = "Четверть дома · 250"; break;
                case 500: m = "Половина дома · 500"; break;
                case 750: m = "Почти готово · 750"; break;
                case 1000: m = "Дом построен!"; break;
            }
            if (m != null) {
                milestoneMessage = m;
                milestoneUntil = System.currentTimeMillis() + 3200L;
            }
        }

        private void buildBrickMap() {
            bricks.clear();
            // Foundation: 100 bricks (2 x 50).
            for (int row = 0; row < 2; row++) {
                for (int col = 0; col < 50; col++) {
                    bricks.add(new Brick(col / 50f, 0.88f - row * 0.035f, 1f / 50f, 0.035f, 0));
                }
            }
            // Main walls: 600 bricks (12 x 50), bottom-up.
            for (int row = 0; row < 12; row++) {
                float shift = (row % 2 == 0) ? 0f : 0.01f;
                for (int col = 0; col < 50; col++) {
                    float x = col / 50f - shift;
                    bricks.add(new Brick(x, 0.84f - row * 0.035f, 1f / 50f + 0.0015f, 0.035f, 1));
                }
            }
            // Roof: exactly 300 roof tiles, broad base to narrow ridge.
            int[] widths = {46, 44, 42, 40, 38, 36, 34, 20};
            for (int row = 0; row < widths.length; row++) {
                int cols = widths[row];
                float cellW = 1f / 50f;
                float totalW = cols * cellW;
                float startX = (1f - totalW) / 2f;
                for (int col = 0; col < cols; col++) {
                    bricks.add(new Brick(startX + col * cellW, 0.405f - row * 0.034f, cellW + 0.0015f, 0.034f, 2));
                }
            }
            if (bricks.size() != MAX_BRICKS) {
                throw new IllegalStateException("Brick map must contain exactly 1000 bricks, got " + bricks.size());
            }
        }

        private void prepareConfetti() {
            Random random = new Random(1000L);
            int[] colors = {
                Color.rgb(239, 139, 97), Color.rgb(246, 194, 119),
                Color.rgb(111, 203, 176), Color.rgb(126, 159, 255), Color.WHITE
            };
            for (int i = 0; i < 70; i++) {
                Confetti c = new Confetti();
                c.x = random.nextFloat();
                c.y = -random.nextFloat();
                c.speed = 0.10f + random.nextFloat() * 0.22f;
                c.size = 3f + random.nextFloat() * 6f;
                c.phase = random.nextFloat() * 6.28f;
                c.color = colors[random.nextInt(colors.length)];
                confetti.add(c);
            }
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0) return;
            drawBackground(canvas, w, h);
            if (tab == 0) drawHome(canvas, w, h);
            else drawHistory(canvas, w, h);
            drawBottomNav(canvas, w, h);
            drawMilestone(canvas, w, h);
            if (finalCelebration && System.currentTimeMillis() - celebrationStart < 6500L) {
                drawConfetti(canvas, w, h);
                invalidate();
            }
        }

        private void drawBackground(Canvas c, int w, int h) {
            paint.setShader(new LinearGradient(0, 0, 0, h,
                    Color.rgb(15, 18, 28), Color.rgb(25, 30, 43), Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, paint);
            paint.setShader(null);
            paint.setColor(Color.argb(110, 255, 255, 255));
            Random r = new Random(42);
            for (int i = 0; i < 30; i++) {
                float x = r.nextFloat() * w;
                float y = dp(18) + r.nextFloat() * h * 0.38f;
                c.drawCircle(x, y, dp(r.nextBoolean() ? 0.8f : 0.5f), paint);
            }
            paint.setColor(Color.rgb(247, 224, 174));
            c.drawCircle(w - dp(52), dp(58), dp(18), paint);
            paint.setColor(Color.rgb(20, 24, 36));
            c.drawCircle(w - dp(44), dp(52), dp(18), paint);
        }

        private void drawHome(Canvas c, int w, int h) {
            float margin = dp(18);
            text(c, "1000 кирпичей", margin, dp(38), dp(23), Color.WHITE, true, Paint.Align.LEFT);
            text(c, "один день · один кирпич", margin, dp(61), dp(13), Color.rgb(158, 166, 186), false, Paint.Align.LEFT);

            RectF stats = new RectF(margin, dp(82), w - margin, dp(157));
            card(c, stats, Color.argb(218, 31, 36, 50), dp(18));
            text(c, brickCount + " / 1000", stats.left + dp(16), stats.top + dp(28), dp(22), Color.WHITE, true, Paint.Align.LEFT);
            String pct = String.format(Locale.US, "%.1f%%", brickCount / 10.0);
            text(c, pct + " дома построено", stats.left + dp(16), stats.top + dp(51), dp(12), Color.rgb(167, 176, 195), false, Paint.Align.LEFT);
            text(c, "🔥 " + currentStreak + " дн.", stats.right - dp(16), stats.top + dp(28), dp(14), Color.rgb(245, 176, 105), true, Paint.Align.RIGHT);
            drawProgress(c, stats.left + dp(16), stats.bottom - dp(15), stats.width() - dp(32), dp(6), brickCount / 1000f);

            float houseTop = dp(174);
            float houseBottom = Math.min(h - dp(210), houseTop + w * 0.91f);
            RectF houseFrame = new RectF(margin, houseTop, w - margin, houseBottom);
            drawHouseScene(c, houseFrame);

            float infoY = houseFrame.bottom + dp(16);
            String remaining = brickCount >= MAX_BRICKS ? "Дом завершён" : "Осталось " + (MAX_BRICKS - brickCount) + " кирпичей";
            text(c, remaining, margin, infoY, dp(14), Color.rgb(205, 211, 225), true, Paint.Align.LEFT);
            text(c, bestStreak > 0 ? "Рекорд серии: " + bestStreak + " дн." : "Начни строительство сегодня",
                    w - margin, infoY, dp(12), Color.rgb(140, 149, 170), false, Paint.Align.RIGHT);

            float navTop = h - dp(72);
            float buttonBottom = navTop - dp(12);
            float buttonTop = buttonBottom - dp(58);
            addButton.set(margin, buttonTop, w - margin, buttonBottom);
            boolean enabled = canAddToday();
            int buttonColor = enabled ? Color.rgb(223, 108, 73) : Color.rgb(48, 54, 69);
            card(c, addButton, buttonColor, dp(18));
            if (brickCount >= MAX_BRICKS) {
                text(c, "Дом построен ✓", w / 2f, buttonTop + dp(25), dp(17), Color.WHITE, true, Paint.Align.CENTER);
                text(c, "1000 из 1000", w / 2f, buttonTop + dp(43), dp(11), Color.rgb(228, 229, 234), false, Paint.Align.CENTER);
            } else if (enabled) {
                text(c, "Положить кирпич", w / 2f, buttonTop + dp(34), dp(17), Color.WHITE, true, Paint.Align.CENTER);
            } else if (clockLooksRolledBack()) {
                text(c, "Проверь дату телефона", w / 2f, buttonTop + dp(26), dp(16), Color.rgb(222, 225, 233), true, Paint.Align.CENTER);
                text(c, "Дата раньше последнего кирпича", w / 2f, buttonTop + dp(43), dp(10.5f), Color.rgb(155, 164, 184), false, Paint.Align.CENTER);
            } else {
                text(c, "Следующий кирпич завтра", w / 2f, buttonTop + dp(24), dp(15), Color.rgb(222, 225, 233), true, Paint.Align.CENTER);
                text(c, "через " + countdownToMidnight(), w / 2f, buttonTop + dp(43), dp(11), Color.rgb(155, 164, 184), false, Paint.Align.CENTER);
            }
        }

        private void drawHouseScene(Canvas c, RectF frame) {
            card(c, frame, Color.argb(190, 21, 27, 39), dp(24));
            float pad = dp(18);
            RectF area = new RectF(frame.left + pad, frame.top + pad, frame.right - pad, frame.bottom - dp(20));

            // Ground glow and lot.
            paint.setShader(new LinearGradient(0, area.top, 0, area.bottom,
                    Color.rgb(39, 48, 67), Color.rgb(31, 47, 43), Shader.TileMode.CLAMP));
            c.drawRoundRect(new RectF(area.left, area.top, area.right, area.bottom), dp(18), dp(18), paint);
            paint.setShader(null);
            paint.setColor(Color.rgb(44, 70, 55));
            c.drawRoundRect(new RectF(area.left, area.top + area.height() * 0.79f, area.right, area.bottom), dp(12), dp(12), paint);

            float houseLeft = area.left + area.width() * 0.08f;
            float houseRight = area.right - area.width() * 0.08f;
            float houseWidth = houseRight - houseLeft;
            float houseTop = area.top + area.height() * 0.05f;
            float houseHeight = area.height() * 0.83f;

            // Ghost silhouette of the future house.
            paint.setColor(Color.argb(45, 230, 235, 245));
            c.drawRect(houseLeft, houseTop + houseHeight * 0.43f, houseRight, houseTop + houseHeight * 0.90f, paint);
            Path ghostRoof = new Path();
            ghostRoof.moveTo(houseLeft - houseWidth * 0.03f, houseTop + houseHeight * 0.43f);
            ghostRoof.lineTo((houseLeft + houseRight) / 2f, houseTop + houseHeight * 0.10f);
            ghostRoof.lineTo(houseRight + houseWidth * 0.03f, houseTop + houseHeight * 0.43f);
            ghostRoof.close();
            c.drawPath(ghostRoof, paint);

            long now = System.currentTimeMillis();
            float animT = newBrickAnimStart == 0 ? 1f : Math.min(1f, (now - newBrickAnimStart) / 750f);
            if (animT < 1f) invalidate();
            for (int i = 0; i < brickCount; i++) {
                Brick b = bricks.get(i);
                float x = houseLeft + b.x * houseWidth;
                float y = houseTop + b.y * houseHeight;
                float bw = b.w * houseWidth;
                float bh = b.h * houseHeight;
                float offset = 0f;
                int alpha = 255;
                if (i == brickCount - 1 && animT < 1f) {
                    float eased = 1f - (1f - animT) * (1f - animT);
                    offset = -dp(30) * (1f - eased);
                    alpha = (int)(90 + 165 * eased);
                }
                int base;
                if (b.kind == 0) base = Color.rgb(139, 111, 92);
                else if (b.kind == 1) base = (i % 3 == 0) ? Color.rgb(215, 109, 74) : Color.rgb(230, 126, 87);
                else base = (i % 2 == 0) ? Color.rgb(107, 77, 70) : Color.rgb(124, 86, 76);
                paint.setColor(withAlpha(base, alpha));
                RectF br = new RectF(x + dp(0.5f), y + offset + dp(0.5f), x + bw - dp(0.5f), y + bh + offset - dp(0.5f));
                c.drawRoundRect(br, dp(1.6f), dp(1.6f), paint);
            }

            // Architectural details reveal as walls reach them.
            float wallTop = houseTop + 0.435f * houseHeight;
            float wallBottom = houseTop + 0.89f * houseHeight;
            if (brickCount >= 230) {
                RectF door = new RectF(houseLeft + houseWidth * 0.42f, wallBottom - houseHeight * 0.23f,
                        houseLeft + houseWidth * 0.58f, wallBottom);
                paint.setColor(Color.rgb(48, 43, 45)); c.drawRoundRect(door, dp(4), dp(4), paint);
                paint.setColor(Color.rgb(93, 70, 59)); c.drawRoundRect(new RectF(door.left + dp(4), door.top + dp(5), door.right - dp(4), door.bottom), dp(3), dp(3), paint);
                paint.setColor(Color.rgb(236, 192, 111)); c.drawCircle(door.right - dp(8), door.centerY(), dp(1.8f), paint);
            }
            if (brickCount >= 360) {
                drawWindow(c, houseLeft + houseWidth * 0.14f, wallTop + houseHeight * 0.13f, houseWidth * 0.17f, houseHeight * 0.16f);
                drawWindow(c, houseLeft + houseWidth * 0.69f, wallTop + houseHeight * 0.13f, houseWidth * 0.17f, houseHeight * 0.16f);
            }
            if (brickCount >= 700) {
                paint.setColor(Color.argb(90, 255, 219, 158));
                c.drawCircle((houseLeft + houseRight)/2f, houseTop + houseHeight * 0.33f, dp(6), paint);
            }

            // Path to the front door.
            paint.setColor(Color.rgb(112, 101, 88));
            Path p = new Path();
            p.moveTo(area.centerX() - dp(18), area.bottom);
            p.lineTo(area.centerX() - dp(10), wallBottom);
            p.lineTo(area.centerX() + dp(10), wallBottom);
            p.lineTo(area.centerX() + dp(22), area.bottom);
            p.close();
            c.drawPath(p, paint);
        }

        private int withAlpha(int color, int alpha) {
            return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
        }

        private void drawWindow(Canvas c, float x, float y, float w, float h) {
            paint.setColor(Color.rgb(49, 58, 72));
            c.drawRoundRect(new RectF(x - dp(3), y - dp(3), x + w + dp(3), y + h + dp(3)), dp(4), dp(4), paint);
            paint.setShader(new LinearGradient(x, y, x, y + h, Color.rgb(246, 200, 121), Color.rgb(145, 172, 177), Shader.TileMode.CLAMP));
            c.drawRect(x, y, x + w, y + h, paint);
            paint.setShader(null);
            paint.setColor(Color.rgb(66, 70, 78));
            c.drawRect(x + w/2f - dp(1), y, x + w/2f + dp(1), y + h, paint);
            c.drawRect(x, y + h/2f - dp(1), x + w, y + h/2f + dp(1), paint);
        }

        private void drawHistory(Canvas c, int w, int h) {
            float margin = dp(18);
            text(c, "История", margin, dp(40), dp(24), Color.WHITE, true, Paint.Align.LEFT);
            text(c, "Твоя стройка по дням", margin, dp(63), dp(13), Color.rgb(158, 166, 186), false, Paint.Align.LEFT);

            float gap = dp(10);
            float cardW = (w - margin*2 - gap) / 2f;
            RectF a = new RectF(margin, dp(84), margin + cardW, dp(151));
            RectF b = new RectF(a.right + gap, dp(84), w - margin, dp(151));
            RectF d = new RectF(margin, dp(161), margin + cardW, dp(228));
            RectF e = new RectF(d.right + gap, dp(161), w - margin, dp(228));
            statCard(c, a, "Кирпичей", String.valueOf(brickCount));
            statCard(c, b, "Серия", currentStreak + " дн.");
            statCard(c, d, "Рекорд", bestStreak + " дн.");
            statCard(c, e, "Пропущено", String.valueOf(missedDays));

            RectF cal = new RectF(margin, dp(242), w - margin, Math.min(h - dp(250), dp(490)));
            card(c, cal, Color.argb(218, 31, 36, 50), dp(20));
            drawCalendar(c, cal);

            float y = cal.bottom + dp(22);
            text(c, "Последние кирпичи", margin, y, dp(15), Color.WHITE, true, Paint.Align.LEFT);
            y += dp(23);
            if (history.isEmpty()) {
                text(c, "Здесь появится история после первого кирпича.", margin, y + dp(8), dp(12), Color.rgb(148, 158, 178), false, Paint.Align.LEFT);
            } else {
                int shown = Math.min(4, history.size());
                for (int i = 0; i < shown; i++) {
                    String raw = history.get(history.size() - 1 - i);
                    String pretty = raw;
                    try { pretty = displayDate.format(dayFormat.parse(raw)); } catch (Exception ignored) {}
                    paint.setColor(Color.rgb(229, 126, 88));
                    c.drawRoundRect(new RectF(margin, y - dp(12), margin + dp(25), y + dp(8)), dp(6), dp(6), paint);
                    text(c, "▦", margin + dp(12.5f), y + dp(3), dp(12), Color.WHITE, true, Paint.Align.CENTER);
                    text(c, pretty, margin + dp(37), y + dp(3), dp(12.5f), Color.rgb(218, 222, 232), false, Paint.Align.LEFT);
                    text(c, "№" + (brickCount - i), w - margin, y + dp(3), dp(11), Color.rgb(135, 145, 166), true, Paint.Align.RIGHT);
                    y += dp(31);
                }
            }
        }

        private void statCard(Canvas c, RectF r, String label, String value) {
            card(c, r, Color.argb(218, 31, 36, 50), dp(16));
            text(c, label, r.left + dp(13), r.top + dp(22), dp(11), Color.rgb(148, 158, 178), false, Paint.Align.LEFT);
            text(c, value, r.left + dp(13), r.top + dp(48), dp(19), Color.WHITE, true, Paint.Align.LEFT);
        }

        private void drawCalendar(Canvas c, RectF r) {
            Calendar now = Calendar.getInstance();
            int year = now.get(Calendar.YEAR);
            int month = now.get(Calendar.MONTH);
            String[] months = {"Январь","Февраль","Март","Апрель","Май","Июнь","Июль","Август","Сентябрь","Октябрь","Ноябрь","Декабрь"};
            text(c, months[month] + " " + year, r.left + dp(16), r.top + dp(27), dp(15), Color.WHITE, true, Paint.Align.LEFT);

            String[] days = {"ПН","ВТ","СР","ЧТ","ПТ","СБ","ВС"};
            float left = r.left + dp(12);
            float right = r.right - dp(12);
            float colW = (right - left) / 7f;
            float yDays = r.top + dp(52);
            for (int i=0; i<7; i++) {
                text(c, days[i], left + colW*(i+0.5f), yDays, dp(9.5f), Color.rgb(128, 139, 162), true, Paint.Align.CENTER);
            }

            Calendar first = Calendar.getInstance();
            first.set(year, month, 1, 12, 0, 0);
            int dow = first.get(Calendar.DAY_OF_WEEK); // Sun=1
            int start = (dow + 5) % 7; // Mon=0
            int max = first.getActualMaximum(Calendar.DAY_OF_MONTH);
            float rowH = dp(31);
            float baseY = r.top + dp(73);
            int today = now.get(Calendar.DAY_OF_MONTH);
            for (int day=1; day<=max; day++) {
                int idx = start + day - 1;
                int row = idx / 7;
                int col = idx % 7;
                float cx = left + colW*(col+0.5f);
                float cy = baseY + row*rowH;
                Calendar d = Calendar.getInstance();
                d.set(year, month, day, 12, 0, 0);
                String key = dayFormat.format(d.getTime());
                boolean built = historySet.contains(key);
                if (built) {
                    paint.setColor(Color.rgb(220, 111, 76));
                    c.drawCircle(cx, cy - dp(3), dp(11.5f), paint);
                } else if (day == today) {
                    stroke.setStyle(Paint.Style.STROKE); stroke.setStrokeWidth(dp(1)); stroke.setColor(Color.rgb(101, 116, 145));
                    c.drawCircle(cx, cy - dp(3), dp(11.5f), stroke);
                    stroke.setStyle(Paint.Style.FILL);
                }
                text(c, String.valueOf(day), cx, cy + dp(1), dp(10.5f), built ? Color.WHITE : Color.rgb(185, 192, 207), built, Paint.Align.CENTER);
            }
        }

        private void drawBottomNav(Canvas c, int w, int h) {
            float navH = dp(68);
            float top = h - navH;
            paint.setColor(Color.rgb(18, 22, 32));
            c.drawRect(0, top, w, h, paint);
            float mid = w / 2f;
            homeTab.set(0, top, mid, h);
            historyTab.set(mid, top, w, h);
            int active = Color.rgb(238, 135, 96);
            int inactive = Color.rgb(125, 136, 158);
            text(c, "⌂", mid/2f, top + dp(27), dp(22), tab == 0 ? active : inactive, true, Paint.Align.CENTER);
            text(c, "Дом", mid/2f, top + dp(49), dp(11), tab == 0 ? active : inactive, tab == 0, Paint.Align.CENTER);
            text(c, "◫", mid + mid/2f, top + dp(26), dp(20), tab == 1 ? active : inactive, true, Paint.Align.CENTER);
            text(c, "История", mid + mid/2f, top + dp(49), dp(11), tab == 1 ? active : inactive, tab == 1, Paint.Align.CENTER);
        }

        private void drawMilestone(Canvas c, int w, int h) {
            if (System.currentTimeMillis() > milestoneUntil || milestoneMessage.isEmpty()) return;
            float boxW = Math.min(w - dp(44), dp(330));
            RectF r = new RectF((w-boxW)/2f, dp(178), (w+boxW)/2f, dp(248));
            card(c, r, Color.argb(246, 44, 49, 63), dp(20));
            text(c, brickCount == 1000 ? "🏆" : "🧱", r.left + dp(28), r.top + dp(42), dp(25), Color.WHITE, true, Paint.Align.CENTER);
            text(c, milestoneMessage, r.left + dp(54), r.top + dp(31), dp(15), Color.WHITE, true, Paint.Align.LEFT);
            text(c, "Продолжай строить день за днём", r.left + dp(54), r.top + dp(51), dp(11), Color.rgb(159, 168, 187), false, Paint.Align.LEFT);
            invalidate();
        }

        private void drawConfetti(Canvas c, int w, int h) {
            float t = (System.currentTimeMillis() - celebrationStart) / 1000f;
            for (Confetti cf : confetti) {
                float yy = ((cf.y + t * cf.speed) % 1.25f) * h;
                float xx = cf.x * w + (float)Math.sin(t * 3 + cf.phase) * dp(8);
                paint.setColor(cf.color);
                c.save();
                c.rotate((t * 120 + cf.phase * 30) % 360, xx, yy);
                c.drawRect(xx - dp(cf.size)/2f, yy - dp(cf.size)/3f, xx + dp(cf.size)/2f, yy + dp(cf.size)/3f, paint);
                c.restore();
            }
        }

        private String countdownToMidnight() {
            Calendar next = Calendar.getInstance();
            next.add(Calendar.DAY_OF_MONTH, 1);
            next.set(Calendar.HOUR_OF_DAY, 0);
            next.set(Calendar.MINUTE, 0);
            next.set(Calendar.SECOND, 0);
            next.set(Calendar.MILLISECOND, 0);
            long diff = Math.max(0, next.getTimeInMillis() - System.currentTimeMillis());
            long hours = TimeUnit.MILLISECONDS.toHours(diff);
            long mins = TimeUnit.MILLISECONDS.toMinutes(diff) % 60;
            long secs = TimeUnit.MILLISECONDS.toSeconds(diff) % 60;
            return String.format(Locale.US, "%02d:%02d:%02d", hours, mins, secs);
        }

        private void drawProgress(Canvas c, float x, float y, float w, float h, float progress) {
            paint.setColor(Color.rgb(51, 58, 73));
            c.drawRoundRect(new RectF(x,y,x+w,y+h), h/2f,h/2f,paint);
            paint.setColor(Color.rgb(228, 121, 82));
            c.drawRoundRect(new RectF(x,y,x+w*Math.max(0f,Math.min(1f,progress)),y+h), h/2f,h/2f,paint);
        }

        private void card(Canvas c, RectF r, int color, float radius) {
            paint.setShadowLayer(dp(12), 0, dp(4), Color.argb(65,0,0,0));
            paint.setColor(color);
            c.drawRoundRect(r, radius, radius, paint);
            paint.clearShadowLayer();
        }

        private void text(Canvas c, String s, float x, float baseline, float size, int color, boolean bold, Paint.Align align) {
            paint.setShader(null);
            paint.setColor(color);
            paint.setTextSize(size);
            paint.setTextAlign(align);
            paint.setTypeface(bold ? android.graphics.Typeface.create("sans", android.graphics.Typeface.BOLD) : android.graphics.Typeface.create("sans", android.graphics.Typeface.NORMAL));
            c.drawText(s, x, baseline, paint);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.getAction() != MotionEvent.ACTION_UP) return true;
            float x = event.getX(), y = event.getY();
            if (homeTab.contains(x,y)) {
                tab = 0; performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); invalidate(); return true;
            }
            if (historyTab.contains(x,y)) {
                tab = 1; performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP); invalidate(); return true;
            }
            if (tab == 0 && addButton.contains(x,y) && canAddToday()) {
                addBrick();
                return true;
            }
            return true;
        }
    }
}
