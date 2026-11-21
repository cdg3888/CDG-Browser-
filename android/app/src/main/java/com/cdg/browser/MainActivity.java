package com.cdg.browser;

import android.app.Activity;
import android.app.AlertDialog;
import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedDispatcher;

import org.json.JSONArray;
import org.json.JSONObject;
import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.WebResponse;
import androidx.core.content.FileProvider;
import com.cdg.browser.downloads.VideoDownloadManager;
import com.cdg.browser.ytdlp.ChainExtractor;
import com.cdg.browser.ytdlp.DirectMediaExtractor;
import com.cdg.browser.ytdlp.MediaExtractor;
import com.cdg.browser.ytdlp.YtDlpFormatOption;
import com.cdg.browser.ytdlp.YtDlpEngine;
import com.cdg.browser.ytdlp.YtDlpMediaInfo;
import com.cdg.browser.ytdlp.YtDlpUniversalExtractor;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.net.HttpURLConnection;
import java.net.URL;
import android.content.SharedPreferences;

public final class MainActivity extends Activity {
    private static final String HOME_URL = "https://duckduckgo.com/";
    private static final String PREFERENCES = "cdg_browser";
    private static final String HISTORY_KEY = "history";
    private static final String FAVORITES_KEY = "favorites";
    private static final String DOWNLOADS_KEY = "downloads";
    private static final String DOWNLOAD_FOLDER_KEY = "download_folder_uri";
    private static final int REQUEST_DOWNLOAD_FOLDER = 4701;
    private static final String THEME_KEY = "dark_theme";
    private static final int MAX_HISTORY_ITEMS = 50;
    private static GeckoRuntime runtime;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private int colorBackground;
    private int colorSurface;
    private int colorText;
    private int colorMuted;
    private int colorBorder;
    private int colorAccent;
    private int colorRipple;
    private final List<String> history = new ArrayList<>();
    private final List<String> favorites = new ArrayList<>();
    private final List<BrowserTab> tabs = new ArrayList<>();
    private final List<DownloadEntry> downloads = new ArrayList<>();
    private final LinkedHashMap<String, DetectedMedia> detectedMedia = new LinkedHashMap<>();
    private final LinkedHashMap<String, TextView> detectedMediaStatusViews = new LinkedHashMap<>();
    private final LinkedHashMap<String, Button> detectedMediaDownloadButtons = new LinkedHashMap<>();

    private GeckoView browserView;
    private GeckoSession session;
    private static AdBlockEngine adBlockEngine;
    private VideoDownloadManager videoDownloadManager;
    private YtDlpEngine ytDlpEngine;
    private ChainExtractor mediaExtractor;
    private BrowserTab activeTab;
    private EditText addressField;
    private TextView statusView;
    private TextView tabCountView;
    private LinearLayout addressRow;
    private LinearLayout downloadsList;
    private AlertDialog downloadsDialog;
    private AlertDialog detectedMediaDialog;
    private LinearLayout detectedMediaList;
    private EditText mediaSearchField;
    private Button privateButton;
    private Button addressToggleButton;
    private Button favoriteButton;
    private Button siteExceptionButton;
    private Button updateFiltersButton;
    private Button mediaButton;
    private Button mediaFab;
    private Switch protectionEnabledSwitch;
    private TextView protectionSummaryView;
    private boolean privateMode;
    private boolean darkTheme;
    private boolean addressBarVisible = true;
    private boolean canGoBack;
    private boolean initialNavigationStarted;
    private String mediaTypeFilter = "Todos";
    private boolean showAllDetectedMedia;
    private String currentUrl = HOME_URL;
    private String statusMessage = "Protección estricta GeckoView activa";
    private final Runnable adBlockStartupFallback = () -> {
        if (!initialNavigationStarted) {
            initialNavigationStarted = true;
            updateStatus("Filtros locales no disponibles; protección GeckoView sigue activa");
            navigate(currentUrl);
        }
    };

    private static final class BrowserTab {
        GeckoSession session;
        String url = HOME_URL;
        String title = "Nueva pestaña";
        boolean privateMode;
        String pendingPopupUrl;
        boolean pendingPopupHasGesture;
        String lastRedirectUrl;
        long redirectWindowStartedAt;
        int redirectCount;
    }

    private static final class DownloadEntry {
        String name;
        String mimeType;
        String status;
        String location;
        String engine;
        String url;
        String formatSelector;
        String processId;
        boolean audioOnly;
        boolean includeSubtitles;
        boolean copying;
        long bytes;
        long lastPersistAt;
    }

    private static final class DetectedMedia {
        final String key;
        final String url;
        final String pageUrl;
        final String pageTitle;
        final String type;
        String downloadStatus = "Disponible";
        boolean downloadActive;
        String title;
        String thumbnailUrl;
        String durationText;

        DetectedMedia(String key, String url, String pageUrl, String pageTitle, String type) {
            this.key = key;
            this.url = url;
            this.pageUrl = pageUrl;
            this.pageTitle = pageTitle;
            this.type = type;
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBackNavigation);
        }
        loadSavedData();
        applyThemeColors();
        buildInterface();
        createRuntime();
        videoDownloadManager = new VideoDownloadManager(this);
        ytDlpEngine = new YtDlpEngine(this);
        mediaExtractor = new ChainExtractor(
                new DirectMediaExtractor(),
                new ArrayList<>(),
                new YtDlpUniversalExtractor(ytDlpEngine));
        activateTab(createSession());
        if (adBlockEngine == null) {
            adBlockEngine = new AdBlockEngine(this, runtime, null);
        }
        adBlockEngine.setListener(new AdBlockEngine.Listener() {
            @Override
            public void onReady() {
                runOnUiThread(() -> {
                    mainHandler.removeCallbacks(adBlockStartupFallback);
                    startInitialNavigation();
                    refreshProtectionControls();
                });
            }

            @Override
            public void onStateChanged() {
                runOnUiThread(MainActivity.this::refreshProtectionControls);
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_LONG).show();
                    if (!initialNavigationStarted && !adBlockEngine.isInstalled()) {
                        mainHandler.removeCallbacks(adBlockStartupFallback);
                        startInitialNavigation();
                    }
                    refreshProtectionControls();
                });
            }

            @Override
            public void onMediaDetected(String url, String pageUrl, String mediaType) {
                runOnUiThread(() -> addDetectedMedia(url, pageUrl, mediaType));
            }
        });
        adBlockEngine.install();
        mainHandler.postDelayed(adBlockStartupFallback, 8000);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_DOWNLOAD_FOLDER
                || resultCode != Activity.RESULT_OK
                || data == null
                || data.getData() == null) {
            return;
        }
        Uri folderUri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(
                    folderUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                            | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (SecurityException ignored) {
        }
        getSharedPreferences(PREFERENCES, MODE_PRIVATE)
                .edit()
                .putString(DOWNLOAD_FOLDER_KEY, folderUri.toString())
                .apply();
        Toast.makeText(this, "Carpeta de descargas actualizada", Toast.LENGTH_SHORT).show();
        refreshDownloadsDialog();
    }

    private void createRuntime() {
        if (runtime == null) {
            runtime = GeckoRuntime.create(
                    getApplicationContext(), AdBlockEngine.createRuntimeSettings());
        }
        runtime.notifyTelemetryPrefChanged(false);
    }

    private void buildInterface() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(colorBackground);

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.setPadding(dp(18), dp(6), dp(14), dp(6));
        titleRow.setBackgroundColor(colorSurface);
        TextView title = new TextView(this);
        title.setText("CDG Browser");
        title.setTextColor(colorText);
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, dp(44), 1));
        privateButton = button("Privada · no", () -> togglePrivateMode());
        titleRow.addView(privateButton);
        addressToggleButton = button("⌄", this::toggleAddressBar);
        titleRow.addView(addressToggleButton);
        root.addView(titleRow);

        addressRow = new LinearLayout(this);
        addressRow.setGravity(Gravity.CENTER_VERTICAL);
        addressRow.setPadding(dp(12), dp(8), dp(12), dp(8));
        addressRow.setBackgroundColor(colorSurface);
        addressField = new EditText(this);
        addressField.setSingleLine(true);
        addressField.setTextSize(14);
        addressField.setHint("Buscar o introducir dirección");
        addressField.setHintTextColor(colorMuted);
        addressField.setTextColor(colorText);
        addressField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        addressField.setImeOptions(EditorInfo.IME_ACTION_GO);
        addressField.setSelectAllOnFocus(true);
        addressField.setPadding(dp(14), 0, dp(14), 0);
        addressField.setBackground(roundedBackground(colorBackground, colorBorder, 16));
        addressField.setText(currentUrl);
        addressRow.addView(addressField, new LinearLayout.LayoutParams(0, dp(48), 1));
        favoriteButton = button("☆", this::toggleFavorite);
        LinearLayout.LayoutParams favoriteParams = new LinearLayout.LayoutParams(
                dp(44), dp(44));
        favoriteParams.setMargins(dp(6), 0, dp(6), 0);
        addressRow.addView(favoriteButton, favoriteParams);
        Button goButton = button("Ir", this::submitAddress);
        goButton.setTextColor(Color.WHITE);
        goButton.setBackground(rippleBackground(colorAccent, colorAccent, 14));
        LinearLayout.LayoutParams goParams = new LinearLayout.LayoutParams(
                dp(56), dp(44));
        addressRow.addView(goButton, goParams);
        root.addView(addressRow);
        addressRow.setVisibility(addressBarVisible ? View.VISIBLE : View.GONE);
        addressToggleButton.setText(addressBarVisible ? "⌄" : "⌃");
        addressField.setOnEditorActionListener((view, actionId, event) -> {
            boolean go = actionId == EditorInfo.IME_ACTION_GO
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER);
            if (go) {
                submitAddress();
            }
            return go;
        });

        HorizontalScrollView actionScroll = new HorizontalScrollView(this);
        actionScroll.setHorizontalScrollBarEnabled(false);
        actionScroll.setBackgroundColor(colorSurface);
        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(dp(10), dp(2), dp(10), dp(12));
        actions.addView(button("+ Pestaña", this::newTab), actionButtonParams());
        Button tabsButton = button("Pestañas · 1", this::showTabs);
        actions.addView(tabsButton, actionButtonParams());
        tabCountView = tabsButton;
        actions.addView(button("Favoritos", this::showFavorites), actionButtonParams());
        actions.addView(button("Descargas", this::showDownloads), actionButtonParams());
        actions.addView(button("Recargar", () -> {
            if (session != null) {
                session.reload();
            }
        }), actionButtonParams());
        actions.addView(button("Inicio", () -> navigate(HOME_URL)), actionButtonParams());
        actions.addView(button("Historial", this::showHistory), actionButtonParams());
        actions.addView(button("Protección", this::showProtectionInfo), actionButtonParams());
        actions.addView(button(themeButtonLabel(), this::toggleTheme), actionButtonParams());
        actionScroll.addView(actions);
        root.addView(actionScroll);

        FrameLayout browserContainer = new FrameLayout(this);
        browserView = new GeckoView(this);
        browserContainer.addView(browserView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        mediaFab = new Button(this);
        mediaFab.setText("Medios · 0");
        mediaFab.setAllCaps(false);
        mediaFab.setTextColor(Color.WHITE);
        mediaFab.setTextSize(13);
        mediaFab.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        mediaFab.setPadding(dp(18), 0, dp(18), 0);
        mediaFab.setMinimumHeight(dp(48));
        mediaFab.setBackground(rippleBackground(colorAccent, colorAccent, 24));
        mediaFab.setOnClickListener(view -> {
            if (detectedMedia.isEmpty()) {
                dialogBuilder()
                        .setTitle("Descargas multimedia")
                        .setMessage("Todavía no hay videos o listas HLS detectados en esta página.")
                        .setNegativeButton("Cerrar", null)
                        .setPositiveButton("Añadir dirección", (dialog, which) -> promptYtDlpDownload())
                        .show();
            } else {
                showDetectedMedia();
            }
        });
        FrameLayout.LayoutParams fabParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, dp(48),
                Gravity.BOTTOM | Gravity.END);
        fabParams.setMargins(0, 0, dp(18), dp(18));
        browserContainer.addView(mediaFab, fabParams);
        root.addView(browserContainer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        statusView = new TextView(this);
        statusView.setTextSize(11);
        statusView.setTextColor(colorMuted);
        statusView.setPadding(dp(16), dp(9), dp(12), dp(9));
        statusView.setBackgroundColor(colorSurface);
        root.addView(statusView);
        setContentView(root);
        updateStatus("Protección estricta GeckoView · filtros locales");
    }

    private Button button(String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(12);
        button.setTextColor(colorText);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinimumHeight(dp(42));
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setBackground(rippleBackground(colorSurface, colorBorder, 14));
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private void applyThemeColors() {
        if (darkTheme) {
            colorBackground = Color.rgb(17, 24, 20);
            colorSurface = Color.rgb(25, 34, 29);
            colorText = Color.rgb(232, 240, 235);
            colorMuted = Color.rgb(160, 174, 165);
            colorBorder = Color.rgb(49, 64, 55);
            colorAccent = Color.rgb(112, 199, 156);
            colorRipple = Color.rgb(48, 65, 55);
        } else {
            colorBackground = Color.rgb(246, 248, 247);
            colorSurface = Color.WHITE;
            colorText = Color.rgb(29, 39, 34);
            colorMuted = Color.rgb(106, 119, 111);
            colorBorder = Color.rgb(226, 232, 228);
            colorAccent = Color.rgb(35, 112, 82);
            colorRipple = Color.rgb(231, 238, 234);
        }
        getWindow().setStatusBarColor(colorBackground);
        getWindow().setNavigationBarColor(colorSurface);
        getWindow().getDecorView().setSystemUiVisibility(darkTheme ? 0
                : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    }

    private String themeButtonLabel() {
        return "Tema · " + (darkTheme ? "oscuro" : "claro");
    }

    private void toggleTheme() {
        String previousStatusMessage = statusMessage;
        darkTheme = !darkTheme;
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                .putBoolean(THEME_KEY, darkTheme)
                .apply();

        if (browserView != null) {
            browserView.releaseSession();
        }
        applyThemeColors();
        buildInterface();
        updateStatus(previousStatusMessage);
        if (session != null) {
            session.setActive(true);
            browserView.setSession(session);
            updateTabCount();
            updateDetectedMediaButton();
            updateFavoriteButton();
            updatePrivateButton();
        }
    }

    private LinearLayout.LayoutParams actionButtonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(40));
        params.setMargins(dp(3), 0, dp(3), 0);
        return params;
    }

    private GradientDrawable roundedBackground(int color, int strokeColor, int radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(radius));
        if (strokeColor != Color.TRANSPARENT) {
            background.setStroke(dp(1), strokeColor);
        }
        return background;
    }

    private RippleDrawable rippleBackground(int color, int strokeColor, int radius) {
        return new RippleDrawable(
                ColorStateList.valueOf(colorRipple),
                roundedBackground(color, strokeColor, radius),
                null);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private BrowserTab createSession() {
        BrowserTab tab = new BrowserTab();
        tab.privateMode = privateMode;
        GeckoSessionSettings sessionSettings = new GeckoSessionSettings.Builder()
                .usePrivateMode(privateMode)
                .useTrackingProtection(true)
                .build();
        tab.session = new GeckoSession(sessionSettings);
        tab.session.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
            @Override
            public GeckoResult<AllowOrDeny> onLoadRequest(
                    GeckoSession currentSession,
                    GeckoSession.NavigationDelegate.LoadRequest request) {
                if (!isSafeNavigationUri(request.uri)) {
                    runOnUiThread(() -> Toast.makeText(MainActivity.this,
                            "Por seguridad, solo se permiten páginas HTTP y HTTPS",
                            Toast.LENGTH_SHORT).show());
                    return GeckoResult.fromValue(AllowOrDeny.DENY);
                }
                if (request.target == GeckoSession.NavigationDelegate.TARGET_WINDOW_NEW) {
                    if (request.hasUserGesture) {
                        tab.pendingPopupUrl = request.uri;
                        tab.pendingPopupHasGesture = true;
                    } else if (request.isRedirect && tab.pendingPopupHasGesture) {
                        tab.pendingPopupUrl = request.uri;
                    } else {
                        tab.pendingPopupUrl = null;
                        tab.pendingPopupHasGesture = false;
                        return GeckoResult.fromValue(AllowOrDeny.DENY);
                    }
                }
                if (request.isRedirect) {
                    long now = System.currentTimeMillis();
                    if (now - tab.redirectWindowStartedAt > 15_000L) {
                        tab.redirectWindowStartedAt = now;
                        tab.redirectCount = 0;
                        tab.lastRedirectUrl = null;
                    }
                    tab.redirectCount++;
                    if (tab.redirectCount > 15 || request.uri.equals(tab.lastRedirectUrl)) {
                        tab.pendingPopupUrl = null;
                        tab.pendingPopupHasGesture = false;
                        runOnUiThread(() -> Toast.makeText(MainActivity.this,
                                "Se detuvo una cadena de redirecciones repetidas.",
                                Toast.LENGTH_LONG).show());
                        return GeckoResult.fromValue(AllowOrDeny.DENY);
                    }
                    tab.lastRedirectUrl = request.uri;
                } else {
                    tab.redirectCount = 0;
                    tab.redirectWindowStartedAt = 0;
                    tab.lastRedirectUrl = null;
                }
                return GeckoResult.fromValue(AllowOrDeny.ALLOW);
            }

            @Override
            public GeckoResult<GeckoSession> onNewSession(GeckoSession currentSession, String uri) {
                boolean allowed = tab.pendingPopupHasGesture
                        && uri != null && uri.equals(tab.pendingPopupUrl)
                        && isSafeNavigationUri(uri);
                tab.pendingPopupUrl = null;
                tab.pendingPopupHasGesture = false;
                if (!allowed) {
                    return null;
                }
                BrowserTab popupTab = createSession();
                popupTab.url = uri;
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "Se abrió una pestaña nueva. Comprueba el dominio antes de iniciar sesión.",
                        Toast.LENGTH_LONG).show());
                return GeckoResult.fromValue(popupTab.session);
            }

            @Override
            public void onCanGoBack(GeckoSession currentSession, boolean canGoBack) {
                if (tab == activeTab) {
                    MainActivity.this.canGoBack = canGoBack;
                }
            }

            @Override
            public void onLocationChange(
                    GeckoSession currentSession, String url,
                    List<GeckoSession.PermissionDelegate.ContentPermission> permissions,
                    Boolean hasUserGesture) {
                if (!isSafeNavigationUri(url) || "about:blank".equalsIgnoreCase(url)) {
                    return;
                }
                tab.url = url;
                if (tab == activeTab) {
                    currentUrl = url;
                    runOnUiThread(() -> {
                        if (tab == activeTab) {
                            addressField.setText(url);
                            updateTabCount();
                            updateFavoriteButton();
                            updateDetectedMediaButton();
                        }
                    });
                }
            }
        });
        tab.session.setContentDelegate(new GeckoSession.ContentDelegate() {
            @Override
            public void onTitleChange(GeckoSession currentSession, String pageTitle) {
                if (pageTitle != null && !pageTitle.trim().isEmpty()) {
                    tab.title = pageTitle.trim();
                    if (tab == activeTab) {
                        runOnUiThread(MainActivity.this::updateTabCount);
                    }
                }
            }

            @Override
            public void onExternalResponse(GeckoSession currentSession, WebResponse response) {
                handleExternalResponse(response);
            }
        });
        tab.session.setProgressDelegate(new GeckoSession.ProgressDelegate() {
            @Override
            public void onPageStop(GeckoSession currentSession, boolean success) {
                if (success && !tab.privateMode && tab.url.startsWith("http")) {
                    addToHistory(tab.url);
                }
            }

            @Override
            public void onSecurityChange(
                    GeckoSession currentSession,
                    GeckoSession.ProgressDelegate.SecurityInformation securityInformation) {
                if (tab == activeTab) {
                    runOnUiThread(() -> {
                        if (tab == activeTab) {
                            String message = securityInformation.isSecure
                                    ? "Conexión HTTPS verificada · protección de rastreadores activa"
                                    : "Conexión no segura · protección de rastreadores activa";
                            updateStatus(message);
                        }
                    });
                }
            }
        });
        tab.session.open(runtime);
        tabs.add(tab);
        updateTabCount();
        return tab;
    }

    private void activateTab(BrowserTab tab) {
        if (browserView != null) {
            browserView.releaseSession();
        }
        if (session != null) {
            session.setActive(false);
        }
        activeTab = tab;
        session = tab.session;
        canGoBack = false;
        currentUrl = tab.url;
        session.setActive(true);
        browserView.setSession(session);
        addressField.setText(currentUrl);
        updateFavoriteButton();
        updatePrivateButton();
        updateDetectedMediaButton();
        updateTabCount();
    }

    private void newTab() {
        activateTab(createSession());
        navigate(HOME_URL);
        addressField.requestFocus();
    }

    private void closeTab(BrowserTab tab) {
        if (tabs.size() == 1) {
            navigate(HOME_URL);
            return;
        }
        int index = tabs.indexOf(tab);
        if (tab == activeTab) {
            browserView.releaseSession();
            session = null;
            activeTab = null;
        }
        tabs.remove(tab);
        tab.session.close();
        if (activeTab == null) {
            activateTab(tabs.get(Math.max(0, index - 1)));
        }
        updateTabCount();
    }

    private void showTabs() {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(list);
        AlertDialog dialog = dialogBuilder()
                .setTitle("Pestañas abiertas")
                .setView(scroll)
                .setPositiveButton("Nueva pestaña", (ignored, which) -> newTab())
                .setNegativeButton("Cerrar", null)
                .create();
        for (BrowserTab tab : new ArrayList<>(tabs)) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView item = new TextView(this);
            String label = tab == activeTab ? "● " : "";
            item.setText(label + (tab.title.isEmpty() || tab.title.equals("Nueva pestaña")
                    ? tab.url : tab.title));
            item.setTextColor(colorText);
            item.setSingleLine(true);
            item.setEllipsize(android.text.TextUtils.TruncateAt.END);
            item.setPadding(dp(12), dp(12), dp(8), dp(12));
            row.addView(item, new LinearLayout.LayoutParams(0, dp(48), 1));
            row.setOnClickListener(view -> {
                dialog.dismiss();
                activateTab(tab);
            });
            row.addView(button("×", () -> {
                closeTab(tab);
                dialog.dismiss();
                showTabs();
            }));
            list.addView(row);
        }
        dialog.show();
    }

    private void updateTabCount() {
        if (tabCountView != null) {
            tabCountView.setText("Pestañas · " + tabs.size());
        }
    }

    private void addDetectedMedia(String url, String pageUrl, String mediaType) {
        if (url == null || url.length() > 8192 || pageUrl == null || pageUrl.length() > 8192
                || !isHttpUrl(url) || !isHttpUrl(pageUrl)
                || !("HLS".equals(mediaType) || "DASH".equals(mediaType)
                || "Video".equals(mediaType))) {
            return;
        }
        String key = normalizeMediaUrl(url);
        detectedMedia.remove(key);
        detectedMedia.put(key, new DetectedMedia(
                key, url, pageUrl, pageTitleForUrl(pageUrl), mediaType));
        if (detectedMedia.size() > 100) {
            String oldest = detectedMedia.keySet().iterator().next();
            detectedMedia.remove(oldest);
        }
        updateDetectedMediaButton();
        renderDetectedMediaItems();
    }

    private String pageTitleForUrl(String pageUrl) {
        String normalizedUrl = Uri.parse(pageUrl).buildUpon().fragment(null).build().toString();
        for (BrowserTab tab : tabs) {
            String tabUrl = Uri.parse(tab.url).buildUpon().fragment(null).build().toString();
            if (normalizedUrl.equals(tabUrl) && tab.title != null && !tab.title.isEmpty()) {
                return tab.title;
            }
        }
        return "";
    }

    private void updateDetectedMediaButton() {
        if (mediaFab == null) {
            return;
        }
        String currentHost = Uri.parse(currentUrl).getHost();
        int count = 0;
        if (currentHost != null) {
            for (DetectedMedia item : detectedMedia.values()) {
                String pageHost = Uri.parse(item.pageUrl).getHost();
                if (pageHost != null && currentHost.equalsIgnoreCase(pageHost)) {
                    count++;
                }
            }
        }
        mediaFab.setText("Medios · " + count);
        mediaFab.setVisibility(count > 0 ? View.VISIBLE : View.GONE);
    }

    private void showDetectedMedia() {
        if (detectedMedia.isEmpty()) {
            dialogBuilder()
                    .setTitle("Medios detectados")
                    .setMessage("Aún no se detectaron enlaces HLS, DASH o de video en este sitio.")
                    .setPositiveButton("Cerrar", null)
                    .show();
            return;
        }

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(8), dp(4), dp(8), dp(4));
        mediaSearchField = new EditText(this);
        mediaSearchField.setSingleLine(true);
        mediaSearchField.setInputType(InputType.TYPE_CLASS_TEXT);
        mediaSearchField.setHint("Buscar URL, página o tipo");
        mediaSearchField.setPadding(dp(12), dp(8), dp(12), dp(8));
        content.addView(mediaSearchField);

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        Button scopeButton = button(
                showAllDetectedMedia ? "Todos los sitios" : "Sitio actual",
                () -> { });
        scopeButton.setOnClickListener(view -> {
            showAllDetectedMedia = !showAllDetectedMedia;
            scopeButton.setText(showAllDetectedMedia ? "Todos los sitios" : "Sitio actual");
            renderDetectedMediaItems();
        });
        toolbar.addView(scopeButton, new LinearLayout.LayoutParams(0, -2, 1));
        toolbar.addView(button("Limpiar", () -> dialogBuilder()
                .setTitle("Borrar medios detectados")
                .setMessage("Se borrará la lista temporal de enlaces. No se eliminarán archivos descargados.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Borrar", (dialog, which) -> {
                    detectedMedia.clear();
                    updateDetectedMediaButton();
                    renderDetectedMediaItems();
                })
                .show()));
        content.addView(toolbar);

        Button filterButton = button("Tipo: " + mediaTypeFilter, () -> { });
        filterButton.setOnClickListener(view -> {
            String[] filters = {"Todos", "HLS", "DASH", "Video"};
            dialogBuilder()
                    .setTitle("Filtrar por tipo")
                    .setItems(filters, (dialog, which) -> {
                        mediaTypeFilter = filters[which];
                        filterButton.setText("Tipo: " + mediaTypeFilter);
                        renderDetectedMediaItems();
                    })
                    .show();
        });
        content.addView(filterButton);

        ScrollView scroll = new ScrollView(this);
        detectedMediaList = new LinearLayout(this);
        detectedMediaList.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(detectedMediaList);
        content.addView(scroll, new LinearLayout.LayoutParams(-1, dp(360)));

        mediaSearchField.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence value, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence value, int start, int before, int count) {
                renderDetectedMediaItems();
            }

            @Override
            public void afterTextChanged(Editable value) {
            }
        });

        detectedMediaDialog = dialogBuilder()
                .setTitle("Medios detectados · " + detectedMedia.size())
                .setView(content)
                .setNegativeButton("Cerrar", null)
                .create();
        detectedMediaDialog.setOnDismissListener(dialog -> {
            detectedMediaDialog = null;
            detectedMediaList = null;
            mediaSearchField = null;
            detectedMediaStatusViews.clear();
            detectedMediaDownloadButtons.clear();
        });
        detectedMediaDialog.show();
        renderDetectedMediaItems();
    }

    private void renderDetectedMediaItems() {
        if (detectedMediaList == null || detectedMediaDialog == null
                || !detectedMediaDialog.isShowing()) {
            return;
        }
        detectedMediaList.removeAllViews();
        detectedMediaStatusViews.clear();
        detectedMediaDownloadButtons.clear();
        String query = mediaSearchField == null ? "" : mediaSearchField.getText().toString()
                .trim().toLowerCase(Locale.ROOT);
        String currentHost = Uri.parse(currentUrl).getHost();
        int shown = 0;
        for (DetectedMedia item : detectedMedia.values()) {
            if (!showAllDetectedMedia) {
                String pageHost = Uri.parse(item.pageUrl).getHost();
                if (currentHost == null || pageHost == null
                        || !currentHost.equalsIgnoreCase(pageHost)) {
                    continue;
                }
            }
            if (!"Todos".equals(mediaTypeFilter) && !mediaTypeFilter.equals(item.type)) {
                continue;
            }
            String searchable = (item.url + " " + item.pageUrl + " " + item.pageTitle + " " + item.type)
                    .toLowerCase(Locale.ROOT);
            if (!query.isEmpty() && !searchable.contains(query)) {
                continue;
            }
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(12), dp(8), dp(12), dp(8));
            row.setBackground(rippleBackground(colorSurface, colorBorder, 14));
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            rowParams.setMargins(dp(4), dp(6), dp(4), dp(6));
            row.setLayoutParams(rowParams);
            LinearLayout heading = new LinearLayout(this);
            heading.setOrientation(LinearLayout.HORIZONTAL);
            heading.setGravity(Gravity.CENTER_VERTICAL);
            if (item.thumbnailUrl != null) {
                ImageView thumbnail = new ImageView(this);
                thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
                thumbnail.setBackground(rippleBackground(colorBackground, colorBorder, 12));
                heading.addView(thumbnail, new LinearLayout.LayoutParams(dp(96), dp(54)));
                loadThumbnail(thumbnail, item.thumbnailUrl);
            }
            TextView label = new TextView(this);
            String source = item.pageTitle.isEmpty()
                    ? Uri.parse(item.pageUrl).getHost() : item.pageTitle;
            String cardTitle = item.title == null || item.title.isEmpty()
                    ? source : item.title;
            String details = item.type + " detectado"
                    + (item.durationText == null || item.durationText.isEmpty()
                    ? "" : " · " + item.durationText);
            label.setText(cardTitle + "\n" + details);
            label.setTextColor(colorText);
            label.setTextSize(14);
            label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            label.setMaxLines(2);
            label.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            label.setPadding(item.thumbnailUrl == null ? 0 : dp(10), 0, 0, 0);
            heading.addView(label, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            row.addView(heading);

            TextView urlView = new TextView(this);
            urlView.setText(item.url);
            urlView.setTextColor(colorMuted);
            urlView.setTextSize(11);
            urlView.setMaxLines(1);
            urlView.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            urlView.setPadding(0, dp(6), 0, dp(2));
            row.addView(urlView);

            TextView status = new TextView(this);
            status.setText(item.downloadStatus);
            status.setTextColor(colorMuted);
            status.setTextSize(12);
            status.setPadding(0, dp(4), 0, 0);
            row.addView(status);
            detectedMediaStatusViews.put(item.key, status);

            LinearLayout actions = new LinearLayout(this);
            actions.setGravity(Gravity.END);
            actions.addView(button("Copiar enlace", () -> {
                ClipboardManager clipboard = (ClipboardManager) getSystemService(
                        Context.CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(ClipData.newPlainText("Enlace de medio", item.url));
                Toast.makeText(this, "Enlace copiado", Toast.LENGTH_SHORT).show();
            }));
            Button downloadButton = button("Descargar", () -> {
                setDetectedMediaStatus(item, "Analizando con yt-dlp…", true);
                inspectWithYtDlp(item.url, item);
            });
            downloadButton.setEnabled(!item.downloadActive);
            detectedMediaDownloadButtons.put(item.key, downloadButton);
            actions.addView(downloadButton);
            row.addView(actions);
            detectedMediaList.addView(row);
            shown++;
        }
        detectedMediaDialog.setTitle("Medios detectados · " + shown);
        if (shown == 0) {
            TextView empty = new TextView(this);
            empty.setText("No hay resultados con estos filtros.");
            empty.setTextColor(colorMuted);
            empty.setPadding(dp(12), dp(16), dp(12), dp(16));
            detectedMediaList.addView(empty);
        }
    }

    private void setDetectedMediaStatus(DetectedMedia item, String status, boolean active) {
        item.downloadStatus = status;
        item.downloadActive = active;
        runOnUiThread(() -> {
            TextView statusView = detectedMediaStatusViews.get(item.key);
            if (statusView != null) {
                statusView.setText(status);
            }
            Button downloadButton = detectedMediaDownloadButtons.get(item.key);
            if (downloadButton != null) {
                downloadButton.setEnabled(!active);
            }
        });
    }

    private String normalizeMediaUrl(String value) {
        Uri uri = Uri.parse(value);
        Uri.Builder builder = uri.buildUpon().fragment(null);
        String encodedQuery = uri.getEncodedQuery();
        if (encodedQuery != null) {
            StringBuilder query = new StringBuilder();
            for (String parameter : encodedQuery.split("&", -1)) {
                String rawName = parameter.split("=", 2)[0];
                String name = Uri.decode(rawName).toLowerCase(Locale.ROOT);
                if (name.startsWith("utm_") || name.equals("fbclid") || name.equals("gclid")
                        || name.equals("mc_cid") || name.equals("mc_eid")) {
                    continue;
                }
                if (query.length() > 0) {
                    query.append('&');
                }
                query.append(parameter);
            }
            builder.encodedQuery(query.length() == 0 ? null : query.toString());
        }
        return builder.build().toString();
    }

    private boolean isHttpUrl(String value) {
        return isSafeNavigationUri(value) && !"about:blank".equalsIgnoreCase(value);
    }

    private boolean isSafeNavigationUri(String value) {
        if (value == null || value.length() > 8192) {
            return false;
        }
        if ("about:blank".equalsIgnoreCase(value)) {
            return true;
        }
        try {
            java.net.URI uri = new java.net.URI(value);
            String scheme = uri.getScheme();
            return uri.getHost() != null && uri.getUserInfo() == null
                    && uri.getPort() <= 65535
                    && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme));
        } catch (java.net.URISyntaxException exception) {
            return false;
        }
    }

    private void toggleAddressBar() {
        addressBarVisible = !addressBarVisible;
        addressRow.setVisibility(addressBarVisible ? View.VISIBLE : View.GONE);
        addressToggleButton.setText(addressBarVisible ? "⌄" : "⌃");
        if (addressBarVisible) {
            addressField.setText(currentUrl);
        }
    }

    private void submitAddress() {
        String text = addressField.getText().toString().trim();
        if (text.isEmpty()) {
            return;
        }
        String target = resolveAddress(text);
        addressField.clearFocus();
        ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE))
                .hideSoftInputFromWindow(addressField.getWindowToken(), 0);
        navigate(target);
    }

    private String resolveAddress(String input) {
        if (input.matches("(?i)^https?://.*")) {
            Uri uri = Uri.parse(input);
            return uri.getHost() != null ? input : search(input);
        }
        if (input.matches("(?i)^[a-z][a-z0-9+.-]*:.*")) {
            return search(input);
        }
        if (!input.contains(" ") && input.contains(".")) {
            Uri candidate = Uri.parse("https://" + input);
            if (candidate.getHost() != null) {
                return "https://" + input;
            }
        }
        return search(input);
    }

    private String search(String query) {
        return "https://duckduckgo.com/?q=" + Uri.encode(query);
    }

    private void navigate(String url) {
        if (!isHttpUrl(url)) {
            Toast.makeText(this, "Introduce una dirección HTTP o HTTPS válida.",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        currentUrl = url;
        if (activeTab != null) {
            activeTab.url = url;
        }
        addressField.setText(url);
        updateFavoriteButton();
        if (session != null && (initialNavigationStarted
                || adBlockEngine == null || adBlockEngine.isInstalled())) {
            session.loadUri(url);
        }
    }

    private void startInitialNavigation() {
        if (initialNavigationStarted) {
            return;
        }
        initialNavigationStarted = true;
        mainHandler.removeCallbacks(adBlockStartupFallback);
        navigate(currentUrl);
    }

    private void togglePrivateMode() {
        boolean enable = !privateMode;
        if (enable) {
            dialogBuilder()
                    .setTitle("Iniciar sesión privada")
                    .setMessage("Las pestañas actuales se cerrarán. El historial y los datos de esta sesión no se guardarán.")
                    .setNegativeButton("Cancelar", null)
                    .setPositiveButton("Continuar", (dialog, which) -> replaceSession(true))
                    .show();
        } else {
            replaceSession(false);
        }
    }

    private void replaceSession(boolean usePrivateMode) {
        String url = currentUrl;
        browserView.releaseSession();
        for (BrowserTab tab : tabs) {
            tab.session.close();
        }
        tabs.clear();
        session = null;
        activeTab = null;
        privateMode = usePrivateMode;
        activateTab(createSession());
        navigate(url);
    }

    private void updatePrivateButton() {
        if (privateButton != null) {
            privateButton.setText(privateMode ? "Privada · sí" : "Privada · no");
            privateButton.setTextColor(privateMode ? colorAccent : colorMuted);
        }
    }

    private void toggleFavorite() {
        if (currentUrl == null || !currentUrl.startsWith("http")) {
            return;
        }
        if (favorites.remove(currentUrl)) {
            Toast.makeText(this, "Favorito eliminado", Toast.LENGTH_SHORT).show();
        } else {
            favorites.add(0, currentUrl);
            Toast.makeText(this, "Página guardada en favoritos", Toast.LENGTH_SHORT).show();
        }
        saveFavorites();
        updateFavoriteButton();
    }

    private void updateFavoriteButton() {
        if (favoriteButton != null) {
            favoriteButton.setText(favorites.contains(currentUrl) ? "★" : "☆");
        }
    }

    private void showFavorites() {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(list);
        AlertDialog dialog = dialogBuilder()
                .setTitle("Favoritos")
                .setView(scroll)
                .setNegativeButton("Cerrar", null)
                .create();
        if (favorites.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("Todavía no hay favoritos. Usa ☆ en la barra para guardar la página actual.");
            empty.setTextColor(colorText);
            empty.setPadding(dp(16), dp(16), dp(16), dp(16));
            list.addView(empty);
        }
        for (String favorite : new ArrayList<>(favorites)) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView item = new TextView(this);
            item.setText(favorite);
            item.setTextColor(colorText);
            item.setSingleLine(true);
            item.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            item.setPadding(dp(12), dp(12), dp(8), dp(12));
            row.addView(item, new LinearLayout.LayoutParams(0, dp(48), 1));
            row.setOnClickListener(view -> {
                dialog.dismiss();
                navigate(favorite);
            });
            row.addView(button("Quitar", () -> {
                favorites.remove(favorite);
                saveFavorites();
                updateFavoriteButton();
                dialog.dismiss();
                showFavorites();
            }));
            list.addView(row);
        }
        dialog.show();
    }

    private void handleExternalResponse(WebResponse response) {
        String mimeType = VideoDownloadManager.mimeTypeForResponse(response.headers);
        String filename = VideoDownloadManager.filenameForResponse(response.uri, response.headers);
        if (!VideoDownloadManager.supportsDirectVideo(mimeType, filename)) {
            closeQuietly(response.body);
            runOnUiThread(() -> Toast.makeText(this,
                    "Solo se admiten archivos de video descargables directamente",
                    Toast.LENGTH_SHORT).show());
            return;
        }
        if (response.body == null) {
            runOnUiThread(() -> Toast.makeText(this,
                    "El sitio no entregó un archivo descargable",
                    Toast.LENGTH_SHORT).show());
            return;
        }

        filename = VideoDownloadManager.withVideoExtension(filename, mimeType);
        String finalMimeType = VideoDownloadManager.normalizedVideoMimeType(mimeType, filename);
        String finalFilename = filename;
        runOnUiThread(() -> dialogBuilder()
                .setTitle("Descargar video")
                .setMessage(finalFilename + "\n\nSolo se descargará el archivo que el sitio ofrece directamente. No se extraen streams protegidos ni se evita DRM.")
                .setNegativeButton("Cancelar", (dialog, which) -> closeQuietly(response.body))
                .setPositiveButton("Descargar", (dialog, which) ->
                        startVideoDownload(response.body, finalFilename, finalMimeType,
                                response.uri))
                .setOnCancelListener(dialog -> closeQuietly(response.body))
                .show());
    }

    private void startVideoDownload(
            InputStream input, String filename, String mimeType, String sourceUrl) {
        DownloadEntry entry = new DownloadEntry();
        entry.name = filename;
        entry.mimeType = mimeType;
        entry.url = sourceUrl;
        entry.engine = "direct";
        entry.status = "En cola";
        addDownloadEntry(entry);
        refreshDownloadsDialog();
        String taskId = videoDownloadManager.download(input, filename, mimeType,
                getCustomDownloadFolder(),
                new VideoDownloadManager.Listener() {
                    @Override
                    public void onProgress(long bytes) {
                        entry.bytes = bytes;
                        entry.status = "Descargando · " + formatBytes(bytes);
                        persistDownloadIfDue(entry, false);
                        runOnUiThread(MainActivity.this::refreshDownloadsDialog);
                    }

                    @Override
                    public void onComplete(String location, String savedFilename, long bytes) {
                        entry.processId = null;
                        entry.location = location;
                        entry.name = savedFilename;
                        entry.bytes = bytes;
                        entry.status = "Completada · " + formatBytes(bytes);
                        runOnUiThread(() -> {
                            saveDownloads();
                            refreshDownloadsDialog();
                        });
                    }

                    @Override
                    public void onError(String message) {
                        entry.processId = null;
                        entry.status = message.contains("cancelada")
                                ? "Cancelada" : "Error: " + message;
                        persistDownloadIfDue(entry, true);
                        runOnUiThread(() -> {
                            saveDownloads();
                            refreshDownloadsDialog();
                        });
                    }
                });
        if (isDownloadActive(entry)) {
            entry.processId = taskId;
        }
    }

    private void promptYtDlpDownload() {
        EditText urlField = new EditText(this);
        urlField.setSingleLine(true);
        urlField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlField.setText(currentUrl);
        urlField.setSelectAllOnFocus(true);
        urlField.setHint("https://sitio.example/video");
        urlField.setPadding(dp(16), dp(12), dp(16), dp(12));
        dialogBuilder()
                .setTitle("Descargar con yt-dlp")
                .setMessage("Analiza la página actual o pega una dirección HTTP o HTTPS.")
                .setView(urlField)
                .setNegativeButton("Cancelar", null)
                .setNeutralButton("Actualizar yt-dlp", (dialog, which) -> updateYtDlp())
                .setPositiveButton("Analizar", (dialog, which) -> {
                    String target = urlField.getText().toString().trim();
                    Uri uri = Uri.parse(target);
                    String scheme = uri.getScheme();
                    if (uri.getHost() == null || scheme == null
                            || (!scheme.equalsIgnoreCase("http")
                            && !scheme.equalsIgnoreCase("https"))) {
                        Toast.makeText(this, "Introduce una dirección HTTP o HTTPS válida.",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    inspectWithYtDlp(uri.toString());
                })
                .show();
    }

    private void updateYtDlp() {
        AlertDialog progress = dialogBuilder()
                .setTitle("Actualizando yt-dlp")
                .setMessage("Comprobando la versión estable…")
                .create();
        progress.setCancelable(false);
        progress.show();
        ytDlpEngine.initialize(new YtDlpEngine.ResultCallback() {
            @Override
            public void onReady() {
                ytDlpEngine.update(new YtDlpEngine.UpdateCallback() {
                    @Override
                    public void onComplete(String version, boolean updated) {
                        progress.dismiss();
                        Toast.makeText(MainActivity.this,
                                updated ? "yt-dlp actualizado · " + version
                                        : "yt-dlp ya está actualizado · " + version,
                                Toast.LENGTH_LONG).show();
                    }

                    @Override
                    public void onError(String message) {
                        progress.dismiss();
                        showYtDlpError(message);
                    }
                });
            }

            @Override
            public void onError(String message) {
                progress.dismiss();
                showYtDlpError(message);
            }
        });
    }

    private void inspectWithYtDlp(String url) {
        inspectWithYtDlp(url, null);
    }

    private void inspectWithYtDlp(String url, DetectedMedia source) {
        boolean[] cancelled = {false};
        String[] analysisProcessId = {null};
        AlertDialog loading = dialogBuilder()
                .setTitle("Preparando yt-dlp")
                .setMessage("Analizando los formatos disponibles…")
                .setNegativeButton("Cerrar", (dialog, which) -> {
                    cancelled[0] = true;
                    if (analysisProcessId[0] != null) {
                        ytDlpEngine.cancelInfo(analysisProcessId[0]);
                    }
                    if (source != null) {
                        setDetectedMediaStatus(source, "Análisis cancelado.", false);
                    }
                })
                .create();
        loading.setOnCancelListener(dialog -> {
            cancelled[0] = true;
            if (analysisProcessId[0] != null) {
                ytDlpEngine.cancelInfo(analysisProcessId[0]);
            }
            if (source != null) {
                setDetectedMediaStatus(source, "Análisis cancelado.", false);
            }
        });
        loading.show();
        MediaExtractor.Callback extractionCallback = new MediaExtractor.Callback() {
            @Override
            public void onStarted(String processId) {
                analysisProcessId[0] = processId;
                if (cancelled[0]) {
                    ytDlpEngine.cancelInfo(processId);
                }
            }

            @Override
            public void onResult(YtDlpMediaInfo info) {
                loading.dismiss();
                if (cancelled[0] || isFinishing()) {
                    return;
                }
                if (source != null) {
                    setDetectedMediaStatus(
                            source, "Listo para descargar: " + info.getTitle(), false);
                    source.title = info.getTitle();
                    source.thumbnailUrl = info.getThumbnailUrl();
                    source.durationText = info.getDurationSeconds() > 0
                            ? formatDuration(info.getDurationSeconds()) : "";
                    renderDetectedMediaItems();
                }
                String duration = info.getDurationSeconds() > 0
                        ? "\nDuración: " + formatDuration(info.getDurationSeconds()) : "";
                String thumbnail = info.getThumbnailUrl() == null
                        ? "\nMiniatura: no disponible" : "\nMiniatura: disponible";
                dialogBuilder()
                        .setTitle("Descargar video")
                        .setMessage(info.getTitle() + duration
                                + "\nFormatos disponibles: " + info.getFormats().size()
                                + thumbnail
                                + "\n\nyt-dlp no usa las cookies del navegador. "
                                + "Solo descarga contenido que tengas derecho a guardar; "
                                + "no evita DRM ni protecciones de acceso.")
                        .setNegativeButton("Cancelar", null)
                        .setPositiveButton("Elegir calidad", (dialog, which) ->
                                selectYtDlpQuality(url, info, source))
                        .show();
            }

            @Override
            public void onNotHandled() {
                loading.dismiss();
                if (!cancelled[0] && !isFinishing()) {
                    showYtDlpError("No hay un extractor compatible para esta dirección.");
                }
            }

            @Override
            public void onError(String message) {
                loading.dismiss();
                if (!cancelled[0] && source != null) {
                    setDetectedMediaStatus(source, "Error de análisis: " + message, false);
                }
                if (!cancelled[0] && !isFinishing()) {
                    showYtDlpError(message);
                }
            }
        };
        Runnable startExtraction = () -> {
            if (cancelled[0]) {
                return;
            }
            String referer = source == null ? null : source.pageUrl;
            mediaExtractor.extract(url, referer, extractionCallback);
        };
        ytDlpEngine.initialize(new YtDlpEngine.ResultCallback() {
            @Override
            public void onReady() {
                startExtraction.run();
            }

            @Override
            public void onError(String message) {
                loading.dismiss();
                if (!cancelled[0] && source != null) {
                    setDetectedMediaStatus(source, "Error de inicio: " + message, false);
                }
                if (!cancelled[0] && !isFinishing()) {
                    showYtDlpError(message);
                }
            }
        });
    }

    private void selectYtDlpQuality(String url, YtDlpMediaInfo media, DetectedMedia source) {
        LinearLayout options = new LinearLayout(this);
        options.setOrientation(LinearLayout.VERTICAL);
        if (media.getThumbnailUrl() != null) {
            ImageView thumbnail = new ImageView(this);
            thumbnail.setAdjustViewBounds(true);
            thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
            LinearLayout.LayoutParams thumbnailParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(180));
            thumbnailParams.setMargins(0, 0, 0, dp(8));
            options.addView(thumbnail, thumbnailParams);
            loadThumbnail(thumbnail, media.getThumbnailUrl());
        }
        TextView title = new TextView(this);
        title.setText(media.getTitle()
                + (media.getDurationSeconds() > 0
                ? "\nDuración: " + formatDuration(media.getDurationSeconds()) : ""));
        title.setTextColor(colorText);
        title.setTextSize(14);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setPadding(dp(4), dp(4), dp(4), dp(10));
        options.addView(title);
        CheckBox includeSubtitles = new CheckBox(this);
        includeSubtitles.setText("Incluir subtítulos si están disponibles");
        options.addView(includeSubtitles);
        ScrollView qualityScroll = new ScrollView(this);
        qualityScroll.addView(options);
        AlertDialog qualityDialog = dialogBuilder()
                .setTitle("Calidad y formato")
                .setView(qualityScroll)
                .setNegativeButton("Cancelar", null)
                .create();
        for (YtDlpFormatOption format : media.getFormats()) {
            Button formatButton = button(
                    format.getTitle() + "\n" + format.getDetails(), () -> {
                qualityDialog.dismiss();
                boolean audioOnly = format.getAudioOnly();
                startYtDlpDownload(url, media.getTitle(), format.getSelector(), audioOnly,
                        !audioOnly && includeSubtitles.isChecked(), source);
            });
            formatButton.setMaxLines(3);
            options.addView(formatButton, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        Button audioButton = button("Solo audio · MP3", () -> {
            qualityDialog.dismiss();
            startYtDlpDownload(
                    url, media.getTitle(), "bestaudio/best", true, false, source);
        });
        options.addView(audioButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        qualityDialog.show();
    }

    private void startYtDlpDownload(
            String url,
            String title,
            String formatSelector,
            boolean audioOnly,
            boolean includeSubtitles,
            DetectedMedia source) {
        File temporaryDirectory = new File(
                new File(getCacheDir(), "ytdlp"), UUID.randomUUID().toString());
        DownloadEntry entry = new DownloadEntry();
        entry.name = title;
        entry.mimeType = audioOnly ? "audio/mpeg" : "video/*";
        entry.engine = "yt-dlp";
        entry.url = url;
        entry.formatSelector = formatSelector;
        entry.audioOnly = audioOnly;
        entry.includeSubtitles = includeSubtitles;
        entry.status = "En cola · yt-dlp";
        addDownloadEntry(entry);
        if (source != null) {
            setDetectedMediaStatus(source, "En cola · " + title, true);
        }
        refreshDownloadsDialog();

        ytDlpEngine.download(url, temporaryDirectory, formatSelector, audioOnly, includeSubtitles,
                source == null ? null : source.pageUrl,
                new YtDlpEngine.DownloadCallback() {
            @Override
            public void onStarted(String processId) {
                entry.processId = processId;
                entry.status = "Iniciando yt-dlp…";
                persistDownloadIfDue(entry, true);
                if (source != null) {
                    setDetectedMediaStatus(source, "Descargando…", true);
                }
                refreshDownloadsDialog();
            }

            @Override
            public void onProgress(float percent, long etaSeconds, String status) {
                String eta = etaSeconds >= 0
                        ? " · ETA " + formatDuration(etaSeconds) : "";
                entry.status = percent >= 0
                        ? String.format(Locale.ROOT, "yt-dlp · %.0f%%%s", percent, eta)
                        : "yt-dlp · " + (status.isEmpty() ? "procesando" : status);
                persistDownloadIfDue(entry, false);
                if (source != null) {
                    setDetectedMediaStatus(source, entry.status, true);
                }
                refreshDownloadsDialog();
            }

            @Override
            public void onComplete(File file) {
                entry.processId = null;
                if (!VideoDownloadManager.supportsDownloadFile(file.getName())) {
                    entry.status = "Error: yt-dlp no produjo un formato compatible.";
                    if (source != null) {
                        setDetectedMediaStatus(source, entry.status, false);
                    }
                    cleanupYtDlpTemporaryFiles(temporaryDirectory);
                    saveDownloads();
                    refreshDownloadsDialog();
                    return;
                }
                entry.name = file.getName();
                entry.mimeType = VideoDownloadManager.normalizedMediaMimeType(file.getName());
                entry.copying = true;
                entry.status = "Guardando en Descargas/CDG Browser…";
                if (source != null) {
                    setDetectedMediaStatus(source, "Guardando en Descargas/CDG Browser…", true);
                }
                refreshDownloadsDialog();
                try {
                    String taskId = videoDownloadManager.download(
                            new FileInputStream(file),
                            file.getName(),
                            entry.mimeType,
                            getCustomDownloadFolder(),
                            new VideoDownloadManager.Listener() {
                                @Override
                                public void onProgress(long bytes) {
                                    entry.bytes = bytes;
                                    entry.status = "Guardando · " + formatBytes(bytes);
                                    if (source != null) {
                                        setDetectedMediaStatus(source, entry.status, true);
                                    }
                                    refreshDownloadsDialog();
                                }

                                @Override
                                public void onComplete(
                                        String location, String savedFilename, long bytes) {
                                    entry.processId = null;
                                    entry.copying = false;
                                    entry.location = location;
                                    entry.name = savedFilename;
                                    entry.bytes = bytes;
                                    entry.status = "Completada · " + formatBytes(bytes);
                                    if (source != null) {
                                        setDetectedMediaStatus(source, entry.status, false);
                                    }
                                    cleanupYtDlpTemporaryFiles(temporaryDirectory);
                                    runOnUiThread(() -> {
                                        saveDownloads();
                                        refreshDownloadsDialog();
                                    });
                                }

                                @Override
                                public void onError(String message) {
                                    entry.processId = null;
                                    entry.copying = false;
                                    entry.status = message.contains("cancelada")
                                            ? "Cancelada" : "Error al guardar: " + message;
                                    if (source != null) {
                                        setDetectedMediaStatus(source, entry.status, false);
                                    }
                                    cleanupYtDlpTemporaryFiles(temporaryDirectory);
                                    runOnUiThread(() -> {
                                        saveDownloads();
                                        refreshDownloadsDialog();
                                    });
                                }
                            });
                    if (isDownloadActive(entry)) {
                        entry.processId = taskId;
                    }
                } catch (IOException exception) {
                    entry.copying = false;
                    entry.status = "Error al abrir el video: " + exception.getMessage();
                    if (source != null) {
                        setDetectedMediaStatus(source, entry.status, false);
                    }
                    cleanupYtDlpTemporaryFiles(temporaryDirectory);
                    saveDownloads();
                    refreshDownloadsDialog();
                }
            }

            @Override
            public void onError(String message) {
                entry.processId = null;
                entry.status = message.contains("cancel")
                        ? "Cancelada" : "Error yt-dlp: " + message;
                if (source != null) {
                    setDetectedMediaStatus(source, entry.status, false);
                }
                cleanupYtDlpTemporaryFiles(temporaryDirectory);
                saveDownloads();
                refreshDownloadsDialog();
            }
        });
    }

    private void cleanupYtDlpTemporaryFiles(File temporaryDirectory) {
        File[] files = temporaryDirectory.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile()) {
                    file.delete();
                }
            }
        }
        temporaryDirectory.delete();
    }

    private void persistDownloadIfDue(DownloadEntry entry, boolean force) {
        long now = System.currentTimeMillis();
        if (force || now - entry.lastPersistAt >= 3000) {
            entry.lastPersistAt = now;
            runOnUiThread(this::saveDownloads);
        }
    }

    private void addDownloadEntry(DownloadEntry entry) {
        downloads.add(0, entry);
        while (downloads.size() > 100) {
            int removable = -1;
            for (int index = downloads.size() - 1; index >= 0; index--) {
                if (!isDownloadActive(downloads.get(index))) {
                    removable = index;
                    break;
                }
            }
            if (removable < 0) {
                break;
            }
            downloads.remove(removable);
        }
        saveDownloads();
    }

    private boolean isDownloadActive(DownloadEntry entry) {
        return entry.processId != null
                || entry.status.startsWith("En cola")
                || entry.status.startsWith("Iniciando")
                || entry.status.startsWith("Descargando")
                || entry.status.startsWith("Guardando")
                || entry.status.startsWith("Cancelando")
                || entry.status.startsWith("yt-dlp ·");
    }

    private void showYtDlpError(String message) {
        dialogBuilder()
                .setTitle("No se pudo analizar el video")
                .setMessage(message)
                .setPositiveButton("Cerrar", null)
                .show();
    }

    private String formatDuration(long seconds) {
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    private Uri getCustomDownloadFolder() {
        SharedPreferences preferences =
                getSharedPreferences(PREFERENCES, MODE_PRIVATE);
        String value = preferences.getString(DOWNLOAD_FOLDER_KEY, null);
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return Uri.parse(value);
        } catch (Exception exception) {
            return null;
        }
    }

    private void chooseDownloadFolder() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        intent.putExtra(Intent.EXTRA_TITLE, "Elegir carpeta para CDG Browser");
        startActivityForResult(intent, REQUEST_DOWNLOAD_FOLDER);
    }

    private void loadThumbnail(ImageView imageView, String thumbnailUrl) {
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(thumbnailUrl).openConnection();
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(8000);
                connection.setInstanceFollowRedirects(true);
                if (connection.getResponseCode() >= 200 && connection.getResponseCode() < 300) {
                    try (InputStream input = connection.getInputStream()) {
                        Bitmap bitmap = BitmapFactory.decodeStream(input);
                        if (bitmap != null) {
                            mainHandler.post(() -> imageView.setImageBitmap(bitmap));
                        }
                    }
                }
            } catch (Exception ignored) {
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }).start();
    }

    private void showDownloads() {
        downloadsList = new LinearLayout(this);
        downloadsList.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(downloadsList);
        downloadsDialog = dialogBuilder()
                .setTitle("Descargas")
                .setView(scroll)
                .setNegativeButton("Cerrar", (dialog, which) -> {
                    downloadsDialog = null;
                    downloadsList = null;
                })
                .create();
        downloadsDialog.setOnDismissListener(dialog -> {
            downloadsDialog = null;
            downloadsList = null;
        });
        refreshDownloadsDialog();
        downloadsDialog.show();
    }

    private void refreshDownloadsDialog() {
        runOnUiThread(() -> {
            if (downloadsList == null || downloadsDialog == null || !downloadsDialog.isShowing()) {
                return;
            }
            downloadsList.removeAllViews();
            TextView destination = new TextView(this);
            Uri customFolder = getCustomDownloadFolder();
            String folder = customFolder == null
                    ? (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                            ? "Descargas/CDG Browser"
                            : "almacenamiento de la app / Descargas")
                    : customFolder.getLastPathSegment();
            destination.setText("Destino: " + (folder == null ? "carpeta elegida" : folder));
            destination.setTextSize(12);
            destination.setTextColor(colorMuted);
            destination.setPadding(dp(16), dp(12), dp(16), dp(8));
            downloadsList.addView(destination);
            LinearLayout folderActions = new LinearLayout(this);
            folderActions.setGravity(Gravity.END);
            folderActions.addView(button("Elegir carpeta", this::chooseDownloadFolder));
            if (customFolder != null) {
                folderActions.addView(button("Abrir carpeta", () -> openDownloadFolder(customFolder)));
            }
            downloadsList.addView(folderActions);
            if (customFolder == null) {
                addFilesFromMediaStore();
            } else {
                addFilesFromCustomFolder(customFolder);
            }
            Button clearHistory = button("Limpiar historial terminado", () -> dialogBuilder()
                    .setTitle("Limpiar historial")
                    .setMessage("Se quitarán del historial las descargas terminadas o fallidas, pero no se borrarán los archivos.")
                    .setNegativeButton("Cancelar", null)
                    .setPositiveButton("Limpiar", (dialog, which) -> {
                        downloads.removeIf(entry -> !isDownloadActive(entry));
                        saveDownloads();
                        refreshDownloadsDialog();
                    })
                    .show());
            clearHistory.setPadding(dp(16), dp(8), dp(16), dp(8));
            downloadsList.addView(clearHistory);
            if (downloads.isEmpty()) {
                TextView empty = new TextView(this);
                empty.setText("Aún no hay descargas.");
                empty.setTextColor(colorText);
                empty.setPadding(dp(16), dp(16), dp(16), dp(16));
                downloadsList.addView(empty);
                return;
            }
            for (DownloadEntry entry : downloads) {
                LinearLayout row = new LinearLayout(this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setBackground(rippleBackground(colorSurface, colorBorder, 14));
                LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
                rowParams.setMargins(dp(12), dp(5), dp(12), dp(5));
                row.setLayoutParams(rowParams);
                TextView item = new TextView(this);
                String size = entry.bytes > 0 ? " · " + formatBytes(entry.bytes) : "";
                item.setText(entry.name + size + "\n" + entry.status);
                item.setTextSize(13);
                item.setTextColor(colorText);
                item.setPadding(dp(16), dp(12), dp(8), dp(12));
                row.addView(item);
                LinearLayout actions = new LinearLayout(this);
                actions.setGravity(Gravity.END);
                if (isDownloadActive(entry) && entry.processId != null) {
                    actions.addView(button("Cancelar", () -> {
                        boolean cancelled;
                        if ("direct".equals(entry.engine) || entry.copying) {
                            cancelled = videoDownloadManager.cancel(entry.processId);
                            if (!cancelled) {
                                entry.status = "No se pudo cancelar la descarga.";
                            }
                        } else {
                            cancelled = ytDlpEngine.cancel(entry.processId);
                            entry.status = cancelled
                                    ? "Cancelando yt-dlp…" : "No se pudo cancelar yt-dlp.";
                        }
                        persistDownloadIfDue(entry, true);
                        refreshDownloadsDialog();
                    }));
                } else if (!isDownloadActive(entry)
                        && !entry.status.startsWith("Completada")
                        && entry.url != null && !entry.url.isEmpty()) {
                    actions.addView(button("Reintentar", () -> inspectWithYtDlp(entry.url)));
                }
                if (entry.status.startsWith("Completada") && entry.location != null) {
                    actions.addView(button("Abrir", () -> openDownloadedFile(entry, false)));
                    actions.addView(button("Compartir", () -> openDownloadedFile(entry, true)));
                }
                if (!isDownloadActive(entry)) {
                    actions.addView(button("Quitar", () -> {
                        downloads.remove(entry);
                        saveDownloads();
                        refreshDownloadsDialog();
                    }));
                }
                row.addView(actions);
                downloadsList.addView(row);
            }
        });
    }

    private void addFilesFromMediaStore() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return;
        }
        TextView title = new TextView(this);
        title.setText("Archivos descargados");
        title.setTextSize(14);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setTextColor(colorText);
        title.setPadding(dp(16), dp(12), dp(16), dp(4));
        downloadsList.addView(title);
        int shown = 0;
        try (Cursor cursor = getContentResolver().query(
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                new String[]{
                        MediaStore.Downloads._ID,
                        MediaStore.Downloads.DISPLAY_NAME,
                        MediaStore.Downloads.MIME_TYPE,
                        MediaStore.Downloads.SIZE,
                        MediaStore.Downloads.RELATIVE_PATH
                },
                MediaStore.Downloads.RELATIVE_PATH + " LIKE ?",
                new String[]{"%CDG Browser%"},
                MediaStore.Downloads.DATE_ADDED + " DESC")) {
            if (cursor != null) {
                int idColumn = cursor.getColumnIndex(MediaStore.Downloads._ID);
                int nameColumn = cursor.getColumnIndex(MediaStore.Downloads.DISPLAY_NAME);
                int mimeColumn = cursor.getColumnIndex(MediaStore.Downloads.MIME_TYPE);
                int sizeColumn = cursor.getColumnIndex(MediaStore.Downloads.SIZE);
                while (cursor.moveToNext()) {
                    long id = idColumn >= 0 ? cursor.getLong(idColumn) : 0;
                    if (id == 0) {
                        continue;
                    }
                    String name = nameColumn >= 0 ? cursor.getString(nameColumn) : "Archivo";
                    String mime = mimeColumn >= 0 ? cursor.getString(mimeColumn) : "*/*";
                    long size = sizeColumn >= 0 ? cursor.getLong(sizeColumn) : 0;
                    Uri fileUri = Uri.withAppendedPath(
                            MediaStore.Downloads.getContentUri(
                                    MediaStore.VOLUME_EXTERNAL_PRIMARY),
                            String.valueOf(id));
                    LinearLayout row = new LinearLayout(this);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    TextView item = new TextView(this);
                    item.setText(name + "\n" + formatBytes(size));
                    item.setTextSize(13);
                    item.setTextColor(colorText);
                    item.setPadding(dp(16), dp(10), dp(8), dp(10));
                    row.addView(item, new LinearLayout.LayoutParams(0, -2, 1));
                    row.addView(button("Abrir", () -> openUriFile(fileUri, false, mime)));
                    row.addView(button("Compartir", () -> openUriFile(fileUri, true, mime)));
                    downloadsList.addView(row);
                    shown++;
                }
            }
        } catch (Exception exception) {
            TextView error = new TextView(this);
            error.setText("No se pudo leer Descargas/CDG Browser.");
            error.setTextColor(colorMuted);
            error.setPadding(dp(16), dp(8), dp(16), dp(8));
            downloadsList.addView(error);
        }
        if (shown == 0) {
            TextView empty = new TextView(this);
            empty.setText("No hay archivos todavía en Descargas/CDG Browser.");
            empty.setTextColor(colorMuted);
            empty.setPadding(dp(16), dp(8), dp(16), dp(12));
            downloadsList.addView(empty);
        }
    }

    private void addFilesFromCustomFolder(Uri folderUri) {
        if (folderUri == null) {
            return;
        }
        TextView title = new TextView(this);
        title.setText("Archivos en la carpeta");
        title.setTextSize(14);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setTextColor(colorText);
        title.setPadding(dp(16), dp(12), dp(16), dp(4));
        downloadsList.addView(title);
        int shown = 0;
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                folderUri, DocumentsContract.getTreeDocumentId(folderUri));
        try (Cursor cursor = getContentResolver().query(
                childrenUri,
                new String[]{
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_SIZE
                },
                null, null, null)) {
            if (cursor != null) {
                int idColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
                int nameColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                int mimeColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
                int sizeColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE);
                while (cursor.moveToNext()) {
                    String mime = mimeColumn >= 0 ? cursor.getString(mimeColumn) : null;
                    if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                        continue;
                    }
                    String name = nameColumn >= 0 ? cursor.getString(nameColumn) : "Archivo";
                    long size = sizeColumn >= 0 ? cursor.getLong(sizeColumn) : 0;
                    String documentId = idColumn >= 0 ? cursor.getString(idColumn) : null;
                    if (documentId == null) {
                        continue;
                    }
                    Uri fileUri = DocumentsContract.buildDocumentUriUsingTree(folderUri, documentId);
                    LinearLayout row = new LinearLayout(this);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    TextView item = new TextView(this);
                    item.setText(name + "\n" + formatBytes(size));
                    item.setTextSize(13);
                    item.setTextColor(colorText);
                    item.setPadding(dp(16), dp(10), dp(8), dp(10));
                    row.addView(item, new LinearLayout.LayoutParams(0, -2, 1));
                    row.addView(button("Abrir", () -> openUriFile(fileUri, false, mime)));
                    row.addView(button("Compartir", () -> openUriFile(fileUri, true, mime)));
                    downloadsList.addView(row);
                    shown++;
                }
            }
        } catch (Exception exception) {
            TextView error = new TextView(this);
            error.setText("No se pudo leer la carpeta elegida.");
            error.setTextColor(colorMuted);
            error.setPadding(dp(16), dp(8), dp(16), dp(8));
            downloadsList.addView(error);
        }
        if (shown == 0) {
            TextView empty = new TextView(this);
            empty.setText("No hay archivos descargados en esta carpeta.");
            empty.setTextColor(colorMuted);
            empty.setPadding(dp(16), dp(8), dp(16), dp(12));
            downloadsList.addView(empty);
        }
    }

    private void openUriFile(Uri uri, boolean share, String mimeType) {
        Intent intent = new Intent(share ? Intent.ACTION_SEND : Intent.ACTION_VIEW);
        if (share) {
            intent.setType(mimeType == null ? "*/*" : mimeType);
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, "Compartir descarga"));
        } else {
            intent.setDataAndType(uri, mimeType == null ? "*/*" : mimeType);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(intent, "Abrir descarga"));
        }
    }

    private void openDownloadFolder(Uri folderUri) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(folderUri, "vnd.android.document/directory");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(intent, "Abrir carpeta"));
        } catch (ActivityNotFoundException exception) {
            Toast.makeText(this, "No hay una aplicación compatible para abrir carpetas.",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void openDownloadedFile(DownloadEntry entry, boolean share) {
        if (entry.location == null || entry.location.isEmpty()) {
            Toast.makeText(this, "No se encontró la ubicación del archivo.", Toast.LENGTH_SHORT)
                    .show();
            return;
        }
        try {
            Uri uri;
            if (entry.location.startsWith("content://")) {
                uri = Uri.parse(entry.location);
            } else {
                File file = new File(entry.location);
                if (!file.isFile()) {
                    Toast.makeText(this, "El archivo ya no está disponible.", Toast.LENGTH_SHORT)
                            .show();
                    return;
                }
                uri = FileProvider.getUriForFile(
                        this, getPackageName() + ".fileprovider", file);
            }
            String mimeType = entry.mimeType == null ? "*/*" : entry.mimeType;
            Intent intent;
            if (share) {
                intent = new Intent(Intent.ACTION_SEND)
                        .setType(mimeType)
                        .putExtra(Intent.EXTRA_STREAM, uri);
            } else {
                intent = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeType);
            }
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(share ? Intent.createChooser(intent, "Compartir descarga") : intent);
        } catch (ActivityNotFoundException exception) {
            Toast.makeText(this, "No hay una aplicación compatible instalada.",
                    Toast.LENGTH_SHORT).show();
        } catch (IllegalArgumentException | SecurityException exception) {
            Toast.makeText(this, "No se pudo abrir o compartir el archivo: "
                    + exception.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void showHistory() {
        if (history.isEmpty()) {
            dialogBuilder()
                    .setTitle("Historial")
                    .setMessage("Todavía no hay páginas guardadas.")
                    .setPositiveButton("Cerrar", null)
                    .show();
            return;
        }
        String[] entries = history.toArray(new String[0]);
        dialogBuilder()
                .setTitle("Historial")
                .setItems(entries, (dialog, which) -> navigate(entries[which]))
                .setNeutralButton("Borrar historial", (dialog, which) -> clearHistory())
                .setNegativeButton("Cerrar", null)
                .show();
    }

    private void showProtectionInfo() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(12), dp(20), dp(8));

        protectionSummaryView = new TextView(this);
        protectionSummaryView.setTextSize(13);
        protectionSummaryView.setTextColor(colorMuted);
        content.addView(protectionSummaryView);

        protectionEnabledSwitch = new Switch(this);
        protectionEnabledSwitch.setText("Filtros locales de anuncios");
        protectionEnabledSwitch.setTextColor(colorText);
        protectionEnabledSwitch.setChecked(adBlockEngine.isEnabled());
        protectionEnabledSwitch.setOnCheckedChangeListener(
                (view, checked) -> adBlockEngine.setEnabled(checked));
        content.addView(protectionEnabledSwitch);

        siteExceptionButton = button(
                "Desactivar filtros locales en este sitio", this::toggleCurrentSiteException);
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
        actionParams.topMargin = dp(8);
        content.addView(siteExceptionButton, actionParams);

        updateFiltersButton = button("Actualizar listas manualmente", this::confirmFilterUpdate);
        LinearLayout.LayoutParams updateParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
        updateParams.topMargin = dp(8);
        content.addView(updateFiltersButton, updateParams);

        dialogBuilder()
                .setTitle("Protección y privacidad")
                .setView(content)
                .setPositiveButton("Cerrar", null)
                .setOnDismissListener(dialog -> {
                    protectionSummaryView = null;
                    protectionEnabledSwitch = null;
                    siteExceptionButton = null;
                    updateFiltersButton = null;
                })
                .show();
        refreshProtectionControls();
    }

    private void confirmFilterUpdate() {
        dialogBuilder()
                .setTitle("Actualizar filtros")
                .setMessage("Se descargará la lista pública de StevenBlack/hosts desde GitHub solo al confirmar. GitHub verá la IP de red; no se envían el historial ni la dirección que estás visitando. La lista se guarda localmente.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Descargar lista", (dialog, which) ->
                        adBlockEngine.updateFilters())
                .show();
    }

    private void toggleCurrentSiteException() {
        String host = currentHost();
        if (host == null || !adBlockEngine.toggleSiteException(host)) {
            return;
        }
        Toast.makeText(this, "Preferencia del sitio guardada; recarga para aplicarla.",
                Toast.LENGTH_SHORT).show();
        refreshProtectionControls();
    }

    private void refreshProtectionControls() {
        if (adBlockEngine == null) {
            return;
        }
        if (protectionSummaryView != null) {
            String engineState = adBlockEngine.isConnected()
                    ? (adBlockEngine.isEnabled() ? "Activo" : "Pausado")
                    : adBlockEngine.isInstalled()
                    ? "Instalado · sin conexión nativa"
                    : "No disponible";
            String updated = adBlockEngine.getLastUpdated().isEmpty()
                    ? "Lista inicial incluida; sin actualización manual"
                    : "Lista actualizada: " + adBlockEngine.getLastUpdated();
            String error = adBlockEngine.getLastError().isEmpty()
                    ? ""
                    : "\n\nAviso: " + adBlockEngine.getLastError();
            String blockedDomains = adBlockEngine.getBlockedDomainSummary();
            protectionSummaryView.setText(
                    "Protección estricta de GeckoView + reglas locales\n"
                            + "Estado: " + engineState
                            + "\nDominios en lista: " + adBlockEngine.getRuleCount()
                            + "\nBloqueos en esta sesión: " + adBlockEngine.getBlockedCount()
                            + "\nDominios responsables: "
                            + (blockedDomains.isEmpty() ? "aún no hay bloqueos" : blockedDomains)
                            + "\n" + updated
                            + "\n\nLa telemetría móvil de GeckoView está desactivada. "
                            + "La lista solo se actualiza cuando lo solicitas."
                            + error);
        }
        if (protectionEnabledSwitch != null
                && protectionEnabledSwitch.isChecked() != adBlockEngine.isEnabled()) {
            protectionEnabledSwitch.setChecked(adBlockEngine.isEnabled());
        }
        if (siteExceptionButton != null) {
            String host = currentHost();
            siteExceptionButton.setEnabled(host != null);
            siteExceptionButton.setText(host != null && adBlockEngine.isSiteAllowed(host)
                    ? "Reactivar filtros locales en este sitio"
                    : "Desactivar filtros locales en este sitio");
        }
        if (updateFiltersButton != null) {
            updateFiltersButton.setEnabled(
                    adBlockEngine.isConnected() && !adBlockEngine.isUpdateInProgress());
            updateFiltersButton.setText(adBlockEngine.isUpdateInProgress()
                    ? "Actualizando lista…" : "Actualizar listas manualmente");
        }
    }

    private String currentHost() {
        Uri uri = Uri.parse(currentUrl);
        return "http".equalsIgnoreCase(uri.getScheme())
                || "https".equalsIgnoreCase(uri.getScheme())
                ? uri.getHost() : null;
    }

    private void addToHistory(String url) {
        history.remove(url);
        history.add(0, url);
        if (history.size() > MAX_HISTORY_ITEMS) {
            history.subList(MAX_HISTORY_ITEMS, history.size()).clear();
        }
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                .putString(HISTORY_KEY, String.join("\n", history))
                .apply();
    }

    private void clearHistory() {
        history.clear();
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit().remove(HISTORY_KEY).apply();
        Toast.makeText(this, "Historial borrado", Toast.LENGTH_SHORT).show();
    }

    private void loadSavedData() {
        android.content.SharedPreferences preferences =
                getSharedPreferences(PREFERENCES, MODE_PRIVATE);
        darkTheme = preferences.getBoolean(THEME_KEY, false);
        loadLines(preferences.getString(HISTORY_KEY, ""), history);
        loadLines(preferences.getString(FAVORITES_KEY, ""), favorites);
        String savedDownloads = preferences.getString(DOWNLOADS_KEY, "[]");
        try {
            JSONArray items = new JSONArray(savedDownloads);
            long now = System.currentTimeMillis();
            for (int i = 0; i < items.length(); i++) {
                JSONObject saved = items.getJSONObject(i);
                DownloadEntry entry = new DownloadEntry();
                entry.name = saved.getString("name");
                entry.mimeType = saved.optString("mimeType", "video/*");
                entry.location = saved.optString("location", "");
                entry.url = saved.optString("url", "");
                entry.engine = saved.optString("engine", "");
                entry.formatSelector = saved.optString("formatSelector", "");
                entry.audioOnly = saved.optBoolean("audioOnly", false);
                entry.includeSubtitles = saved.optBoolean("includeSubtitles", false);
                entry.status = saved.optString("status", "Completada");
                entry.bytes = saved.optLong("bytes", 0);
                entry.lastPersistAt = now;
                if (isDownloadActive(entry)) {
                    entry.status = "Interrumpida · puedes reintentar";
                }
                downloads.add(entry);
            }
            if (!downloads.isEmpty()) {
                saveDownloads();
            }
        } catch (Exception exception) {
            Toast.makeText(this, "No se pudo leer el historial de descargas",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void loadLines(String saved, List<String> target) {
        if (saved == null || saved.isEmpty()) {
            return;
        }
        for (String item : saved.split("\n")) {
            if (!item.isEmpty()) {
                target.add(item);
            }
        }
    }

    private void saveFavorites() {
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                .putString(FAVORITES_KEY, String.join("\n", favorites))
                .apply();
    }

    private void saveDownloads() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread(this::saveDownloads);
            return;
        }
        JSONArray items = new JSONArray();
        int last = Math.min(100, downloads.size());
        for (int index = 0; index < last; index++) {
            DownloadEntry entry = downloads.get(index);
            JSONObject item = new JSONObject();
            try {
                item.put("name", entry.name);
                item.put("mimeType", entry.mimeType);
                item.put("location", entry.location);
                item.put("bytes", entry.bytes);
                item.put("url", entry.url);
                item.put("engine", entry.engine);
                item.put("formatSelector", entry.formatSelector);
                item.put("audioOnly", entry.audioOnly);
                item.put("includeSubtitles", entry.includeSubtitles);
                item.put("status", entry.status);
                items.put(item);
            } catch (org.json.JSONException exception) {
                runOnUiThread(() -> Toast.makeText(this,
                        "No se pudo guardar el historial de descargas",
                        Toast.LENGTH_SHORT).show());
            }
        }
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                .putString(DOWNLOADS_KEY, items.toString())
                .apply();
    }

    private AlertDialog.Builder dialogBuilder() {
        return new AlertDialog.Builder(this,
                darkTheme ? R.style.AppDialogDark : R.style.AppDialogLight);
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    private void closeQuietly(InputStream input) {
        if (input != null) {
            try {
                input.close();
            } catch (IOException ignored) {
                // The stream is discarded when a download is declined.
            }
        }
    }

    private void updateStatus(String message) {
        statusMessage = message;
        if (statusView != null) {
            statusView.setText((privateMode ? "Privada  ·  " : "Protegido  ·  ") + message);
        }
    }

    private void handleBackNavigation() {
        if (session != null && canGoBack) {
            session.goBack();
        } else {
            finish();
        }
    }

    @SuppressLint("GestureBackNavigation")
    @Override
    public void onBackPressed() {
        handleBackNavigation();
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacks(adBlockStartupFallback);
        if (browserView != null) {
            browserView.releaseSession();
        }
        for (BrowserTab tab : tabs) {
            tab.session.close();
        }
        tabs.clear();
        if (videoDownloadManager != null) {
            videoDownloadManager.close();
        }
        if (adBlockEngine != null) {
            adBlockEngine.setListener(null);
        }
        super.onDestroy();
    }
}
