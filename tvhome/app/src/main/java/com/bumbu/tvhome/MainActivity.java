package com.bumbu.tvhome;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * TV Home - a minimal launcher for Android TV.
 *
 * No network permission, no services, no AndroidX.
 * Rectangular cards with a centered (never stretched) icon, light theme by
 * default and an optional dark theme.
 */
public class MainActivity extends Activity {

    /** Order of pinned apps, before the rest (then alphabetical). */
    private static final String[] PINNED = {
            "com.google.android.youtube.tv",
            "com.disney.disneyplus",
            "com.crunchyroll.crunchyroid",
            "com.netflix.ninja",
            "com.android.vending"
    };

    /** System apps that are not shown as icons (Settings has its own slot below). */
    private static final String[] HIDDEN = {
            "com.android.tv.settings",
            "com.google.android.tv.settings",
            "com.android.settings"
    };

    // ---- geometry in dp (the mockup is 1280x720 at density 1.33) ----
    private static final float CARD_W = 150f;
    private static final float CARD_H = 87f;
    private static final float CARD_R = 15f;
    private static final float ICON = 60f;
    private static final float GAP = 18f;

    private static final float SYS_W = 150f;
    private static final float SYS_H = 66f;
    private static final float SYS_ICON = 40f;

    private static final float SW_W = 72f;
    private static final float SW_H = 30f;
    private static final float SW_KNOB = 24f;
    private static final float SW_PAD = 3f;
    private static final float SW_ICON = 13f;

    private static final int FOCUS_MS = 160;
    private static final int CACHE_BYTES = 2 * 1024 * 1024;

    private String hintDefault, hintMove, hintMenu;

    private float density;
    private int cardW, cardH, cardR, iconPx, gap, sysW, sysH, sysIcon;
    private int swW, swH, swKnob, swPad, swTravel;

    // ---- current palette ----
    private boolean dark;
    private int colCard, colCardStroke, colLabel, colLabelFocus, colRing;
    private int colClock, colDate, colHint;
    private int colSwTrack, colSwStroke, colKnob, colIconOn, colIconOff;
    private int colMenuFocus, colScrim;

    private SharedPreferences prefs;

    private final LruCache<String, Bitmap> icons = new LruCache<String, Bitmap>(CACHE_BYTES) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    private FrameLayout rootView;
    private HorizontalScrollView appScroll;
    private LinearLayout appRow, sysRow;
    private TextView clockView, dateView, hintView;
    private final List<Tile> tiles = new ArrayList<Tile>();

    private View switchView, knob;
    private ImageView sunIcon, moonIcon;
    private GradientDrawable trackBg, knobBg;

    private String lastFocusedPkg;
    private final List<String> displayedPkgs = new ArrayList<String>();
    private final List<Item> displayedItems = new ArrayList<Item>();
    private final List<String> savedOrder = new ArrayList<String>();
    private boolean receiverRegistered;
    private boolean appResumed;
    private boolean appsDirty;

    // context menu (long press on OK)
    private FrameLayout menuOverlay;
    private final List<LinearLayout> menuRows = new ArrayList<LinearLayout>();
    private final List<Runnable> menuActions = new ArrayList<Runnable>();
    private int menuIndex;

    // move mode
    private String movePkg;
    private List<Item> moveBackup;

    private final Handler handler = new Handler(Looper.getMainLooper());

    /** Fires when an app is installed, uninstalled or enabled/disabled. */
    private final BroadcastReceiver packageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            appsDirty = true;
            if (!appResumed) return;   // it will be refreshed on resume
            // several broadcasts in a row (REMOVED + ADDED on update): refresh once
            handler.removeCallbacks(refreshRunnable);
            handler.postDelayed(refreshRunnable, 400);
        }
    };

    private final Runnable refreshRunnable = new Runnable() {
        @Override
        public void run() {
            appsDirty = false;
            refreshApps();
        }
    };

    private final Runnable clockTick = new Runnable() {
        @Override
        public void run() {
            updateClock();
            handler.postDelayed(this, 30000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        hintDefault = getString(R.string.hint_default);
        hintMove = getString(R.string.hint_move);
        hintMenu = getString(R.string.hint_menu);

        density = getResources().getDisplayMetrics().density;

        cardW = dp(CARD_W);
        cardH = dp(CARD_H);
        cardR = dp(CARD_R);
        iconPx = dp(ICON);
        gap = dp(GAP);
        sysW = dp(SYS_W);
        sysH = dp(SYS_H);
        sysIcon = dp(SYS_ICON);

        swW = dp(SW_W);
        swH = dp(SW_H);
        swKnob = dp(SW_KNOB);
        swPad = dp(SW_PAD);
        swTravel = swW - swKnob - 2 * swPad;

        prefs = getSharedPreferences("tvhome", MODE_PRIVATE);
        dark = prefs.getBoolean("dark", false);
        loadOrder();
        loadPalette();

        setContentView(buildUi());
        loadApps();
        loadInputs();
        applyTheme();

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        filter.addAction(Intent.ACTION_PACKAGE_CHANGED);
        filter.addDataScheme("package");
        registerReceiver(packageReceiver, filter);
        receiverRegistered = true;

        handler.post(clockTick);
    }

    @Override
    protected void onResume() {
        super.onResume();
        appResumed = true;

        // only re-query the list if something changed while we were away
        if (appsDirty) {
            appsDirty = false;
            refreshApps();
        }
        restoreFocus();
    }

    @Override
    protected void onPause() {
        appResumed = false;
        if (menuOverlay != null) closeMenu();
        if (movePkg != null) endMove(true);

        handler.removeCallbacks(refreshRunnable);
        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && getCurrentFocus() == null) {
            restoreFocus();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        lastFocusedPkg = null;
        restoreFocus();
        if (appScroll != null) appScroll.smoothScrollTo(0, 0);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(clockTick);
        handler.removeCallbacks(refreshRunnable);
        if (receiverRegistered) {
            try {
                unregisterReceiver(packageReceiver);
            } catch (Exception ignored) {
            }
            receiverRegistered = false;
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        lastFocusedPkg = null;
        restoreFocus();
    }

    // ----------------------------------------------------------------- theme

    private void loadPalette() {
        if (dark) {
            colCard = 0xFF1A2130;
            colCardStroke = 0xFF2A3346;
            colLabel = 0xFFC9CFDB;
            colLabelFocus = 0xFFFFFFFF;
            colRing = 0xFFFFFFFF;
            colClock = 0xFFE9EDF5;
            colDate = 0xFF8B94A8;
            colHint = 0xFF67708A;
            colSwTrack = 0xFF232B3A;
            colSwStroke = 0xFF2E3850;
            colKnob = 0xFFE9EDF5;
            colIconOn = 0xFF1E2534;
            colIconOff = 0xFF6B7488;
            colMenuFocus = 0xFF2A3346;
            colScrim = 0x99000000;
        } else {
            colCard = 0xFFFFFFFF;
            colCardStroke = 0xFFD5D5D5;
            colLabel = 0xFF34383D;
            colLabelFocus = 0xFF131313;
            // soft outline: it reads through the shadow and the scale, not a black border
            colRing = 0xFF8E9297;
            colClock = 0xFF16181A;
            colDate = 0xFF676B70;
            colHint = 0xFF8A8E93;
            colSwTrack = 0xFFDCDCDC;
            colSwStroke = 0xFFC8C8C8;
            colKnob = 0xFFFFFFFF;
            colIconOn = 0xFF2A2F3A;
            colIconOff = 0xFF7C8596;
            colMenuFocus = 0xFFEFEFEF;
            colScrim = 0x4D000000;
        }
    }

    private void toggleTheme() {
        dark = !dark;
        prefs.edit().putBoolean("dark", dark).apply();
        loadPalette();
        applyTheme();
    }

    private void applyTheme() {
        if (rootView != null) {
            rootView.setBackgroundResource(dark ? R.drawable.bg_dark : R.drawable.bg_light);
        }
        if (clockView != null) clockView.setTextColor(colClock);
        if (dateView != null) dateView.setTextColor(colDate);
        if (hintView != null) hintView.setTextColor(colHint);

        for (Tile t : tiles) applyTileTheme(t);

        if (trackBg != null) {
            trackBg.setColor(colSwTrack);
            trackBg.setStroke(dp(1), colSwStroke);
        }
        if (knobBg != null) knobBg.setColor(colKnob);
        if (knob != null) {
            knob.animate().translationX(dark ? swTravel : 0)
                    .setDuration(180)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        }
        if (sunIcon != null) sunIcon.setColorFilter(dark ? colIconOff : colIconOn);
        if (moonIcon != null) moonIcon.setColorFilter(dark ? colIconOn : colIconOff);
    }

    private void applyTileTheme(Tile t) {
        boolean focused = t.box.isFocused();
        t.bg.setColor(colCard);
        t.bg.setStroke(focused ? dp(2) : dp(1), focused ? colRing : colCardStroke);
        t.label.setTextColor(focused ? colLabelFocus : colLabel);
    }

    // -------------------------------------------------------------------- UI

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setClipChildren(false);
        root.setBackgroundResource(dark ? R.drawable.bg_dark : R.drawable.bg_light);
        rootView = root;

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(42), dp(28), dp(42), dp(26));
        // let the focused card (1.06 scale) overflow the padding area
        col.setClipToPadding(false);
        col.setClipChildren(false);
        root.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ---- top bar: switch + clock
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        topBar.setClipChildren(false);

        buildSwitch();
        LinearLayout.LayoutParams swLp = new LinearLayout.LayoutParams(swW, swH);
        swLp.rightMargin = dp(16);
        topBar.addView(switchView, swLp);

        LinearLayout clockCol = new LinearLayout(this);
        clockCol.setOrientation(LinearLayout.VERTICAL);
        clockCol.setGravity(Gravity.END);

        clockView = new TextView(this);
        clockView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        clockView.setTextColor(colClock);
        clockView.setGravity(Gravity.END);
        clockView.setIncludeFontPadding(false);

        dateView = new TextView(this);
        dateView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        dateView.setTextColor(colDate);
        dateView.setGravity(Gravity.END);
        dateView.setIncludeFontPadding(false);

        clockCol.addView(clockView);
        clockCol.addView(dateView);
        topBar.addView(clockCol);
        col.addView(topBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        col.addView(spacer(dp(52)));

        // ---- row 1: apps
        appScroll = new HorizontalScrollView(this);
        appScroll.setHorizontalScrollBarEnabled(false);
        appScroll.setClipChildren(false);
        appScroll.setClipToPadding(false);
        appScroll.setFillViewport(false);
        appScroll.setFocusable(false);

        appRow = new LinearLayout(this);
        appRow.setOrientation(LinearLayout.HORIZONTAL);
        appRow.setClipChildren(false);
        appRow.setClipToPadding(false);
        appRow.setFocusable(false);
        appScroll.addView(appRow, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        col.addView(appScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        col.addView(spacer(dp(46)));

        // ---- row 2: system / inputs
        sysRow = new LinearLayout(this);
        sysRow.setOrientation(LinearLayout.HORIZONTAL);
        sysRow.setClipChildren(false);
        sysRow.setClipToPadding(false);
        sysRow.setFocusable(false);
        col.addView(sysRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        View flex = new View(this);
        col.addView(flex, new LinearLayout.LayoutParams(1, 0, 1f));

        hintView = new TextView(this);
        hintView.setText(hintDefault);
        hintView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        hintView.setTextColor(colHint);
        hintView.setGravity(Gravity.CENTER);
        col.addView(hintView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        return root;
    }

    private void buildSwitch() {
        FrameLayout sw = new FrameLayout(this);
        sw.setFocusable(true);
        sw.setFocusableInTouchMode(false);
        sw.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        sw.setClipChildren(false);
        switchView = sw;

        View track = new View(this);
        trackBg = new GradientDrawable();
        trackBg.setCornerRadius(swH / 2f);
        trackBg.setColor(colSwTrack);
        trackBg.setStroke(dp(1), colSwStroke);
        track.setBackground(trackBg);
        sw.addView(track, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        knob = new View(this);
        knobBg = new GradientDrawable();
        knobBg.setShape(GradientDrawable.OVAL);
        knobBg.setColor(colKnob);
        knob.setBackground(knobBg);
        knob.setElevation(dp(2));
        FrameLayout.LayoutParams klp = new FrameLayout.LayoutParams(swKnob, swKnob);
        klp.leftMargin = swPad;
        klp.topMargin = (swH - swKnob) / 2;
        sw.addView(knob, klp);

        int is = dp(SW_ICON);
        sunIcon = new ImageView(this);
        sunIcon.setImageResource(R.drawable.ic_sun);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(is, is);
        slp.leftMargin = dp(9);
        slp.topMargin = (swH - is) / 2;
        sw.addView(sunIcon, slp);

        moonIcon = new ImageView(this);
        moonIcon.setImageResource(R.drawable.ic_moon);
        FrameLayout.LayoutParams mlp = new FrameLayout.LayoutParams(is, is);
        mlp.leftMargin = swW - dp(9) - is;
        mlp.topMargin = (swH - is) / 2;
        sw.addView(moonIcon, mlp);

        // the icons sit above the knob
        sunIcon.setTranslationZ(dp(4));
        moonIcon.setTranslationZ(dp(4));

        sw.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleTheme();
            }
        });

        sw.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                trackBg.setStroke(hasFocus ? dp(2) : dp(1), hasFocus ? colRing : colSwStroke);
                float s = hasFocus ? 1.06f : 1f;
                v.animate().scaleX(s).scaleY(s)
                        .setDuration(FOCUS_MS)
                        .setInterpolator(new DecelerateInterpolator())
                        .start();
            }
        });
    }

    private View spacer(int h) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, h));
        return v;
    }

    private int dp(float v) {
        return (int) (v * density + 0.5f);
    }

    // ------------------------------------------------------------------ cards

    private void addAppTile(String pkg, String label, Drawable icon) {
        View tile = makeTile(label, icon, cardW, cardH, iconPx, pkg, new Launcher(pkg));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                cardW, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = gap;
        appRow.addView(tile, lp);
    }

    private void addSysTile(String label, Drawable icon, final Runnable action) {
        View tile = makeTile(label, icon, sysW, sysH, sysIcon, null, action);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                sysW, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = gap;
        sysRow.addView(tile, lp);
    }

    private View makeTile(final String label, Drawable icon, int w, int h, int iconSize,
                          final String pkg, final Runnable action) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setFocusable(true);
        box.setFocusableInTouchMode(false);
        box.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        box.setClipChildren(false);

        final ImageView card = new ImageView(this);
        final GradientDrawable bg = new GradientDrawable();
        bg.setColor(colCard);
        bg.setStroke(dp(1), colCardStroke);
        bg.setCornerRadius(cardR);
        card.setBackground(bg);
        card.setScaleType(ImageView.ScaleType.FIT_CENTER);
        card.setClipToOutline(true);
        card.setElevation(dp(4));
        int padH = Math.max(0, (w - iconSize) / 2);
        int padV = Math.max(0, (h - iconSize) / 2);
        card.setPadding(padH, padV, padH, padV);
        if (icon != null) {
            card.setImageDrawable(icon);
        }
        box.addView(card, new LinearLayout.LayoutParams(w, h));

        final TextView text = new TextView(this);
        text.setText(label);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        text.setTextColor(colLabel);
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END);
        text.setGravity(Gravity.CENTER);
        text.setIncludeFontPadding(false);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                w, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(7);
        box.addView(text, tlp);

        box.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                action.run();
            }
        });

        if (pkg != null) {
            box.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    openTileMenu(pkg, label, v);
                    return true;
                }
            });
        }

        box.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                float s = hasFocus ? (pkg != null && pkg.equals(movePkg) ? 1.10f : 1.06f) : 1f;
                card.animate()
                        .scaleX(s)
                        .scaleY(s)
                        .translationZ(hasFocus ? dp(4) : 0)
                        .setDuration(FOCUS_MS)
                        .setInterpolator(new DecelerateInterpolator())
                        .start();
                bg.setStroke(hasFocus ? dp(2) : dp(1), hasFocus ? colRing : colCardStroke);
                text.setTextColor(hasFocus ? colLabelFocus : colLabel);
                text.setTypeface(null, hasFocus ? Typeface.BOLD : Typeface.NORMAL);

                if (hasFocus) {
                    if (pkg != null) lastFocusedPkg = pkg;
                    scrollIntoView(box);
                }
            }
        });

        tiles.add(new Tile(box, bg, text, pkg != null));
        return box;
    }

    private void scrollIntoView(View child) {
        if (appScroll == null || child.getParent() != appRow) return;
        int viewW = appScroll.getWidth();
        if (viewW <= 0) return;

        // minimal scroll: only enough to make the card visible
        int cur = appScroll.getScrollX();
        int margin = gap;
        int left = child.getLeft() - margin;
        int right = child.getRight() + margin;

        int target = cur;
        if (left < cur) {
            target = left;
        } else if (right > cur + viewW) {
            target = right - viewW;
        }

        int max = Math.max(0, appRow.getWidth() - viewW);
        if (target < 0) target = 0;
        if (target > max) target = max;
        if (target != cur) appScroll.smoothScrollTo(target, 0);
    }

    private void restoreFocus() {
        restoreFocusTo(lastFocusedPkg, 0);
    }

    private void restoreFocusTo(String pkg, int fallback) {
        if (appRow != null && appRow.getChildCount() > 0) {
            int i = indexOfPkg(pkg);
            if (i < 0) i = Math.max(0, Math.min(fallback, appRow.getChildCount() - 1));
            appRow.getChildAt(i).requestFocus();
        } else if (sysRow != null && sysRow.getChildCount() > 0) {
            sysRow.getChildAt(0).requestFocus();
        }
    }

    private int indexOfPkg(String pkg) {
        return pkg == null ? -1 : displayedPkgs.indexOf(pkg);
    }

    // --------------------------------------- context menu (OK held down)

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        boolean down = event.getAction() == KeyEvent.ACTION_DOWN;

        // menu open: handled here (focus stays on the card)
        if (menuOverlay != null) {
            if (!down) return super.dispatchKeyEvent(event);
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_UP:
                    moveMenu(-1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    moveMenu(1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_LEFT:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    if (event.getRepeatCount() == 0) {
                        Runnable action = menuIndex < menuActions.size()
                                ? menuActions.get(menuIndex) : null;
                        closeMenu();
                        if (action != null) action.run();
                    }
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    closeMenu();
                    return true;
                default:
                    return super.dispatchKeyEvent(event);
            }
        }

        // move mode: left/right shift, OK drops, Back cancels
        if (movePkg != null) {
            if (!down) return super.dispatchKeyEvent(event);
            if (event.getRepeatCount() > 0) return true;
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    moveTile(-1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    moveTile(1);
                    return true;
                case KeyEvent.KEYCODE_DPAD_CENTER:
                case KeyEvent.KEYCODE_ENTER:
                    endMove(true);
                    return true;
                case KeyEvent.KEYCODE_BACK:
                    endMove(false);
                    return true;
                case KeyEvent.KEYCODE_DPAD_UP:
                case KeyEvent.KEYCODE_DPAD_DOWN:
                    return true;
                default:
                    return super.dispatchKeyEvent(event);
            }
        }

        return super.dispatchKeyEvent(event);
    }

    private void openTileMenu(final String pkg, final String label, View anchor) {
        if (rootView == null) return;
        closeMenu();

        menuIndex = 0;
        menuRows.clear();
        menuActions.clear();

        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(colScrim);
        overlay.setClickable(true);
        menuOverlay = overlay;

        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable pbg = new GradientDrawable();
        pbg.setColor(colCard);
        pbg.setCornerRadius(dp(16));
        pbg.setStroke(dp(1), colCardStroke);
        panel.setBackground(pbg);
        panel.setElevation(dp(20));

        int pad = dp(8);
        panel.setPadding(pad, pad, pad, pad);

        addMenuRow(panel, R.drawable.ic_move, getString(R.string.menu_move), new Runnable() {
            @Override
            public void run() {
                startMove(pkg);
            }
        });
        addMenuRow(panel, R.drawable.ic_info, getString(R.string.menu_info), new Runnable() {
            @Override
            public void run() {
                openAppInfo(pkg);
            }
        });

        int w = dp(252);
        int h = dp(54) * menuRows.size() + pad * 2;

        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(w, h);

        Rect r = new Rect(0, 0, anchor.getWidth(), anchor.getHeight());
        rootView.offsetDescendantRectToMyCoords(anchor, r);
        int rootW = rootView.getWidth();
        int rootH = rootView.getHeight();
        int margin = dp(16);

        int x = r.centerX() - w / 2;
        int y = r.bottom + dp(10);
        if (y + h > rootH - margin) y = r.top - h - dp(10);
        if (y < margin) y = Math.max(margin, (rootH - h) / 2);
        x = Math.max(margin, Math.min(x, Math.max(margin, rootW - w - margin)));

        plp.leftMargin = x;
        plp.topMargin = y;
        overlay.addView(panel, plp);

        rootView.addView(overlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        applyMenuSelection();
        if (hintView != null) hintView.setText(hintMenu);
    }

    private void addMenuRow(LinearLayout panel, int iconRes, String title, final Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), 0, dp(14), 0);

        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0x00000000);
        bg.setCornerRadius(dp(11));
        row.setBackground(bg);

        ImageView icon = new ImageView(this);
        icon.setImageResource(iconRes);
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(20), dp(20));
        ilp.rightMargin = dp(14);
        row.addView(icon, ilp);

        TextView text = new TextView(this);
        text.setText(title);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        row.addView(text, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        panel.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(54)));

        menuRows.add(row);
        menuActions.add(action);
    }

    private void applyMenuSelection() {
        for (int i = 0; i < menuRows.size(); i++) {
            LinearLayout row = menuRows.get(i);
            boolean selected = i == menuIndex;

            GradientDrawable bg = (GradientDrawable) row.getBackground();
            bg.setColor(selected ? colMenuFocus : 0x00000000);

            ImageView icon = (ImageView) row.getChildAt(0);
            TextView text = (TextView) row.getChildAt(1);
            icon.setColorFilter(selected ? colLabelFocus : colLabel);
            text.setTextColor(selected ? colLabelFocus : colLabel);
            text.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
        }
    }

    private void moveMenu(int delta) {
        if (menuRows.isEmpty()) return;
        menuIndex = Math.max(0, Math.min(menuRows.size() - 1, menuIndex + delta));
        applyMenuSelection();
    }

    private void closeMenu() {
        if (menuOverlay != null && rootView != null) {
            rootView.removeView(menuOverlay);
        }
        menuOverlay = null;
        menuRows.clear();
        menuActions.clear();
        if (hintView != null) {
            hintView.setText(movePkg != null ? hintMove : hintDefault);
        }
    }

    private void startMove(String pkg) {
        closeMenu();
        movePkg = pkg;
        moveBackup = new ArrayList<Item>(displayedItems);
        if (hintView != null) hintView.setText(hintMove);

        displayApps(displayedItems);
        restoreFocusTo(pkg, 0);
    }

    private void moveTile(int delta) {
        int i = indexOfPkg(movePkg);
        if (i < 0) return;
        int j = i + delta;
        if (j < 0 || j >= displayedItems.size()) return;

        Collections.swap(displayedItems, i, j);
        String keep = displayedItems.get(j).pkg;

        displayApps(displayedItems);
        saveOrder(displayedItems);
        restoreFocusTo(keep, j);
    }

    private void endMove(boolean keep) {
        if (movePkg == null) return;

        String pkg = movePkg;
        List<Item> order = keep ? new ArrayList<Item>(displayedItems) : moveBackup;

        movePkg = null;
        moveBackup = null;

        if (order != null) {
            displayApps(order);
            saveOrder(order);
            restoreFocusTo(pkg, 0);
        }
        if (hintView != null) hintView.setText(hintDefault);
    }

    private void openAppInfo(String pkg) {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + pkg));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, R.string.error_open_info, Toast.LENGTH_SHORT).show();
        }
    }

    // ------------------------------------------------------------------- apps

    private void loadApps() {
        displayApps(queryApps());
    }

    /** Re-reads the app list and rebuilds the row only if something changed. */
    private void refreshApps() {
        // do not rebuild while the user is moving an icon or the menu is open
        if (appRow == null || movePkg != null || menuOverlay != null) return;

        List<Item> items = queryApps();
        if (samePkgs(items)) return;

        View focused = getCurrentFocus();
        boolean focusInApps = focused != null && focused.getParent() == appRow;
        int oldIndex = focusInApps ? appRow.indexOfChild(focused) : indexOfPkg(lastFocusedPkg);

        // forget the icons of apps that are gone
        for (String old : displayedPkgs) {
            boolean still = false;
            for (Item it : items) {
                if (it.pkg.equals(old)) {
                    still = true;
                    break;
                }
            }
            if (!still) icons.remove(old);
        }

        displayApps(items);

        int idx = indexOfPkg(lastFocusedPkg);
        if (idx < 0) idx = Math.max(0, Math.min(oldIndex, appRow.getChildCount() - 1));
        if (idx >= 0 && idx < displayedPkgs.size()) lastFocusedPkg = displayedPkgs.get(idx);

        if ((focusInApps || getCurrentFocus() == null) && appRow.getChildCount() > 0) {
            View tile = appRow.getChildAt(Math.max(0, Math.min(idx, appRow.getChildCount() - 1)));
            tile.requestFocus();
            scrollIntoView(tile);
        }
    }

    private boolean samePkgs(List<Item> items) {
        if (items.size() != displayedPkgs.size()) return false;
        for (int i = 0; i < items.size(); i++) {
            if (!items.get(i).pkg.equals(displayedPkgs.get(i))) return false;
        }
        return true;
    }

    private void displayApps(List<Item> items) {
        // defensive copy: the caller may pass the list we are about to clear
        List<Item> list = new ArrayList<Item>(items);

        // app cards are rebuilt; system cards (Settings/HDMI) stay
        for (Iterator<Tile> it = tiles.iterator(); it.hasNext(); ) {
            if (it.next().app) it.remove();
        }
        appRow.removeAllViews();

        displayedPkgs.clear();
        displayedItems.clear();
        for (Item it : list) {
            displayedPkgs.add(it.pkg);
            displayedItems.add(it);
            addAppTile(it.pkg, it.label, loadIcon(it.pkg));
        }
    }

    private List<Item> queryApps() {
        PackageManager pm = getPackageManager();

        Intent leanback = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> found = new ArrayList<ResolveInfo>(
                pm.queryIntentActivities(leanback, PackageManager.MATCH_ALL));

        Set<String> seen = new HashSet<String>();
        for (ResolveInfo ri : found) {
            if (ri.activityInfo != null) seen.add(ri.activityInfo.packageName);
        }

        Intent normal = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (ResolveInfo ri : pm.queryIntentActivities(normal, PackageManager.MATCH_ALL)) {
            if (ri.activityInfo == null) continue;
            // user apps only: avoids system screens
            int flags = ri.activityInfo.applicationInfo.flags;
            if ((flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0) continue;
            if (seen.add(ri.activityInfo.packageName)) {
                found.add(ri);
            }
        }

        List<Item> items = new ArrayList<Item>();
        Set<String> dupes = new HashSet<String>();
        for (ResolveInfo ri : found) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (getPackageName().equals(pkg)) continue;
            if (isHidden(pkg)) continue;
            if (!dupes.add(pkg)) continue;
            CharSequence label = ri.loadLabel(pm);
            items.add(new Item(pkg, label == null ? pkg : label.toString()));
        }

        Collections.sort(items, new Comparator<Item>() {
            @Override
            public int compare(Item a, Item b) {
                // the order saved by the user wins; the rest follows PINNED + alphabetical
                int oa = orderIndex(a.pkg);
                int ob = orderIndex(b.pkg);
                if (oa != ob) return oa < ob ? -1 : 1;
                int pa = pinnedIndex(a.pkg);
                int pb = pinnedIndex(b.pkg);
                if (pa != pb) return pa < pb ? -1 : 1;
                return a.label.compareToIgnoreCase(b.label);
            }
        });

        return items;
    }

    private int orderIndex(String pkg) {
        int i = savedOrder.indexOf(pkg);
        return i < 0 ? Integer.MAX_VALUE : i;
    }

    private void loadOrder() {
        savedOrder.clear();
        String raw = prefs.getString("order", "");
        if (raw == null || raw.length() == 0) return;
        for (String p : raw.split(",")) {
            if (p.length() > 0) savedOrder.add(p);
        }
    }

    private void saveOrder(List<Item> items) {
        StringBuilder sb = new StringBuilder();
        for (Item it : items) {
            if (sb.length() > 0) sb.append(',');
            sb.append(it.pkg);
        }
        prefs.edit().putString("order", sb.toString()).apply();
        loadOrder();
    }

    private boolean isHidden(String pkg) {
        for (String h : HIDDEN) {
            if (h.equals(pkg)) return true;
        }
        return false;
    }

    private int pinnedIndex(String pkg) {
        for (int i = 0; i < PINNED.length; i++) {
            if (PINNED[i].equals(pkg)) return i;
        }
        return PINNED.length + 1000;
    }

    private Drawable loadIcon(String pkg) {
        Bitmap cached = icons.get(pkg);
        if (cached != null) {
            return new BitmapDrawable(getResources(), cached);
        }
        try {
            Drawable d = getPackageManager().getApplicationIcon(pkg);
            int size = dp(ICON + 4);
            Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            // crop to a rounded square (Android hands out adaptive icons unmasked)
            Path clip = new Path();
            float r = size * 0.225f;
            clip.addRoundRect(new RectF(0, 0, size, size), r, r, Path.Direction.CW);
            c.clipPath(clip);
            d.setBounds(0, 0, size, size);
            d.draw(c);
            icons.put(pkg, bmp);
            return new BitmapDrawable(getResources(), bmp);
        } catch (Exception e) {
            return null;
        }
    }

    private void launch(String pkg) {
        PackageManager pm = getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) {
            Toast.makeText(this, R.string.error_open_app, Toast.LENGTH_SHORT).show();
            return;
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, R.string.error_open_app, Toast.LENGTH_SHORT).show();
        }
    }

    // ----------------------------------------------------------------- inputs

    private void loadInputs() {
        Drawable settingsIcon = null;
        try {
            settingsIcon = getPackageManager().getApplicationIcon("com.android.tv.settings");
        } catch (Exception ignored) {
        }
        addSysTile(getString(R.string.settings), settingsIcon, new Runnable() {
            @Override
            public void run() {
                try {
                    startActivity(new Intent(Settings.ACTION_SETTINGS));
                } catch (Exception ignored) {
                }
            }
        });

        try {
            TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
            if (tim == null) return;

            List<TvInputInfo> inputs = new ArrayList<>();
            for (TvInputInfo info : tim.getTvInputList()) {
                if (info != null && info.isPassthroughInput()) inputs.add(info);
            }
            Collections.sort(inputs, new Comparator<TvInputInfo>() {
                @Override
                public int compare(TvInputInfo a, TvInputInfo b) {
                    return inputLabel(a).compareToIgnoreCase(inputLabel(b));
                }
            });

            for (final TvInputInfo info : inputs) {
                String label = inputLabel(info);

                // Passthrough input icons are null, empty or blank depending on
                // the firmware: always use the generic screen icon, which also
                // looks the same in the light and dark themes.
                Drawable icon = getDrawable(R.drawable.ic_input);

                addSysTile(label, icon, new Runnable() {
                    @Override
                    public void run() {
                        try {
                            Uri uri = TvContract.buildChannelUriForPassthroughInput(info.getId());
                            startActivity(new Intent(Intent.ACTION_VIEW, uri));
                        } catch (Exception e) {
                            Toast.makeText(MainActivity.this, R.string.error_input_unavailable,
                                    Toast.LENGTH_SHORT).show();
                        }
                    }
                });
            }
        } catch (Exception ignored) {
        }
    }

    private String inputLabel(TvInputInfo info) {
        try {
            return String.valueOf(info.loadLabel(this));
        } catch (Exception e) {
            return getString(R.string.input_fallback);
        }
    }

    // ------------------------------------------------------------------ clock

    private void updateClock() {
        Date now = new Date();
        clockView.setText(DateFormat.getTimeFormat(this).format(now));
        try {
            String pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEdMMMM");
            dateView.setText(new SimpleDateFormat(pattern, Locale.getDefault()).format(now));
        } catch (Exception e) {
            dateView.setText("");
        }
    }

    // ------------------------------------------------------------------- misc

    private static class Item {
        final String pkg;
        final String label;

        Item(String pkg, String label) {
            this.pkg = pkg;
            this.label = label;
        }
    }

    private static class Tile {
        final View box;
        final GradientDrawable bg;
        final TextView label;
        final boolean app;

        Tile(View box, GradientDrawable bg, TextView label, boolean app) {
            this.box = box;
            this.bg = bg;
            this.label = label;
            this.app = app;
        }
    }

    private class Launcher implements Runnable {
        private final String pkg;

        Launcher(String pkg) {
            this.pkg = pkg;
        }

        @Override
        public void run() {
            launch(pkg);
        }
    }
}
