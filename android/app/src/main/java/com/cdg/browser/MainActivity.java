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
import android.util.Log;

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
import com.cdg.browser.downloads.DownloadHistoryStore;
import com.cdg.browser.media.MediaGalleryActivity;
import com.cdg.browser.media.MediaPlayerActivity;
import com.cdg.browser.ytdlp.ChainExtractor;
import com.cdg.browser.ytdlp.DirectMediaExtractor;
import com.cdg.browser.ytdlp.MediaExtractor;
import com.cdg.browser.ytdlp.YtDlpFormatOption;
import com.cdg.browser.ytdlp.YtDlpEngine;
import com.cdg.browser.ytdlp.YtDlpMediaInfo;
import com.cdg.browser.ytdlp.YtDlpUniversalExtractor;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.BufferedInputStream;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.net.HttpURLConnection;
import java.net.URL;
import android.content.SharedPreferences;

public final class MainActivity extends Activity {
    private static final String HOME_URL = "https://duckduckgo.com/";
    private static final String PREFERENCES = "cdg_browser";
    private static final String HISTORY_KEY = "history";
    private static final String FAVORITES_KEY = "favorites";
    private static final String DOWNLOADS_KEY = "downloads";
    private static final String PENDING_MEDIA_KEY = "pending_media";
    private static final String DOWNLOAD_FOLDER_KEY = "download_folder_uri";
    private static final String ADDRESS_AUTO_HIDE_KEY = "address_auto_hide";
    private static final String MINIMUM_MEDIA_HEIGHT_KEY = "minimum_media_height";
    private static final String CONCURRENT_FRAGMENTS_KEY = "concurrent_fragments";
    private static final String REMUX_MP4_KEY = "remux_mp4";
    private static final String DEFAULT_SUBTITLES_KEY = "default_subtitles";
    private static final String VISUAL_THEME_KEY = "visual_theme";
    private static final String SEARCH_ENGINE_KEY = "search_engine";
    private static final String HOME_PAGE_KEY = "home_page";
    private static final String BLOCK_POPUPS_KEY = "block_popups";
    private static final String BLOCK_CLICK_REDIRECTS_KEY = "block_click_redirects";
    private static final String MEDIA_FAB_ENABLED_KEY = "media_fab_enabled";
    private static final String MEDIA_FAB_HIDDEN_SITES_KEY = "media_fab_hidden_sites";
    private static final int REQUEST_DOWNLOAD_FOLDER = 4701;
    private static final String THEME_KEY = "dark_theme";
    private static final int MAX_HISTORY_ITEMS = 50;
    private static final int MAX_BULK_ITEMS = 100;
    private static final int MAX_PAGE_IMAGE_CANDIDATES = 200;
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
    private final LinkedHashMap<String, DetectedMedia> pendingMedia = new LinkedHashMap<>();
    private final LinkedHashMap<String, PageImageSet> pageImagesByPage = new LinkedHashMap<>();
    private final LinkedHashMap<String, TextView> detectedMediaStatusViews = new LinkedHashMap<>();
    private final LinkedHashMap<String, Button> detectedMediaDownloadButtons = new LinkedHashMap<>();
    private final LinkedHashMap<String, CheckBox> detectedMediaSelectionViews =
            new LinkedHashMap<>();
    private final Set<String> selectedDetectedMediaKeys = new HashSet<>();

    private GeckoView browserView;
    private GeckoSession session;
    private static AdBlockEngine adBlockEngine;
    private VideoDownloadManager videoDownloadManager;
    private DownloadHistoryStore downloadHistoryStore;
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
    private Button reloadButton;
    private Button favoriteButton;
    private Button settingsButton;
    private Button siteExceptionButton;
    private Button updateFiltersButton;
    private Button mediaButton;
    private Button mediaFab;
    private Switch protectionEnabledSwitch;
    private Switch mediaDetectionSwitch;
    private TextView protectionSummaryView;
    private TextView downloadDestinationView;
    private AlertDialog settingsDialog;
    private FrameLayout browserContainer;
    private LinearLayout leftSidebar;
    private LinearLayout rightSidebar;
    private View sidebarScrim;
    private Button navigationPanelButton;
    private TextView rightAdBlockSummary;
    private Switch sidebarProtectionSwitch;
    private Switch sidebarMediaDetectionSwitch;
    private Switch sidebarPopupSwitch;
    private Switch sidebarRedirectSwitch;
    private Button sidebarExceptionButton;
    private Button sidebarUpdateButton;
    private Button sidebarImageBatchButton;
    private Button sidebarVideoBatchButton;
    private Button sidebarMediaLibraryButton;
    private Button mediaFabSiteButton;
    private int openSidebar;
    private final List<AdBlockEngine.PageImageCandidate> pageImageCandidates =
            new ArrayList<>();
    private String pageImagePageUrl = "";
    private String pageImagePageTitle = "";
    private final ArrayDeque<DetectedMedia> bulkVideoQueue = new ArrayDeque<>();
    private final List<DetectedMedia> bulkVideoAnalysisQueue = new ArrayList<>();
    private final Set<String> mediaFabHiddenSites = new HashSet<>();
    private boolean privateMode;
    private boolean bulkVideoBatchRunning;
    private int bulkVideoActiveDownloads;
    private int bulkVideoAnalysisIndex;
    private int bulkVideoMaximumHeight;
    private boolean mediaFabEnabled;
    private boolean darkTheme;
    private boolean addressBarVisible = true;
    private boolean addressAutoHide = true;
    private boolean remuxVideosToMp4 = true;
    private boolean subtitlesByDefault;
    private boolean blockPopups = true;
    private boolean blockClickRedirects = true;
    private int minimumMediaHeight = 1080;
    private int concurrentFragments = 32;
    private boolean canGoBack;
    private boolean initialNavigationStarted;
    private String mediaTypeFilter = "Todos";
    private boolean showAllDetectedMedia;
    private String visualTheme = "neon_orange";
    private String searchEngine = "duckduckgo";
    private String homeUrl = HOME_URL;
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
        boolean redirectTriggeredByUserGesture;
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
        String pageTitle;
        final String type;
        String downloadStatus = "Disponible";
        boolean downloadActive;
        String title;
        String thumbnailUrl;
        String durationText;
        int videoWidth;
        int videoHeight;
        boolean includeSubtitles;
        boolean analysisInProgress;
        String analysisProcessId;
        final List<YtDlpFormatOption> formats = new ArrayList<>();

        DetectedMedia(
                String key,
                String url,
                String pageUrl,
                String pageTitle,
                String type,
                String thumbnailUrl,
                int videoWidth,
                int videoHeight,
                int durationSeconds) {
            this.key = key;
            this.url = url;
            this.pageUrl = pageUrl;
            this.pageTitle = pageTitle;
            this.type = type;
            this.thumbnailUrl = thumbnailUrl;
            this.videoWidth = videoWidth;
            this.videoHeight = videoHeight;
            this.durationText = durationSeconds > 0
                    ? String.format(
                    Locale.ROOT, "%d:%02d", durationSeconds / 60, durationSeconds % 60) : "";
        }

    }

    private static final class PageImageSet {
        final String pageUrl;
        final String title;
        final List<AdBlockEngine.PageImageCandidate> images;

        PageImageSet(
                String pageUrl, String title, List<AdBlockEngine.PageImageCandidate> images) {
            this.pageUrl = pageUrl;
            this.title = title;
            this.images = images;
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
        downloadHistoryStore = new DownloadHistoryStore(this);
        ytDlpEngine = new YtDlpEngine(this);
        mediaExtractor = new ChainExtractor(
                new DirectMediaExtractor(),
                new ArrayList<>(),
                new YtDlpUniversalExtractor(ytDlpEngine));
        activateTab(createSession());
        if (adBlockEngine == null) {
            adBlockEngine = new AdBlockEngine(this, runtime, null);
        }
        if (isTvVersion()) {
            navigationPanelButton.post(navigationPanelButton::requestFocus);
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
            public void onMediaDetected(
                    String url,
                    String pageUrl,
                    String mediaType,
                    String pageTitle,
                    String thumbnailUrl,
                    int videoWidth,
                    int videoHeight,
                    int durationSeconds) {
                runOnUiThread(() -> addDetectedMedia(
                        url, pageUrl, mediaType, pageTitle, thumbnailUrl,
                        videoWidth, videoHeight, durationSeconds));
            }

            @Override
            public void onPageScroll(String pageUrl, boolean scrollingDown) {
                runOnUiThread(() -> handlePageScroll(pageUrl, scrollingDown));
            }

            @Override
            public void onPageImagesDetected(
                    String pageUrl,
                    String pageTitle,
                    List<AdBlockEngine.PageImageCandidate> images) {
                runOnUiThread(() -> updatePageImageCandidates(pageUrl, pageTitle, images));
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
        updateSettingsDownloadDestination();
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
        titleRow.setPadding(dp(isTvVersion() ? 18 : 6), dp(8),
                dp(isTvVersion() ? 18 : 8), dp(8));
        titleRow.setBackgroundColor(colorSurface);
        navigationPanelButton = button(
                isTvVersion() ? "Pestañas" : "☰", () -> toggleSidePanel(1));
        navigationPanelButton.setContentDescription("Abrir pestañas y favoritos");
        titleRow.addView(navigationPanelButton, headerButtonParams());
        TextView title = new TextView(this);
        title.setText(isTvVersion() ? "CDG BROWSER  /  TV" : "CDG / BROWSER");
        title.setTextColor(colorText);
        title.setTextSize(isTvVersion() ? 19 : 15);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setLetterSpacing(0.06f);
        titleRow.addView(title, new LinearLayout.LayoutParams(0, dp(44), 1));
        privateButton = button("Privada · no", () -> togglePrivateMode());
        titleRow.addView(privateButton);
        settingsButton = button(isTvVersion() ? "Menú" : "⚙", () -> toggleSidePanel(2));
        settingsButton.setContentDescription("Abrir descargas, AdBlock y ajustes");
        titleRow.addView(settingsButton, headerButtonParams());
        addressToggleButton = button(
                isTvVersion() ? "Ocultar barra" : "⌄", this::toggleAddressBar);
        addressToggleButton.setContentDescription("Mostrar u ocultar barra de direcciones");
        titleRow.addView(addressToggleButton, headerButtonParams());
        root.addView(titleRow);

        addressRow = new LinearLayout(this);
        addressRow.setGravity(Gravity.CENTER_VERTICAL);
        addressRow.setPadding(dp(isTvVersion() ? 18 : 12), dp(8),
                dp(isTvVersion() ? 18 : 12), dp(8));
        addressRow.setBackgroundColor(colorSurface);
        addressField = new EditText(this);
        addressField.setSingleLine(true);
        addressField.setTextSize(isTvVersion() ? 18 : 14);
        addressField.setHint("Buscar o introducir dirección");
        addressField.setHintTextColor(colorMuted);
        addressField.setTextColor(colorText);
        addressField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        addressField.setImeOptions(EditorInfo.IME_ACTION_GO);
        addressField.setSelectAllOnFocus(true);
        addressField.setPadding(dp(14), 0, dp(14), 0);
        addressField.setBackground(roundedBackground(colorBackground, colorBorder, 16));
        if (isTvVersion()) {
            addressField.setOnFocusChangeListener((view, focused) -> addressField.setBackground(
                    roundedBackground(
                            colorBackground, focused ? colorAccent : colorBorder, 16)));
        }
        addressField.setText(currentUrl);
        addressRow.addView(addressField,
                new LinearLayout.LayoutParams(0, dp(isTvVersion() ? 58 : 48), 1));
        favoriteButton = button(isTvVersion() ? "Favorito" : "☆", this::toggleFavorite);
        LinearLayout.LayoutParams favoriteParams = new LinearLayout.LayoutParams(
                isTvVersion() ? -2 : dp(44), dp(isTvVersion() ? 54 : 44));
        favoriteParams.setMargins(dp(6), 0, dp(6), 0);
        addressRow.addView(favoriteButton, favoriteParams);
        Button goButton = button(isTvVersion() ? "Buscar" : "Ir", this::submitAddress);
        goButton.setTextColor(Color.WHITE);
        goButton.setBackground(rippleBackground(colorAccent, colorAccent, 14));
        if (isTvVersion()) {
            goButton.setOnFocusChangeListener((view, focused) -> goButton.setBackground(
                    rippleBackground(colorAccent, focused ? Color.WHITE : colorAccent, 14)));
        }
        LinearLayout.LayoutParams goParams = new LinearLayout.LayoutParams(
                dp(isTvVersion() ? 88 : 48), dp(isTvVersion() ? 54 : 44));
        addressRow.addView(goButton, goParams);
        reloadButton = button("↻", () -> {
            if (session != null) {
                session.reload();
            }
        });
        reloadButton.setContentDescription("Actualizar página");
        if (isTvVersion()) {
            reloadButton.setText("Actualizar");
        }
        LinearLayout.LayoutParams reloadParams = new LinearLayout.LayoutParams(
                isTvVersion() ? -2 : dp(44), dp(isTvVersion() ? 54 : 44));
        reloadParams.setMargins(dp(4), 0, 0, 0);
        addressRow.addView(reloadButton, reloadParams);
        root.addView(addressRow);
        addressRow.setVisibility(addressBarVisible ? View.VISIBLE : View.GONE);
        addressToggleButton.setText(addressToggleLabel());
        addressField.setOnEditorActionListener((view, actionId, event) -> {
            boolean go = actionId == EditorInfo.IME_ACTION_GO
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER);
            if (go) {
                submitAddress();
            }
            return go;
        });

        browserContainer = new FrameLayout(this);
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
        sidebarScrim = new View(this);
        sidebarScrim.setBackgroundColor(Color.argb(160, 0, 0, 0));
        sidebarScrim.setVisibility(View.GONE);
        sidebarScrim.setOnClickListener(view -> closeSidePanel());
        browserContainer.addView(sidebarScrim, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        leftSidebar = new LinearLayout(this);
        leftSidebar.setOrientation(LinearLayout.VERTICAL);
        leftSidebar.setBackgroundColor(colorSurface);
        leftSidebar.setElevation(dp(12));
        leftSidebar.setVisibility(View.GONE);
        browserContainer.addView(leftSidebar, sidebarLayoutParams(Gravity.START));
        rightSidebar = new LinearLayout(this);
        rightSidebar.setOrientation(LinearLayout.VERTICAL);
        rightSidebar.setBackgroundColor(colorSurface);
        rightSidebar.setElevation(dp(12));
        rightSidebar.setVisibility(View.GONE);
        browserContainer.addView(rightSidebar, sidebarLayoutParams(Gravity.END));
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

    private FrameLayout.LayoutParams sidebarLayoutParams(int gravity) {
        int screenWidth = getResources().getDisplayMetrics().widthPixels;
        int width = isTvVersion()
                ? Math.max(dp(360), Math.min(dp(520), screenWidth - dp(96)))
                : Math.max(dp(240), Math.min(dp(340), screenWidth - dp(48)));
        return new FrameLayout.LayoutParams(
                width, FrameLayout.LayoutParams.MATCH_PARENT, gravity);
    }

    private LinearLayout.LayoutParams headerButtonParams() {
        return new LinearLayout.LayoutParams(
                isTvVersion() ? -2 : dp(42), dp(isTvVersion() ? 54 : 42));
    }

    private boolean isTvVersion() {
        return BuildConfig.IS_TV;
    }

    private void toggleSidePanel(int side) {
        if (openSidebar == side) {
            closeSidePanel();
            return;
        }
        openSidebar = side;
        sidebarScrim.setVisibility(View.VISIBLE);
        sidebarScrim.setAlpha(0f);
        sidebarScrim.animate().alpha(1f).setDuration(160).start();
        if (side == 1) {
            rightSidebar.setVisibility(View.GONE);
            leftSidebar.setVisibility(View.VISIBLE);
            populateNavigationSidebar();
            int width = ((FrameLayout.LayoutParams) leftSidebar.getLayoutParams()).width;
            leftSidebar.setTranslationX(-width - dp(16));
            leftSidebar.animate().translationX(0).setDuration(180).start();
            if (isTvVersion()) {
                leftSidebar.post(() -> leftSidebar.getChildAt(2).requestFocus());
            }
        } else {
            leftSidebar.setVisibility(View.GONE);
            rightSidebar.setVisibility(View.VISIBLE);
            populateToolsSidebar();
            int width = ((FrameLayout.LayoutParams) rightSidebar.getLayoutParams()).width;
            rightSidebar.setTranslationX(width + dp(16));
            rightSidebar.animate().translationX(0).setDuration(180).start();
            if (isTvVersion()) {
                rightSidebar.post(() -> {
                    if (rightSidebar.getChildAt(1) instanceof ScrollView) {
                        ScrollView scroll = (ScrollView) rightSidebar.getChildAt(1);
                        if (scroll.getChildAt(0) instanceof LinearLayout) {
                            ((LinearLayout) scroll.getChildAt(0)).getChildAt(0).requestFocus();
                        }
                    }
                });
            }
        }
    }

    private void closeSidePanel() {
        int closedSide = openSidebar;
        openSidebar = 0;
        sidebarScrim.setVisibility(View.GONE);
        if (leftSidebar != null) {
            leftSidebar.setVisibility(View.GONE);
        }
        if (rightSidebar != null) {
            rightSidebar.setVisibility(View.GONE);
        }
        if (isTvVersion()) {
            Button focusTarget = closedSide == 1 ? navigationPanelButton : settingsButton;
            if (focusTarget != null) {
                focusTarget.requestFocus();
            }
        }
    }

    private void populateNavigationSidebar() {
        leftSidebar.removeAllViews();
        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setPadding(dp(16), dp(12), dp(10), dp(8));
        TextView title = new TextView(this);
        title.setText("NAVEGACIÓN");
        title.setTextColor(colorAccent);
        title.setTextSize(13);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setLetterSpacing(0.08f);
        heading.addView(title, new LinearLayout.LayoutParams(0, dp(36), 1));
        heading.addView(button("×", this::closeSidePanel),
                new LinearLayout.LayoutParams(dp(40), dp(40)));
        leftSidebar.addView(heading);

        TextView count = new TextView(this);
        count.setText("Pestañas abiertas · " + tabs.size());
        count.setTextColor(colorMuted);
        count.setTextSize(12);
        count.setPadding(dp(16), dp(8), dp(16), dp(4));
        leftSidebar.addView(count);
        tabCountView = count;
        leftSidebar.addView(button("+  Nueva pestaña", this::newTab), settingButtonParams());
        leftSidebar.addView(button("⌂  Inicio", () -> {
            closeSidePanel();
            navigate(homeUrl);
        }), settingButtonParams());

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(10), 0, dp(10), dp(14));
        addSidebarSection(content, "PÁGINAS");
        for (BrowserTab tab : new ArrayList<>(tabs)) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            Button open = button(
                    (tab == activeTab ? "●  " : "") + sidebarTabTitle(tab),
                    () -> {
                        activateTab(tab);
                        closeSidePanel();
                    });
            open.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            open.setMaxLines(2);
            row.addView(open, new LinearLayout.LayoutParams(0, dp(48), 1));
            row.addView(button("×", () -> closeTab(tab)),
                    new LinearLayout.LayoutParams(dp(40), dp(42)));
            content.addView(row);
        }

        addSidebarSection(content, "FAVORITOS");
        if (favorites.isEmpty()) {
            addSidebarHint(content, "Guarda una página con ☆ en la barra.");
        } else {
            for (String favorite : new ArrayList<>(favorites)) {
                Button open = button(favoriteLabel(favorite), () -> {
                    closeSidePanel();
                    navigate(favorite);
                });
                open.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
                open.setMaxLines(2);
                content.addView(open, settingButtonParams());
            }
        }
        content.addView(button("Historial", () -> {
            closeSidePanel();
            showHistory();
        }), settingButtonParams());
        scroll.addView(content);
        leftSidebar.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    }

    private void populateToolsSidebar() {
        rightSidebar.removeAllViews();
        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setPadding(dp(16), dp(12), dp(10), dp(8));
        TextView title = new TextView(this);
        title.setText("CENTRO DE CONTROL");
        title.setTextColor(colorAccent);
        title.setTextSize(13);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setLetterSpacing(0.08f);
        heading.addView(title, new LinearLayout.LayoutParams(0, dp(36), 1));
        heading.addView(button("×", this::closeSidePanel),
                new LinearLayout.LayoutParams(dp(40), dp(40)));
        rightSidebar.addView(heading);

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(12), 0, dp(12), dp(16));
        content.addView(button("Descargas", () -> {
            closeSidePanel();
            showDownloads();
        }), settingButtonParams());
        content.addView(button("Ajustes avanzados", () -> {
            closeSidePanel();
            showSettings();
        }), settingButtonParams());
        addSidebarSection(content, "DESCARGAS EN LOTE");
        sidebarImageBatchButton = button(
                "Imágenes de esta página · 0", this::showBulkImagePicker);
        sidebarImageBatchButton.setEnabled(false);
        content.addView(sidebarImageBatchButton, settingButtonParams());
        sidebarVideoBatchButton = button(
                "Descargas para después · " + pendingMedia.size(),
                this::showBulkVideoPicker);
        content.addView(sidebarVideoBatchButton, settingButtonParams());
        sidebarMediaLibraryButton = button(
                "Biblioteca multimedia · " + allDetectedVideos().size(),
                this::showDetectedMedia);
        content.addView(sidebarMediaLibraryButton, settingButtonParams());
        addSidebarSection(content, "ADBLOCKER");
        rightAdBlockSummary = new TextView(this);
        rightAdBlockSummary.setTextColor(colorMuted);
        rightAdBlockSummary.setTextSize(12);
        rightAdBlockSummary.setPadding(dp(8), dp(8), dp(8), dp(8));
        content.addView(rightAdBlockSummary);

        sidebarProtectionSwitch = sidebarSwitch(
                "Bloqueo de anuncios · listas",
                adBlockEngine != null && adBlockEngine.isEnabled());
        sidebarProtectionSwitch.setOnCheckedChangeListener(
                (view, checked) -> adBlockEngine.setEnabled(checked));
        content.addView(sidebarProtectionSwitch);

        sidebarPopupSwitch = sidebarSwitch(
                "Bloquear ventanas emergentes no solicitadas", blockPopups);
        sidebarPopupSwitch.setOnCheckedChangeListener((view, checked) -> setPopupBlocking(checked));
        content.addView(sidebarPopupSwitch);

        sidebarRedirectSwitch = sidebarSwitch(
                "Bloquear toda redirección tras clic", blockClickRedirects);
        sidebarRedirectSwitch.setOnCheckedChangeListener(
                (view, checked) -> setClickRedirectBlocking(checked));
        content.addView(sidebarRedirectSwitch);

        sidebarMediaDetectionSwitch = sidebarSwitch(
                "Detector multimedia",
                adBlockEngine != null && adBlockEngine.isMediaDetectionEnabled());
        sidebarMediaDetectionSwitch.setOnCheckedChangeListener((view, checked) -> {
            adBlockEngine.setMediaDetectionEnabled(checked);
            updateDetectedMediaButton();
        });
        content.addView(sidebarMediaDetectionSwitch);

        sidebarExceptionButton = button(
                "Excepción para este sitio", this::toggleCurrentSiteException);
        content.addView(sidebarExceptionButton, settingButtonParams());
        sidebarUpdateButton = button("Actualizar listas", this::confirmFilterUpdate);
        content.addView(sidebarUpdateButton, settingButtonParams());
        addSidebarHint(content, "Las reglas usan listas de hosts; no aplican filtros cosméticos.");
        scroll.addView(content);
        rightSidebar.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        refreshProtectionControls();
    }

    private void showBulkImagePicker() {
        List<AdBlockEngine.PageImageCandidate> candidates =
                new ArrayList<>(pageImageCandidates.subList(
                        0, Math.min(MAX_BULK_ITEMS, pageImageCandidates.size())));
        if (candidates.isEmpty() || !isCurrentPage(pageImagePageUrl)) {
            Toast.makeText(this, "No hay imágenes de esta página listas para descargar.",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        String[] labels = new String[candidates.size()];
        boolean[] selected = new boolean[candidates.size()];
        for (int index = 0; index < candidates.size(); index++) {
            AdBlockEngine.PageImageCandidate image = candidates.get(index);
            String label = image.label.isBlank() ? "Imagen " + (index + 1) : image.label;
            String dimensions = image.width > 0 && image.height > 0
                    ? " · " + image.width + "×" + image.height : "";
            labels[index] = label + dimensions;
            selected[index] = true;
        }
        dialogBuilder()
                .setTitle("Imágenes · hasta " + MAX_BULK_ITEMS + " de mayor resolución")
                .setMultiChoiceItems(labels, selected,
                        (dialog, which, checked) -> selected[which] = checked)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Descargar seleccionadas", (dialog, which) -> {
                    List<AdBlockEngine.PageImageCandidate> chosen = new ArrayList<>();
                    for (int index = 0; index < selected.length; index++) {
                        if (selected[index]) {
                            chosen.add(candidates.get(index));
                        }
                    }
                    if (chosen.isEmpty()) {
                        Toast.makeText(this, "Selecciona al menos una imagen.",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    new AlertDialog.Builder(this)
                            .setTitle("Confirmar descarga")
                            .setMessage("Se descargarán " + chosen.size()
                                    + " imágenes de la página actual. "
                                    + "Solo se usan las direcciones HTTP(S) visibles en la página.")
                            .setNegativeButton("Cancelar", null)
                            .setPositiveButton("Descargar", (confirm, button) ->
                                    startBulkImageDownloads(
                                            pageImagePageUrl, pageImagePageTitle, chosen))
                            .show();
                })
                .show();
    }

    private void startBulkImageDownloads(
            String pageUrl,
            String pageTitle,
            List<AdBlockEngine.PageImageCandidate> images) {
        int started = 0;
        int skipped = 0;
        int index = 0;
        for (AdBlockEngine.PageImageCandidate image : images) {
            if (hasCompletedDownload(image.url, "")) {
                skipped++;
                continue;
            }
            String filename = image.label.isBlank()
                    ? (pageTitle.isBlank() ? "image" : pageTitle) + "-" + (++index)
                    : image.label;
            DownloadEntry entry = new DownloadEntry();
            entry.name = filename;
            entry.mimeType = "image/*";
            entry.url = image.url;
            entry.engine = "image-extractor";
            entry.status = "En cola · imagen";
            addDownloadEntry(entry);
            String taskId = videoDownloadManager.downloadImageFromUrl(
                    image.url, pageUrl, filename, getCustomDownloadFolder(),
                    new VideoDownloadManager.Listener() {
                        @Override
                        public void onProgress(long bytes) {
                            entry.bytes = bytes;
                            entry.status = "Descargando imagen · " + formatBytes(bytes);
                            persistDownloadIfDue(entry, false);
                            refreshDownloadsDialog();
                        }

                        @Override
                        public void onComplete(String location, String savedFilename, long bytes) {
                            entry.processId = null;
                            entry.location = location;
                            entry.name = savedFilename;
                            entry.mimeType = VideoDownloadManager.normalizedMediaMimeType(
                                    savedFilename);
                            entry.bytes = bytes;
                            entry.status = "Completada · " + formatBytes(bytes);
                            recordDownloadedLink(entry);
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
            started++;
        }
        Toast.makeText(this,
                "Imágenes en cola: " + started + (skipped > 0
                        ? " · ya descargadas omitidas: " + skipped : ""),
                Toast.LENGTH_LONG).show();
    }

    private void showBulkVideoPicker() {
        if (bulkVideoBatchRunning) {
            Toast.makeText(this, "Ya hay una cola de videos procesándose.",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (privateMode) {
            Toast.makeText(this, "La lista para después no está disponible en modo privado.",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        List<DetectedMedia> candidates = new ArrayList<>(pendingMedia.values());
        if (candidates.isEmpty()) {
            Toast.makeText(this, "Añade videos desde la biblioteca para guardarlos aquí.",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (candidates.size() > MAX_BULK_ITEMS) {
            candidates = new ArrayList<>(candidates.subList(0, MAX_BULK_ITEMS));
        }
        List<DetectedMedia> selectedCandidates = candidates;
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(8), dp(4), dp(8), dp(4));
        TextView instructions = new TextView(this);
        instructions.setText("Enlaces guardados para descargar más tarde. "
                + "yt-dlp analizará metadatos antes de descargar.");
        instructions.setTextColor(colorMuted);
        instructions.setPadding(dp(8), dp(4), dp(8), dp(8));
        content.addView(instructions);
        LinearLayout selectionActions = new LinearLayout(this);
        Button selectAll = button("Todos", () -> { });
        Button selectNone = button("Ninguno", () -> { });
        Button removeSelected = button("Quitar", () -> { });
        selectionActions.addView(selectAll, new LinearLayout.LayoutParams(0,
                dp(44), 1));
        selectionActions.addView(selectNone, new LinearLayout.LayoutParams(0,
                dp(44), 1));
        selectionActions.addView(removeSelected, new LinearLayout.LayoutParams(0,
                dp(44), 1));
        content.addView(selectionActions);
        ScrollView scroll = new ScrollView(this);
        LinearLayout choices = new LinearLayout(this);
        choices.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(choices);
        List<CheckBox> checkBoxes = new ArrayList<>();
        for (DetectedMedia media : candidates) {
            CheckBox checkBox = new CheckBox(this);
            String title = titleForDownload(media.title, media.pageTitle);
            String resolution = media.videoHeight > 0
                    ? media.videoHeight + "p" : "calidad pendiente";
            String duration = media.durationText == null || media.durationText.isEmpty()
                    ? "" : " · " + media.durationText;
            checkBox.setText(title + " · " + resolution + duration);
            checkBox.setTextColor(colorText);
            checkBox.setChecked(true);
            checkBox.setPadding(dp(8), dp(4), dp(8), dp(4));
            choices.addView(checkBox);
            checkBoxes.add(checkBox);
        }
        content.addView(scroll, new LinearLayout.LayoutParams(-1, dp(320)));
        AlertDialog picker = dialogBuilder()
                .setTitle("Descargas para después · " + candidates.size())
                .setView(content)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Analizar selección", null)
                .create();
        selectAll.setOnClickListener(view -> {
            for (CheckBox checkBox : checkBoxes) {
                checkBox.setChecked(true);
            }
        });
        selectNone.setOnClickListener(view -> {
            for (CheckBox checkBox : checkBoxes) {
                checkBox.setChecked(false);
            }
        });
        removeSelected.setOnClickListener(view -> {
            for (int index = 0; index < checkBoxes.size(); index++) {
                if (checkBoxes.get(index).isChecked()) {
                    pendingMedia.remove(selectedCandidates.get(index).key);
                }
            }
            savePendingMedia();
            updateBulkVideoButton();
            picker.dismiss();
        });
        picker.setOnShowListener(dialog -> picker.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    bulkVideoAnalysisQueue.clear();
                    for (int index = 0; index < checkBoxes.size(); index++) {
                        if (checkBoxes.get(index).isChecked()) {
                            bulkVideoAnalysisQueue.add(selectedCandidates.get(index));
                        }
                    }
                    if (bulkVideoAnalysisQueue.isEmpty()) {
                        Toast.makeText(this, "Selecciona al menos un video.",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    picker.dismiss();
                    bulkVideoAnalysisIndex = 0;
                    analyzeNextBulkVideo();
                }));
        picker.show();
    }

    private void analyzeNextBulkVideo() {
        if (bulkVideoAnalysisIndex >= bulkVideoAnalysisQueue.size()) {
            showBulkVideoReview();
            return;
        }
        DetectedMedia media = bulkVideoAnalysisQueue.get(bulkVideoAnalysisIndex++);
        setDetectedMediaStatus(media, "Analizando metadatos…", false);
        inspectWithYtDlp(media.url, media, () -> {
            if (isFinishing()) {
                return;
            }
            analyzeNextBulkVideo();
        });
    }

    private void showBulkVideoReview() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(8), dp(4), dp(8), dp(4));
        TextView info = new TextView(this);
        info.setText("Revisa metadatos y miniaturas. La cola admite hasta 3 descargas simultáneas.");
        info.setTextColor(colorMuted);
        info.setPadding(dp(8), dp(4), dp(8), dp(8));
        content.addView(info);
        Button qualityButton = button(bulkVideoQualityLabel(), () -> { });
        qualityButton.setOnClickListener(
                view -> showBulkVideoQualityPicker(qualityButton));
        content.addView(qualityButton, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        for (DetectedMedia item : bulkVideoAnalysisQueue) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(8), dp(8), dp(8), dp(8));
            ImageView thumbnail = new ImageView(this);
            thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumbnail.setBackground(rippleBackground(colorBackground, colorBorder, 8));
            row.addView(thumbnail, new LinearLayout.LayoutParams(dp(96), dp(54)));
            if (item.thumbnailUrl != null) {
                loadThumbnail(thumbnail, item.thumbnailUrl);
            } else {
                thumbnail.setImageResource(android.R.drawable.ic_media_play);
            }
            TextView details = new TextView(this);
            String resolution = highestVideoQuality(item);
            String duration = item.durationText == null || item.durationText.isEmpty()
                    ? "Duración no disponible" : item.durationText;
            String title = titleForDownload(item.title, item.pageTitle);
            String qualities = availableVideoQualities(item);
            String sourceHost = Uri.parse(item.pageUrl).getHost();
            details.setText(title + "\n" + resolution + " · " + duration
                    + "\nCalidades: " + qualities
                    + "\n" + (sourceHost == null ? item.type : sourceHost)
                    + " · " + item.formats.size() + " formatos"
                    + (item.formats.isEmpty() ? " · automática" : ""));
            details.setTextColor(colorText);
            details.setTextSize(13);
            details.setMaxLines(3);
            details.setEllipsize(android.text.TextUtils.TruncateAt.END);
            details.setPadding(dp(10), 0, 0, 0);
            row.addView(details, new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            list.addView(row);
        }
        content.addView(scroll, new LinearLayout.LayoutParams(-1, dp(320)));
        dialogBuilder()
                .setTitle("Revisar selección · " + bulkVideoAnalysisQueue.size())
                .setView(content)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Descargar seleccionados", (dialog, which) -> {
                    bulkVideoQueue.clear();
                    bulkVideoQueue.addAll(bulkVideoAnalysisQueue);
                    bulkVideoBatchRunning = true;
                    bulkVideoActiveDownloads = 0;
                    if (sidebarVideoBatchButton != null) {
                        sidebarVideoBatchButton.setEnabled(false);
                    }
                    downloadNextBulkVideo();
                })
                .show();
    }

    private String highestVideoQuality(DetectedMedia item) {
        int height = item.videoHeight;
        for (YtDlpFormatOption format : item.formats) {
            if (!format.getAudioOnly()) {
                height = Math.max(height, format.getHeight());
            }
        }
        return height > 0 ? height + "p" : "Resolución no disponible";
    }

    private String availableVideoQualities(DetectedMedia item) {
        List<Integer> heights = new ArrayList<>();
        for (YtDlpFormatOption format : item.formats) {
            if (!format.getAudioOnly() && format.getHeight() > 0
                    && !heights.contains(format.getHeight())) {
                heights.add(format.getHeight());
            }
            if (heights.size() == 5) {
                break;
            }
        }
        if (heights.isEmpty()) {
            return item.videoHeight > 0 ? item.videoHeight + "p" : "no disponibles";
        }
        List<String> labels = new ArrayList<>();
        for (Integer height : heights) {
            labels.add(height + "p");
        }
        return String.join(", ", labels);
    }

    private String bulkVideoQualityLabel() {
        return bulkVideoMaximumHeight == 0 ? "Calidad del lote · máxima disponible"
                : "Calidad del lote · hasta " + bulkVideoMaximumHeight + "p";
    }

    private void showBulkVideoQualityPicker(Button qualityButton) {
        String[] labels = {"Máxima disponible", "Hasta 1080p", "Hasta 720p"};
        int[] heights = {0, 1080, 720};
        int selected = bulkVideoMaximumHeight == 1080 ? 1
                : bulkVideoMaximumHeight == 720 ? 2 : 0;
        dialogBuilder()
                .setTitle("Calidad para el lote")
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    bulkVideoMaximumHeight = heights[which];
                    qualityButton.setText(bulkVideoQualityLabel());
                    dialog.dismiss();
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private String bulkVideoFormatSelector(DetectedMedia item) {
        YtDlpFormatOption selected = null;
        for (YtDlpFormatOption format : item.formats) {
            if (format.getAudioOnly()) {
                continue;
            }
            if (bulkVideoMaximumHeight == 0) {
                return format.getSelector();
            }
            if (format.getHeight() > 0 && format.getHeight() <= bulkVideoMaximumHeight
                    && (selected == null || format.getHeight() > selected.getHeight())) {
                selected = format;
            }
        }
        if (selected != null) {
            return selected.getSelector();
        }
        return bulkVideoMaximumHeight == 0 ? "bestvideo+bestaudio/best"
                : "bestvideo[height<=" + bulkVideoMaximumHeight
                        + "]+bestaudio/best[height<=" + bulkVideoMaximumHeight + "]";
    }

    private void downloadNextBulkVideo() {
        while (bulkVideoBatchRunning && bulkVideoActiveDownloads < 3
                && !bulkVideoQueue.isEmpty()) {
            DetectedMedia media = bulkVideoQueue.pollFirst();
            bulkVideoActiveDownloads++;
            startYtDlpDownload(
                    media.url,
                    titleForDownload(media.title, media.pageTitle),
                    bulkVideoFormatSelector(media),
                    false,
                    subtitlesByDefault,
                    media,
                    () -> {
                        bulkVideoActiveDownloads = Math.max(0, bulkVideoActiveDownloads - 1);
                        downloadNextBulkVideo();
                    });
        }
        if (bulkVideoBatchRunning && bulkVideoQueue.isEmpty()
                        && bulkVideoActiveDownloads == 0) {
            bulkVideoBatchRunning = false;
            bulkVideoAnalysisQueue.clear();
            if (sidebarVideoBatchButton != null) {
                sidebarVideoBatchButton.setEnabled(true);
            }
            Toast.makeText(this, "La cola de videos terminó.", Toast.LENGTH_LONG).show();
        }
    }

    private Switch sidebarSwitch(String label, boolean checked) {
        Switch control = new Switch(this);
        control.setText(label);
        control.setTextColor(colorText);
        control.setChecked(checked);
        return control;
    }

    private void addSidebarSection(LinearLayout container, String label) {
        TextView section = new TextView(this);
        section.setText(label);
        section.setTextColor(colorAccent);
        section.setTextSize(11);
        section.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        section.setLetterSpacing(0.1f);
        section.setPadding(dp(8), dp(16), dp(8), dp(4));
        container.addView(section);
    }

    private void addSidebarHint(LinearLayout container, String text) {
        TextView hint = new TextView(this);
        hint.setText(text);
        hint.setTextColor(colorMuted);
        hint.setTextSize(12);
        hint.setPadding(dp(8), dp(6), dp(8), dp(8));
        container.addView(hint);
    }

    private String sidebarTabTitle(BrowserTab tab) {
        String title = tab.title == null || tab.title.isBlank()
                || "Nueva pestaña".equals(tab.title)
                ? favoriteLabel(tab.url) : tab.title;
        return title.length() > 42 ? title.substring(0, 39) + "…" : title;
    }

    private String favoriteLabel(String url) {
        Uri uri = Uri.parse(url);
        String host = uri.getHost();
        String path = uri.getPath();
        return (host == null ? url : host) + (path == null || "/".equals(path) ? "" : path);
    }

    private Button button(String label, Runnable action) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(isTvVersion() ? 16 : 12);
        button.setTextColor(colorText);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinimumHeight(dp(isTvVersion() ? 54 : 42));
        button.setPadding(dp(isTvVersion() ? 18 : 12), 0,
                dp(isTvVersion() ? 18 : 12), 0);
        button.setBackground(rippleBackground(colorSurface, colorBorder, 14));
        button.setFocusable(true);
        button.setFocusableInTouchMode(!isTvVersion());
        if (isTvVersion()) {
            button.setOnFocusChangeListener((view, focused) -> button.setBackground(
                    rippleBackground(
                            focused ? colorRipple : colorSurface,
                            focused ? colorAccent : colorBorder,
                            14)));
        }
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private void applyThemeColors() {
        darkTheme = !"light".equals(visualTheme);
        switch (visualTheme) {
            case "cyber_cyan":
                colorBackground = Color.rgb(6, 12, 19);
                colorSurface = Color.rgb(13, 23, 34);
                colorText = Color.rgb(226, 244, 249);
                colorMuted = Color.rgb(136, 166, 177);
                colorBorder = Color.rgb(31, 63, 76);
                colorAccent = Color.rgb(0, 225, 255);
                colorRipple = Color.rgb(20, 52, 65);
                break;
            case "violet":
                colorBackground = Color.rgb(13, 9, 20);
                colorSurface = Color.rgb(25, 17, 36);
                colorText = Color.rgb(244, 235, 255);
                colorMuted = Color.rgb(172, 151, 194);
                colorBorder = Color.rgb(62, 42, 84);
                colorAccent = Color.rgb(201, 111, 255);
                colorRipple = Color.rgb(52, 35, 69);
                break;
            case "ocean":
                colorBackground = Color.rgb(7, 13, 22);
                colorSurface = Color.rgb(16, 25, 39);
                colorText = Color.rgb(230, 239, 250);
                colorMuted = Color.rgb(147, 167, 193);
                colorBorder = Color.rgb(42, 61, 89);
                colorAccent = Color.rgb(77, 158, 255);
                colorRipple = Color.rgb(34, 51, 77);
                break;
            case "light":
                colorBackground = Color.rgb(246, 248, 250);
                colorSurface = Color.WHITE;
                colorText = Color.rgb(24, 31, 40);
                colorMuted = Color.rgb(103, 113, 127);
                colorBorder = Color.rgb(220, 226, 234);
                colorAccent = Color.rgb(230, 97, 30);
                colorRipple = Color.rgb(240, 232, 227);
                break;
            case "dark_green":
                colorBackground = Color.rgb(17, 24, 20);
                colorSurface = Color.rgb(25, 34, 29);
                colorText = Color.rgb(232, 240, 235);
                colorMuted = Color.rgb(160, 174, 165);
                colorBorder = Color.rgb(49, 64, 55);
                colorAccent = Color.rgb(112, 199, 156);
                colorRipple = Color.rgb(48, 65, 55);
                break;
            default:
                visualTheme = "neon_orange";
                colorBackground = Color.rgb(10, 11, 14);
                colorSurface = Color.rgb(20, 21, 26);
                colorText = Color.rgb(246, 241, 235);
                colorMuted = Color.rgb(164, 155, 148);
                colorBorder = Color.rgb(61, 47, 39);
                colorAccent = Color.rgb(255, 112, 32);
                colorRipple = Color.rgb(64, 38, 24);
        }
        getWindow().setStatusBarColor(colorBackground);
        getWindow().setNavigationBarColor(colorSurface);
        getWindow().getDecorView().setSystemUiVisibility(darkTheme ? 0
                : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    }

    private String themeButtonLabel() {
        return "Tema visual · " + themeName(visualTheme);
    }

    private String themeName(String theme) {
        switch (theme) {
            case "cyber_cyan":
                return "Cyber cian";
            case "violet":
                return "Violeta neón";
            case "ocean":
                return "Océano azul";
            case "light":
                return "Claro";
            case "dark_green":
                return "Verde oscuro";
            default:
                return "Negro / naranja";
        }
    }

    private void showThemeSelector() {
        String[] keys = {"neon_orange", "cyber_cyan", "violet", "ocean", "light"};
        String[] names = {
                "Negro / naranja neón",
                "Cyber cian",
                "Violeta neón",
                "Océano azul",
                "Claro"
        };
        dialogBuilder().setTitle("Tema visual")
                .setItems(names, (dialog, which) -> {
                    if (settingsDialog != null) {
                        settingsDialog.dismiss();
                    }
                    applyVisualTheme(keys[which]);
                    mainHandler.post(this::showSettings);
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void showSearchEngineSelector() {
        String[] engines = {"duckduckgo", "google", "bing", "brave", "ecosia"};
        String[] labels = {"DuckDuckGo", "Google", "Bing", "Brave Search", "Ecosia"};
        int selected = 0;
        for (int index = 0; index < engines.length; index++) {
            if (engines[index].equals(searchEngine)) {
                selected = index;
                break;
            }
        }
        dialogBuilder().setTitle("Motor de búsqueda")
                .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                    searchEngine = engines[which];
                    getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                            .putString(SEARCH_ENGINE_KEY, searchEngine)
                            .apply();
                    dialog.dismiss();
                    if (settingsDialog != null && settingsDialog.isShowing()) {
                        settingsDialog.dismiss();
                        showSettings();
                    }
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void promptHomePage() {
        EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        field.setText(homeUrl);
        field.setSelectAllOnFocus(true);
        dialogBuilder().setTitle("Página de inicio")
                .setView(field)
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Guardar", (dialog, which) -> {
                    String value = field.getText().toString().trim();
                    if (!isHttpUrl(value)) {
                        Toast.makeText(this, "Introduce una dirección HTTP o HTTPS válida.",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    homeUrl = value;
                    getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                            .putString(HOME_PAGE_KEY, homeUrl)
                            .apply();
                    if (settingsDialog != null && settingsDialog.isShowing()) {
                        settingsDialog.dismiss();
                        showSettings();
                    }
                })
                .show();
    }

    private void setPopupBlocking(boolean enabled) {
        blockPopups = enabled;
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                .putBoolean(BLOCK_POPUPS_KEY, enabled)
                .apply();
        refreshProtectionControls();
    }

    private void setClickRedirectBlocking(boolean enabled) {
        blockClickRedirects = enabled;
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                .putBoolean(BLOCK_CLICK_REDIRECTS_KEY, enabled)
                .apply();
        refreshProtectionControls();
    }

    private String searchEngineName(String engine) {
        switch (engine) {
            case "google":
                return "Google";
            case "bing":
                return "Bing";
            case "brave":
                return "Brave Search";
            case "ecosia":
                return "Ecosia";
            default:
                return "DuckDuckGo";
        }
    }

    private boolean isSupportedTheme(String theme) {
        return "neon_orange".equals(theme) || "cyber_cyan".equals(theme)
                || "violet".equals(theme) || "ocean".equals(theme)
                || "light".equals(theme) || "dark_green".equals(theme);
    }

    private boolean isSupportedSearchEngine(String engine) {
        return "duckduckgo".equals(engine) || "google".equals(engine)
                || "bing".equals(engine) || "brave".equals(engine)
                || "ecosia".equals(engine);
    }

    private void applyVisualTheme(String theme) {
        String previousStatusMessage = statusMessage;
        visualTheme = theme;
        darkTheme = !"light".equals(theme);
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                .putBoolean(THEME_KEY, darkTheme)
                .putString(VISUAL_THEME_KEY, theme)
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
        tab.url = homeUrl;
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
                    } else if (!blockPopups) {
                        tab.pendingPopupUrl = request.uri;
                        tab.pendingPopupHasGesture = true;
                    } else {
                        tab.pendingPopupUrl = null;
                        tab.pendingPopupHasGesture = false;
                        return GeckoResult.fromValue(AllowOrDeny.DENY);
                    }
                }
                if (request.isRedirect) {
                    if (blockClickRedirects && tab.redirectTriggeredByUserGesture) {
                        tab.redirectTriggeredByUserGesture = false;
                        tab.pendingPopupUrl = null;
                        tab.pendingPopupHasGesture = false;
                        runOnUiThread(() -> Toast.makeText(MainActivity.this,
                                "Redirección detenida por la protección tras clic.",
                                Toast.LENGTH_LONG).show());
                        return GeckoResult.fromValue(AllowOrDeny.DENY);
                    }
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
                    tab.redirectTriggeredByUserGesture = request.hasUserGesture;
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
                runOnUiThread(() -> {
                    if (!isFinishing() && tabs.contains(popupTab)) {
                        activateTab(popupTab);
                        Toast.makeText(MainActivity.this,
                                "Se abrió una pestaña nueva. Comprueba el dominio antes de iniciar sesión.",
                                Toast.LENGTH_LONG).show();
                    }
                });
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
                            selectPageImages(url);
                            addressField.setText(url);
                            updateTabCount();
                            updateFavoriteButton();
                            updateDetectedMediaButton();
                            updateMediaFabSiteButton();
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
                if (success) {
                    tab.redirectTriggeredByUserGesture = false;
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
        selectPageImages(currentUrl);
        updateFavoriteButton();
        updatePrivateButton();
        updateDetectedMediaButton();
        updateBulkVideoButton();
        updateMediaFabSiteButton();
        updateTabCount();
    }

    private void newTab() {
        closeSidePanel();
        activateTab(createSession());
        navigate(homeUrl);
        addressField.requestFocus();
    }

    private void closeTab(BrowserTab tab) {
        if (tabs.size() == 1) {
            navigate(homeUrl);
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
        if (openSidebar == 1) {
            populateNavigationSidebar();
        }
    }

    private void addDetectedMedia(
            String url,
            String pageUrl,
            String mediaType,
            String detectedPageTitle,
            String thumbnailUrl,
            int videoWidth,
            int videoHeight,
            int durationSeconds) {
        if (privateMode || url == null || url.length() > 8192
                || pageUrl == null || pageUrl.length() > 8192
                || !isHttpUrl(url) || !isHttpUrl(pageUrl)
                || !("HLS".equals(mediaType) || "DASH".equals(mediaType)
                || "Video".equals(mediaType))) {
            return;
        }
        String key = normalizeMediaUrl(url);
        DetectedMedia item = detectedMedia.get(key);
        if (item == null) {
            if ("Video".equals(mediaType) && videoHeight > 0
                    && videoHeight < minimumMediaHeight) {
                return;
            }
            String title = detectedPageTitle == null || detectedPageTitle.isBlank()
                    ? pageTitleForUrl(pageUrl) : detectedPageTitle.trim();
            item = new DetectedMedia(
                    key,
                    url,
                    pageUrl,
                    title,
                    mediaType,
                    isHttpUrl(thumbnailUrl) ? thumbnailUrl : null,
                    videoWidth,
                    videoHeight,
                    durationSeconds);
            item.includeSubtitles = subtitlesByDefault;
            detectedMedia.put(key, item);
        } else {
            if (detectedPageTitle != null && !detectedPageTitle.isBlank()) {
                item.pageTitle = detectedPageTitle.trim();
            }
            if (isHttpUrl(thumbnailUrl)) {
                item.thumbnailUrl = thumbnailUrl;
            }
            if (videoWidth > 0) {
                item.videoWidth = videoWidth;
            }
            if (videoHeight > 0) {
                item.videoHeight = videoHeight;
            }
            if (durationSeconds > 0) {
                item.durationText = formatDuration(durationSeconds);
            }
            if ("Video".equals(item.type) && item.videoHeight > 0
                    && item.videoHeight < minimumMediaHeight) {
                detectedMedia.remove(item.key);
                selectedDetectedMediaKeys.remove(item.key);
                if (pendingMedia.remove(item.key) != null) {
                    savePendingMedia();
                }
                updateDetectedMediaButton();
                updateBulkVideoButton();
                renderDetectedMediaItems();
                return;
            }
        }
        if (pendingMedia.containsKey(item.key)) {
            savePendingMedia();
        }
        if (detectedMedia.size() > 100) {
            String oldest = detectedMedia.keySet().iterator().next();
            detectedMedia.remove(oldest);
        }
        updateDetectedMediaButton();
        updateBulkVideoButton();
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
        if (adBlockEngine != null && !adBlockEngine.isMediaDetectionEnabled()) {
            mediaFab.setText("Medios · 0");
            mediaFab.setVisibility(View.GONE);
            return;
        }
        String currentHost = Uri.parse(currentUrl).getHost();
        if (!mediaFabEnabled || isMediaFabHiddenForHost(currentHost)) {
            mediaFab.setVisibility(View.GONE);
            return;
        }
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

    private void pruneLowQualityMedia() {
        List<String> removedKeys = new ArrayList<>();
        for (DetectedMedia item : detectedMedia.values()) {
            if ("Video".equals(item.type) && item.videoHeight > 0
                    && item.videoHeight < minimumMediaHeight) {
                removedKeys.add(item.key);
            }
        }
        boolean pendingChanged = false;
        for (String key : removedKeys) {
            detectedMedia.remove(key);
            selectedDetectedMediaKeys.remove(key);
            pendingChanged |= pendingMedia.remove(key) != null;
        }
        if (pendingChanged) {
            savePendingMedia();
        }
        updateDetectedMediaButton();
        updateBulkVideoButton();
    }

    private boolean isMediaFabHiddenForHost(String host) {
        if (host == null) {
            return false;
        }
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        for (String hiddenHost : mediaFabHiddenSites) {
            if (normalizedHost.equals(hiddenHost)
                    || normalizedHost.endsWith("." + hiddenHost)) {
                return true;
            }
        }
        return false;
    }

    private void toggleCurrentSiteMediaFabVisibility() {
        String host = Uri.parse(currentUrl).getHost();
        if (host == null) {
            Toast.makeText(this, "La página actual no tiene un sitio válido.",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        host = host.toLowerCase(Locale.ROOT);
        if (!mediaFabHiddenSites.remove(host)) {
            mediaFabHiddenSites.add(host);
        }
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                .putStringSet(MEDIA_FAB_HIDDEN_SITES_KEY, new HashSet<>(mediaFabHiddenSites))
                .apply();
        updateMediaFabSiteButton();
        updateDetectedMediaButton();
    }

    private void updateMediaFabSiteButton() {
        if (mediaFabSiteButton == null) {
            return;
        }
        String host = Uri.parse(currentUrl).getHost();
        boolean hidden = isMediaFabHiddenForHost(host);
        mediaFabSiteButton.setText(host == null
                ? "Botón flotante: sitio no válido"
                : (hidden ? "Mostrar botón en " : "Ocultar botón en ")
                        + host.toLowerCase(Locale.ROOT));
        mediaFabSiteButton.setEnabled(host != null);
    }

    private void updatePageImageCandidates(
            String pageUrl,
            String pageTitle,
            List<AdBlockEngine.PageImageCandidate> images) {
        if (privateMode || !isHttpUrl(pageUrl)) {
            return;
        }
        List<AdBlockEngine.PageImageCandidate> safeImages = new ArrayList<>();
        for (AdBlockEngine.PageImageCandidate image : images) {
            if (safeImages.size() >= MAX_PAGE_IMAGE_CANDIDATES) {
                break;
            }
            if (isHttpUrl(image.url)) {
                safeImages.add(image);
            }
        }
        String title = pageTitle == null ? "" : pageTitle;
        String key = pageImageKey(pageUrl);
        pageImagesByPage.remove(key);
        pageImagesByPage.put(key, new PageImageSet(pageUrl, title, safeImages));
        while (pageImagesByPage.size() > 20) {
            pageImagesByPage.remove(pageImagesByPage.keySet().iterator().next());
        }
        if (!isCurrentPage(pageUrl)) {
            return;
        }
        pageImagePageUrl = pageUrl;
        pageImagePageTitle = title;
        pageImageCandidates.clear();
        pageImageCandidates.addAll(safeImages);
        if (sidebarImageBatchButton != null) {
            sidebarImageBatchButton.setText(
                    "Imágenes de esta página · " + pageImageCandidates.size());
            sidebarImageBatchButton.setEnabled(!pageImageCandidates.isEmpty());
        }
    }

    private void clearPageImageCandidates() {
        pageImageCandidates.clear();
        pageImagePageUrl = "";
        pageImagePageTitle = "";
        if (sidebarImageBatchButton != null) {
            sidebarImageBatchButton.setText("Imágenes de esta página · 0");
            sidebarImageBatchButton.setEnabled(false);
        }
    }

    private void selectPageImages(String pageUrl) {
        clearPageImageCandidates();
        if (!isHttpUrl(pageUrl)) {
            return;
        }
        PageImageSet saved = pageImagesByPage.get(pageImageKey(pageUrl));
        if (saved == null) {
            return;
        }
        pageImagePageUrl = saved.pageUrl;
        pageImagePageTitle = saved.title;
        pageImageCandidates.addAll(saved.images);
        if (sidebarImageBatchButton != null) {
            sidebarImageBatchButton.setText(
                    "Imágenes de esta página · " + pageImageCandidates.size());
            sidebarImageBatchButton.setEnabled(!pageImageCandidates.isEmpty());
        }
    }

    private String pageImageKey(String pageUrl) {
        return Uri.parse(pageUrl).buildUpon().fragment(null).build().toString();
    }

    private void updateBulkVideoButton() {
        if (sidebarVideoBatchButton != null) {
            sidebarVideoBatchButton.setText(
                    "Descargas para después · " + pendingMedia.size());
        }
        if (sidebarMediaLibraryButton != null) {
            sidebarMediaLibraryButton.setText(
                    "Biblioteca multimedia · " + allDetectedVideos().size());
        }
    }

    private List<DetectedMedia> allDetectedVideos() {
        List<DetectedMedia> videos = new ArrayList<>();
        for (DetectedMedia item : detectedMedia.values()) {
            if ("Video".equals(item.type) || "HLS".equals(item.type)
                    || "DASH".equals(item.type)) {
                videos.add(item);
            }
        }
        return videos;
    }

    private void togglePendingMedia(DetectedMedia item) {
        if (privateMode) {
            Toast.makeText(this, "No se guardan enlaces desde el modo privado.",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        if (pendingMedia.containsKey(item.key)) {
            pendingMedia.remove(item.key);
            Toast.makeText(this, "Quitado de la lista para después.", Toast.LENGTH_SHORT).show();
        } else {
            if (pendingMedia.size() >= MAX_BULK_ITEMS) {
                Toast.makeText(this, "La lista admite hasta "
                        + MAX_BULK_ITEMS + " enlaces.", Toast.LENGTH_SHORT).show();
                return;
            }
            pendingMedia.put(item.key, item);
            Toast.makeText(this, "Guardado para descargar después.", Toast.LENGTH_SHORT).show();
        }
        savePendingMedia();
        updateBulkVideoButton();
        renderDetectedMediaItems();
    }

    private void removePendingMedia(DetectedMedia item) {
        if (item != null && pendingMedia.remove(item.key) != null) {
            savePendingMedia();
            updateBulkVideoButton();
        }
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

        LinearLayout selectionActions = new LinearLayout(this);
        selectionActions.setGravity(Gravity.CENTER_VERTICAL);
        selectionActions.addView(button("Seleccionar todos", () -> {
            for (CheckBox checkBox : detectedMediaSelectionViews.values()) {
                checkBox.setChecked(true);
            }
        }), new LinearLayout.LayoutParams(0, dp(44), 1));
        selectionActions.addView(button("Descargar", this::downloadSelectedDetectedMedia),
                new LinearLayout.LayoutParams(0, dp(44), 1));
        selectionActions.addView(button("Después", this::queueSelectedDetectedMedia),
                new LinearLayout.LayoutParams(0, dp(44), 1));
        selectionActions.addView(button("Lista TXT", this::exportSelectedMedia),
                new LinearLayout.LayoutParams(0, dp(44), 1));
        content.addView(selectionActions);

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
            detectedMediaSelectionViews.clear();
            selectedDetectedMediaKeys.clear();
        });
        detectedMediaDialog.show();
        renderDetectedMediaItems();
    }

    private List<DetectedMedia> selectedDetectedMedia() {
        List<DetectedMedia> selected = new ArrayList<>();
        for (DetectedMedia item : detectedMedia.values()) {
            if (selectedDetectedMediaKeys.contains(item.key)) {
                selected.add(item);
                if (selected.size() == MAX_BULK_ITEMS) {
                    break;
                }
            }
        }
        return selected;
    }

    private void downloadSelectedDetectedMedia() {
        List<DetectedMedia> selected = selectedDetectedMedia();
        if (selected.isEmpty()) {
            Toast.makeText(this, "Selecciona al menos un video.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (bulkVideoBatchRunning) {
            Toast.makeText(this, "Ya hay una cola de videos procesándose.",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        bulkVideoQueue.clear();
        bulkVideoQueue.addAll(selected);
        bulkVideoMaximumHeight = 0;
        bulkVideoBatchRunning = true;
        bulkVideoActiveDownloads = 0;
        if (sidebarVideoBatchButton != null) {
            sidebarVideoBatchButton.setEnabled(false);
        }
        downloadNextBulkVideo();
    }

    private void queueSelectedDetectedMedia() {
        if (privateMode) {
            Toast.makeText(this, "No se guardan enlaces desde el modo privado.",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        List<DetectedMedia> selected = selectedDetectedMedia();
        if (selected.isEmpty()) {
            Toast.makeText(this, "Selecciona al menos un video.", Toast.LENGTH_SHORT).show();
            return;
        }
        int added = 0;
        for (DetectedMedia item : selected) {
            if (pendingMedia.size() >= MAX_BULK_ITEMS) {
                break;
            }
            if (pendingMedia.putIfAbsent(item.key, item) == null) {
                added++;
            }
        }
        savePendingMedia();
        updateBulkVideoButton();
        Toast.makeText(this, "Añadidos para después: " + added, Toast.LENGTH_SHORT).show();
    }

    private void exportSelectedMedia() {
        List<DetectedMedia> selected = selectedDetectedMedia();
        if (selected.isEmpty()) {
            Toast.makeText(this, "Selecciona al menos un video.", Toast.LENGTH_SHORT).show();
            return;
        }
        File sharedDirectory = new File(getCacheDir(), "shared");
        if (!sharedDirectory.isDirectory() && !sharedDirectory.mkdirs()) {
            Toast.makeText(this, "No se pudo crear el archivo para exportar.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        try {
            File listFile = File.createTempFile("cdg-video-links-", ".txt", sharedDirectory);
            try (FileOutputStream output = new FileOutputStream(listFile)) {
                for (DetectedMedia item : selected) {
                    output.write((item.url + System.lineSeparator()).getBytes(
                            java.nio.charset.StandardCharsets.UTF_8));
                }
            }
            Uri fileUri = FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", listFile);
            Intent share = new Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "Enlaces de video")
                    .putExtra(Intent.EXTRA_STREAM, fileUri);
            share.setClipData(ClipData.newUri(
                    getContentResolver(), "Enlaces de video", fileUri));
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(share, "Exportar lista TXT"));
        } catch (IOException | ActivityNotFoundException | IllegalArgumentException exception) {
            Toast.makeText(this, "No se pudo exportar la lista: "
                    + exception.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void renderDetectedMediaItems() {
        if (detectedMediaList == null || detectedMediaDialog == null
                || !detectedMediaDialog.isShowing()) {
            return;
        }
        detectedMediaList.removeAllViews();
        detectedMediaStatusViews.clear();
        detectedMediaDownloadButtons.clear();
        detectedMediaSelectionViews.clear();
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
            ImageView thumbnail = new ImageView(this);
            thumbnail.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumbnail.setBackground(rippleBackground(colorBackground, colorBorder, 12));
            heading.addView(thumbnail, new LinearLayout.LayoutParams(dp(96), dp(54)));
            if (item.thumbnailUrl != null) {
                loadThumbnail(thumbnail, item.thumbnailUrl);
            } else {
                thumbnail.setImageResource(android.R.drawable.ic_media_play);
            }
            TextView label = new TextView(this);
            String source = item.pageTitle == null || item.pageTitle.isEmpty()
                    ? Uri.parse(item.pageUrl).getHost() : item.pageTitle;
            String cardTitle = item.title == null || item.title.isEmpty()
                    || isGenericMediaTitle(item.title)
                    ? source : item.title;
            String resolution = item.videoHeight > 0
                    ? (item.videoWidth > 0
                    ? item.videoWidth + "×" + item.videoHeight + " · "
                    : "") + item.videoHeight + "p"
                    : "Calidad no detectada";
            String duration = item.durationText == null || item.durationText.isEmpty()
                    ? "Duración no detectada" : item.durationText;
            String details = resolution + " · " + item.type + " · " + duration;
            label.setText(cardTitle + "\n" + details);
            label.setTextColor(colorText);
            label.setTextSize(14);
            label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            label.setMaxLines(2);
            label.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            label.setPadding(dp(10), 0, 0, 0);
            heading.addView(label, new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            CheckBox selection = new CheckBox(this);
            selection.setChecked(selectedDetectedMediaKeys.contains(item.key));
            selection.setOnCheckedChangeListener((button, checked) -> {
                if (checked) {
                    selectedDetectedMediaKeys.add(item.key);
                } else {
                    selectedDetectedMediaKeys.remove(item.key);
                }
            });
            detectedMediaSelectionViews.put(item.key, selection);
            heading.addView(selection);
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
            Button downloadButton = button(
                    item.analysisInProgress ? "Analizando…"
                            : item.formats.isEmpty()
                            ? "Descargar · analizar calidades"
                            : "Descargar · elegir calidad",
                    () -> {
                        if (!item.formats.isEmpty()) {
                            selectYtDlpQuality(item.url, new YtDlpMediaInfo(
                                    titleForDownload(item.title, item.pageTitle),
                                    0,
                                    item.thumbnailUrl,
                                    new ArrayList<>(item.formats)), item);
                        } else {
                            setDetectedMediaStatus(item, "Analizando con yt-dlp…", false);
                            inspectWithYtDlp(item.url, item);
                        }
                    });
            downloadButton.setEnabled(!item.downloadActive && !item.analysisInProgress);
            detectedMediaDownloadButtons.put(item.key, downloadButton);
            if (item.analysisProcessId != null) {
                actions.addView(button("Cancelar análisis", () -> {
                    ytDlpEngine.cancelInfo(item.analysisProcessId);
                    item.analysisProcessId = null;
                    item.analysisInProgress = false;
                    setDetectedMediaStatus(item, "Análisis cancelado.", false);
                    renderDetectedMediaItems();
                }));
            }
            actions.addView(downloadButton);
            row.addView(actions);
            Button saveForLaterButton = button(
                    pendingMedia.containsKey(item.key)
                            ? "Quitar de descargas para después"
                            : "Guardar para después",
                    () -> togglePendingMedia(item));
            row.addView(saveForLaterButton, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
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

    private boolean isGenericMediaTitle(String title) {
        return title == null || title.trim().isEmpty()
                || title.trim().matches(
                "(?i)(playlist|video|media|stream|index)(\\.[a-z0-9]{2,5})?")
                || title.trim().matches(
                "(?i).+\\.(m3u8?|mpd|mp4|m4v|mov|webm|mkv|avi|mpeg|mpg|3gp)");
    }

    private String titleForDownload(String mediaTitle, String pageTitle) {
        return isGenericMediaTitle(mediaTitle)
                ? (pageTitle == null || pageTitle.isBlank() ? "video" : pageTitle)
                : mediaTitle;
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
                downloadButton.setEnabled(!active && !item.analysisInProgress);
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
        setAddressBarVisible(!addressBarVisible);
    }

    private void setAddressBarVisible(boolean visible) {
        addressBarVisible = visible;
        addressRow.setVisibility(addressBarVisible ? View.VISIBLE : View.GONE);
        addressToggleButton.setText(addressToggleLabel());
        if (addressBarVisible) {
            addressField.setText(currentUrl);
        }
    }

    private String addressToggleLabel() {
        if (isTvVersion()) {
            return addressBarVisible ? "Ocultar barra" : "Mostrar barra";
        }
        return addressBarVisible ? "⌄" : "⌃";
    }

    private void handlePageScroll(String pageUrl, boolean scrollingDown) {
        if (isTvVersion() || !addressAutoHide || addressField == null || addressField.hasFocus()
                || !isCurrentPage(pageUrl)) {
            return;
        }
        if (scrollingDown && addressBarVisible) {
            setAddressBarVisible(false);
        } else if (!scrollingDown && !addressBarVisible) {
            setAddressBarVisible(true);
        }
    }

    private boolean isCurrentPage(String pageUrl) {
        if (!isHttpUrl(pageUrl) || !isHttpUrl(currentUrl)) {
            return false;
        }
        return Uri.parse(pageUrl).buildUpon().fragment(null).build().toString()
                .equals(Uri.parse(currentUrl).buildUpon().fragment(null).build().toString());
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
        String encoded = Uri.encode(query);
        switch (searchEngine) {
            case "google":
                return "https://www.google.com/search?q=" + encoded;
            case "bing":
                return "https://www.bing.com/search?q=" + encoded;
            case "brave":
                return "https://search.brave.com/search?q=" + encoded;
            case "ecosia":
                return "https://www.ecosia.org/search?q=" + encoded;
            default:
                return "https://duckduckgo.com/?q=" + encoded;
        }
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
        if (openSidebar == 1) {
            populateNavigationSidebar();
        }
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
        if (hasCompletedDownload(sourceUrl, "")) {
            closeQuietly(input);
            Toast.makeText(this, "Este enlace ya se descargó; se omitió el duplicado.",
                    Toast.LENGTH_LONG).show();
            return;
        }
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
                        recordDownloadedLink(entry);
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
        inspectWithYtDlp(url, source, () -> { });
    }

    private void inspectWithYtDlp(
            String url, DetectedMedia source, Runnable onFinished) {
        if (source != null && source.analysisInProgress) {
            return;
        }
        if (source != null) {
            source.analysisInProgress = true;
            renderDetectedMediaItems();
        }
        boolean[] cancelled = {false};
        String[] analysisProcessId = {null};
        AtomicBoolean analysisFinished = new AtomicBoolean();
        Runnable finishAnalysis = () -> {
            if (analysisFinished.compareAndSet(false, true)) {
                mainHandler.post(onFinished);
            }
        };
        final AlertDialog loading;
        if (source == null) {
            loading = dialogBuilder()
                    .setTitle("Preparando yt-dlp")
                    .setMessage("Analizando los formatos disponibles…")
                    .setNegativeButton("Cerrar", (dialog, which) -> {
                        cancelled[0] = true;
                        if (analysisProcessId[0] != null) {
                            ytDlpEngine.cancelInfo(analysisProcessId[0]);
                        }
                    })
                    .create();
            loading.setOnCancelListener(dialog -> {
                cancelled[0] = true;
                if (analysisProcessId[0] != null) {
                    ytDlpEngine.cancelInfo(analysisProcessId[0]);
                }
            });
            loading.show();
        } else {
            loading = null;
        }
        MediaExtractor.Callback extractionCallback = new MediaExtractor.Callback() {
            @Override
            public void onStarted(String processId) {
                analysisProcessId[0] = processId;
                if (source != null) {
                    source.analysisProcessId = processId;
                    renderDetectedMediaItems();
                }
                if (cancelled[0]) {
                    ytDlpEngine.cancelInfo(processId);
                }
            }

            @Override
            public void onResult(YtDlpMediaInfo info) {
                if (loading != null) {
                    loading.dismiss();
                }
                if (cancelled[0] || isFinishing()) {
                    return;
                }
                if (source != null) {
                    source.analysisProcessId = null;
                    source.analysisInProgress = false;
                    source.title = titleForDownload(info.getTitle(), source.pageTitle);
                    source.thumbnailUrl = info.getThumbnailUrl() != null
                            ? info.getThumbnailUrl() : source.thumbnailUrl;
                    source.durationText = info.getDurationSeconds() > 0
                            ? formatDuration(info.getDurationSeconds()) : source.durationText;
                    source.formats.clear();
                    int bestHeight = source.videoHeight;
                    for (YtDlpFormatOption format : info.getFormats()) {
                        if (!format.getAudioOnly()
                                && (format.getHeight() == 0
                                || format.getHeight() >= minimumMediaHeight)) {
                            source.formats.add(format);
                            bestHeight = Math.max(bestHeight, format.getHeight());
                        }
                    }
                    source.videoHeight = bestHeight;
                    if (source.formats.isEmpty()) {
                        detectedMedia.remove(source.key);
                        pendingMedia.remove(source.key);
                        selectedDetectedMediaKeys.remove(source.key);
                        savePendingMedia();
                        updateDetectedMediaButton();
                        updateBulkVideoButton();
                        renderDetectedMediaItems();
                        finishAnalysis.run();
                        return;
                    }
                    setDetectedMediaStatus(
                            source, "Listo · elige una calidad en esta tarjeta.", false);
                    renderDetectedMediaItems();
                    finishAnalysis.run();
                    return;
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
                finishAnalysis.run();
            }

            @Override
            public void onNotHandled() {
                if (loading != null) {
                    loading.dismiss();
                }
                if (!cancelled[0] && source != null) {
                    source.analysisProcessId = null;
                    source.analysisInProgress = false;
                    setDetectedMediaStatus(source, "No se encontró un formato descargable.", false);
                    renderDetectedMediaItems();
                } else if (!cancelled[0] && !isFinishing()) {
                    showYtDlpError("No hay un extractor compatible para esta dirección.");
                }
                finishAnalysis.run();
            }

            @Override
            public void onError(String message) {
                if (loading != null) {
                    loading.dismiss();
                }
                if (source != null) {
                    source.analysisProcessId = null;
                    source.analysisInProgress = false;
                    setDetectedMediaStatus(source,
                            "Análisis cancelado.".equals(message)
                                    ? message : "Error de análisis: " + message,
                            false);
                    renderDetectedMediaItems();
                }
                if (!cancelled[0] && source == null && !isFinishing()) {
                    showYtDlpError(message);
                }
                finishAnalysis.run();
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
                if (loading != null) {
                    loading.dismiss();
                }
                if (source != null) {
                    source.analysisProcessId = null;
                    source.analysisInProgress = false;
                    setDetectedMediaStatus(source, "Error de inicio: " + message, false);
                    renderDetectedMediaItems();
                }
                if (!cancelled[0] && source == null && !isFinishing()) {
                    showYtDlpError(message);
                }
                finishAnalysis.run();
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
        String duration = media.getDurationSeconds() > 0
                ? formatDuration(media.getDurationSeconds())
                : source == null ? "" : source.durationText;
        title.setText(media.getTitle()
                + (duration == null || duration.isEmpty() ? "" : "\nDuración: " + duration));
        title.setTextColor(colorText);
        title.setTextSize(14);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setPadding(dp(4), dp(4), dp(4), dp(10));
        options.addView(title);
        CheckBox includeSubtitles = new CheckBox(this);
        includeSubtitles.setText("Incluir subtítulos si están disponibles");
        includeSubtitles.setChecked(source == null
                ? subtitlesByDefault : source.includeSubtitles);
        includeSubtitles.setOnCheckedChangeListener((button, checked) -> {
            if (source != null) {
                source.includeSubtitles = checked;
            }
        });
        options.addView(includeSubtitles);
        TextView qualityHint = new TextView(this);
        qualityHint.setText("Calidades disponibles · se prioriza "
                + minimumMediaHeight + "p o superior");
        qualityHint.setTextColor(colorMuted);
        qualityHint.setTextSize(12);
        qualityHint.setPadding(dp(4), dp(8), dp(4), dp(4));
        options.addView(qualityHint);
        ScrollView qualityScroll = new ScrollView(this);
        AlertDialog qualityDialog = dialogBuilder()
                .setTitle("Calidad y formato")
                .setView(qualityScroll)
                .setNegativeButton("Cancelar", null)
                .create();
        List<YtDlpFormatOption> preferredFormats = new ArrayList<>();
        List<YtDlpFormatOption> otherFormats = new ArrayList<>();
        for (YtDlpFormatOption format : media.getFormats()) {
            if (!format.getAudioOnly() && format.getHeight() >= minimumMediaHeight) {
                preferredFormats.add(format);
            } else {
                otherFormats.add(format);
            }
        }
        List<YtDlpFormatOption> orderedFormats = new ArrayList<>(preferredFormats);
        orderedFormats.addAll(otherFormats);
        for (YtDlpFormatOption format : orderedFormats) {
            Button formatButton = button(
                    format.getTitle() + "\n" + format.getDetails(), () -> {
                qualityDialog.dismiss();
                boolean audioOnly = format.getAudioOnly();
                startYtDlpDownload(url, titleForDownload(media.getTitle(),
                                source == null ? null : source.pageTitle),
                        format.getSelector(), audioOnly,
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
        qualityScroll.addView(options);
        qualityDialog.show();
    }

    private void startYtDlpDownload(
            String url,
            String title,
            String formatSelector,
            boolean audioOnly,
            boolean includeSubtitles,
            DetectedMedia source) {
        startYtDlpDownload(
                url, title, formatSelector, audioOnly, includeSubtitles, source, () -> { });
    }

    private void startYtDlpDownload(
            String url,
            String title,
            String formatSelector,
            boolean audioOnly,
            boolean includeSubtitles,
            DetectedMedia source,
            Runnable onFinished) {
        if (hasCompletedDownload(url, formatSelector)) {
            Toast.makeText(this, "Este enlace y formato ya se descargaron; se omitió.",
                    Toast.LENGTH_LONG).show();
            if (source != null) {
                setDetectedMediaStatus(source, "Ya descargado", false);
                removePendingMedia(source);
            }
            mainHandler.post(onFinished);
            return;
        }
        if (!startYtDlpBackgroundService()) {
            if (source != null) {
                setDetectedMediaStatus(source, "No se pudo iniciar la descarga en segundo plano.",
                        false);
            }
            bulkVideoQueue.clear();
            mainHandler.post(onFinished);
            return;
        }
        AtomicBoolean finished = new AtomicBoolean();
        Runnable finish = () -> {
            if (finished.compareAndSet(false, true)) {
                finishYtDlpBackgroundService();
                runOnUiThread(onFinished);
            }
        };
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
                title,
                concurrentFragments,
                remuxVideosToMp4,
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
                    finish.run();
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
                                    recordDownloadedLink(entry);
                                    if (source != null) {
                                        setDetectedMediaStatus(source, entry.status, false);
                                    }
                                    cleanupYtDlpTemporaryFiles(temporaryDirectory);
                                    runOnUiThread(() -> {
                                        removePendingMedia(source);
                                        saveDownloads();
                                        refreshDownloadsDialog();
                                        finish.run();
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
                                        finish.run();
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
                    finish.run();
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
                finish.run();
            }
        });
    }

    private boolean startYtDlpBackgroundService() {
        Intent intent = new Intent(this, YtDlpDownloadService.class)
                .setAction(YtDlpDownloadService.ACTION_START);
        try {
            androidx.core.content.ContextCompat.startForegroundService(this, intent);
            return true;
        } catch (IllegalStateException | SecurityException exception) {
            Toast.makeText(this, "Android no permitió iniciar la descarga en segundo plano: "
                    + exception.getMessage(), Toast.LENGTH_LONG).show();
            return false;
        }
    }

    private void finishYtDlpBackgroundService() {
        Intent intent = new Intent(this, YtDlpDownloadService.class)
                .setAction(YtDlpDownloadService.ACTION_FINISH);
        startService(intent);
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

    private boolean hasCompletedDownload(String url, String formatSelector) {
        try {
            return downloadHistoryStore.contains(url, formatSelector);
        } catch (RuntimeException exception) {
            Toast.makeText(this,
                    "No se pudo consultar el historial de enlaces; se canceló para evitar duplicados.",
                    Toast.LENGTH_LONG).show();
            return true;
        }
    }

    private void recordDownloadedLink(DownloadEntry entry) {
        if (entry.url == null || entry.url.isBlank()) {
            return;
        }
        try {
            downloadHistoryStore.record(
                    entry.url,
                    entry.formatSelector,
                    entry.name,
                    entry.location);
        } catch (RuntimeException exception) {
            runOnUiThread(() -> Toast.makeText(this,
                    "La descarga terminó, pero no se pudo guardar su enlace en el historial.",
                    Toast.LENGTH_LONG).show());
        }
    }

    private void showDownloadedLinkHistory() {
        List<DownloadHistoryStore.DownloadRecord> records;
        try {
            records = downloadHistoryStore.recent(200);
        } catch (RuntimeException exception) {
            Toast.makeText(this, "No se pudo leer el historial de enlaces descargados.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (records.isEmpty()) {
            dialogBuilder().setTitle("Enlaces ya descargados")
                    .setMessage("Aún no hay enlaces completados.")
                    .setPositiveButton("Cerrar", null)
                    .show();
            return;
        }
        String[] labels = new String[records.size()];
        for (int index = 0; index < records.size(); index++) {
            DownloadHistoryStore.DownloadRecord record = records.get(index);
            labels[index] = record.filename + "\n" + record.sourceUrl;
        }
        dialogBuilder()
                .setTitle("Enlaces ya descargados")
                .setItems(labels, (dialog, which) -> {
                    DownloadHistoryStore.DownloadRecord record = records.get(which);
                    DownloadEntry entry = new DownloadEntry();
                    entry.name = record.filename;
                    entry.location = record.location;
                    entry.mimeType = VideoDownloadManager.normalizedMediaMimeType(
                            record.filename);
                    openDownloadedFile(entry, false);
                })
                .setNeutralButton("Borrar historial", (dialog, which) ->
                        confirmClearDownloadedLinkHistory())
                .setNegativeButton("Cerrar", null)
                .show();
    }

    private void confirmClearDownloadedLinkHistory() {
        dialogBuilder()
                .setTitle("Borrar historial de enlaces")
                .setMessage("Se borrará el registro usado para omitir duplicados. "
                        + "Los archivos descargados no se eliminarán.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Borrar historial", (dialog, which) -> {
                    try {
                        downloadHistoryStore.clear();
                        Toast.makeText(this, "Historial de enlaces borrado.",
                                Toast.LENGTH_SHORT).show();
                    } catch (RuntimeException exception) {
                        Toast.makeText(this, "No se pudo borrar el historial de enlaces.",
                                Toast.LENGTH_LONG).show();
                    }
                })
                .show();
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
        if (!isHttpUrl(thumbnailUrl)) {
            return;
        }
        imageView.setTag(thumbnailUrl);
        imageView.setImageResource(android.R.drawable.ic_media_play);
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) new URL(thumbnailUrl).openConnection();
                connection.setConnectTimeout(8000);
                connection.setReadTimeout(8000);
                connection.setInstanceFollowRedirects(true);
                if (connection.getResponseCode() >= 200 && connection.getResponseCode() < 300) {
                    try (BufferedInputStream input =
                                 new BufferedInputStream(connection.getInputStream())) {
                        BitmapFactory.Options bounds = new BitmapFactory.Options();
                        bounds.inJustDecodeBounds = true;
                        input.mark(64 * 1024);
                        BitmapFactory.decodeStream(input, null, bounds);
                        input.reset();
                        BitmapFactory.Options options = new BitmapFactory.Options();
                        options.inSampleSize = 1;
                        int targetWidth = dp(192);
                        int targetHeight = dp(108);
                        while (bounds.outWidth / options.inSampleSize > targetWidth * 2
                                || bounds.outHeight / options.inSampleSize > targetHeight * 2) {
                            options.inSampleSize *= 2;
                        }
                        options.inPreferredConfig = Bitmap.Config.RGB_565;
                        Bitmap bitmap = BitmapFactory.decodeStream(input, null, options);
                        if (bitmap != null) {
                            mainHandler.post(() -> {
                                if (thumbnailUrl.equals(imageView.getTag())) {
                                    imageView.setImageBitmap(bitmap);
                                }
                            });
                        }
                    }
                }
            } catch (Exception error) {
                Log.w("CDGBrowser", "No se pudo cargar una miniatura.", error);
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
        downloadsDialog.show();
        refreshDownloadsDialog();
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
            int activeCount = 0;
            int completedCount = 0;
            for (DownloadEntry entry : downloads) {
                if (isDownloadActive(entry)) {
                    activeCount++;
                } else if (entry.status != null && entry.status.startsWith("Completada")) {
                    completedCount++;
                }
            }
            downloadsList.addView(button(
                    "Detector de videos · " + allDetectedVideos().size(),
                    this::showDetectedMedia), settingButtonParams());
            TextView downloadSummary = new TextView(this);
            downloadSummary.setText(
                    "En proceso · " + activeCount + "     Descargas hechas · " + completedCount);
            downloadSummary.setTextColor(colorMuted);
            downloadSummary.setTextSize(12);
            downloadSummary.setPadding(dp(16), dp(8), dp(16), dp(8));
            downloadsList.addView(downloadSummary);
            LinearLayout folderActions = new LinearLayout(this);
            folderActions.setOrientation(LinearLayout.VERTICAL);
            Button galleryButton = button("Galería de videos", () -> {
                Intent intent = new Intent(this, MediaGalleryActivity.class);
                if (customFolder != null) {
                    intent.putExtra(MediaGalleryActivity.EXTRA_FOLDER_URI,
                            customFolder.toString());
                }
                startActivity(intent);
            });
            folderActions.addView(galleryButton, settingButtonParams());
            folderActions.addView(button(
                    "Enlaces ya descargados", this::showDownloadedLinkHistory),
                    settingButtonParams());
            folderActions.addView(button("Elegir carpeta", this::chooseDownloadFolder),
                    settingButtonParams());
            if (customFolder != null) {
                folderActions.addView(button(
                        "Abrir carpeta", () -> openDownloadFolder(customFolder)),
                        settingButtonParams());
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
        if (!share && mimeType != null && mimeType.startsWith("video/")) {
            startActivity(new Intent(this, MediaPlayerActivity.class)
                    .setDataAndType(uri, mimeType)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
            return;
        }
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
            } else if (mimeType.startsWith("video/")) {
                intent = new Intent(this, MediaPlayerActivity.class).setDataAndType(uri, mimeType);
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

    private void showSettings() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(8), dp(16), dp(8));

        TextView generalHeading = settingsSection("General");
        content.addView(generalHeading);
        content.addView(button(themeButtonLabel(), this::showThemeSelector),
                settingButtonParams());
        content.addView(button(
                "Motor de búsqueda · " + searchEngineName(searchEngine),
                this::showSearchEngineSelector), settingButtonParams());
        content.addView(button("Página de inicio · " + favoriteLabel(homeUrl),
                this::promptHomePage), settingButtonParams());

        Switch addressAutoHideSwitch = new Switch(this);
        addressAutoHideSwitch.setText(isTvVersion()
                ? "Autoocultar barra (no disponible en TV)"
                : "Ocultar barra al desplazarse hacia abajo");
        addressAutoHideSwitch.setTextColor(colorText);
        addressAutoHideSwitch.setChecked(addressAutoHide);
        addressAutoHideSwitch.setOnCheckedChangeListener((view, checked) -> {
            addressAutoHide = checked;
            getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                    .putBoolean(ADDRESS_AUTO_HIDE_KEY, checked)
                    .apply();
            if (!checked) {
                setAddressBarVisible(true);
            }
        });
        addressAutoHideSwitch.setEnabled(!isTvVersion());
        content.addView(addressAutoHideSwitch);

        TextView protectionHeading = settingsSection("Protección y privacidad");
        content.addView(protectionHeading);

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

        mediaDetectionSwitch = new Switch(this);
        mediaDetectionSwitch.setText("Detector multimedia");
        mediaDetectionSwitch.setTextColor(colorText);
        mediaDetectionSwitch.setChecked(adBlockEngine.isMediaDetectionEnabled());
        mediaDetectionSwitch.setOnCheckedChangeListener(
                (view, checked) -> {
                    adBlockEngine.setMediaDetectionEnabled(checked);
                    updateDetectedMediaButton();
                });
        content.addView(mediaDetectionSwitch);

        Switch popupSwitch = new Switch(this);
        popupSwitch.setText("Bloquear ventanas emergentes no solicitadas");
        popupSwitch.setTextColor(colorText);
        popupSwitch.setChecked(blockPopups);
        popupSwitch.setOnCheckedChangeListener((view, checked) -> setPopupBlocking(checked));
        content.addView(popupSwitch);

        Switch redirectSwitch = new Switch(this);
        redirectSwitch.setText("Bloquear toda redirección tras clic");
        redirectSwitch.setTextColor(colorText);
        redirectSwitch.setChecked(blockClickRedirects);
        redirectSwitch.setOnCheckedChangeListener(
                (view, checked) -> setClickRedirectBlocking(checked));
        content.addView(redirectSwitch);
        TextView redirectWarning = new TextView(this);
        redirectWarning.setText(
                "Puede bloquear inicios de sesión y cambios HTTP a HTTPS. "
                        + "Desactívalo si una página no funciona.");
        redirectWarning.setTextSize(11);
        redirectWarning.setTextColor(colorMuted);
        content.addView(redirectWarning);

        siteExceptionButton = button(
                "Desactivar filtros locales en este sitio", this::toggleCurrentSiteException);
        content.addView(siteExceptionButton, settingButtonParams());

        updateFiltersButton = button("Actualizar listas manualmente", this::confirmFilterUpdate);
        content.addView(updateFiltersButton, settingButtonParams());

        TextView mediaHeading = settingsSection("Descargas multimedia");
        content.addView(mediaHeading);
        Switch mediaFabSwitch = new Switch(this);
        mediaFabSwitch.setText("Mostrar botón flotante de medios");
        mediaFabSwitch.setTextColor(colorText);
        mediaFabSwitch.setChecked(mediaFabEnabled);
        mediaFabSwitch.setOnCheckedChangeListener((view, checked) -> {
            mediaFabEnabled = checked;
            getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                    .putBoolean(MEDIA_FAB_ENABLED_KEY, checked)
                    .apply();
            updateDetectedMediaButton();
        });
        content.addView(mediaFabSwitch);
        TextView mediaFabHint = new TextView(this);
        mediaFabHint.setText("El detector y el botón flotante empiezan desactivados. "
                + "Activa el detector aquí; el botón flotante se puede activar por separado.");
        mediaFabHint.setTextSize(12);
        mediaFabHint.setTextColor(colorMuted);
        content.addView(mediaFabHint);
        mediaFabSiteButton = button("", this::toggleCurrentSiteMediaFabVisibility);
        content.addView(mediaFabSiteButton, settingButtonParams());
        updateMediaFabSiteButton();
        Button[] qualityButtonRef = new Button[1];
        qualityButtonRef[0] = button(
                minimumMediaHeight + "p o superior · Cambiar", () -> {
            int[] heights = {720, 1080, 1440, 2160};
            String[] labels = {"720p", "1080p", "1440p", "2160p"};
            int selected = 0;
            for (int index = 0; index < heights.length; index++) {
                if (heights[index] == minimumMediaHeight) {
                    selected = index;
                    break;
                }
            }
            dialogBuilder().setTitle("Calidad mínima visible")
                    .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                        minimumMediaHeight = heights[which];
                        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                                .putInt(MINIMUM_MEDIA_HEIGHT_KEY, minimumMediaHeight)
                                .apply();
                        qualityButtonRef[0].setText(
                                minimumMediaHeight + "p o superior · Cambiar");
                        pruneLowQualityMedia();
                        renderDetectedMediaItems();
                        dialog.dismiss();
                    })
                    .setNegativeButton("Cancelar", null)
                    .show();
        });
        content.addView(qualityButtonRef[0], settingButtonParams());

        Button[] fragmentsButtonRef = new Button[1];
        fragmentsButtonRef[0] = button(
                "Fragmentos simultáneos · " + concurrentFragments, () -> {
                    String[] values = {"1", "4", "8", "16", "32"};
                    String[] labels = {
                            "1 · mínimo uso de conexiones",
                            "4",
                            "8",
                            "16",
                            "32 · máximo"
                    };
                    int selected = 0;
                    for (int index = 0; index < values.length; index++) {
                        if (Integer.parseInt(values[index]) == concurrentFragments) {
                            selected = index;
                            break;
                        }
                    }
                    dialogBuilder().setTitle("Fragmentos HLS/DASH")
                            .setSingleChoiceItems(labels, selected, (dialog, which) -> {
                                concurrentFragments = Integer.parseInt(values[which]);
                                getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                                        .putInt(CONCURRENT_FRAGMENTS_KEY, concurrentFragments)
                                        .apply();
                                fragmentsButtonRef[0].setText(
                                        "Fragmentos simultáneos · " + concurrentFragments);
                                dialog.dismiss();
                            })
                            .setNegativeButton("Cancelar", null)
                            .show();
                });
        content.addView(fragmentsButtonRef[0], settingButtonParams());

        Switch mp4Switch = new Switch(this);
        mp4Switch.setText("Remultiplexar videos a MP4");
        mp4Switch.setTextColor(colorText);
        mp4Switch.setChecked(remuxVideosToMp4);
        mp4Switch.setOnCheckedChangeListener((view, checked) -> {
            remuxVideosToMp4 = checked;
            getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                    .putBoolean(REMUX_MP4_KEY, checked)
                    .apply();
        });
        content.addView(mp4Switch);

        Switch subtitlesSwitch = new Switch(this);
        subtitlesSwitch.setText("Incluir subtítulos por defecto");
        subtitlesSwitch.setTextColor(colorText);
        subtitlesSwitch.setChecked(subtitlesByDefault);
        subtitlesSwitch.setOnCheckedChangeListener((view, checked) -> {
            subtitlesByDefault = checked;
            getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                    .putBoolean(DEFAULT_SUBTITLES_KEY, checked)
                    .apply();
        });
        content.addView(subtitlesSwitch);

        TextView downloadHeading = settingsSection("Almacenamiento");
        content.addView(downloadHeading);
        downloadDestinationView = new TextView(this);
        downloadDestinationView.setTextSize(12);
        downloadDestinationView.setTextColor(colorMuted);
        downloadDestinationView.setPadding(0, dp(4), 0, dp(4));
        updateSettingsDownloadDestination();
        content.addView(downloadDestinationView);
        content.addView(button("Elegir carpeta de descargas", this::chooseDownloadFolder),
                settingButtonParams());

        TextView engineHeading = settingsSection("Módulos y actualizaciones");
        content.addView(engineHeading);
        content.addView(button("Comprobar y actualizar yt-dlp", this::updateYtDlp),
                settingButtonParams());
        TextView moduleUpdateInfo = new TextView(this);
        moduleUpdateInfo.setText(
                "yt-dlp y las listas de protección se actualizan por separado y con verificaciones. "
                        + "El reproductor, el detector de imágenes y el código de los extractores "
                        + "se actualizan mediante una APK firmada; la app no ejecuta código "
                        + "descargado. La compatibilidad de códecs depende del dispositivo.");
        moduleUpdateInfo.setTextSize(12);
        moduleUpdateInfo.setTextColor(colorMuted);
        content.addView(moduleUpdateInfo);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);

        settingsDialog = dialogBuilder()
                .setTitle("Ajustes")
                .setView(scroll)
                .setPositiveButton("Cerrar", null)
                .create();
        settingsDialog.setOnDismissListener(dialog -> {
            settingsDialog = null;
            protectionSummaryView = null;
            protectionEnabledSwitch = null;
            mediaDetectionSwitch = null;
            siteExceptionButton = null;
            updateFiltersButton = null;
            downloadDestinationView = null;
        });
        settingsDialog.show();
        refreshProtectionControls();
    }

    private void showProtectionInfo() {
        showSettings();
    }

    private TextView settingsSection(String title) {
        TextView heading = new TextView(this);
        heading.setText(title);
        heading.setTextColor(colorAccent);
        heading.setTextSize(14);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        heading.setPadding(0, dp(14), 0, dp(4));
        return heading;
    }

    private LinearLayout.LayoutParams settingButtonParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(44));
        params.topMargin = dp(4);
        return params;
    }

    private void updateSettingsDownloadDestination() {
        if (downloadDestinationView == null) {
            return;
        }
        Uri folder = getCustomDownloadFolder();
        String path = folder == null
                ? (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                ? "Descargas/CDG Browser" : "Carpeta privada de la aplicación")
                : folder.getLastPathSegment();
        downloadDestinationView.setText("Carpeta actual: " + path);
    }

    private boolean isSupportedMediaHeight(int height) {
        return height == 720 || height == 1080 || height == 1440 || height == 2160;
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
        if (mediaDetectionSwitch != null
                && mediaDetectionSwitch.isChecked() != adBlockEngine.isMediaDetectionEnabled()) {
            mediaDetectionSwitch.setChecked(adBlockEngine.isMediaDetectionEnabled());
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
        if (rightAdBlockSummary != null) {
            rightAdBlockSummary.setText(
                    (adBlockEngine.isEnabled() ? "Activo" : "Pausado")
                            + " · " + adBlockEngine.getRuleCount() + " dominios"
                            + " · " + adBlockEngine.getBlockedCount() + " bloqueos");
        }
        if (sidebarProtectionSwitch != null
                && sidebarProtectionSwitch.isChecked() != adBlockEngine.isEnabled()) {
            sidebarProtectionSwitch.setChecked(adBlockEngine.isEnabled());
        }
        if (sidebarMediaDetectionSwitch != null
                && sidebarMediaDetectionSwitch.isChecked()
                != adBlockEngine.isMediaDetectionEnabled()) {
            sidebarMediaDetectionSwitch.setChecked(adBlockEngine.isMediaDetectionEnabled());
        }
        if (sidebarPopupSwitch != null && sidebarPopupSwitch.isChecked() != blockPopups) {
            sidebarPopupSwitch.setChecked(blockPopups);
        }
        if (sidebarRedirectSwitch != null
                && sidebarRedirectSwitch.isChecked() != blockClickRedirects) {
            sidebarRedirectSwitch.setChecked(blockClickRedirects);
        }
        String host = currentHost();
        if (sidebarExceptionButton != null) {
            sidebarExceptionButton.setEnabled(host != null);
            sidebarExceptionButton.setText(host != null && adBlockEngine.isSiteAllowed(host)
                    ? "Reactivar filtros para este sitio"
                    : "Crear excepción para este sitio");
        }
        if (sidebarUpdateButton != null) {
            sidebarUpdateButton.setEnabled(
                    adBlockEngine.isConnected() && !adBlockEngine.isUpdateInProgress());
            sidebarUpdateButton.setText(adBlockEngine.isUpdateInProgress()
                    ? "Actualizando lista…" : "Actualizar listas");
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
        boolean legacyDarkTheme = preferences.getBoolean(THEME_KEY, false);
        visualTheme = preferences.getString(
                VISUAL_THEME_KEY,
                preferences.contains(THEME_KEY)
                        ? (legacyDarkTheme ? "dark_green" : "light")
                        : "neon_orange");
        if (!isSupportedTheme(visualTheme)) {
            visualTheme = "neon_orange";
        }
        darkTheme = !"light".equals(visualTheme);
        searchEngine = preferences.getString(SEARCH_ENGINE_KEY, "duckduckgo");
        if (!isSupportedSearchEngine(searchEngine)) {
            searchEngine = "duckduckgo";
        }
        String savedHomePage = preferences.getString(HOME_PAGE_KEY, HOME_URL);
        homeUrl = isHttpUrl(savedHomePage) ? savedHomePage : HOME_URL;
        blockPopups = preferences.getBoolean(BLOCK_POPUPS_KEY, true);
        blockClickRedirects = preferences.getBoolean(BLOCK_CLICK_REDIRECTS_KEY, true);
        addressAutoHide = !isTvVersion()
                && preferences.getBoolean(ADDRESS_AUTO_HIDE_KEY, true);
        mediaFabEnabled = preferences.getBoolean(MEDIA_FAB_ENABLED_KEY, false);
        mediaFabHiddenSites.clear();
        for (String host : preferences.getStringSet(
                MEDIA_FAB_HIDDEN_SITES_KEY, new HashSet<>())) {
            if (host != null && !host.isBlank()) {
                mediaFabHiddenSites.add(host.toLowerCase(Locale.ROOT));
            }
        }
        minimumMediaHeight = preferences.getInt(MINIMUM_MEDIA_HEIGHT_KEY, 1080);
        if (!isSupportedMediaHeight(minimumMediaHeight)) {
            minimumMediaHeight = 1080;
        }
        concurrentFragments = Math.max(
                1, Math.min(preferences.getInt(CONCURRENT_FRAGMENTS_KEY, 32), 32));
        remuxVideosToMp4 = preferences.getBoolean(REMUX_MP4_KEY, true);
        subtitlesByDefault = preferences.getBoolean(DEFAULT_SUBTITLES_KEY, false);
        loadLines(preferences.getString(HISTORY_KEY, ""), history);
        loadLines(preferences.getString(FAVORITES_KEY, ""), favorites);
        loadPendingMedia(preferences.getString(PENDING_MEDIA_KEY, "[]"));
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

    private void loadPendingMedia(String savedPendingMedia) {
        try {
            JSONArray items = new JSONArray(savedPendingMedia);
            for (int index = 0; index < items.length()
                    && pendingMedia.size() < MAX_BULK_ITEMS; index++) {
                JSONObject saved = items.optJSONObject(index);
                if (saved == null) {
                    continue;
                }
                String url = saved.optString("url", "");
                String pageUrl = saved.optString("pageUrl", "");
                String type = saved.optString("type", "");
                int savedVideoHeight = Math.max(
                        0, Math.min(saved.optInt("videoHeight", 0), 16384));
                if (url.length() > 8192 || !isHttpUrl(url) || !isHttpUrl(pageUrl)
                        || !("Video".equals(type) || "HLS".equals(type)
                        || "DASH".equals(type))
                        || ("Video".equals(type) && savedVideoHeight > 0
                        && savedVideoHeight < minimumMediaHeight)) {
                    continue;
                }
                String key = normalizeMediaUrl(url);
                if (pendingMedia.containsKey(key)) {
                    continue;
                }
                String thumbnailUrl = saved.optString("thumbnailUrl", "");
                if (!isHttpUrl(thumbnailUrl) || thumbnailUrl.length() > 2048) {
                    thumbnailUrl = null;
                }
                String pageTitle = saved.optString("pageTitle", "");
                if (pageTitle.length() > 200) {
                    pageTitle = pageTitle.substring(0, 200);
                }
                DetectedMedia item = new DetectedMedia(
                        key,
                        url,
                        pageUrl,
                        pageTitle,
                        type,
                        thumbnailUrl,
                        Math.max(0, Math.min(saved.optInt("videoWidth", 0), 16384)),
                        savedVideoHeight,
                        0);
                item.title = saved.optString("title", "");
                String durationText = saved.optString("durationText", "");
                if (durationText.matches("\\d{1,5}:\\d{2}")) {
                    item.durationText = durationText;
                }
                item.includeSubtitles = subtitlesByDefault;
                pendingMedia.put(key, item);
                detectedMedia.putIfAbsent(key, item);
            }
        } catch (org.json.JSONException exception) {
            Toast.makeText(this, "No se pudo leer la lista de descargas para después",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void savePendingMedia() {
        JSONArray items = new JSONArray();
        for (DetectedMedia media : pendingMedia.values()) {
            JSONObject item = new JSONObject();
            try {
                item.put("url", media.url);
                item.put("pageUrl", media.pageUrl);
                item.put("pageTitle", media.pageTitle);
                item.put("title", media.title);
                item.put("type", media.type);
                item.put("thumbnailUrl", media.thumbnailUrl);
                item.put("videoWidth", media.videoWidth);
                item.put("videoHeight", media.videoHeight);
                item.put("durationText", media.durationText);
                items.put(item);
            } catch (org.json.JSONException exception) {
                Toast.makeText(this, "No se pudo guardar un enlace para después",
                        Toast.LENGTH_SHORT).show();
            }
        }
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                .putString(PENDING_MEDIA_KEY, items.toString())
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
        if (openSidebar != 0) {
            closeSidePanel();
            return;
        }
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
