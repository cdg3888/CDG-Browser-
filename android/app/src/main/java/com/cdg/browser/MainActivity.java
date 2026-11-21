package com.cdg.browser;

import android.app.Activity;
import android.app.AlertDialog;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
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
import com.cdg.browser.downloads.VideoDownloadManager;
import com.cdg.browser.ytdlp.YtDlpEngine;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class MainActivity extends Activity {
    private static final String HOME_URL = "https://duckduckgo.com/";
    private static final String PREFERENCES = "cdg_browser";
    private static final String HISTORY_KEY = "history";
    private static final String FAVORITES_KEY = "favorites";
    private static final String DOWNLOADS_KEY = "downloads";
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

    private GeckoView browserView;
    private GeckoSession session;
    private static AdBlockEngine adBlockEngine;
    private VideoDownloadManager videoDownloadManager;
    private YtDlpEngine ytDlpEngine;
    private BrowserTab activeTab;
    private EditText addressField;
    private TextView statusView;
    private TextView tabCountView;
    private LinearLayout addressRow;
    private LinearLayout downloadsList;
    private AlertDialog downloadsDialog;
    private Button privateButton;
    private Button addressToggleButton;
    private Button favoriteButton;
    private Button siteExceptionButton;
    private Button updateFiltersButton;
    private Switch protectionEnabledSwitch;
    private TextView protectionSummaryView;
    private boolean privateMode;
    private boolean darkTheme;
    private boolean addressBarVisible = true;
    private boolean canGoBack;
    private boolean initialNavigationStarted;
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
    }

    private static final class DownloadEntry {
        String name;
        String mimeType;
        String status;
        String location;
        String engine;
        String processId;
        long bytes;
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
        });
        adBlockEngine.install();
        mainHandler.postDelayed(adBlockStartupFallback, 8000);
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
        actions.addView(button("yt-dlp", this::promptYtDlpDownload), actionButtonParams());
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

        browserView = new GeckoView(this);
        root.addView(browserView, new LinearLayout.LayoutParams(
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
                String scheme = Uri.parse(request.uri).getScheme();
                if ("https".equalsIgnoreCase(scheme)
                        || "http".equalsIgnoreCase(scheme)
                        || ("about".equalsIgnoreCase(scheme)
                        && "about:blank".equalsIgnoreCase(request.uri))) {
                    return GeckoResult.fromValue(AllowOrDeny.ALLOW);
                }
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "Por seguridad, solo se permiten páginas HTTP y HTTPS",
                        Toast.LENGTH_SHORT).show());
                return GeckoResult.fromValue(AllowOrDeny.DENY);
            }

            @Override
            public GeckoResult<GeckoSession> onNewSession(GeckoSession currentSession, String uri) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "Usa + Pestaña para abrir otra pestaña",
                        Toast.LENGTH_SHORT).show());
                return null;
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
                if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) {
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
                        startVideoDownload(response.body, finalFilename, finalMimeType))
                .setOnCancelListener(dialog -> closeQuietly(response.body))
                .show());
    }

    private void startVideoDownload(InputStream input, String filename, String mimeType) {
        DownloadEntry entry = new DownloadEntry();
        entry.name = filename;
        entry.mimeType = mimeType;
        entry.status = "En cola";
        downloads.add(0, entry);
        refreshDownloadsDialog();
        videoDownloadManager.download(input, filename, mimeType,
                new VideoDownloadManager.Listener() {
                    @Override
                    public void onProgress(long bytes) {
                        entry.bytes = bytes;
                        entry.status = "Descargando · " + formatBytes(bytes);
                        runOnUiThread(MainActivity.this::refreshDownloadsDialog);
                    }

                    @Override
                    public void onComplete(String location, long bytes) {
                        entry.location = location;
                        entry.bytes = bytes;
                        entry.status = "Completada · " + formatBytes(bytes);
                        runOnUiThread(() -> {
                            saveDownloads();
                            refreshDownloadsDialog();
                        });
                    }

                    @Override
                    public void onError(String message) {
                        entry.status = "Error: " + message;
                        runOnUiThread(MainActivity.this::refreshDownloadsDialog);
                    }
                });
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

    private void inspectWithYtDlp(String url) {
        boolean[] cancelled = {false};
        AlertDialog loading = dialogBuilder()
                .setTitle("Preparando yt-dlp")
                .setMessage("La primera inicialización puede tardar un poco.")
                .setNegativeButton("Cerrar", (dialog, which) -> cancelled[0] = true)
                .create();
        loading.setOnCancelListener(dialog -> cancelled[0] = true);
        loading.show();
        ytDlpEngine.initialize(new YtDlpEngine.ResultCallback() {
            @Override
            public void onReady() {
                ytDlpEngine.getInfo(url, new YtDlpEngine.InfoCallback() {
                    @Override
                    public void onInfo(String title, int durationSeconds) {
                        loading.dismiss();
                        if (cancelled[0] || isFinishing()) {
                            return;
                        }
                        String duration = durationSeconds > 0
                                ? "\nDuración: " + formatDuration(durationSeconds)
                                : "";
                        dialogBuilder()
                                .setTitle("Descargar video")
                                .setMessage(title + duration
                                        + "\n\nyt-dlp no usa las cookies del navegador. "
                                        + "Solo descarga contenido que tengas derecho a guardar; "
                                        + "no evita DRM ni protecciones de acceso.")
                                .setNegativeButton("Cancelar", null)
                                .setPositiveButton("Descargar", (dialog, which) ->
                                        startYtDlpDownload(url, title))
                                .show();
                    }

                    @Override
                    public void onError(String message) {
                        loading.dismiss();
                        if (!cancelled[0] && !isFinishing()) {
                            showYtDlpError(message);
                        }
                    }
                });
            }

            @Override
            public void onError(String message) {
                loading.dismiss();
                if (!cancelled[0] && !isFinishing()) {
                    showYtDlpError(message);
                }
            }
        });
    }

    private void startYtDlpDownload(String url, String title) {
        File temporaryDirectory = new File(
                new File(getCacheDir(), "ytdlp"), UUID.randomUUID().toString());
        DownloadEntry entry = new DownloadEntry();
        entry.name = title;
        entry.mimeType = "video/*";
        entry.engine = "yt-dlp";
        entry.status = "En cola · yt-dlp";
        downloads.add(0, entry);
        refreshDownloadsDialog();

        ytDlpEngine.download(url, temporaryDirectory, new YtDlpEngine.DownloadCallback() {
            @Override
            public void onStarted(String processId) {
                entry.processId = processId;
                entry.status = "Iniciando yt-dlp…";
                refreshDownloadsDialog();
            }

            @Override
            public void onProgress(float percent, long etaSeconds, String status) {
                String eta = etaSeconds >= 0
                        ? " · ETA " + formatDuration(etaSeconds) : "";
                entry.status = percent >= 0
                        ? String.format(Locale.ROOT, "yt-dlp · %.0f%%%s", percent, eta)
                        : "yt-dlp · " + (status.isEmpty() ? "procesando" : status);
                refreshDownloadsDialog();
            }

            @Override
            public void onComplete(File file) {
                entry.processId = null;
                if (!VideoDownloadManager.supportsDirectVideo(null, file.getName())) {
                    entry.status = "Error: yt-dlp no produjo un formato de video compatible.";
                    cleanupYtDlpTemporaryFiles(temporaryDirectory);
                    refreshDownloadsDialog();
                    return;
                }
                entry.name = file.getName();
                entry.mimeType = VideoDownloadManager.normalizedVideoMimeType(
                        null, file.getName());
                entry.status = "Guardando en Películas/CDG Browser…";
                refreshDownloadsDialog();
                try {
                    videoDownloadManager.download(
                            new FileInputStream(file),
                            file.getName(),
                            entry.mimeType,
                            new VideoDownloadManager.Listener() {
                                @Override
                                public void onProgress(long bytes) {
                                    entry.bytes = bytes;
                                    entry.status = "Guardando · " + formatBytes(bytes);
                                    refreshDownloadsDialog();
                                }

                                @Override
                                public void onComplete(String location, long bytes) {
                                    entry.location = location;
                                    entry.bytes = bytes;
                                    entry.status = "Completada · " + formatBytes(bytes);
                                    cleanupYtDlpTemporaryFiles(temporaryDirectory);
                                    runOnUiThread(() -> {
                                        saveDownloads();
                                        refreshDownloadsDialog();
                                    });
                                }

                                @Override
                                public void onError(String message) {
                                    entry.status = "Error al guardar: " + message;
                                    cleanupYtDlpTemporaryFiles(temporaryDirectory);
                                    refreshDownloadsDialog();
                                }
                            });
                } catch (IOException exception) {
                    entry.status = "Error al abrir el video: " + exception.getMessage();
                    cleanupYtDlpTemporaryFiles(temporaryDirectory);
                    refreshDownloadsDialog();
                }
            }

            @Override
            public void onError(String message) {
                entry.processId = null;
                entry.status = "Error yt-dlp: " + message;
                cleanupYtDlpTemporaryFiles(temporaryDirectory);
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

    private void showDownloads() {
        downloadsList = new LinearLayout(this);
        downloadsList.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(downloadsList);
        downloadsDialog = dialogBuilder()
                .setTitle("Descargas de video")
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
            String folder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    ? "Películas/CDG Browser"
                    : "almacenamiento de la app / Películas";
            destination.setText("Destino: " + folder);
            destination.setTextSize(12);
            destination.setTextColor(colorMuted);
            destination.setPadding(dp(16), dp(12), dp(16), dp(8));
            downloadsList.addView(destination);
            if (downloads.isEmpty()) {
                TextView empty = new TextView(this);
                empty.setText("Aún no hay videos descargados.");
                empty.setTextColor(colorText);
                empty.setPadding(dp(16), dp(16), dp(16), dp(16));
                downloadsList.addView(empty);
                return;
            }
            for (DownloadEntry entry : downloads) {
                LinearLayout row = new LinearLayout(this);
                row.setGravity(Gravity.CENTER_VERTICAL);
                TextView item = new TextView(this);
                item.setText(entry.name + "\n" + entry.status);
                item.setTextSize(13);
                item.setTextColor(colorText);
                item.setPadding(dp(16), dp(12), dp(8), dp(12));
                row.addView(item, new LinearLayout.LayoutParams(0, -2, 1));
                if ("yt-dlp".equals(entry.engine) && entry.processId != null) {
                    row.addView(button("Cancelar", () -> {
                        if (ytDlpEngine.cancel(entry.processId)) {
                            entry.status = "Cancelando yt-dlp…";
                        } else {
                            entry.status = "No se pudo cancelar yt-dlp.";
                        }
                        refreshDownloadsDialog();
                    }));
                }
                downloadsList.addView(row);
            }
        });
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
            protectionSummaryView.setText(
                    "Protección estricta de GeckoView + reglas locales\n"
                            + "Estado: " + engineState
                            + "\nDominios en lista: " + adBlockEngine.getRuleCount()
                            + "\nBloqueos en esta sesión: " + adBlockEngine.getBlockedCount()
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
            for (int i = 0; i < items.length(); i++) {
                JSONObject saved = items.getJSONObject(i);
                DownloadEntry entry = new DownloadEntry();
                entry.name = saved.getString("name");
                entry.mimeType = saved.optString("mimeType", "video/*");
                entry.location = saved.optString("location", "");
                entry.status = "Completada";
                entry.bytes = saved.optLong("bytes", 0);
                downloads.add(entry);
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
        JSONArray items = new JSONArray();
        for (DownloadEntry entry : downloads) {
            if (!entry.status.startsWith("Completada")) {
                continue;
            }
            JSONObject item = new JSONObject();
            try {
                item.put("name", entry.name);
                item.put("mimeType", entry.mimeType);
                item.put("location", entry.location);
                item.put("bytes", entry.bytes);
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
