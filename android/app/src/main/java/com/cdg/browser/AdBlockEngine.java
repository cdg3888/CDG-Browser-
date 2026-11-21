package com.cdg.browser;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.mozilla.geckoview.ContentBlocking;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.WebExtension;

import java.util.HashSet;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Set;
import java.util.List;

final class AdBlockEngine {
    static final class PageImageCandidate {
        final String url;
        final String label;
        final int width;
        final int height;

        PageImageCandidate(String url, String label, int width, int height) {
            this.url = url;
            this.label = label;
            this.width = width;
            this.height = height;
        }
    }

    interface Listener {
        void onReady();

        void onStateChanged();

        void onError(String message);

        void onMediaDetected(
                String url,
                String pageUrl,
                String mediaType,
                String pageTitle,
                String thumbnailUrl,
                int videoWidth,
                int videoHeight,
                int durationSeconds);

        void onPageScroll(String pageUrl, boolean scrollingDown);

        void onPageImagesDetected(
                String pageUrl, String pageTitle, List<PageImageCandidate> images);
    }

    private static final String EXTENSION_URI =
            "resource://android/assets/web_extensions/cdg-adblock/";
    private static final String EXTENSION_ID = "cdg-adblock@cdg.browser";
    private static final String NATIVE_APP = "com.cdg.browser";
    private static final String PREFERENCES = "cdg_browser";
    private static final String ENABLED_KEY = "ad_block_enabled";
    private static final String MEDIA_DETECTION_KEY = "media_detection_enabled";
    private static final String ALLOWED_SITES_KEY = "ad_block_allowed_sites";

    private final GeckoRuntime runtime;
    private final SharedPreferences preferences;
    private final Set<String> allowedSites = new HashSet<>();
    private final List<String> blockedDomainSummary = new ArrayList<>();

    private Listener listener;
    private WebExtension extension;
    private WebExtension.Port nativePort;
    private boolean enabled;
    private boolean mediaDetectionEnabled;
    private boolean installed;
    private boolean installing;
    private boolean configured;
    private boolean updateInProgress;
    private int blockedCount;
    private int ruleCount;
    private String lastUpdated = "";
    private String lastError = "";

    AdBlockEngine(Context context, GeckoRuntime runtime, Listener listener) {
        Context appContext = context.getApplicationContext();
        this.runtime = runtime;
        this.listener = listener;
        preferences = appContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
        enabled = preferences.getBoolean(ENABLED_KEY, true);
        mediaDetectionEnabled = preferences.getBoolean(MEDIA_DETECTION_KEY, true);
        Set<String> savedSites = preferences.getStringSet(ALLOWED_SITES_KEY, new HashSet<>());
        for (String site : savedSites) {
            String normalized = normalizeDomain(site);
            if (normalized != null) {
                allowedSites.add(normalized);
            }
        }
    }

    void setListener(Listener listener) {
        this.listener = listener;
        notifyReadyIfAvailable();
        notifyStateChanged();
    }

    static GeckoRuntimeSettings createRuntimeSettings() {
        ContentBlocking.Settings blockingSettings = new ContentBlocking.Settings.Builder()
                .antiTracking(ContentBlocking.AntiTracking.STRICT
                        | ContentBlocking.AntiTracking.AD
                        | ContentBlocking.AntiTracking.ANALYTIC
                        | ContentBlocking.AntiTracking.SOCIAL
                        | ContentBlocking.AntiTracking.CONTENT
                        | ContentBlocking.AntiTracking.STP
                        | ContentBlocking.AntiTracking.FINGERPRINTING
                        | ContentBlocking.AntiTracking.CRYPTOMINING
                        | ContentBlocking.AntiTracking.EMAIL)
                .strictSocialTrackingProtection(true)
                .cookieBehavior(ContentBlocking.CookieBehavior.ACCEPT_NON_TRACKERS)
                .cookiePurging(true)
                .queryParameterStrippingEnabled(true)
                .queryParameterStrippingPrivateBrowsingEnabled(true)
                .build();

        return new GeckoRuntimeSettings.Builder()
                .contentBlocking(blockingSettings)
                .extensionsProcessEnabled(true)
                .globalPrivacyControlEnabled(true)
                .build();
    }

    void install() {
        if (installed || installing) {
            return;
        }
        installing = true;
        runtime.getWebExtensionController()
                .ensureBuiltIn(EXTENSION_URI, EXTENSION_ID)
                .accept(this::onExtensionInstalled, this::onInstallFailure);
    }

    private void onExtensionInstalled(WebExtension installedExtension) {
        extension = installedExtension;
        extension.setMessageDelegate(messageDelegate, NATIVE_APP);
        runtime.getWebExtensionController()
                .setAllowedInPrivateBrowsing(extension, true)
                .accept(allowedExtension -> {
                    extension = allowedExtension;
                    installed = true;
                    installing = false;
                    lastError = "";
                    notifyReadyIfAvailable();
                    notifyStateChanged();
                }, this::onInstallFailure);
    }

    private final WebExtension.MessageDelegate messageDelegate = new WebExtension.MessageDelegate() {
        @Override
        public void onConnect(WebExtension.Port port) {
            if (!NATIVE_APP.equals(port.name)) {
                port.disconnect();
                return;
            }
            if (nativePort != null && nativePort != port) {
                nativePort.setDelegate(null);
                nativePort.disconnect();
            }
            nativePort = port;
            configured = false;
            lastError = "";
            nativePort.setDelegate(new WebExtension.PortDelegate() {
                @Override
                public void onPortMessage(Object message, WebExtension.Port source) {
                    if (source == nativePort) {
                        handleExtensionMessage(message);
                    }
                }

                @Override
                public void onDisconnect(WebExtension.Port source) {
                    if (source == nativePort) {
                        nativePort = null;
                        configured = false;
                        notifyStateChanged();
                    }
                }
            });
            sendConfiguration();
            notifyReadyIfAvailable();
            notifyStateChanged();
        }
    };

    private void handleExtensionMessage(Object message) {
        if (!(message instanceof JSONObject)) {
            reportError("El motor de filtros envió un mensaje no válido.");
            return;
        }
        JSONObject data = (JSONObject) message;
        String type = data.optString("type", "");
        switch (type) {
            case "state":
                blockedCount = Math.max(0, data.optInt("blockedCount", blockedCount));
                ruleCount = Math.max(0, data.optInt("ruleCount", ruleCount));
                updateBlockedDomainSummary(data.optJSONArray("blockedByDomain"));
                lastUpdated = data.optString("lastUpdated", lastUpdated);
                updateInProgress = data.optBoolean("updateInProgress", false);
                configured = data.optBoolean("configured", configured);
                notifyReadyIfAvailable();
                notifyStateChanged();
                break;
            case "update-started":
                updateInProgress = true;
                lastError = "";
                notifyStateChanged();
                break;
            case "update-complete":
                updateInProgress = false;
                lastError = "";
                blockedCount = Math.max(0, data.optInt("blockedCount", blockedCount));
                ruleCount = Math.max(0, data.optInt("ruleCount", ruleCount));
                updateBlockedDomainSummary(data.optJSONArray("blockedByDomain"));
                lastUpdated = data.optString("lastUpdated", lastUpdated);
                notifyStateChanged();
                break;
            case "update-error":
            case "engine-error":
                updateInProgress = false;
                reportError(data.optString("message", "No se pudo completar la operación."));
                break;
            case "media-detected":
                String url = data.optString("url", "");
                String pageUrl = data.optString("pageUrl", "");
                String mediaType = data.optString("mediaType", "");
                String pageTitle = data.optString("pageTitle", "").trim();
                String thumbnailUrl = data.optString("thumbnailUrl", "");
                if (pageTitle.length() > 200) {
                    pageTitle = pageTitle.substring(0, 200);
                }
                if (!isHttpUrl(thumbnailUrl) || thumbnailUrl.length() > 2048) {
                    thumbnailUrl = "";
                }
                if (listener != null && isHttpUrl(url) && isHttpUrl(pageUrl)
                        && ("HLS".equals(mediaType) || "DASH".equals(mediaType)
                        || "Video".equals(mediaType))) {
                    listener.onMediaDetected(
                            url,
                            pageUrl,
                            mediaType,
                            pageTitle,
                            thumbnailUrl,
                            Math.max(0, Math.min(data.optInt("videoWidth", 0), 16384)),
                            Math.max(0, Math.min(data.optInt("videoHeight", 0), 16384)),
                            Math.max(0, Math.min(data.optInt("durationSeconds", 0), 604800)));
                }
                break;
            case "page-scroll":
                String scrollPageUrl = data.optString("pageUrl", "");
                String direction = data.optString("direction", "");
                if (listener != null && isHttpUrl(scrollPageUrl)
                        && ("up".equals(direction) || "down".equals(direction))) {
                    listener.onPageScroll(scrollPageUrl, "down".equals(direction));
                }
                break;
            case "page-images-detected":
                String imagePageUrl = data.optString("pageUrl", "");
                JSONArray imageItems = data.optJSONArray("images");
                if (listener == null || !isHttpUrl(imagePageUrl) || imageItems == null) {
                    break;
                }
                List<PageImageCandidate> images = new ArrayList<>();
                for (int index = 0; index < Math.min(imageItems.length(), 200); index++) {
                    JSONObject image = imageItems.optJSONObject(index);
                    if (image == null) {
                        continue;
                    }
                    String imageUrl = image.optString("url", "");
                    if (!isHttpUrl(imageUrl) || imageUrl.length() > 8192) {
                        continue;
                    }
                    String label = image.optString("label", "").trim();
                    if (label.length() > 120) {
                        label = label.substring(0, 120);
                    }
                    images.add(new PageImageCandidate(
                            imageUrl,
                            label,
                            Math.max(0, Math.min(image.optInt("width", 0), 32768)),
                            Math.max(0, Math.min(image.optInt("height", 0), 32768))));
                }
                if (!images.isEmpty()) {
                    String imagePageTitle = data.optString("pageTitle", "").trim();
                    if (imagePageTitle.length() > 200) {
                        imagePageTitle = imagePageTitle.substring(0, 200);
                    }
                    listener.onPageImagesDetected(imagePageUrl, imagePageTitle, images);
                }
                break;
            default:
                reportError("El motor de filtros envió un evento desconocido.");
        }
    }

    private void sendConfiguration() {
        if (nativePort == null) {
            return;
        }
        try {
            JSONArray exceptions = new JSONArray();
            for (String site : allowedSites) {
                exceptions.put(site);
            }
            JSONObject message = new JSONObject()
                    .put("type", "configure")
                    .put("enabled", enabled)
                    .put("mediaDetectionEnabled", mediaDetectionEnabled)
                    .put("allowedSites", exceptions);
            nativePort.postMessage(message);
        } catch (JSONException exception) {
            reportError("No se pudo preparar la configuración del bloqueador.");
        }
    }

    void setEnabled(boolean enabled) {
        this.enabled = enabled;
        preferences.edit().putBoolean(ENABLED_KEY, enabled).apply();
        sendConfiguration();
        notifyStateChanged();
    }

    boolean isMediaDetectionEnabled() {
        return mediaDetectionEnabled;
    }

    void setMediaDetectionEnabled(boolean enabled) {
        mediaDetectionEnabled = enabled;
        preferences.edit().putBoolean(MEDIA_DETECTION_KEY, enabled).apply();
        sendConfiguration();
    }

    boolean toggleSiteException(String host) {
        String site = normalizeDomain(host);
        if (site == null) {
            reportError("La dirección actual no tiene un dominio válido.");
            return false;
        }
        if (!allowedSites.remove(site)) {
            allowedSites.add(site);
        }
        preferences.edit()
                .putStringSet(ALLOWED_SITES_KEY, new HashSet<>(allowedSites))
                .apply();
        sendConfiguration();
        notifyStateChanged();
        return true;
    }

    boolean isSiteAllowed(String host) {
        String site = normalizeDomain(host);
        if (site == null) {
            return false;
        }
        for (String allowedSite : allowedSites) {
            if (site.equals(allowedSite) || site.endsWith("." + allowedSite)) {
                return true;
            }
        }
        return false;
    }

    void updateFilters() {
        if (nativePort == null) {
            reportError("El motor local todavía no está conectado. Inténtalo de nuevo.");
            return;
        }
        if (updateInProgress) {
            return;
        }
        updateInProgress = true;
        notifyStateChanged();
        try {
            nativePort.postMessage(new JSONObject().put("type", "update-filters"));
        } catch (JSONException exception) {
            updateInProgress = false;
            reportError("No se pudo iniciar la actualización de la lista.");
        }
    }

    boolean isEnabled() {
        return enabled;
    }

    boolean isInstalled() {
        return installed;
    }

    boolean isConnected() {
        return nativePort != null;
    }

    boolean isUpdateInProgress() {
        return updateInProgress;
    }

    int getBlockedCount() {
        return blockedCount;
    }

    String getBlockedDomainSummary() {
        return String.join(", ", blockedDomainSummary);
    }

    int getRuleCount() {
        return ruleCount;
    }

    String getLastUpdated() {
        return lastUpdated;
    }

    String getLastError() {
        return lastError;
    }

    private void onInstallFailure(Throwable throwable) {
        installing = false;
        installed = false;
        reportError(throwable.getMessage() == null
                ? "No se pudo instalar el motor de filtros local."
                : throwable.getMessage());
    }

    private void reportError(String message) {
        lastError = message;
        notifyStateChanged();
        if (listener != null) {
            listener.onError(message);
        }
    }

    private void updateBlockedDomainSummary(JSONArray domains) {
        if (domains == null) {
            return;
        }
        blockedDomainSummary.clear();
        for (int index = 0; index < domains.length() && index < 8; index++) {
            JSONObject item = domains.optJSONObject(index);
            if (item == null) {
                continue;
            }
            String domain = normalizeDomain(item.optString("domain", ""));
            int count = item.optInt("count", 0);
            if (domain != null && count > 0) {
                blockedDomainSummary.add(domain + " (" + count + ")");
            }
        }
    }

    private void notifyStateChanged() {
        if (listener != null) {
            listener.onStateChanged();
        }
    }

    private void notifyReadyIfAvailable() {
        if (installed && configured && nativePort != null && listener != null) {
            listener.onReady();
        }
    }

    private static String normalizeDomain(String value) {
        if (value == null) {
            return null;
        }
        String domain = value.trim().toLowerCase(Locale.ROOT);
        if (domain.endsWith(".")) {
            domain = domain.substring(0, domain.length() - 1);
        }
        if (domain.isEmpty() || domain.length() > 253 || domain.indexOf(':') >= 0) {
            return null;
        }
        String[] labels = domain.split("\\.", -1);
        if (labels.length < 2) {
            return null;
        }
        for (String label : labels) {
            if (label.isEmpty() || label.length() > 63
                    || !label.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")) {
                return null;
            }
        }
        return domain;
    }

    private static boolean isHttpUrl(String value) {
        try {
            java.net.URI uri = new java.net.URI(value);
            String scheme = uri.getScheme();
            return uri.getHost() != null
                    && uri.getUserInfo() == null
                    && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme));
        } catch (java.net.URISyntaxException exception) {
            return false;
        }
    }
}
