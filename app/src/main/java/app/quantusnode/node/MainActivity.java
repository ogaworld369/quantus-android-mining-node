package app.quantusnode.node;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;

import app.quantusnode.node.depin.DepinFiles;
import app.quantusnode.node.depin.DepinIpc;
import app.quantusnode.node.depin.DepinTransferClient;
import app.quantusnode.node.depin.TransferFlow;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.animation.DecelerateInterpolator;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import app.quantusnode.node.depin.DepinBundleExport;
import app.quantusnode.node.depin.DepinFiles;
import app.quantusnode.node.depin.DepinIpc;
import app.quantusnode.node.depin.DepinModule;
import app.quantusnode.node.depin.DepinStatus;

import java.io.File;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;

/**
 * Dashboard. Since the vision UI (spec: depin-vision-ui-spec.md)
 * the activity carries six screens in one activity — three tabs
 * under a floating bottom navigation (home: the status and price
 * cards and the one primary action; network: the peer field;
 * member: the DePIN member card) and the three sub-screens the
 * app already had (node details with exact heights, sync gap and
 * diagnostics; the track record; settings) — switched by swapping
 * root views. On the tab screens, content groups into floating
 * cards over the void background; the sub-screens keep the
 * earlier flat layout.
 *
 * Everything shown is a fact the service recorded ({@link NodeState}
 * / {@link TrackStore}); the display rules themselves — which of the
 * five home presentations applies, when a sync gap may be shown, how
 * durations round, when a setting counts as pending — live in
 * {@link UiLogic} and are unit-tested there. The "failed" home is
 * driven by the service's stop classification, never by a leftover
 * exit code.
 *
 * All data comes from the node on this phone via local RPC (through
 * NodeService / NodeState). Nothing here talks to the outside.
 */
public class MainActivity extends Activity {

    // ---- Palette (Quantus brand tokens from quantus.com, dark only) --
    // void #0E0E0E / surface #181818 / flare #FF6B35 / content #E8E6E0
    // / green #4ADE80. Tokens.MUTED, Tokens.HAIRLINE and Tokens.FIELD_BORDER are
    // content composited over the background at the site's own alpha
    // steps (60% / 12% / 30%), as solid colours. The old 40% step
    // (Tokens.FAINT) is split since UI/UX phase 0: Tokens.FAINT for anything read
    // (text and cues — #656462 on the surface was ~2.9:1), Tokens.DECOR for
    // what is only seen (dots, hairline borders).
    /** How long the attempt-log reset note stays on the
     *  DePIN status after the reset ran (H-06): seven days —
     *  long enough to be seen at the next several opens,
     *  short enough not to become permanent furniture that
     *  hides newer notes. */
    private static final long ATTEMPT_RESET_NOTE_WINDOW_SEC =
            7L * 24 * 3600;

    /** The developer's public QTC receive address for voluntary
     *  donations, shown at the bottom of the Home screen (v4.0.0).
     *  A receive address is public information; nothing about it
     *  is a secret, and the app never sends anything to it on its
     *  own — donating is an action the user takes in a wallet. */
    private static final String DONATE_ADDRESS =
            "qzoqy3pcLsBcPpGFqj9BTv3UyvRB9o8ScnKqcQfSbQTR6odnu";

    private static final int REQ_NOTIF = 42;

    // ---- Screens ------------------------------------------------------
    // The vision UI (spec section 3 / design section 7.2): three
    // tabs — home, network, member — under a floating bottom
    // navigation; details / track / settings stay as sub-screens
    // reached from the tabs, exactly as before, and hide the bar.
    private static final int SCREEN_HOME = 0;
    private static final int SCREEN_DETAILS = 1;
    private static final int SCREEN_TRACK = 2;
    private static final int SCREEN_SETTINGS = 3;
    private static final int SCREEN_NETWORK = 4;
    private static final int SCREEN_MEMBER = 5;
    // Settings pages (UI/UX phase 1 hub, design 6.2): contiguous
    // so the back key can return to the hub as one range.
    private static final int SCREEN_SET_NODE = 6;
    private static final int SCREEN_SET_MINING = 7;
    private static final int SCREEN_SET_DEPIN = 8;
    private static final int SCREEN_SET_STORAGE = 9;
    private static final int SCREEN_SET_TRANSFER = 10;
    private static final int SCREEN_SET_EXTERNAL = 11;
    private static final int SCREEN_SET_LANGUAGE = 12;
    private static final int SCREEN_SET_ABOUT = 13;
    private static final int SCREEN_SET_LICENSES = 14;

    private final View[] screens = new View[15];
    private int currentScreen = SCREEN_HOME;
    /** The tab a sub-screen's back returns to. */
    private int tabScreen = SCREEN_HOME;

    // Bottom navigation
    private LinearLayout bottomNav;
    private final LinearLayout[] navButtons = new LinearLayout[3];
    private final NavIconView[] navIcons = new NavIconView[3];
    private final TextView[] navLabels = new TextView[3];

    // Home (vision): header, status card, price card, the action.
    private View stateDotView;
    private int stateDotColor = -1;
    private TextView statusView;
    private TextView vhBestLabel;
    private LinearLayout vhStatsRow;
    private TextView vhBestValue;
    private TextView vhPeersValue;
    private TextView vhUptimeValue;
    private TextView supplementView;
    private TextView firstRunView;
    private Button mainButton;
    private TextView pendingLinkView;
    private TextView priceDeltaView;
    private TextView priceValueView;
    private TextView priceSrcView;

    // Network tab
    private NetworkFieldView networkField;
    private TextView netNumView;
    private TextView netSubView;
    private TextView netTagView;
    private SparklineView sparkView;
    private TextView netSegNowBtn;
    private TextView netSeg24hBtn;
    private TextView netGhostNoteView;
    private LinearLayout tickerBox;
    private boolean netShow24h;
    // How-to-read sheet (stage 1 spec S1-02): an overlay panel
    // on the network root, built once, filled when opened.
    private FrameLayout readSheetOverlay;
    private ScrollView networkScrollView;
    private TextView readSheetBestView;
    private TextView readSheetFactsView;
    // Ticker (stage 2 spec S2-03): in-memory only, newest
    // first, capped at 50 lines; never persisted. The
    // baselines are DISPLAY baselines in the L-1 sense: adopted
    // at display start so a rebuild cannot manufacture events.
    private final java.util.List<String> tickerLines =
            new java.util.ArrayList<>();
    private boolean tickerInit;
    private boolean tickerRunning;
    private long tickerBest = -1;
    private int tickerPeers = Integer.MIN_VALUE;
    private long tickerCpCount = -1;
    /** The identity the checkpoint baseline belongs to
     *  (residual F-24): a count alone cannot tell this
     *  identity's growth from another lineage's journal. */
    private String tickerCpDeviceId;
    private long tickerCpGeneration = -1;
    private boolean tickerHeld;
    /** Best-block advance tracking for the stalled state:
     *  the best this UI has seen and when (wall) it last grew. */
    private long netAdvanceBest = -1;
    private long netAdvanceWallMs = -1;
    /** Residual F-22: when the current SEARCHING stretch
     *  began (elapsed clock), and which condition it is
     *  measuring — a measured zero or not-yet-measured.
     *  The searching line's elapsed time is read from
     *  here, not from the session's acceptance time. */
    private long netSearchSinceElapsedMs = -1;
    private boolean netSearchMeasuredZero = false;

    // Home (vision): today's-record row (stage 1 spec S1-10;
    // a row with a 2 dp bar since the UI/UX phase 1 list).
    private LinearLayout homeTodayRow;
    private TextView homeTodayText;
    private FrameLayout homeTodayTrack;
    private View homeTodayFill;

    // Home (vision): the mining row (v4.0.0; a row since the
    // UI/UX phase 1 list). Shown only while mining is in use;
    // an install with mining off never sees it.
    private LinearLayout homeMiningRow;
    private TextView homeMiningText;

    // Home (UI/UX phase 1): the price row's value and the two
    // home sheets (price, donation), overlays on the home root.
    private TextView priceRowValue;
    private FrameLayout priceSheetOverlay;
    private ToggleView priceFetchToggle;
    /** The price sheet's switch row and its locked notice
     *  (v4.2.2 audit, L6): the lock closes the row with the
     *  toggle, and the sheet says why, as the settings pages do. */
    private LinearLayout priceSheetSwitchRow;
    private TextView priceSheetLockedView;
    private FrameLayout donateSheetOverlay;
    private LinearLayout priceSheetPanel;
    private LinearLayout donateSheetPanel;
    private LinearLayout readSheetPanel;

    // Settings hub (UI/UX phase 1): the row values, and the
    // external page's price switch.
    private TextView hubMiningValue;
    private TextView hubDepinValue;
    private TextView hubStorageValue;
    private TextView hubExternalValue;
    private TextView hubLanguageValue;
    private ToggleView priceSwitch;
    private LinearLayout priceSwitchRow;
    private TextView priceLockedView;

    // Home (vision): age of the last block observation.
    private TextView vhBestAgoView;

    // Member tab
    private GlowCardView memberGlow;
    private FrameLayout memberCardBox;
    private LinearLayout memberContent;
    private View memberSpacer;
    private String memIdRaw;
    private boolean memIdGrouped = true;
    private TextView memberEmptyView;
    private TextView memNameView;
    private TextView memIdView;
    private TextView memSinceValue;
    private TextView memCpsValue;
    private TextView memDaysValue;
    private final java.util.List<TextView> statLabelViews =
            new java.util.ArrayList<>();
    private final java.util.List<TextView> statValueViews =
            new java.util.ArrayList<>();

    // Node details
    private LinearLayout detailsRunningBox;
    private LinearLayout detailsStoppedBox;
    private TextView detBestValue;
    private TextView detHighestValue;
    private TextView detGapValue;
    private TextView detPeersValue;
    private TextView detSessionValue;
    /** Node details' RPC line (v4.2.4, audit V4-7: it names the
     *  address the node reported, refreshed with the details). */
    private TextView rpcNoteView;
    private TextView cumUptimeValue;
    private TextView cumBlocksValue;
    private TextView cumPeersValue;
    private LinearLayout diagSection;
    private LinearLayout diagContent;
    private TextView diagText;
    private TextView diagChevron;

    // Track
    private TextView trackUptimeValue;
    private TextView trackBlocksValue;
    private TextView trackPeersValue;

    // Settings
    private LinearLayout telemetryRow;
    private ToggleView telemetrySwitch;
    private TextView telemetryPendingView;
    private TextView telemetryAppliedView;
    private LinearLayout depinRow;
    private ToggleView depinSwitch;
    private TextView depinDeviceIdValue;
    private TextView depinStateValue;
    private TextView depinCheckpointsValue;
    private TextView depinLastCheckpointValue;
    private TextView depinNoteView;
    // Settings-lock reason lines and the bundle export
    // (integration step 5): the lock disables controls silently,
    // so each affected section also says WHY while locked.
    private TextView telemetryLockedView;
    private TextView depinLockedView;
    // Mining (v4.0.0): the opt-in switch with its pending lines,
    // the two rows that open an editor (rewards inner hash, CPU
    // workers), and the status block that exists on screen only
    // while mining is in use. The inner hash itself is never held
    // in a field or shown by any of these views.
    private LinearLayout miningRow;
    private ToggleView miningSwitch;
    private TextView miningPendingView;
    private TextView miningAppliedView;
    private TextView miningLockedView;
    private LinearLayout miningRewardsRow;
    private TextView miningRewardsValue;
    private TextView miningRewardsPendingView;
    private LinearLayout miningWorkersRow;
    private TextView miningWorkersValue;
    private TextView miningWorkersPendingView;
    private LinearLayout miningStatusBox;
    private TextView miningStateValue;
    private TextView miningReasonView;
    private TextView miningPendingNoteView;
    private TextView miningNodeFailedView;
    private LinearLayout miningStatsBox;
    private TextView miningHashrateValue;
    private TextView miningJobsValue;
    private TextView miningSubmittedValue;
    private TextView miningAcceptedValue;
    private TextView miningAddressValue;
    private Button miningRestartButton;
    private TextView miningLogView;
    private AlertDialog miningDialog;
    private Button depinExportButton;
    private Button depinAttemptResetButton;
    private TextView depinExportNoteView;
    private static final int REQ_EXPORT_SAVE = 2103;
    // Transfer UI (integration step 4; consolidated into a
    // state-driven single main action per role): each role's
    // block has a state line ("what is done, what is next"),
    // ONE main button whose label and action follow the derived
    // state (TransferFlow), and a collapsed "other actions"
    // container for the off-path operations. Failures and
    // section 3.5 pauses land on the result line.
    private TextView depinTransferReceiverStateView;
    private TextView depinTransferSenderStateView;
    private TextView depinTransferResultView;
    private TextView depinTransferLockedView;
    private Button depinTransferReceiverMainButton;
    private Button depinTransferSenderMainButton;
    private Button depinTransferRepresentAckButton;
    private Button depinTransferSaveErrorButton;
    private Button depinTransferConfirmOneshotButton;
    private Button depinTransferCancelOneshotButton;
    private Button depinTransferReexportButton;
    /** The picked handover package's Uri (E1: after a
     *  successful intake the app tries to delete the source
     *  file; when it cannot, it prompts the user). */
    private android.net.Uri pickedPackageUri;
    private int receiverMainAction;
    private int senderMainAction;
    private boolean receiverOthersExpanded;
    private boolean senderOthersExpanded;
    /** The unnotified staging the last state derivation found
     *  (the resume target); refreshed with the state lines. */
    private volatile TransferFlow.StagingRef currentUnnotifiedStaging;
    /** The receiver record the last derivation resolved to. */
    private String currentReceiverDeviceId;
    private long currentReceiverGeneration = -1;
    /** The transfer state's active binding identity, from the
     *  latest derived snapshot (null when none). */
    private String currentTransferActiveDeviceId;
    private String currentReceiverPackageSha256;
    // Session hints for the receiver's two file-production
    // steps, which leave no durable trace of their own: once
    // the notice (receipt) has been saved, the main action
    // advances to the matching intake until the state moves
    // on. A hint is matched against the derived record
    // identity (device, generation, package hash), so it can
    // never fire on a different record; losing it (recreation)
    // simply returns to the export step, which is idempotent,
    // and both exports also live under "other actions".
    private String hintNoticeDeviceId;
    private long hintNoticeGeneration = -1;
    private String hintNoticePackageSha256;
    private String hintReceiptDeviceId;
    private long hintReceiptGeneration = -1;
    private String hintReceiptPackageSha256;
    /** Generation of the completed (S2) send whose ack the
     *  re-presentation action re-saves; -1 when none. */
    private long currentCompletedSenderGeneration = -1;
    private boolean representAckAvailable;
    /** The sender record the last derivation resolved to. */
    private String currentSenderDeviceId;
    private long currentSenderGeneration = -1;
    /** The open sender record is a one-shot handover. */
    private boolean oneshotSenderOpen;
    /** A staged, notified receiver record can be confirmed in
     *  one-shot mode (the confirm-oneshot other-action). */
    private boolean oneshotConfirmAvailable;
    /** Audit M1: the staged identity differs from the live
     *  one, so the one-shot confirm is not offered anywhere
     *  (E8 would refuse it at confirm time regardless). */
    private boolean currentOneshotBlockedByLive;
    // The most recent transfer failure, held for the error-file
    // export (metadata only — see TransferErrorReport; nothing
    // here can hold a secret: class names, messages, ids,
    // counts and durable state labels only).
    private String pendingErrorOp;
    private String pendingErrorMode;
    private String pendingErrorRole;
    private String pendingErrorReason;
    private long pendingErrorWhenMs;
    private String pendingErrorDeviceId;
    private String pendingErrorCounterpartId;
    private long pendingErrorGeneration = -1;
    private String pendingErrorPackageSha256;
    private long pendingErrorTotal = -1;
    private long pendingErrorVerified = -1;
    private final java.util.List<Button> depinTransferButtons =
            new java.util.ArrayList<>();
    // Audit H-3 / M-4: transfer operations and bundle exports
    // run strictly one at a time. A single-thread executor
    // serialises the background work (each tap formerly
    // spawned its own thread over the same transfer state,
    // staging files and seed material), and the busy flags
    // disable the buttons while an operation is in flight.
    private final java.util.concurrent.ExecutorService
            depinWorkExecutor =
            java.util.concurrent.Executors.newSingleThreadExecutor(
                    r -> {
                        Thread t = new Thread(r, "depin-ui-work");
                        t.setDaemon(true);
                        return t;
                    });
    private boolean transferBusy;
    private boolean exportBusy;
    // Storage section (v4.1.0). The figures come from a walk of the
    // app's directories and the resync deletes a directory tree, so
    // both run off the UI thread, one at a time, on the process's
    // one storage worker. Whether a resync is running and how the
    // last one ended is the process's to know, not this instance's
    // (StorageResetState): an activity recreated mid-deletion still
    // shows it busy and then its result.
    /** While the settings screen is open the figures are measured
     *  again this often. */
    private static final long STORAGE_REMEASURE_MS = 15000;
    private TextView storageChainDbValue;
    private TextView storageNodeValue;
    private TextView storageLogsValue;
    private TextView storageDepinValue;
    private TextView storageTotalValue;
    private TextView storageFreeValue;
    private TextView storageRunningNote;
    private Button storageResyncButton;
    private TextView storageNeedsStopNote;
    private TextView storageResultView;
    private StorageLogic.Usage storageUsage;
    private boolean storageMeasuring;
    private long storageMeasuredElapsedMs = -1;
    /** The finished resync run whose result this instance has
     *  taken in (and measured again after). */
    private long storageResetSeen;
    /** A line of this screen's own — the resync was not even
     *  attempted — shown until the next run starts. */
    private String storageNote;
    private static final int REQ_TRANSFER_OPEN = 2101;
    private static final int REQ_TRANSFER_SAVE = 2102;
    private static final int T_OPEN_PACKAGE = 1;
    private static final int T_OPEN_PERMIT = 2;
    private static final int T_OPEN_ACK = 3;
    private static final int T_OPEN_NOTICE = 4;
    private static final int T_OPEN_RECEIPT = 5;
    private static final int T_OPEN_ABANDON = 6;
    private static final int T_OPEN_CANCEL_TOKEN = 7;
    private static final int ACT_IMPORT_PACKAGE = 11;
    private static final int ACT_EXPORT_NOTICE = 12;
    private static final int ACT_IMPORT_PERMIT = 13;
    private static final int ACT_CONFIRM = 14;
    private static final int ACT_EXPORT_RECEIPT = 15;
    private static final int ACT_IMPORT_ACK = 16;
    private static final int ACT_IMPORT_CANCEL_TOKEN = 17;
    private static final int ACT_EXPORT_ABANDON = 18;
    private static final int ACT_EXPORT_PACKAGE = 21;
    private static final int ACT_IMPORT_NOTICE = 22;
    private static final int ACT_ISSUE_PERMIT = 23;
    private static final int ACT_CANCEL_TRANSFER = 24;
    private static final int ACT_IMPORT_RECEIPT = 25;
    private static final int ACT_IMPORT_ABANDON = 26;
    private static final int ACT_REPRESENT_ACK = 27;
    private static final int ACT_RESUME_INTAKE = 31;
    private static final int ACT_EXPORT_PACKAGE_ONESHOT = 32;
    private static final int ACT_CONFIRM_ONESHOT = 33;
    private static final int ACT_CLOSE_ONESHOT = 34;
    private static final int ACT_CANCEL_ONESHOT = 35;
    private static final int ACT_REEXPORT_ONESHOT = 38;
    private static final int ACT_SAVE_ERROR = 36;
    private int pendingTransferOpen;
    // H-1 IPC: a produced transfer message is no longer held
    // as bytes in this process; the :depin process stages it
    // in a file and this field holds that file (or, for the
    // sender package, the core outbox file) until the save
    // picker returns a destination to stream it to.
    private java.io.File pendingTransferSaveFile;
    private volatile app.quantusnode.node.depin.core.TransferReceiver
            .IntakeResult pendingStaged;
    private LinearLayout nameRow;
    private TextView nameValue;
    private TextView nameChevron;
    private TextView namePendingView;
    private TextView nameAppliedView;
    private TextView nameLockedView;
    private LinearLayout languageRow;
    private TextView languageValue;
    private ToggleView lockSwitch;

    // ---- UI state ------------------------------------------------------
    /** Stop requested, service has not finished yet (spec H): an
     *  operation flag, not a sixth state. */
    private boolean stopping = false;
    private boolean diagExpanded = false;
    /** Set by the failure / stop detail links: this once, the saved
     *  scroll position yields to landing on the diagnostics section. */
    private boolean scrollToDiagnosticsOnce = false;
    /** Guards programmatic switch syncs from re-entering listeners. */
    private boolean syncingSettings = false;
    private String lastDiagText;
    private AlertDialog nameDialog;
    private AlertDialog languageDialog;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean firstRefresh = true;
    /**
     * The observation baseline the ripple keys off. Static — outside
     * the views and outside the activity instance — so a rebuild
     * (language change) alone can never fire a ripple (spec G).
     * The baseline is the snapshot of the service's observation the
     * UI adopts when it (re)starts displaying (onCreate / onResume):
     * adopting raises the baseline to the snapshot as a VALUE, never
     * as a firing, so the first increase detected after a cold
     * start ripples like any other, while blocks observed while the
     * UI was away are never replayed (audit finding L-1).
     */
    private static long sLastRippleObservedBest = -1;
    /** True once a display-start snapshot has been adopted. */
    private static boolean sRippleBaselineReady = false;

    /** The primary button's backgrounds, built once per instance
     *  and reused by every refresh (audit finding L-5). */
    private android.graphics.drawable.Drawable mainBtnAccentBg;
    private android.graphics.drawable.Drawable mainBtnSurfaceBg;
    private android.graphics.drawable.Drawable mainBtnStoppingBg;

    /** Settings rows that can anchor a language-change restore
     *  (spec G), in visual order; each carries a stable view id. */
    private final java.util.List<View> settingsAnchors =
            new java.util.ArrayList<>();

    // Carried across a language-change recreate() (spec G): current
    // screen, per-screen scroll offsets, section open states, and
    // whether focus returns to the language row.
    private static int sScreenAfterRecreate = -1;
    private static int[] sScrollAfterRecreate;
    private static boolean sDiagAfterRecreate;
    private static boolean sFocusLanguageAfterRecreate;
    /** Anchor restore for the settings screen (spec G): the stable
     *  id of the row at the top of the viewport when the language
     *  changed, and the scroll offset inside that row. -1 = none
     *  (fall back to the raw scroll offset). */
    private static int sAnchorIdAfterRecreate = -1;
    private static int sAnchorOffsetAfterRecreate = 0;

    // Stable ids for the settings anchor rows (spec G). Generated
    // once per process; the same rows get the same ids in every
    // activity instance, which is what makes an anchor recorded
    // before recreate() findable after it.
    private static final int ID_ANCHOR_TELEMETRY = View.generateViewId();
    private static final int ID_ANCHOR_MINING = View.generateViewId();
    private static final int ID_ANCHOR_DEPIN = View.generateViewId();
    private static final int ID_ANCHOR_NAME = View.generateViewId();
    private static final int ID_ANCHOR_LANGUAGE = View.generateViewId();
    private static final int ID_ANCHOR_LOCK = View.generateViewId();
    private static final int ID_ANCHOR_PRICE = View.generateViewId();

    private final Runnable refresher = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 1000);
        }
    };

    /**
     * Adopt the service's current observation as the ripple
     * baseline (finding L-1): the display-start snapshot is taken
     * as the baseline VALUE, so nothing about this moment can fire
     * a ripple, and the first increase after it can.
     */
    private static void adoptRippleBaseline() {
        NodeState st = NodeState.get();
        if (st.lastObservedBestBlock > sLastRippleObservedBest) {
            sLastRippleObservedBest = st.lastObservedBestBlock;
        }
        sRippleBaselineReady = true;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // The price fetch identifies itself with this version
        // (PriceStore, v4.2.2 B-9).
        PriceStore.initUserAgent(this);
        // v4.2.4 (audit V4-1): an endpoint record no running node
        // stands behind goes before the first refresh, whose DePIN
        // reconcile can relaunch the observer that reads it.
        NodeService.clearStaleRpcEndpointRecord(this);
        // Transfer picker hand-offs survive recreation: the
        // document picker's result is delivered to the RECREATED
        // activity, and the pending kind / save source are plain
        // instance state. Without this restore, a recreation
        // while a picker was open silently dropped the picked
        // file's dispatch (kind 0 matches nothing) or the save
        // (null source) — one of the first production attempt's
        // failure paths.
        if (savedInstanceState != null) {
            pendingTransferOpen = savedInstanceState.getInt(
                    "transfer_pending_open", 0);
            String savePath = savedInstanceState.getString(
                    "transfer_pending_save");
            if (savePath != null) {
                pendingTransferSaveFile = new java.io.File(savePath);
            }
        }
        // Before the first refresh runs: adopt the display-start
        // snapshot as the ripple baseline (finding L-1).
        adoptRippleBaseline();
        int dir = I18n.isRtl(this)
                ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR;
        getWindow().getDecorView().setLayoutDirection(dir);
        if (sScreenAfterRecreate >= 0) {
            currentScreen = sScreenAfterRecreate;
            diagExpanded = sDiagAfterRecreate;
        }
        buildUi();
        showScreen(currentScreen);
        refresh();
        // The DePIN settings' identity binding is NOT synced
        // from here (G-01: the settings writers are :depin-only
        // — the audit M6 sync this replaces ran in the main
        // process, and a main-process SharedPreferences write
        // is exactly the F-03 hazard). The sync now happens
        // where the settings are owned: at the observer's
        // initialisation and when the transfer service is
        // bound (both :depin). This process only renders the
        // status snapshot the facade merges, whose binding the
        // :depin side keeps current.
        if (sScrollAfterRecreate != null) {
            final int[] ys = sScrollAfterRecreate;
            final boolean focusLang = sFocusLanguageAfterRecreate;
            final int anchorId = sAnchorIdAfterRecreate;
            final int anchorOffset = sAnchorOffsetAfterRecreate;
            sScrollAfterRecreate = null;
            sScreenAfterRecreate = -1;
            sFocusLanguageAfterRecreate = false;
            sAnchorIdAfterRecreate = -1;
            sAnchorOffsetAfterRecreate = 0;
            for (int i = 0; i < screens.length; i++) {
                if (!(screens[i] instanceof ScrollView)) {
                    continue;
                }
                final ScrollView sv = (ScrollView) screens[i];
                final int y = ys[i];
                if (i == SCREEN_SETTINGS && anchorId >= 0) {
                    // Anchor restore (spec G): translations change
                    // row heights, so the raw offset is meaningless
                    // for this screen — land the same row at the
                    // same in-row offset instead.
                    sv.post(() -> {
                        View anchor = sv.findViewById(anchorId);
                        if (anchor != null && sv.getChildAt(0) != null) {
                            sv.scrollTo(0, Math.max(0,
                                    offsetInContent(anchor, sv.getChildAt(0))
                                            + anchorOffset));
                        } else {
                            sv.scrollTo(0, Math.max(0, y));
                        }
                    });
                } else if (y > 0) {
                    sv.post(() -> sv.scrollTo(0, y));
                }
            }
            if (focusLang && languageRow != null) {
                // Focus returns to the language row (spec G); the
                // scroll position is the anchor's job, not focus's.
                languageRow.post(() -> languageRow.requestFocus());
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Coming back to the screen must not replay ripples for
        // blocks observed while away (spec H): adopt the service's
        // current observation as the baseline before the tick runs.
        adoptRippleBaseline();
        handler.removeCallbacks(refresher);
        handler.post(refresher);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(refresher);
        // UI/UX phase 0: the frame loops belong to the visible tab
        // only; leaving the activity stops them too (showScreen is
        // the only other place that drives these).
        if (networkField != null) {
            networkField.setFieldActive(false);
        }
        if (memberGlow != null) {
            memberGlow.setCardActive(false);
        }
    }

    @Override
    public void onBackPressed() {
        // Dialogs take the back press first (the platform routes it
        // to a showing dialog before this is ever called). Sub-screen
        // back goes to the tab it was opened from; a non-home tab
        // goes home; home back is the platform default and never
        // sends a stop request (spec G).
        // UI/UX phase 0: the how-to-read sheet is an overlay, not a
        // screen; back closes it before anything else moves.
        if (readSheetOverlay != null
                && readSheetOverlay.getVisibility() == View.VISIBLE) {
            closeSheet(readSheetOverlay);
            return;
        }
        if (priceSheetOverlay != null
                && priceSheetOverlay.getVisibility() == View.VISIBLE) {
            closeSheet(priceSheetOverlay);
            return;
        }
        if (donateSheetOverlay != null
                && donateSheetOverlay.getVisibility() == View.VISIBLE) {
            closeSheet(donateSheetOverlay);
            return;
        }
        if (currentScreen == SCREEN_SET_LICENSES) {
            // The licences page is a child of About, not of the
            // hub: back returns to About (v4.2.2 audit, I10).
            showScreen(SCREEN_SET_ABOUT);
            return;
        }
        if (isSettingsPage(currentScreen)) {
            showScreen(SCREEN_SETTINGS);
            return;
        }
        if (!isTabScreen(currentScreen)) {
            showScreen(tabScreen);
            return;
        }
        if (currentScreen != SCREEN_HOME) {
            showScreen(SCREEN_HOME);
            return;
        }
        super.onBackPressed();
    }

    // ------------------------------------------------------------------
    // UI construction
    // ------------------------------------------------------------------

    private int dp(float v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    /** Side padding: 24 dp, growing so the column caps at 560 dp. */
    private int sidePadding() {
        int w = getResources().getDisplayMetrics().widthPixels;
        return Math.max(dp(24), (w - dp(560)) / 2);
    }

    /**
     * The floating bottom nav's zone, in dp from the screen's
     * bottom edge: its 22 dp bottom margin plus its own height
     * (6+6 padding around a button of 8+20 icon + 3 + ~15 label
     * + 8). Tab content that must never rest under the pill is
     * kept clear of this zone.
     */
    private int navZone() {
        return dp(92);
    }

    /** Bottom padding for a tab's scroll column: the nav zone
     *  plus a breathing gap, so the last row can scroll fully
     *  clear of the pill. */
    private int navClearance() {
        return navZone() + dp(32);
    }

    private void buildUi() {
        int dir = I18n.isRtl(this)
                ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR;
        FrameLayout container = new FrameLayout(this);
        container.setBackgroundColor(Tokens.VOID);
        container.setLayoutDirection(dir);
        screens[SCREEN_HOME] = buildHome(container, dir);
        screens[SCREEN_DETAILS] = buildDetails(container, dir);
        screens[SCREEN_TRACK] = buildTrack(container, dir);
        screens[SCREEN_SETTINGS] = buildSettings(container, dir);
        screens[SCREEN_SET_NODE] = buildNodeSettingsPage(container, dir);
        screens[SCREEN_SET_MINING] =
                buildMiningSettingsPage(container, dir);
        screens[SCREEN_SET_DEPIN] = buildDepinSettingsPage(container, dir);
        screens[SCREEN_SET_STORAGE] =
                buildStorageSettingsPage(container, dir);
        screens[SCREEN_SET_TRANSFER] =
                buildTransferSettingsPage(container, dir);
        screens[SCREEN_SET_EXTERNAL] =
                buildExternalSettingsPage(container, dir);
        screens[SCREEN_SET_LANGUAGE] =
                buildLanguageSettingsPage(container, dir);
        screens[SCREEN_SET_ABOUT] = buildAboutSettingsPage(container, dir);
        screens[SCREEN_SET_LICENSES] =
                buildLicencesPage(container, dir);
        screens[SCREEN_NETWORK] = buildNetwork(container, dir);
        screens[SCREEN_MEMBER] = buildMember(container, dir);
        buildBottomNav(container, dir);
        container.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars =
                    insets.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        setContentView(container);
    }

    /** A screen: ScrollView + vertical content column. */
    private LinearLayout screenContent(FrameLayout container, int screen,
                                       int dir) {
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Tokens.VOID);
        sv.setLayoutDirection(dir);
        sv.setVisibility(View.GONE);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int side = sidePadding();
        root.setPadding(side, dp(24), side, dp(24));
        root.setLayoutDirection(dir);
        sv.addView(root);
        container.addView(sv, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        screens[screen] = sv;
        return root;
    }

    private static boolean isSettingsPage(int screen) {
        return screen >= SCREEN_SET_NODE && screen <= SCREEN_SET_LICENSES;
    }

    private static boolean isTabScreen(int screen) {
        return screen == SCREEN_HOME
                || screen == SCREEN_NETWORK
                || screen == SCREEN_MEMBER;
    }

    /** Whether motion is welcome: the same
     *  ANIMATOR_DURATION_SCALE read the custom views use
     *  (design 9, layer 3). Zero means every transition is
     *  instant. */
    private boolean motionEnabled() {
        try {
            float scale = Settings.Global.getFloat(
                    getContentResolver(),
                    Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
            return scale != 0f;
        } catch (RuntimeException e) {
            return true;
        }
    }

    /** Sheet entrance (design 9, layer 3): the scrim fades in
     *  over 160ms while the panel rises over 240ms. Closing
     *  stays instant. */
    private void animateSheetIn(View overlay, View panel) {
        if (!motionEnabled()) {
            return;
        }
        overlay.setAlpha(0f);
        overlay.animate().alpha(1f).setDuration(160).start();
        if (panel != null) {
            panel.setTranslationY(dp(48));
            panel.animate().translationY(0f).setDuration(240)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        }
    }

    /** The spoken name of a screen, for the transition
     *  announcement (design 9, layer 3). */
    private String screenName(int screen) {
        switch (screen) {
            case SCREEN_HOME:
                return I18n.get(this, StringsTable.VISION_NAV_HOME);
            case SCREEN_NETWORK:
                return I18n.get(this, StringsTable.VISION_NAV_NETWORK);
            case SCREEN_MEMBER:
                return I18n.get(this, StringsTable.VISION_NAV_MEMBER);
            case SCREEN_DETAILS:
                return I18n.get(this, StringsTable.SCREEN_NODE_DETAILS);
            case SCREEN_TRACK:
                return I18n.get(this, StringsTable.SECTION_TRACK);
            case SCREEN_SETTINGS:
                return I18n.get(this, StringsTable.SECTION_SETTINGS);
            case SCREEN_SET_NODE:
                return I18n.get(this, StringsTable.SET_NODE_TITLE);
            case SCREEN_SET_MINING:
                return I18n.get(this, StringsTable.MINING_SECTION);
            case SCREEN_SET_STORAGE:
                return I18n.get(this, StringsTable.STORAGE_SECTION);
            case SCREEN_SET_DEPIN:
                return I18n.get(this,
                        StringsTable.SETTINGS_DEPIN_SECTION);
            case SCREEN_SET_TRANSFER:
                return I18n.get(this,
                        StringsTable.DEPIN_TRANSFER_SECTION);
            case SCREEN_SET_EXTERNAL:
                return I18n.get(this, StringsTable.SET_EXTERNAL_TITLE);
            case SCREEN_SET_LANGUAGE:
                return I18n.get(this, StringsTable.SETTINGS_LANGUAGE);
            case SCREEN_SET_ABOUT:
                return I18n.get(this, StringsTable.SET_ABOUT_TITLE);
            case SCREEN_SET_LICENSES:
                return I18n.get(this, StringsTable.SET_LICENSES_TITLE);
            default:
                return null;
        }
    }

    private void showScreen(int screen) {
        int previous = currentScreen;
        currentScreen = screen;
        // UI/UX phase 0: the how-to-read sheet never survives a
        // screen change. UI/UX phase 1: neither do the home
        // sheets.
        closeSheet(readSheetOverlay);
        closeSheet(priceSheetOverlay);
        closeSheet(donateSheetOverlay);
        if (isTabScreen(screen)) {
            tabScreen = screen;
        }
        for (int i = 0; i < screens.length; i++) {
            if (screens[i] != null) {
                // Land on final states first: a fast double
                // switch must not inherit a half-run transition.
                screens[i].animate().cancel();
                screens[i].setAlpha(1f);
                screens[i].setTranslationX(0f);
                screens[i].setTranslationY(0f);
                screens[i].setVisibility(
                        i == screen ? View.VISIBLE : View.GONE);
            }
        }
        // Screen motion and the accessibility announcement are
        // one piece of work (design 9, layer 3): tabs crossfade
        // with a small rise, a push slides in at 200ms, a pop
        // slides back at 160ms, and the new screen's name is
        // announced either way.
        View incoming = screens[screen];
        if (incoming != null && previous >= 0 && previous != screen) {
            if (motionEnabled()) {
                // The slide follows the language the app is
                // showing, not the device's: with the app in
                // Arabic on an LTR system (or the reverse) the
                // system direction slid pages the wrong way
                // (v4.2.2, audit L-2).
                boolean rtl = I18n.isRtl(this);
                int dirSign = rtl ? -1 : 1;
                if (isTabScreen(screen) && isTabScreen(previous)) {
                    incoming.setAlpha(0f);
                    incoming.setTranslationY(dp(4));
                    incoming.animate().alpha(1f).translationY(0f)
                            .setDuration(160)
                            .setInterpolator(
                                    new DecelerateInterpolator())
                            .start();
                } else if (isTabScreen(screen)
                        || (screen == SCREEN_SETTINGS
                                && isSettingsPage(previous))
                        || (screen == SCREEN_SET_ABOUT
                                && previous == SCREEN_SET_LICENSES)) {
                    // Back to a tab, or back from a settings page
                    // to the hub — and back from the licences page
                    // to its parent, About: the returning slide.
                    // Arriving at the hub FROM a tab is a forward
                    // move and takes the push slide below — it used
                    // to be treated as a return unconditionally
                    // (v4.2.2, audit L-1; the licences return is
                    // v4.2.2 audit, I10).
                    incoming.setAlpha(0f);
                    incoming.setTranslationX(-dirSign * dp(24));
                    incoming.animate().alpha(1f).translationX(0f)
                            .setDuration(160)
                            .setInterpolator(
                                    new DecelerateInterpolator())
                            .start();
                } else {
                    incoming.setTranslationX(dirSign * dp(24));
                    incoming.animate().translationX(0f)
                            .setDuration(200)
                            .setInterpolator(
                                    new DecelerateInterpolator())
                            .start();
                }
            }
            String name = screenName(screen);
            if (name != null) {
                incoming.announceForAccessibility(name);
            }
        }
        // The bar belongs to the tabs; sub-screens hide it.
        if (bottomNav != null) {
            bottomNav.setVisibility(
                    isTabScreen(screen) ? View.VISIBLE : View.GONE);
            applyNavSelection();
        }
        // The field and the card glow run their frame loops only
        // while their tab is the visible one.
        if (networkField != null) {
            networkField.setFieldActive(screen == SCREEN_NETWORK);
        }
        if (memberGlow != null) {
            memberGlow.setCardActive(screen == SCREEN_MEMBER);
        }
        if (screen == SCREEN_DETAILS && scrollToDiagnosticsOnce) {
            scrollToDiagnosticsOnce = false;
            diagSection.post(() -> {
                ScrollView sv = (ScrollView) screens[SCREEN_DETAILS];
                int y = offsetInContent(diagSection, sv.getChildAt(0));
                sv.scrollTo(0, Math.max(0, y - dp(8)));
            });
        }
    }

    /** Highlight the nav button of the current tab. */
    private void applyNavSelection() {
        int active = currentScreen == SCREEN_NETWORK ? 1
                : currentScreen == SCREEN_MEMBER ? 2 : 0;
        float density = getResources().getDisplayMetrics().density;
        for (int i = 0; i < navButtons.length; i++) {
            if (navButtons[i] == null) {
                continue;
            }
            boolean on = i == active;
            navButtons[i].setBackground(on
                    ? rounded(Color.argb(20, 0xE8, 0xE6, 0xE0),
                            999, density, 0, 0)
                    : null);
            navIcons[i].setIconColor(on ? Tokens.CONTENT : Tokens.FAINT);
            navLabels[i].setTextColor(on ? Tokens.CONTENT : Tokens.FAINT);
        }
    }

    /** Vertical offset of a view inside its scroll content root. */
    private static int offsetInContent(View v, View contentRoot) {
        int y = 0;
        View cur = v;
        while (cur != null && cur != contentRoot) {
            y += cur.getTop();
            Object parent = cur.getParent();
            cur = parent instanceof View ? (View) parent : null;
        }
        return y;
    }

    // ---- Shared pieces -------------------------------------------------

    private TextView text(String s, float sp, int color, boolean medium) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(medium
                ? Typeface.create("sans-serif-medium", Typeface.NORMAL)
                : Typeface.DEFAULT);
        t.setLineSpacing(0, 1.15f);
        return t;
    }

    private LinearLayout.LayoutParams matchLp() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams matchLpTop(int topDp) {
        LinearLayout.LayoutParams lp = matchLp();
        lp.topMargin = dp(topDp);
        return lp;
    }

    private View hairline(int topDp) {
        View v = new View(this);
        v.setBackgroundColor(Tokens.HAIRLINE);
        LinearLayout.LayoutParams lp = matchLpTop(topDp);
        lp.height = Math.max(1, dp(1));
        v.setLayoutParams(lp);
        return v;
    }

    private static GradientDrawable rounded(int color, float radiusDp,
                                            float density, int strokeColor,
                                            int strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusDp * density);
        if (strokeDp > 0) {
            g.setStroke((int) (strokeDp * density), strokeColor);
        }
        return g;
    }

    /** Press feedback: a 12% accent ripple over the given base. */
    private RippleDrawable ripple(android.graphics.drawable.Drawable base) {
        return new RippleDrawable(
                ColorStateList.valueOf(Tokens.FLARE_RIPPLE), base, null);
    }

    // The primary button's three dresses, cached per instance:
    // refresh() runs every second and must not rebuild drawables
    // it only re-applies (finding L-5).

    /** Accent fill, pill (start / retry; vision spec 4.1). */
    private android.graphics.drawable.Drawable accentButtonBg() {
        if (mainBtnAccentBg == null) {
            float d = getResources().getDisplayMetrics().density;
            mainBtnAccentBg = ripple(rounded(Tokens.FLARE, 999, d, 0, 0));
        }
        return mainBtnAccentBg;
    }

    /** Bordered surface-2, pill (stop / cancel startup). */
    private android.graphics.drawable.Drawable surfaceButtonBg() {
        if (mainBtnSurfaceBg == null) {
            float d = getResources().getDisplayMetrics().density;
            mainBtnSurfaceBg = ripple(rounded(Tokens.SURFACE2, 999, d, Tokens.LINE, 1));
        }
        return mainBtnSurfaceBg;
    }

    /** Plain surface, no press ripple (stopping, disabled). */
    private android.graphics.drawable.Drawable stoppingButtonBg() {
        if (mainBtnStoppingBg == null) {
            float d = getResources().getDisplayMetrics().density;
            mainBtnStoppingBg = rounded(Tokens.SURFACE2, 999, d, 0, 0);
        }
        return mainBtnStoppingBg;
    }

    /**
     * A label/value information row: label at start, value at end,
     * at least 24 dp between them, 56 dp tall. When the two sides
     * cannot share one line they stack, label above value (§6);
     * nothing is shrunk or ellipsized away. The measuring and the
     * fallback live in {@link InfoRowView}.
     */
    private TextView infoRow(LinearLayout parent, String label,
                             boolean hairlineAbove) {
        if (hairlineAbove) {
            parent.addView(hairline(0));
        }
        InfoRowView r = new InfoRowView(this);
        TextView l = text(label, 16, Tokens.MUTED, false);
        r.addView(l);
        TextView v = text("", 16, Tokens.CONTENT, false);
        v.setGravity(Gravity.END);
        r.addView(v);
        parent.addView(r, matchLp());
        return v;
    }

    /** A full-width tappable row with a trailing chevron. */
    private LinearLayout navRow(LinearLayout parent, String label,
                                int topDp, View.OnClickListener click) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(dp(56));
        r.setPadding(0, dp(12), 0, dp(12));
        r.setBackground(ripple(null));
        r.setClickable(true);
        r.setFocusable(true);
        r.setOnClickListener(click);
        TextView l = text(label, 16, Tokens.CONTENT, false);
        r.addView(l, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView chev = text(I18n.isRtl(this) ? "‹" : "›", 22, Tokens.FAINT, false);
        r.addView(chev);
        parent.addView(r, matchLpTop(topDp));
        return r;
    }

    /** Sub-screen header: back target + 20 sp title. */
    private void subHeader(LinearLayout root, String title) {
        subHeaderTo(root, title, -1);
    }

    /** Sub-screen header whose back leads to a fixed screen
     *  (the settings hub, for its pages); -1 keeps the tab the
     *  screen was opened from. */
    private void subHeaderTo(LinearLayout root, String title,
                             int backScreen) {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.setMinimumHeight(dp(56));
        TextView back = text(I18n.isRtl(this) ? "›" : "‹", 30, Tokens.MUTED, false);
        back.setGravity(Gravity.CENTER);
        back.setContentDescription(I18n.get(this, StringsTable.A11Y_BACK));
        back.setBackground(ripple(null));
        back.setClickable(true);
        back.setFocusable(true);
        back.setOnClickListener(v -> showScreen(
                backScreen >= 0 ? backScreen : tabScreen));
        h.addView(back, new LinearLayout.LayoutParams(dp(48), dp(48)));
        TextView t = text(title, 20, Tokens.CONTENT, true);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tlp.setMarginStart(dp(4));
        h.addView(t, tlp);
        root.addView(h, matchLp());
    }

    /** A 14 sp medium section heading for expandable sections. */
    private TextView sectionLabel(String s) {
        return text(s, 14, Tokens.MUTED, true);
    }

    /**
     * Give a dialog the redesign's surface instead of the platform
     * theme's (finding L-7): #181818 with 16 dp corners, applied to
     * the dialog window once it exists. The platform layout keeps
     * its own inner padding; only the surface behind it changes.
     */
    private void styleDialog(AlertDialog dialog) {
        if (dialog == null || dialog.getWindow() == null) {
            return;
        }
        float d = getResources().getDisplayMetrics().density;
        dialog.getWindow().setBackgroundDrawable(
                rounded(Tokens.SURFACE, 16, d, 0, 0));
    }

    // ------------------------------------------------------------------
    // Home
    // ------------------------------------------------------------------

    /** A vision card surface: surface fill, the 8% line, 24 dp
     *  corners (spec sections 1 and 3). */
    private android.graphics.drawable.Drawable cardBg() {
        float d = getResources().getDisplayMetrics().density;
        return rounded(Tokens.SURFACE, 24, d, Tokens.LINE, 1);
    }

    /** The official logo in its rounded tile (spec section 7):
     *  the artwork itself is the vector drawable, unmodified;
     *  the rounding belongs to the tile around it. */
    private ImageView logoView(int sizeDp, int radiusDp) {
        ImageView iv = new ImageView(this);
        iv.setImageResource(R.drawable.quantus_logo);
        iv.setScaleType(ImageView.ScaleType.FIT_XY);
        final float radius = dp(radiusDp);
        iv.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(),
                        view.getHeight(), radius);
            }
        });
        iv.setClipToOutline(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                dp(sizeDp), dp(sizeDp));
        iv.setLayoutParams(lp);
        return iv;
    }

    /** A small label in the vision cards (13 sp, muted). */
    private TextView cardLabel(String s) {
        return text(s, 12, Tokens.MUTED, false);
    }

    /** The first-run paragraph's text (design 3.2): the node and
     *  chain sentences form one paragraph, then the record and
     *  first-sync paragraphs. Japanese and Chinese join sentences
     *  without a space; the other languages take one. */
    private String firstRunText() {
        String tag = I18n.effectiveTag(this);
        String joiner = ("ja".equals(tag) || "zh".equals(tag)) ? "" : " ";
        return I18n.get(this, StringsTable.FIRST_RUN_NODE) + joiner
                + I18n.get(this, StringsTable.FIRST_RUN_CHAIN) + "\n\n"
                + I18n.get(this, StringsTable.FIRST_RUN_RECORD) + "\n\n"
                + I18n.get(this, StringsTable.FIRST_RUN_SYNC);
    }

    private ScrollView buildHome(FrameLayout container, int dir) {
        LinearLayout root = screenContent(container, SCREEN_HOME, dir);
        // The floating nav overlays the bottom; give the column
        // room to scroll clear of it (nav zone + gap, see
        // navClearance()).
        root.setPadding(root.getPaddingLeft(), dp(24),
                root.getPaddingRight(), navClearance());

        // Header: logo tile + name + settings icon.
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setMinimumHeight(dp(56));
        header.addView(logoView(32, 9));
        TextView title = text("Quantus Node", 20, Tokens.CONTENT, true);
        title.setTypeface(VisionFonts.displayBold(this));
        LinearLayout.LayoutParams ttlp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        ttlp.setMarginStart(dp(12));
        header.addView(title, ttlp);
        SettingsIconView settingsIcon = new SettingsIconView(this);
        settingsIcon.setContentDescription(
                I18n.get(this, StringsTable.VISION_SETTINGS));
        settingsIcon.setBackground(ripple(null));
        settingsIcon.setOnClickListener(v -> showScreen(SCREEN_SETTINGS));
        header.addView(settingsIcon, new LinearLayout.LayoutParams(dp(48), dp(48)));
        root.addView(header, matchLp());

        // Status card: state, the big best number, peers/uptime.
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(cardBg());
        card.setPadding(dp(26), dp(26), dp(26), dp(26));
        LinearLayout stateRow = new LinearLayout(this);
        stateRow.setOrientation(LinearLayout.HORIZONTAL);
        stateRow.setGravity(Gravity.CENTER_VERTICAL);
        stateDotView = new View(this);
        stateRow.addView(stateDotView,
                new LinearLayout.LayoutParams(dp(7), dp(7)));
        statusView = text("", 20, Tokens.CONTENT, true);
        statusView.setTypeface(VisionFonts.displayMedium(this));
        LinearLayout.LayoutParams stlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        stlp.setMarginStart(dp(9));
        stateRow.addView(statusView, stlp);
        card.addView(stateRow, matchLp());
        // First-run paragraph (design 3.2, decided in 14-10):
        // inside the status card, shown only until the node has
        // run once. Visibility is driven from refreshVisionHome;
        // no setting and no dismiss control - starting the node
        // is what removes it.
        firstRunView = text(firstRunText(), 14, Tokens.MUTED, false);
        firstRunView.setVisibility(View.GONE);
        card.addView(firstRunView, matchLpTop(16));
        vhBestLabel = cardLabel(
                I18n.get(this, StringsTable.VISION_BEST));
        card.addView(vhBestLabel, matchLpTop(32));
        vhBestValue = text("", 56, Tokens.CONTENT, true);
        // The refresh fits this number in pixels (fitSingleLinePx,
        // starting at 56dp); build it at that same pixel size so
        // the first frame does not jump when the system font
        // scale is not 1.0 — as an sp size it started larger
        // (v4.2.2, audit L-11).
        vhBestValue.setTextSize(
                android.util.TypedValue.COMPLEX_UNIT_PX, dp(56));
        vhBestValue.setTypeface(VisionFonts.displayBold(this));
        card.addView(vhBestValue, matchLpTop(8));
        // Age of the last block observation, under the number
        // (stage 1 spec S1-10): a relative age while an
        // observation exists, a dash while none does — the
        // same 0-vs-dash discipline as everywhere else.
        vhBestAgoView = text("", 12, Tokens.FAINT, false);
        vhBestAgoView.setTypeface(VisionFonts.mono(this));
        card.addView(vhBestAgoView, matchLpTop(8));
        LinearLayout stats = new LinearLayout(this);
        stats.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout peersCol = new LinearLayout(this);
        peersCol.setOrientation(LinearLayout.VERTICAL);
        peersCol.addView(cardLabel(
                I18n.get(this, StringsTable.VISION_PEERS)), matchLp());
        vhPeersValue = text("", 26, Tokens.CONTENT, true);
        vhPeersValue.setTypeface(VisionFonts.displayMedium(this));
        peersCol.addView(vhPeersValue, matchLpTop(8));
        stats.addView(peersCol, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout upCol = new LinearLayout(this);
        upCol.setOrientation(LinearLayout.VERTICAL);
        upCol.addView(cardLabel(
                I18n.get(this, StringsTable.VISION_UPTIME)), matchLp());
        vhUptimeValue = text("", 26, Tokens.CONTENT, false);
        vhUptimeValue.setTypeface(VisionFonts.monoMedium(this));
        upCol.addView(vhUptimeValue, matchLpTop(8));
        stats.addView(upCol, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        vhStatsRow = stats;
        card.addView(stats, matchLpTop(32));
        supplementView = text("", 13, Tokens.FAINT, false);
        supplementView.setVisibility(View.GONE);
        card.addView(supplementView, matchLpTop(16));
        root.addView(card, matchLpTop(8));

        // The one primary action (pill dress, vision spec 4.1),
        // directly under the status card (design 7.1): its place
        // no longer depends on how long the list below it grows.
        mainButton = new Button(this);
        mainButton.setTextSize(15);
        mainButton.setAllCaps(false);
        mainButton.setStateListAnimator(null);
        mainButton.setMinHeight(dp(58));
        mainButton.setPadding(dp(24), dp(12), dp(24), dp(12));
        mainButton.setOnClickListener(v -> onMainButtonClicked());
        // Press response (design 9, layer 3): the button dips
        // to 0.98 at once - the only feedback that does not
        // wait for the one-second tick. Returning false keeps
        // the click (and its ripple) exactly as it was.
        mainButton.setOnTouchListener((v, ev) -> {
            if (motionEnabled()) {
                int action = ev.getAction();
                if (action == MotionEvent.ACTION_DOWN) {
                    v.animate().scaleX(0.98f).scaleY(0.98f)
                            .setDuration(120).start();
                } else if (action == MotionEvent.ACTION_UP
                        || action == MotionEvent.ACTION_CANCEL) {
                    v.animate().scaleX(1f).scaleY(1f)
                            .setDuration(120).start();
                }
            }
            return false;
        });
        mainButton.setFocusableInTouchMode(false);
        LinearLayout.LayoutParams mlp = matchLpTop(16);
        mlp.height = LinearLayout.LayoutParams.WRAP_CONTENT;
        root.addView(mainButton, mlp);

        pendingLinkView = text("", 14, Tokens.FLARE, true);
        pendingLinkView.setGravity(Gravity.CENTER);
        pendingLinkView.setBackground(ripple(null));
        pendingLinkView.setClickable(true);
        pendingLinkView.setFocusable(true);
        pendingLinkView.setPadding(0, dp(8), 0, dp(8));
        pendingLinkView.setOnClickListener(v -> openPendingSettings());
        root.addView(pendingLinkView, matchLpTop(8));

        // The list (design 7.1): everything that is not the
        // state is a row, and hairlines close the list at both
        // ends. The old detail link is absorbed into the node
        // details row; the failure wording stays in the card's
        // supplement.
        root.addView(hairline(16));

        // Today's record (stage 1 spec S1-10): the share of
        // today's elapsed checkpoint slots that actually hold a
        // record, as a row with a 2 dp bar along its bottom
        // (design 7.1). Shown only while DePIN is on: a device
        // that is not recording must not be shown a 0% progress
        // bar for a record it never claimed to keep.
        homeTodayRow = new LinearLayout(this);
        homeTodayRow.setOrientation(LinearLayout.VERTICAL);
        // A row like its siblings (design 7.1): an InfoRowView
        // for the label and value — so a long value stacks
        // instead of crushing the label — plus the chevron the
        // design shows, and a tap that opens the record tab
        // (v4.2.2, audit L-3). The 2 dp bar stays along the
        // row's bottom edge.
        LinearLayout todayInner = new LinearLayout(this);
        todayInner.setOrientation(LinearLayout.HORIZONTAL);
        todayInner.setGravity(Gravity.CENTER_VERTICAL);
        InfoRowView todayInfo = new InfoRowView(this);
        TextView todayLabel = text(
                I18n.get(this, StringsTable.HOME_TODAY),
                16, Tokens.CONTENT, false);
        todayInfo.addView(todayLabel);
        homeTodayText = text("", 16, Tokens.CONTENT, false);
        homeTodayText.setTypeface(VisionFonts.mono(this));
        homeTodayText.setGravity(Gravity.END);
        todayInfo.addView(homeTodayText);
        todayInner.addView(todayInfo, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView todayChev = text(I18n.isRtl(this) ? "‹" : "›",
                22, Tokens.FAINT, false);
        LinearLayout.LayoutParams tclp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        tclp.setMarginStart(dp(12));
        todayInner.addView(todayChev, tclp);
        homeTodayRow.addView(todayInner, matchLp());
        homeTodayRow.setBackground(ripple(null));
        homeTodayRow.setClickable(true);
        homeTodayRow.setFocusable(true);
        // The row shows today's record, so it opens the record
        // (member) tab, where the checkpoints and the streak
        // live — not the cumulative Track screen (v4.2.2 audit,
        // L5).
        homeTodayRow.setOnClickListener(v -> showScreen(SCREEN_MEMBER));
        homeTodayTrack = new FrameLayout(this);
        homeTodayTrack.setBackground(rounded(
                Color.rgb(0x22, 0x22, 0x2A), 1,
                getResources().getDisplayMetrics().density, 0, 0));
        homeTodayFill = new View(this);
        homeTodayFill.setBackground(rounded(Tokens.FLARE, 1,
                getResources().getDisplayMetrics().density, 0, 0));
        homeTodayTrack.addView(homeTodayFill,
                new FrameLayout.LayoutParams(0, dp(2)));
        LinearLayout.LayoutParams tlp2 = matchLp();
        tlp2.height = dp(2);
        homeTodayRow.addView(homeTodayTrack, tlp2);
        homeTodayRow.setVisibility(View.GONE);
        root.addView(homeTodayRow, matchLp());

        // Mining (v4.0.0): a row, shown only while mining is in
        // use (the switch is on, or the running node was
        // launched with it). It says what the miner is doing
        // and leads to the mining settings; with mining off it
        // stays GONE and the home screen carries no trace of it.
        homeMiningText = valueRow(root,
                I18n.get(this, StringsTable.MINING_SECTION), 0,
                v -> openMiningSettings());
        homeMiningRow = (LinearLayout) homeMiningText.getTag();
        homeMiningRow.setVisibility(View.GONE);

        // Price: a row (design 7.1; decided in 14-1). The big
        // number, the source and the fetch switch live in the
        // price sheet.
        priceRowValue = valueRow(root, "QTC", 0,
                v -> openPriceSheet());
        priceRowValue.setTypeface(VisionFonts.mono(this));

        // In a failure or time-limit stop the row lands on the
        // diagnostics, expanded (audit M-1: the v4.1.0 flare
        // link's behaviour, absorbed into this row).
        navRow(root, I18n.get(this, StringsTable.SCREEN_NODE_DETAILS), 0,
                v -> {
                    NodeState nst = NodeState.get();
                    UiLogic.HomeState nhs = UiLogic.homeState(
                            nst.status, nst.lastStopKind);
                    if (UiLogic.isFailure(nhs)
                            || nhs == UiLogic.HomeState
                                    .STOPPED_TIME_LIMIT) {
                        openDiagnosticsFromHome();
                    } else {
                        showScreen(SCREEN_DETAILS);
                    }
                });
        navRow(root, I18n.get(this, StringsTable.SECTION_TRACK), 0,
                v -> showScreen(SCREEN_TRACK));
        // Support (v4.0.0): the developer's QTC donation address
        // is a row now (decided in 14-9); the address itself and
        // the copy action live in the donation sheet.
        navRow(root, I18n.get(this, StringsTable.DONATE_TITLE), 0,
                v -> openDonateSheet());
        root.addView(hairline(0));

        buildPriceSheet(container);
        buildDonateSheet(container);
        return (ScrollView) screens[SCREEN_HOME];
    }

    /** Hide the content behind an opening sheet from
     *  accessibility services (v4.2.2, audit L-10): a sheet that
     *  TalkBack can see through is not modal, whatever it looks
     *  like. The price and donation sheets sit at the activity
     *  level, so every screen and the nav are hidden; the read
     *  sheet lives inside the network screen, so only its
     *  sibling scroll view and the nav are hidden — hiding the
     *  screen itself would hide the sheet with it. */
    private void hideBackgroundForSheet(boolean wholeActivity) {
        int flag = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS;
        if (wholeActivity) {
            for (View s : screens) {
                if (s != null) {
                    s.setImportantForAccessibility(flag);
                }
            }
        } else if (networkScrollView != null) {
            networkScrollView.setImportantForAccessibility(flag);
        }
        if (bottomNav != null) {
            bottomNav.setImportantForAccessibility(flag);
        }
    }

    /** Undo {@link #hideBackgroundForSheet}. Every sheet close
     *  path funnels through {@link #closeSheet}, so the restore
     *  cannot be forgotten on one of them. */
    private void restoreBackgroundAfterSheet() {
        int flag = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO;
        for (View s : screens) {
            if (s != null) {
                s.setImportantForAccessibility(flag);
            }
        }
        if (networkScrollView != null) {
            networkScrollView.setImportantForAccessibility(flag);
        }
        if (bottomNav != null) {
            bottomNav.setImportantForAccessibility(flag);
        }
    }

    /** Close a sheet overlay (null-safe) and restore the
     *  background's accessibility reachability. */
    private void closeSheet(FrameLayout overlay) {
        if (overlay != null) {
            overlay.setVisibility(View.GONE);
        }
        restoreBackgroundAfterSheet();
    }

    /** One home sheet's shell, in the how-to-read sheet's shape:
     *  a scrim overlay (a tap outside closes it) with a bottom
     *  panel; the panel is handed back through panelOut for the
     *  caller to fill. */
    private FrameLayout buildSheet(FrameLayout container, String title,
                                   LinearLayout[] panelOut) {
        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(Color.argb(160, 0, 0, 0));
        // The sheet names itself to accessibility services
        // (v4.2.2 audit, I4): once the background is hidden,
        // TalkBack's focus would otherwise drop with no word
        // that a sheet appeared.
        overlay.setAccessibilityPaneTitle(title);
        // Above the bottom nav (elevation 12): the scrim covers
        // the pill and the sheet is truly modal (audit H-2 - the
        // panel used to end under the pill, which hid the
        // donation address and stole its taps).
        overlay.setElevation(dp(16));
        overlay.setVisibility(View.GONE);
        overlay.setOnClickListener(v -> closeSheet(overlay));
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(cardBg());
        panel.setPadding(dp(26), dp(22), dp(26), dp(22));
        panel.setOnClickListener(v -> {
            // Swallow taps so the panel itself does not close.
        });
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = text(title, 20, Tokens.CONTENT, true);
        head.addView(t, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView close = text("✕", 16, Tokens.MUTED, false);
        close.setGravity(Gravity.CENTER);
        // The glyph alone says nothing to a screen reader
        // (v4.2.2, audit L-10).
        close.setContentDescription(
                I18n.get(this, StringsTable.SHEET_CLOSE));
        close.setOnClickListener(v -> closeSheet(overlay));
        head.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));
        panel.addView(head, matchLp());
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        plp.setMargins(dp(16), 0, dp(16), dp(16));
        overlay.addView(panel, plp);
        container.addView(overlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        panelOut[0] = panel;
        return overlay;
    }

    /** The price sheet (design 7.1): the big number, the 24 h
     *  change, the source, and the fetch switch with its
     *  disclosure (decided in 14-2). */
    private void buildPriceSheet(FrameLayout container) {
        LinearLayout[] panelBox = new LinearLayout[1];
        priceSheetOverlay = buildSheet(container,
                I18n.get(this, StringsTable.VISION_PRICE), panelBox);
        LinearLayout panel = panelBox[0];
        priceSheetPanel = panel;
        priceValueView = text("", 40, Tokens.CONTENT, true);
        priceValueView.setTypeface(VisionFonts.displayBold(this));
        panel.addView(priceValueView, matchLpTop(16));
        priceDeltaView = text("", 13, Tokens.MUTED, false);
        priceDeltaView.setTypeface(VisionFonts.mono(this));
        panel.addView(priceDeltaView, matchLpTop(8));
        // Tapping the "24h —" placeholder explains itself
        // (stage 1 spec S1-07): the fetch simply carried no
        // change rate. The tap is live only in that state.
        priceDeltaView.setClickable(true);
        priceDeltaView.setOnClickListener(v -> {
            PriceStore.Snapshot s = PriceStore.snapshot(this);
            if (s.hasValue && !s.hasChange) {
                android.widget.Toast.makeText(this,
                        I18n.get(this, StringsTable.PRICE_NO_CHANGE),
                        android.widget.Toast.LENGTH_LONG).show();
            }
        });
        priceSrcView = text("", 12, Tokens.FAINT, false);
        priceSrcView.setTypeface(VisionFonts.mono(this));
        panel.addView(priceSrcView, matchLpTop(8));
        LinearLayout swRow = new LinearLayout(this);
        swRow.setOrientation(LinearLayout.HORIZONTAL);
        swRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView swLabel = text(
                I18n.get(this, StringsTable.PRICE_FETCH),
                16, Tokens.CONTENT, false);
        swRow.addView(swLabel, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        priceFetchToggle = new ToggleView(this);
        priceFetchToggle.setContentDescription(
                I18n.get(this, StringsTable.PRICE_FETCH));
        priceFetchToggle.setChecked(PriceStore.isEnabled(this));
        priceFetchToggle.setOnCheckedChangeListener((view, on) -> {
            PriceStore.setEnabled(this, on);
            refreshPriceViews();
        });
        swRow.addView(priceFetchToggle,
                new LinearLayout.LayoutParams(dp(52), dp(48)));
        swRow.setOnClickListener(v -> priceFetchToggle.performClick());
        priceSheetSwitchRow = swRow;
        panel.addView(swRow, matchLpTop(16));
        priceSheetLockedView = text(I18n.get(
                this, StringsTable.SETTINGS_LOCKED_NOTICE),
                14, Tokens.FAINT, false);
        priceSheetLockedView.setVisibility(View.GONE);
        panel.addView(priceSheetLockedView, matchLpTop(4));
        TextView disc = text(
                I18n.get(this, StringsTable.PRICE_DISCLOSURE),
                13, Tokens.MUTED, false);
        disc.setLineSpacing(0f, 1.3f);
        panel.addView(disc, matchLpTop(8));
    }

    private void openPriceSheet() {
        if (priceSheetOverlay == null) {
            return;
        }
        priceFetchToggle.setChecked(PriceStore.isEnabled(this));
        refreshPriceViews();
        priceSheetOverlay.setVisibility(View.VISIBLE);
        hideBackgroundForSheet(true);
        animateSheetIn(priceSheetOverlay, priceSheetPanel);
    }

    /** Copy the donation address, with a toast as the receipt:
     *  the address tap and the sheet's copy button do exactly
     *  this (the button makes the action visible instead of
     *  leaving it to be discovered — v4.2.2, audit L-10). */
    private void copyDonateAddress() {
        android.content.ClipboardManager cm =
                (android.content.ClipboardManager)
                        getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(android.content.ClipData.newPlainText(
                    I18n.get(this, StringsTable.DONATE_TITLE),
                    DONATE_ADDRESS));
            android.widget.Toast.makeText(this,
                    I18n.get(this, StringsTable.DONATE_COPIED),
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    /** The donation sheet (decided in 14-9): the voluntary note
     *  and the address; tapping the address copies it, with a
     *  toast as the receipt, and an explicit copy button says so
     *  (v4.2.2, audit L-10). The app never sends anything to it
     *  on its own. The QR joins in v4.4.0 (design 10.4). */
    private void buildDonateSheet(FrameLayout container) {
        LinearLayout[] panelBox = new LinearLayout[1];
        donateSheetOverlay = buildSheet(container,
                I18n.get(this, StringsTable.DONATE_TITLE), panelBox);
        LinearLayout panel = panelBox[0];
        donateSheetPanel = panel;
        TextView note = text(
                I18n.get(this, StringsTable.DONATE_NOTE),
                13, Tokens.MUTED, false);
        note.setLineSpacing(0f, 1.3f);
        panel.addView(note, matchLpTop(16));
        TextView addr = text(DONATE_ADDRESS, 12, Tokens.CONTENT, false);
        addr.setTypeface(VisionFonts.mono(this));
        addr.setBackground(ripple(null));
        addr.setClickable(true);
        addr.setFocusable(true);
        addr.setPadding(0, dp(8), 0, dp(8));
        addr.setOnClickListener(v -> copyDonateAddress());
        panel.addView(addr, matchLpTop(8));
        Button copyButton = linkButton(
                I18n.get(this, StringsTable.DONATE_COPY),
                v -> copyDonateAddress());
        panel.addView(copyButton, matchLpTop(8));
    }

    private void openDonateSheet() {
        if (donateSheetOverlay != null) {
            donateSheetOverlay.setVisibility(View.VISIBLE);
            hideBackgroundForSheet(true);
            animateSheetIn(donateSheetOverlay, donateSheetPanel);
        }
    }

    // ------------------------------------------------------------------
    // Network tab (vision): hero, field card, ticker, read sheet
    // ------------------------------------------------------------------

    private View buildNetwork(FrameLayout container, int dir) {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Tokens.VOID);
        root.setLayoutDirection(dir);
        root.setVisibility(View.GONE);
        float density = getResources().getDisplayMetrics().density;

        ScrollView sv = new ScrollView(this);
        // Kept in a field: while the read sheet is open, this
        // scroll view is the background accessibility hides
        // (the sheet is its sibling inside root).
        networkScrollView = sv;
        sv.setBackgroundColor(Tokens.VOID);
        root.addView(sv, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int side = sidePadding();
        col.setPadding(side, dp(24), side, navClearance());
        sv.addView(col);

        // Header: title left; the segment and the how-to-read
        // button right (stage 1 spec S1-02 / S1-03).
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = text(I18n.get(this, StringsTable.VISION_NET_TITLE),
                20, Tokens.CONTENT, true);
        t.setTypeface(VisionFonts.displayBold(this));
        header.addView(t, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout seg = new LinearLayout(this);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        seg.setBackground(rounded(Color.rgb(0x1A, 0x1A, 0x20),
                999, density, 0, 0));
        seg.setPadding(dp(3), dp(3), dp(3), dp(3));
        netSegNowBtn = segmentButton(
                I18n.get(this, StringsTable.NET_SEG_NOW), false);
        netSeg24hBtn = segmentButton(
                I18n.get(this, StringsTable.NET_SEG_24H), true);
        seg.addView(netSegNowBtn);
        seg.addView(netSeg24hBtn);
        header.addView(seg);
        TextView info = text("i", 13, Tokens.CONTENT, true);
        info.setGravity(Gravity.CENTER);
        info.setBackground(rounded(0, 999, density, Tokens.DECOR, 1));
        info.setClickable(true);
        info.setOnClickListener(v -> openReadSheet());
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(
                dp(30), dp(30));
        ilp.setMarginStart(dp(10));
        header.addView(info, ilp);
        col.addView(header, matchLp());
        updateSegmentButtons();

        // Hero row: the count, its label (or the state line),
        // and the source tag on the left; the slot sparkline
        // on the right (stage 1 spec S1-03, stage 2 S2-02).
        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout heroLeft = new LinearLayout(this);
        heroLeft.setOrientation(LinearLayout.VERTICAL);
        netNumView = text("", 88, Tokens.FLARE, true);
        netNumView.setTypeface(VisionFonts.displayBold(this));
        heroLeft.addView(netNumView, matchLp());
        netSubView = text("", 15, Tokens.MUTED, false);
        heroLeft.addView(netSubView, matchLpTop(8));
        netTagView = text("", 12, Tokens.FAINT, false);
        netTagView.setTypeface(VisionFonts.mono(this));
        heroLeft.addView(netTagView, matchLpTop(8));
        hero.addView(heroLeft, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        sparkView = new SparklineView(this);
        LinearLayout.LayoutParams splp = new LinearLayout.LayoutParams(
                dp(96), dp(32));
        splp.gravity = Gravity.CENTER_VERTICAL;
        splp.setMarginStart(dp(12));
        hero.addView(sparkView, splp);
        col.addView(hero, matchLpTop(16));

        // The field, in its card.
        FrameLayout fieldCard = new FrameLayout(this);
        fieldCard.setBackground(cardBg());
        networkField = new NetworkFieldView(this);
        networkField.setOnClickListener(v -> openReadSheet());
        fieldCard.addView(networkField, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams flp = matchLpTop(16);
        flp.height = dp(340);
        col.addView(fieldCard, flp);

        netGhostNoteView = text(
                I18n.get(this, StringsTable.SEG_24H_NOTE),
                12, Tokens.FAINT, false);
        netGhostNoteView.setVisibility(View.GONE);
        col.addView(netGhostNoteView, matchLpTop(8));

        // The ticker zone: the live log's latest lines, newest
        // first, resting above the nav zone (S2-03).
        tickerBox = new LinearLayout(this);
        tickerBox.setOrientation(LinearLayout.VERTICAL);
        col.addView(tickerBox, matchLpTop(16));

        buildReadSheet(root);

        container.addView(root, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        return root;
    }

    /** One segment pill of the network header. */
    private TextView segmentButton(String label, boolean is24h) {
        TextView b = text(label, 12, Tokens.FAINT, true);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(12), dp(6), dp(12), dp(6));
        b.setClickable(true);
        b.setOnClickListener(v -> {
            netShow24h = is24h;
            updateSegmentButtons();
        });
        return b;
    }

    private void updateSegmentButtons() {
        if (netSegNowBtn == null || netSeg24hBtn == null) {
            return;
        }
        float density = getResources().getDisplayMetrics().density;
        android.graphics.drawable.Drawable sel = rounded(
                Color.argb(38, 0xE8, 0xE6, 0xE0), 999, density, 0, 0);
        netSegNowBtn.setBackground(netShow24h ? null : sel);
        netSegNowBtn.setTextColor(netShow24h ? Tokens.FAINT : Tokens.CONTENT);
        netSeg24hBtn.setBackground(netShow24h ? sel : null);
        netSeg24hBtn.setTextColor(netShow24h ? Tokens.CONTENT : Tokens.FAINT);
        if (netGhostNoteView != null) {
            netGhostNoteView.setVisibility(
                    netShow24h ? View.VISIBLE : View.GONE);
        }
    }

    /** The how-to-read sheet (S1-02): an overlay panel on the
     *  network root; a tap on the scrim closes it. */
    private void buildReadSheet(FrameLayout root) {
        readSheetOverlay = new FrameLayout(this);
        readSheetOverlay.setBackgroundColor(Color.argb(160, 0, 0, 0));
        readSheetOverlay.setVisibility(View.GONE);
        readSheetOverlay.setOnClickListener(
                v -> closeSheet(readSheetOverlay));
        LinearLayout panel = new LinearLayout(this);
        readSheetPanel = panel;
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackground(cardBg());
        panel.setPadding(dp(26), dp(22), dp(26), dp(22));
        panel.setOnClickListener(v -> {
            // Swallow taps so the panel itself does not close.
        });
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = text(
                I18n.get(this, StringsTable.FIELD_HOWTO),
                20, Tokens.CONTENT, true);
        head.addView(title, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView close = text("✕", 16, Tokens.MUTED, false);
        close.setGravity(Gravity.CENTER);
        // The glyph alone says nothing to a screen reader
        // (v4.2.2, audit L-10).
        close.setContentDescription(
                I18n.get(this, StringsTable.SHEET_CLOSE));
        close.setOnClickListener(v -> closeSheet(readSheetOverlay));
        head.addView(close, new LinearLayout.LayoutParams(dp(48), dp(48)));
        panel.addView(head, matchLp());
        readSheetBestView = text("", 15, Tokens.CONTENT, false);
        readSheetBestView.setTypeface(VisionFonts.mono(this));
        panel.addView(readSheetBestView, matchLpTop(16));
        readSheetFactsView = text("", 13, Tokens.MUTED, false);
        readSheetFactsView.setTypeface(VisionFonts.mono(this));
        panel.addView(readSheetFactsView, matchLpTop(8));
        TextView rings = text(
                I18n.get(this, StringsTable.FIELD_READ_RINGS),
                13, Tokens.MUTED, false);
        rings.setLineSpacing(0f, 1.3f);
        panel.addView(rings, matchLpTop(16));
        TextView ghost = text(
                I18n.get(this, StringsTable.FIELD_READ_GHOST),
                13, Tokens.MUTED, false);
        ghost.setLineSpacing(0f, 1.3f);
        panel.addView(ghost, matchLpTop(12));
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        // The read sheet lives inside its tab's screen, so an
        // elevation cannot lift it over the container-level nav;
        // its panel instead clears the pill zone (audit H-2).
        plp.setMargins(dp(16), 0, dp(16), dp(16) + navZone());
        readSheetOverlay.addView(panel, plp);
        root.addView(readSheetOverlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
    }

    /** Fill and show the how-to-read sheet with the aggregate
     *  facts of this moment (S1-02): the node's own best, the
     *  connection count, the last observation, the session
     *  uptime. Nothing per-peer exists to show. */
    private void openReadSheet() {
        if (readSheetOverlay == null) {
            return;
        }
        NodeState st = NodeState.get();
        String tag = I18n.effectiveTag(this);
        long now = SystemClock.elapsedRealtime();
        readSheetBestView.setText(
                I18n.get(this, StringsTable.FIELD_BEST) + "  "
                        + (st.bestBlock >= 0
                                ? "#" + UiLogic.grouped(st.bestBlock)
                                : "\u2014"));
        String peersText = st.peers >= 0
                ? UiLogic.grouped(st.peers) : "\u2014";
        String obsText = st.lastRpcOkWallMs > 0
                ? timeHhMmSs(st.lastRpcOkWallMs) : "\u2014";
        String upText = st.sessionAcceptedElapsedMs >= 0
                ? UiLogic.durationText(tag,
                        sessionUptimeMs(st, now), false)
                : "\u2014";
        readSheetFactsView.setText(I18n.format(this,
                StringsTable.FIELD_FACTS_FMT,
                peersText, obsText, upText));
        readSheetOverlay.setVisibility(View.VISIBLE);
        hideBackgroundForSheet(false);
        animateSheetIn(readSheetOverlay, readSheetPanel);
    }

    // ------------------------------------------------------------------
    // Member tab (vision): the card, or the guidance alone
    // ------------------------------------------------------------------

    private View buildMember(FrameLayout container, int dir) {
        // A scrolling column (design 7.3): the title, then the
        // card under it, then the standing note. The centred
        // overlay area the first build used is gone - the tab
        // grows downward from here (the track graph and the
        // totals join below the card in v4.3.0).
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Tokens.VOID);
        sv.setLayoutDirection(dir);
        sv.setVisibility(View.GONE);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(dir);
        root.setPadding(0, 0, 0, navClearance());
        sv.addView(root);
        int side = sidePadding();

        TextView title = text(
                I18n.get(this, StringsTable.VISION_MEM_TITLE),
                20, Tokens.CONTENT, true);
        title.setTypeface(VisionFonts.displayBold(this));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        tlp.setMarginStart(side);
        tlp.setMarginEnd(side);
        tlp.topMargin = dp(24);
        tlp.bottomMargin = dp(16);
        root.addView(title, tlp);

        // Guidance while DePIN holds no records yet: no card is
        // dressed up for a record that does not exist.
        memberEmptyView = text(
                I18n.get(this, StringsTable.VISION_MEMBER_EMPTY),
                15, Tokens.MUTED, false);
        memberEmptyView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        elp.setMarginStart(side);
        elp.setMarginEnd(side);
        elp.topMargin = dp(24);
        root.addView(memberEmptyView, elp);

        // The card: glow layer behind, content on top.
        memberCardBox = new FrameLayout(this);
        memberGlow = new GlowCardView(this);
        memberCardBox.addView(memberGlow, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        LinearLayout content = new LinearLayout(this);
        memberContent = content;
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(22), dp(14), dp(22), dp(14));
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(logoView(40, 11));
        TextView memberWord = text("MEMBER", 11, Tokens.FLARE, true);
        memberWord.setTypeface(VisionFonts.displayBold(this));
        memberWord.setLetterSpacing(0.32f);
        LinearLayout.LayoutParams mwlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        mwlp.setMarginStart(dp(12));
        top.addView(memberWord, mwlp);
        content.addView(top, matchLp());
        memNameView = text("", 40, Tokens.CONTENT, true);
        memNameView.setTypeface(VisionFonts.displayBold(this));
        memNameView.setSingleLine(true);
        content.addView(memNameView, matchLpTop(8));
        TextView idLabel = cardLabel(
                I18n.get(this, StringsTable.VISION_MEM_ID));
        content.addView(idLabel, matchLpTop(8));
        memIdView = text("", 14, Tokens.CONTENT, false);
        memIdView.setTypeface(VisionFonts.mono(this));
        memIdView.setSingleLine(true);
        // The number rests grouped in fours for reading; a tap
        // shows the raw id for copying, and back (S1-11).
        memIdView.setClickable(true);
        // A long press copies the raw number (A-18): the card
        // could show it and flip its grouping, but until now
        // there was no way to take it anywhere.
        memIdView.setOnLongClickListener(v -> {
            if (memIdRaw == null) {
                return false;
            }
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager)
                            getSystemService(CLIPBOARD_SERVICE);
            if (cm == null) {
                return false;
            }
            cm.setPrimaryClip(android.content.ClipData.newPlainText(
                    "member id", memIdRaw));
            Toast.makeText(this, I18n.get(
                    this, StringsTable.MEMBER_ID_COPIED),
                    Toast.LENGTH_SHORT).show();
            return true;
        });
        memIdView.setOnClickListener(v -> {
            memIdGrouped = !memIdGrouped;
            if (memIdRaw != null) {
                memIdView.setText(memIdGrouped
                        ? groupMemberId(memIdRaw) : memIdRaw);
                fitSingleLinePx(memIdView, 14f, 10f, 7f);
            }
        });
        content.addView(memIdView, matchLpTop(4));
        LinearLayout mstats = new LinearLayout(this);
        mstats.setOrientation(LinearLayout.HORIZONTAL);
        memSinceValue = memberStat(mstats,
                I18n.get(this, StringsTable.VISION_SINCE), 1.35f, true);
        memSinceValue.setTypeface(VisionFonts.mono(this));
        memCpsValue = memberStat(mstats,
                I18n.get(this, StringsTable.VISION_CPS), 1f, false);
        memDaysValue = memberStat(mstats,
                I18n.get(this, StringsTable.MEM_STREAK), 1f, false);
        content.addView(mstats, matchLpTop(12));
        // The card keeps its declared ratio (1.42:1, decided
        // in 14-8; the brief's section 6-4) when its content is
        // shorter than the ratio demands; the spacer absorbs
        // the difference and the refresh loop sizes it. Content
        // taller than the ratio always wins: nothing is ever
        // clipped for the ratio's sake (S1-11).
        memberSpacer = new View(this);
        content.addView(memberSpacer, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0));
        memberCardBox.addView(content, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                Math.min(dp(330), getResources().getDisplayMetrics()
                        .widthPixels - side * 2),
                LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.gravity = Gravity.CENTER_HORIZONTAL;
        clp.topMargin = dp(8);
        root.addView(memberCardBox, clp);

        // The standing note (design 7.3): the first principle,
        // always on this tab.
        TextView principle = text(
                I18n.get(this, StringsTable.RECORD_PRINCIPLE),
                12, Tokens.FAINT, false);
        principle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams prlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        prlp.setMarginStart(side);
        prlp.setMarginEnd(side);
        prlp.topMargin = dp(24);
        root.addView(principle, prlp);

        container.addView(sv, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        return sv;
    }

    /**
     * One member-card stat column; returns its value view. The
     * label is single-line and both label and value are fitted
     * to the column's width at refresh time (see
     * fitSingleLinePx): the equal split used to starve the date
     * column until the value was clipped and the longest label
     * wrapped onto the value's line (device finding,
     * 2026-11-09). Columns are disjoint layout cells, so with
     * every text fitted to its own cell nothing can overlap or
     * be cut.
     */
    private TextView memberStat(LinearLayout parent, String label,
                                float weight, boolean first) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView lab = text(label, 12, Tokens.MUTED, false);
        lab.setSingleLine(true);
        col.addView(lab, matchLp());
        TextView v = text("", 20, Tokens.CONTENT, true);
        v.setTypeface(VisionFonts.displayMedium(this));
        v.setSingleLine(true);
        col.addView(v, matchLpTop(4));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, weight);
        if (!first) {
            lp.setMarginStart(dp(12));
        }
        parent.addView(col, lp);
        statLabelViews.add(lab);
        statValueViews.add(v);
        return v;
    }

    // ------------------------------------------------------------------
    // Bottom navigation (vision): a floating pill, three tabs
    // ------------------------------------------------------------------

    private void buildBottomNav(FrameLayout container, int dir) {
        bottomNav = new LinearLayout(this);
        bottomNav.setOrientation(LinearLayout.HORIZONTAL);
        bottomNav.setLayoutDirection(dir);
        float density = getResources().getDisplayMetrics().density;
        bottomNav.setBackground(rounded(
                Color.argb(209, 0x18, 0x18, 0x18), 999, density,
                Tokens.LINE, 1));
        bottomNav.setPadding(dp(6), dp(6), dp(6), dp(6));
        bottomNav.setElevation(dp(12));
        String[] labels = {
                I18n.get(this, StringsTable.VISION_NAV_HOME),
                I18n.get(this, StringsTable.VISION_NAV_NETWORK),
                I18n.get(this, StringsTable.VISION_NAV_MEMBER)};
        int[] icons = {NavIconView.ICON_HOME,
                NavIconView.ICON_NETWORK, NavIconView.ICON_MEMBER};
        int[] targets = {SCREEN_HOME, SCREEN_NETWORK, SCREEN_MEMBER};
        for (int i = 0; i < 3; i++) {
            final int target = targets[i];
            LinearLayout btn = new LinearLayout(this);
            btn.setOrientation(LinearLayout.VERTICAL);
            btn.setGravity(Gravity.CENTER);
            btn.setMinimumWidth(dp(78));
            btn.setPadding(dp(14), dp(8), dp(14), dp(8));
            btn.setClickable(true);
            btn.setFocusable(true);
            btn.setContentDescription(labels[i]);
            navIcons[i] = new NavIconView(this, icons[i], Tokens.FAINT);
            btn.addView(navIcons[i], new LinearLayout.LayoutParams(
                    dp(20), dp(20)));
            navLabels[i] = text(labels[i], 11, Tokens.FAINT, true);
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            nlp.topMargin = dp(3);
            btn.addView(navLabels[i], nlp);
            btn.setOnClickListener(v -> showScreen(target));
            navButtons[i] = btn;
            bottomNav.addView(btn);
        }
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        lp.bottomMargin = dp(22);
        container.addView(bottomNav, lp);
    }

    /** Failure / stop details: open details with diagnostics expanded. */
    private void openDiagnosticsFromHome() {
        diagExpanded = true;
        applySectionStates();
        scrollToDiagnosticsOnce = true;
        showScreen(SCREEN_DETAILS);
    }

    // ------------------------------------------------------------------
    // Node details
    // ------------------------------------------------------------------

    private ScrollView buildDetails(FrameLayout container, int dir) {
        LinearLayout root = screenContent(container, SCREEN_DETAILS, dir);
        subHeader(root, I18n.get(this, StringsTable.SCREEN_NODE_DETAILS));

        TextView identity = text(
                I18n.get(this, StringsTable.APP_SUBTITLE), 14, Tokens.FAINT, false);
        root.addView(identity, matchLpTop(8));

        // Running presentation: the exact current values.
        detailsRunningBox = new LinearLayout(this);
        detailsRunningBox.setOrientation(LinearLayout.VERTICAL);
        detBestValue = infoRow(detailsRunningBox,
                I18n.get(this, StringsTable.LABEL_BEST_BLOCK), false);
        detHighestValue = infoRow(detailsRunningBox,
                I18n.get(this, StringsTable.LABEL_HIGHEST_BLOCK), true);
        detGapValue = infoRow(detailsRunningBox,
                I18n.get(this, StringsTable.LABEL_SYNC_GAP), true);
        detPeersValue = infoRow(detailsRunningBox,
                I18n.get(this, StringsTable.LABEL_PEERS), true);
        detSessionValue = infoRow(detailsRunningBox,
                I18n.get(this, StringsTable.LABEL_UPTIME_SESSION), true);
        TextView gapNote = text(
                I18n.get(this, StringsTable.SYNC_GAP_NOTE), 14, Tokens.FAINT, false);
        detailsRunningBox.addView(gapNote, matchLpTop(8));
        root.addView(detailsRunningBox, matchLpTop(16));

        // Stopped presentation (spec D): the cumulative record.
        detailsStoppedBox = new LinearLayout(this);
        detailsStoppedBox.setOrientation(LinearLayout.VERTICAL);
        TextView cumNote = text(I18n.get(
                this, StringsTable.STOPPED_CUMULATIVE_NOTE), 14, Tokens.FAINT, false);
        detailsStoppedBox.addView(cumNote, matchLp());
        cumUptimeValue = infoRow(detailsStoppedBox,
                I18n.get(this, StringsTable.LABEL_UPTIME_TOTAL), false);
        cumBlocksValue = infoRow(detailsStoppedBox,
                I18n.get(this, StringsTable.LABEL_BLOCKS_OBSERVED), true);
        cumPeersValue = infoRow(detailsStoppedBox,
                I18n.get(this, StringsTable.LABEL_MAX_PEERS), true);
        root.addView(detailsStoppedBox, matchLpTop(16));

        // Network / RPC line (raw endpoint — not translated, by design).
        // v4.2.4 (audit V4-7): the address the running node reported,
        // set by refreshDetails — not a fixed 127.0.0.1:9944.
        rpcNoteView = text(UiLogic.rpcNoteLine(
                I18n.get(this, StringsTable.RPC_LOCAL_NOTE), null),
                14, Tokens.FAINT, false);
        root.addView(rpcNoteView, matchLpTop(24));

        // Diagnostics (expandable).
        diagSection = new LinearLayout(this);
        diagSection.setOrientation(LinearLayout.VERTICAL);
        LinearLayout diagHeader = expandableHeader(
                I18n.get(this, StringsTable.SECTION_DIAGNOSTICS), null,
                v -> {
                    diagExpanded = !diagExpanded;
                    applySectionStates();
                });
        diagChevron = (TextView) diagHeader.getChildAt(1);
        diagSection.addView(diagHeader, matchLp());
        diagContent = new LinearLayout(this);
        diagContent.setOrientation(LinearLayout.VERTICAL);
        diagText = text("", 14, Tokens.MUTED, false);
        diagText.setTextIsSelectable(true);
        diagContent.addView(diagText, matchLp());
        TextView logLocLabel = text(
                I18n.get(this, StringsTable.LABEL_LOG_LOCATION), 14, Tokens.FAINT, false);
        diagContent.addView(logLocLabel, matchLpTop(16));
        TextView logLoc = text("files/logs/node.log", 14, Tokens.MUTED, false);
        diagContent.addView(logLoc, matchLpTop(4));
        Button share = new Button(this);
        share.setText(I18n.get(this, StringsTable.BUTTON_SHARE_LOG));
        share.setTextSize(16);
        share.setTextColor(Tokens.FLARE);
        share.setAllCaps(false);
        share.setBackground(ripple(null));
        share.setStateListAnimator(null);
        share.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        share.setMinHeight(dp(48));
        share.setOnClickListener(v -> onShareLogClicked());
        diagContent.addView(share, matchLpTop(8));
        diagSection.addView(diagContent, matchLp());
        root.addView(diagSection, matchLpTop(32));

        applySectionStates();
        return (ScrollView) screens[SCREEN_DETAILS];
    }

    /**
     * A tappable section header: section label, an optional trailing
     * status word, and a chevron that reflects the open state.
     */
    private LinearLayout expandableHeader(String label, String trailing,
                                          View.OnClickListener click) {
        LinearLayout h = new LinearLayout(this);
        h.setOrientation(LinearLayout.HORIZONTAL);
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.setMinimumHeight(dp(56));
        h.setPadding(0, dp(12), 0, dp(12));
        h.setBackground(ripple(null));
        h.setClickable(true);
        h.setFocusable(true);
        h.setOnClickListener(click);
        TextView l = sectionLabel(label);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        h.addView(l, llp);
        if (trailing != null) {
            TextView t = text(trailing, 14, Tokens.FAINT, false);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            tlp.setMarginEnd(dp(12));
            h.addView(t, tlp);
        }
        TextView chev = text("›", 22, Tokens.FAINT, false);
        h.addView(chev);
        h.setTag(label);
        return h;
    }

    /** Apply the diagnostics open state + a11y wording. */
    private void applySectionStates() {
        if (diagContent != null) {
            diagContent.setVisibility(diagExpanded ? View.VISIBLE : View.GONE);
            diagChevron.setText(diagExpanded ? "⌄" : (I18n.isRtl(this) ? "‹" : "›"));
            View header = (View) diagChevron.getParent();
            header.setContentDescription(
                    I18n.get(this, StringsTable.SECTION_DIAGNOSTICS) + ", "
                            + I18n.get(this, diagExpanded
                                    ? StringsTable.A11Y_EXPANDED
                                    : StringsTable.A11Y_COLLAPSED));
        }
    }

    // ------------------------------------------------------------------
    // Track
    // ------------------------------------------------------------------

    private ScrollView buildTrack(FrameLayout container, int dir) {
        LinearLayout root = screenContent(container, SCREEN_TRACK, dir);
        subHeader(root, I18n.get(this, StringsTable.SECTION_TRACK));
        trackUptimeValue = bigMetric(root,
                I18n.get(this, StringsTable.LABEL_UPTIME_TOTAL), 24);
        trackBlocksValue = bigMetric(root,
                I18n.get(this, StringsTable.LABEL_BLOCKS_OBSERVED), 32);
        trackPeersValue = bigMetric(root,
                I18n.get(this, StringsTable.LABEL_MAX_PEERS), 32);
        TextView note = text(
                I18n.get(this, StringsTable.TRACK_LOCAL_NOTE), 14, Tokens.FAINT, false);
        root.addView(note, matchLpTop(32));
        return (ScrollView) screens[SCREEN_TRACK];
    }

    /** One track metric: small label, 24 sp medium value. */
    private TextView bigMetric(LinearLayout parent, String label, int topDp) {
        TextView l = text(label, 14, Tokens.MUTED, false);
        parent.addView(l, matchLpTop(topDp));
        TextView v = text("", 24, Tokens.CONTENT, true);
        parent.addView(v, matchLpTop(4));
        return v;
    }

    // ------------------------------------------------------------------
    // Settings
    // ------------------------------------------------------------------

    private ScrollView buildSettings(FrameLayout container, int dir) {
        LinearLayout root = screenContent(container, SCREEN_SETTINGS, dir);
        subHeader(root, I18n.get(this, StringsTable.SECTION_SETTINGS));

        // The hub (design 6.2): one row per page, each carrying
        // its current value, so the state is visible without
        // opening anything. The pages hold the sections the one
        // long settings scroll used to stack.
        // The Node row carries no value (audit M-2): it used to
        // show the telemetry switch as "Node On/Off", which read
        // as the node's own state. The design's hub wireframe
        // gives this row no value; telemetry's switch lives on
        // the Node page itself.
        navRow(root, I18n.get(this, StringsTable.SET_NODE_TITLE), 16,
                v -> showScreen(SCREEN_SET_NODE));
        hubMiningValue = valueRow(root,
                I18n.get(this, StringsTable.MINING_SECTION), 0,
                v -> showScreen(SCREEN_SET_MINING));
        hubDepinValue = valueRow(root,
                I18n.get(this, StringsTable.SETTINGS_DEPIN_SECTION), 0,
                v -> showScreen(SCREEN_SET_DEPIN));
        hubStorageValue = valueRow(root,
                I18n.get(this, StringsTable.STORAGE_SECTION), 0,
                v -> showScreen(SCREEN_SET_STORAGE));
        navRow(root, I18n.get(this, StringsTable.DEPIN_TRANSFER_SECTION), 0,
                v -> showScreen(SCREEN_SET_TRANSFER));
        hubExternalValue = valueRow(root,
                I18n.get(this, StringsTable.SET_EXTERNAL_TITLE), 0,
                v -> showScreen(SCREEN_SET_EXTERNAL));
        hubLanguageValue = valueRow(root,
                I18n.get(this, StringsTable.SETTINGS_LANGUAGE), 0,
                v -> showScreen(SCREEN_SET_LANGUAGE));
        navRow(root, I18n.get(this, StringsTable.SET_ABOUT_TITLE), 0,
                v -> showScreen(SCREEN_SET_ABOUT));

        root.addView(hairline(24));

        // Settings lock: always reachable, including to unlock.
        lockSwitch = settingsSwitch(root, 8, ID_ANCHOR_LOCK,
                I18n.get(this, StringsTable.SETTINGS_LOCK),
                NodeSettings.settingsLocked(this),
                (view, isChecked) -> {
                    if (syncingSettings) {
                        return;
                    }
                    if (isChecked != NodeSettings.settingsLocked(this)) {
                        NodeSettings.setSettingsLocked(this, isChecked);
                        applySettingsLock();
                        refresh();
                    }
                });
        TextView lockNote = text(I18n.get(
                this, StringsTable.SETTINGS_LOCK_NOTE), 14, Tokens.FAINT, false);
        root.addView(lockNote, matchLpTop(4));

        return (ScrollView) screens[SCREEN_SETTINGS];
    }

    /** Settings page: the node itself - the restart note, the
     *  telemetry switch and the display name (design 6.2). */
    private ScrollView buildNodeSettingsPage(FrameLayout container, int dir) {
        LinearLayout root = screenContent(container, SCREEN_SET_NODE, dir);
        subHeaderTo(root, I18n.get(this, StringsTable.SET_NODE_TITLE),
                SCREEN_SETTINGS);
        TextView restartNote = text(I18n.get(
                this, StringsTable.SETTINGS_NODE_RESTART_NOTE), 14, Tokens.FAINT, false);
        root.addView(restartNote, matchLpTop(8));

        // Telemetry: switch + always-visible public-scope note.
        telemetrySwitch = settingsSwitch(root, 16, ID_ANCHOR_TELEMETRY,
                I18n.get(this, StringsTable.SETTINGS_TELEMETRY),
                NodeSettings.telemetryEnabled(this),
                (view, isChecked) -> {
                    if (syncingSettings) {
                        return;
                    }
                    NodeSettings.setTelemetryEnabled(this, isChecked);
                    refresh();
                });
        telemetryRow = (LinearLayout) telemetrySwitch.getParent();
        telemetryPendingView = text("", 14, Tokens.FLARE, false);
        root.addView(telemetryPendingView, matchLpTop(4));
        telemetryAppliedView = text("", 14, Tokens.FAINT, false);
        root.addView(telemetryAppliedView, matchLpTop(4));
        TextView telemetryNote = text(I18n.get(
                this, StringsTable.SETTINGS_TELEMETRY_NOTE), 14, Tokens.FAINT, false);
        root.addView(telemetryNote, matchLpTop(4));
        telemetryLockedView = text(I18n.get(
                this, StringsTable.SETTINGS_LOCKED_NOTICE), 14, Tokens.FAINT, false);
        telemetryLockedView.setVisibility(View.GONE);
        root.addView(telemetryLockedView, matchLpTop(4));


        root.addView(hairline(24));

        // Display name: shows the stored value; opens the editor.
        nameRow = new LinearLayout(this);
        nameRow.setOrientation(LinearLayout.HORIZONTAL);
        nameRow.setGravity(Gravity.CENTER_VERTICAL);
        nameRow.setMinimumHeight(dp(56));
        nameRow.setPadding(0, dp(12), 0, dp(12));
        nameRow.setBackground(ripple(null));
        nameRow.setClickable(true);
        nameRow.setFocusable(true);
        nameRow.setOnClickListener(v -> {
            if (!NodeSettings.settingsLocked(this)) {
                showNameDialog();
            }
        });
        nameRow.setId(ID_ANCHOR_NAME);
        settingsAnchors.add(nameRow);
        TextView nameLabel = text(
                I18n.get(this, StringsTable.SETTINGS_DISPLAY_NAME), 16, Tokens.MUTED, false);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        nlp.setMarginEnd(dp(24));
        nameRow.addView(nameLabel, nlp);
        nameValue = text("", 16, Tokens.CONTENT, false);
        nameValue.setGravity(Gravity.END);
        nameRow.addView(nameValue);
        nameChevron = text(I18n.isRtl(this) ? "‹" : "›", 22, Tokens.FAINT, false);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.setMarginStart(dp(12));
        nameRow.addView(nameChevron, clp);
        root.addView(nameRow, matchLpTop(8));
        namePendingView = text("", 14, Tokens.FLARE, false);
        root.addView(namePendingView, matchLpTop(4));
        nameAppliedView = text("", 14, Tokens.FAINT, false);
        root.addView(nameAppliedView, matchLpTop(4));
        nameLockedView = text(I18n.get(
                this, StringsTable.SETTINGS_NAME_LOCKED), 14, Tokens.FAINT, false);
        root.addView(nameLockedView, matchLpTop(4));

        return (ScrollView) screens[SCREEN_SET_NODE];
    }

    /** Settings page: mining (design 6.2). The section builder
     *  is the one the settings scroll used. */
    private ScrollView buildMiningSettingsPage(FrameLayout container,
                                               int dir) {
        LinearLayout root = screenContent(container, SCREEN_SET_MINING, dir);
        subHeaderTo(root, I18n.get(this, StringsTable.MINING_SECTION),
                SCREEN_SETTINGS);
        buildMiningSection(root);
        return (ScrollView) screens[SCREEN_SET_MINING];
    }

    /** Settings page: the DePIN record (design 6.2). */
    private ScrollView buildDepinSettingsPage(FrameLayout container,
                                              int dir) {
        LinearLayout root = screenContent(container, SCREEN_SET_DEPIN, dir);
        subHeaderTo(root, I18n.get(this,
                StringsTable.SETTINGS_DEPIN_SECTION), SCREEN_SETTINGS);
        // DePIN: opt-in observation of this node's uptime, kept
        // entirely separate from node operation (integration step 2).
        // The toggle talks only to the DepinModule facade; the rows
        // below it are a read-only view of its latest status.
        // (No section heading of its own: the page header above
        // already carries this title — v4.2.2, audit L-9.)
        depinSwitch = settingsSwitch(root, 8, ID_ANCHOR_DEPIN,
                I18n.get(this, StringsTable.SETTINGS_DEPIN_TOGGLE),
                false,
                (view, isChecked) -> {
                    if (syncingSettings) {
                        return;
                    }
                    DepinStatus cur = DepinModule.get(this).latestStatus();
                    String deviceId = cur != null ? cur.deviceId : null;
                    long generation = cur != null
                            ? cur.identityGeneration : 0;
                    if (deviceId != null
                            && currentTransferActiveDeviceId != null
                            && !deviceId.equals(
                                    currentTransferActiveDeviceId)) {
                        // The status snapshot still describes the
                        // identity a completed transfer displaced:
                        // its binding is not an expectation the
                        // service may honour for the live identity.
                        // Send no expectation — the command then
                        // means "the live identity", exactly as
                        // for a fresh identity's first enable
                        // (progress file section 9).
                        deviceId = null;
                        generation = 0;
                    }
                    if (isChecked) {
                        DepinModule.get(this).enable(
                                deviceId, generation, notifStrings());
                    } else {
                        DepinModule.get(this).disable(deviceId, generation);
                    }
                    refresh();
                });
        depinRow = (LinearLayout) depinSwitch.getParent();
        TextView depinNote = text(I18n.get(
                this, StringsTable.SETTINGS_DEPIN_NOTE), 14, Tokens.FAINT, false);
        root.addView(depinNote, matchLpTop(4));
        depinLockedView = text(I18n.get(
                this, StringsTable.SETTINGS_LOCKED_NOTICE), 14, Tokens.FAINT, false);
        depinLockedView.setVisibility(View.GONE);
        root.addView(depinLockedView, matchLpTop(4));
        depinDeviceIdValue = infoRow(root,
                I18n.get(this, StringsTable.DEPIN_LABEL_DEVICE_ID), false);
        depinStateValue = infoRow(root,
                I18n.get(this, StringsTable.DEPIN_LABEL_STATE), true);
        depinCheckpointsValue = infoRow(root,
                I18n.get(this, StringsTable.DEPIN_LABEL_CHECKPOINTS), true);
        depinLastCheckpointValue = infoRow(root,
                I18n.get(this, StringsTable.DEPIN_LABEL_LAST_CHECKPOINT), true);
        depinNoteView = text("", 14, Tokens.FAINT, false);
        depinNoteView.setVisibility(View.GONE);
        root.addView(depinNoteView, matchLpTop(4));

        // Attempt-log repair (design section 2.3; G-11): shown
        // only while the status reports the attempt-log
        // self-stop with the corruption still present (the
        // visibility is driven in the settings refresh). The
        // tap asks for an explicit confirmation first — the
        // reset is a maintenance operation, never a silent one.
        depinAttemptResetButton = new Button(this);
        depinAttemptResetButton.setText(I18n.get(
                this, StringsTable.DEPIN_ATTEMPT_RESET_BUTTON));
        depinAttemptResetButton.setTextSize(15);
        depinAttemptResetButton.setTextColor(Tokens.FLARE);
        depinAttemptResetButton.setAllCaps(false);
        depinAttemptResetButton.setBackground(ripple(null));
        depinAttemptResetButton.setStateListAnimator(null);
        depinAttemptResetButton.setGravity(
                Gravity.START | Gravity.CENTER_VERTICAL);
        depinAttemptResetButton.setMinHeight(dp(48));
        depinAttemptResetButton.setVisibility(View.GONE);
        depinAttemptResetButton.setOnClickListener(
                v -> confirmAttemptLogReset());
        root.addView(depinAttemptResetButton, matchLpTop(4));

        // Proof-bundle file export (integration step 5): the
        // companion has always had this; the integrated build
        // gains it here. The bundle is built and staged by the
        // :depin process (see DepinBundleExport); this button
        // only asks the facade and then offers the system
        // document picker for the destination.
        depinExportButton = new Button(this);
        depinExportButton.setText(I18n.get(
                this, StringsTable.DEPIN_EXPORT_BUNDLE));
        depinExportButton.setTextSize(15);
        depinExportButton.setTextColor(Tokens.FLARE);
        depinExportButton.setAllCaps(false);
        depinExportButton.setBackground(ripple(null));
        depinExportButton.setStateListAnimator(null);
        depinExportButton.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        depinExportButton.setMinHeight(dp(48));
        depinExportButton.setOnClickListener(v -> onExportBundleClicked());
        root.addView(depinExportButton, matchLpTop(4));
        depinExportNoteView = text("", 14, Tokens.FAINT, false);
        depinExportNoteView.setVisibility(View.GONE);
        root.addView(depinExportNoteView, matchLpTop(4));

        return (ScrollView) screens[SCREEN_SET_DEPIN];
    }

    /** Settings page: device-to-device transfer, kept apart as
     *  the detailed operation it is (design 6.2). */
    private ScrollView buildTransferSettingsPage(FrameLayout container,
                                                 int dir) {
        LinearLayout root = screenContent(container, SCREEN_SET_TRANSFER, dir);
        subHeaderTo(root, I18n.get(this,
                StringsTable.DEPIN_TRANSFER_SECTION), SCREEN_SETTINGS);
        // Device transfer (integration step 4, design section
        // 5.3; UI consolidated into a state-driven main action):
        // receive a recorded identity from the companion
        // through a straight line of file hand-overs, or act as
        // the sender for a reverse migration. Each role's block
        // shows its derived state and ONE main button — the next
        // step — while the off-path operations (cancel token,
        // cannot-confirm response, transfer cancellation, ack
        // re-presentation) live collapsed under "other actions".
        // Every value shown at the confirmation step is a full
        // value.
        // (No section heading of its own: the page header above
        // already carries this title — v4.2.2, audit L-9.)
        TextView transferNote = text(I18n.get(
                this, StringsTable.DEPIN_TRANSFER_NOTE), 14, Tokens.FAINT, false);
        root.addView(transferNote, matchLpTop(8));
        depinTransferLockedView = text(I18n.get(
                this, StringsTable.DEPIN_TRANSFER_LOCKED), 14, Tokens.FAINT, false);
        depinTransferLockedView.setVisibility(View.GONE);
        root.addView(depinTransferLockedView, matchLpTop(4));
        depinTransferResultView = text("", 14, Tokens.FAINT, false);
        depinTransferResultView.setVisibility(View.GONE);
        root.addView(depinTransferResultView, matchLpTop(4));
        // The error-file export sits directly under the failure
        // line it belongs to (visible only while a failure is
        // held for export). It is not a transfer operation, so
        // it stays out of depinTransferButtons and the busy
        // discipline is applied to it directly.
        depinTransferSaveErrorButton = new Button(this);
        depinTransferSaveErrorButton.setText(I18n.get(this,
                StringsTable.DEPIN_TRANSFER_SAVE_ERROR));
        depinTransferSaveErrorButton.setTextSize(15);
        depinTransferSaveErrorButton.setTextColor(Tokens.FLARE);
        depinTransferSaveErrorButton.setAllCaps(false);
        depinTransferSaveErrorButton.setBackground(ripple(null));
        depinTransferSaveErrorButton.setStateListAnimator(null);
        depinTransferSaveErrorButton.setGravity(
                Gravity.START | Gravity.CENTER_VERTICAL);
        depinTransferSaveErrorButton.setMinHeight(dp(48));
        depinTransferSaveErrorButton.setVisibility(View.GONE);
        depinTransferSaveErrorButton.setOnClickListener(
                v -> runTransferAction(ACT_SAVE_ERROR));
        root.addView(depinTransferSaveErrorButton, matchLpTop(4));

        // Receiver block.
        TextView receiverHeading = text(I18n.get(
                this, StringsTable.DEPIN_TRANSFER_RECEIVER_HEADING),
                15, Tokens.MUTED, true);
        root.addView(receiverHeading, matchLpTop(12));
        depinTransferReceiverStateView = text("", 14, Tokens.FAINT, false);
        root.addView(depinTransferReceiverStateView, matchLpTop(4));
        depinTransferReceiverMainButton = mainTransferButton(
                v -> runTransferAction(receiverMainAction));
        root.addView(depinTransferReceiverMainButton, matchLpTop(4));
        final LinearLayout receiverOthers = new LinearLayout(this);
        receiverOthers.setOrientation(LinearLayout.VERTICAL);
        receiverOthers.setVisibility(View.GONE);
        root.addView(othersToggle(receiverOthers, true), matchLpTop(4));
        root.addView(receiverOthers, matchLpTop(0));
        transferButton(receiverOthers,
                StringsTable.DEPIN_TRANSFER_IMPORT_CANCEL_TOKEN,
                ACT_IMPORT_CANCEL_TOKEN);
        transferButton(receiverOthers,
                StringsTable.DEPIN_TRANSFER_EXPORT_ABANDON,
                ACT_EXPORT_ABANDON);
        transferButton(receiverOthers,
                StringsTable.DEPIN_TRANSFER_EXPORT_NOTICE,
                ACT_EXPORT_NOTICE);
        transferButton(receiverOthers,
                StringsTable.DEPIN_TRANSFER_EXPORT_RECEIPT,
                ACT_EXPORT_RECEIPT);
        depinTransferConfirmOneshotButton = transferButton(receiverOthers,
                StringsTable.DEPIN_TRANSFER_CONFIRM_ONESHOT,
                ACT_CONFIRM_ONESHOT);

        // Sender block (reverse migration).
        TextView senderHeading = text(I18n.get(
                this, StringsTable.DEPIN_TRANSFER_SENDER_HEADING),
                15, Tokens.MUTED, true);
        root.addView(senderHeading, matchLpTop(12));
        depinTransferSenderStateView = text("", 14, Tokens.FAINT, false);
        root.addView(depinTransferSenderStateView, matchLpTop(4));
        depinTransferSenderMainButton = mainTransferButton(
                v -> runTransferAction(senderMainAction));
        root.addView(depinTransferSenderMainButton, matchLpTop(4));
        final LinearLayout senderOthers = new LinearLayout(this);
        senderOthers.setOrientation(LinearLayout.VERTICAL);
        senderOthers.setVisibility(View.GONE);
        root.addView(othersToggle(senderOthers, false), matchLpTop(4));
        root.addView(senderOthers, matchLpTop(0));
        transferButton(senderOthers,
                StringsTable.DEPIN_TRANSFER_CANCEL_TRANSFER,
                ACT_CANCEL_TRANSFER);
        transferButton(senderOthers,
                StringsTable.DEPIN_TRANSFER_IMPORT_ABANDON,
                ACT_IMPORT_ABANDON);
        depinTransferRepresentAckButton = transferButton(senderOthers,
                StringsTable.DEPIN_TRANSFER_REPRESENT_ACK,
                ACT_REPRESENT_ACK);
        transferButton(senderOthers,
                StringsTable.DEPIN_TRANSFER_EXPORT_PACKAGE,
                ACT_EXPORT_PACKAGE);
        depinTransferCancelOneshotButton = transferButton(senderOthers,
                StringsTable.DEPIN_TRANSFER_CANCEL_ONESHOT,
                ACT_CANCEL_ONESHOT);
        depinTransferReexportButton = transferButton(senderOthers,
                StringsTable.DEPIN_TRANSFER_REEXPORT_ONESHOT,
                ACT_REEXPORT_ONESHOT);
        return (ScrollView) screens[SCREEN_SET_TRANSFER];
    }

    /** Settings page: language (design 6.2). */
    private ScrollView buildLanguageSettingsPage(FrameLayout container,
                                                 int dir) {
        LinearLayout root = screenContent(container, SCREEN_SET_LANGUAGE, dir);
        subHeaderTo(root, I18n.get(this, StringsTable.SETTINGS_LANGUAGE),
                SCREEN_SETTINGS);
        // Language: shows the current choice; opens the picker.
        languageRow = new LinearLayout(this);
        languageRow.setOrientation(LinearLayout.HORIZONTAL);
        languageRow.setGravity(Gravity.CENTER_VERTICAL);
        languageRow.setMinimumHeight(dp(56));
        languageRow.setPadding(0, dp(12), 0, dp(12));
        languageRow.setBackground(ripple(null));
        languageRow.setClickable(true);
        languageRow.setFocusable(true);
        languageRow.setOnClickListener(v -> showLanguageDialog());
        languageRow.setId(ID_ANCHOR_LANGUAGE);
        settingsAnchors.add(languageRow);
        TextView langLabel = text(
                I18n.get(this, StringsTable.SETTINGS_LANGUAGE), 16, Tokens.MUTED, false);
        LinearLayout.LayoutParams llp2 = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        llp2.setMarginEnd(dp(24));
        languageRow.addView(langLabel, llp2);
        languageValue = text("", 16, Tokens.CONTENT, false);
        languageValue.setGravity(Gravity.END);
        languageRow.addView(languageValue);
        TextView langChevron = text(I18n.isRtl(this) ? "‹" : "›", 22, Tokens.FAINT, false);
        LinearLayout.LayoutParams clp2 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        clp2.setMarginStart(dp(12));
        languageRow.addView(langChevron, clp2);
        root.addView(languageRow, matchLpTop(8));

        return (ScrollView) screens[SCREEN_SET_LANGUAGE];
    }

    /** Settings page: storage (design 6.2). The v4.1.0 section
     *  moves as it is; only its root changes. */
    private ScrollView buildStorageSettingsPage(FrameLayout container,
                                                int dir) {
        LinearLayout root = screenContent(container, SCREEN_SET_STORAGE, dir);
        subHeaderTo(root, I18n.get(this, StringsTable.STORAGE_SECTION),
                SCREEN_SETTINGS);
        buildStorageSection(root);
        return (ScrollView) screens[SCREEN_SET_STORAGE];
    }

    /** Settings page: information from outside (design 6.2) -
     *  what the app fetches, each with its switch and its
     *  disclosure. The price fetch is here and in the price
     *  sheet, over one preference (14-2). The telemetry node
     *  count joins with #10 (v5.0.0). */
    private ScrollView buildExternalSettingsPage(FrameLayout container,
                                                 int dir) {
        LinearLayout root = screenContent(container, SCREEN_SET_EXTERNAL, dir);
        subHeaderTo(root, I18n.get(this, StringsTable.SET_EXTERNAL_TITLE),
                SCREEN_SETTINGS);
        priceSwitch = settingsSwitch(root, 16, ID_ANCHOR_PRICE,
                I18n.get(this, StringsTable.PRICE_FETCH),
                PriceStore.isEnabled(this),
                (view, isChecked) -> {
                    if (syncingSettings) {
                        return;
                    }
                    PriceStore.setEnabled(this, isChecked);
                    refresh();
                });
        priceSwitchRow = (LinearLayout) priceSwitch.getParent();
        TextView priceNote = text(I18n.get(
                this, StringsTable.PRICE_DISCLOSURE), 14, Tokens.FAINT, false);
        root.addView(priceNote, matchLpTop(4));
        // The same "why it is disabled" notice the other locked
        // sections show (v4.2.2, audit L-4).
        priceLockedView = text(I18n.get(
                this, StringsTable.SETTINGS_LOCKED_NOTICE), 14, Tokens.FAINT, false);
        priceLockedView.setVisibility(View.GONE);
        root.addView(priceLockedView, matchLpTop(4));
        return (ScrollView) screens[SCREEN_SET_EXTERNAL];
    }

    /** Settings page: about this app (design 6.2). */
    private ScrollView buildAboutSettingsPage(FrameLayout container,
                                              int dir) {
        LinearLayout root = screenContent(container, SCREEN_SET_ABOUT, dir);
        subHeaderTo(root, I18n.get(this, StringsTable.SET_ABOUT_TITLE),
                SCREEN_SETTINGS);
        navRow(root, I18n.get(this, StringsTable.SET_LICENSES_TITLE), 8,
                v -> showScreen(SCREEN_SET_LICENSES));
        // Version, at the very bottom.
        TextView version = text(versionText(), 14, Tokens.FAINT, false);
        version.setGravity(Gravity.CENTER);
        root.addView(version, matchLpTop(32));
        return (ScrollView) screens[SCREEN_SET_ABOUT];
    }

    /**
     * Settings page: the bundled binaries' licence notices (gap
     * inventory A-1). The text is generated into
     * assets/licenses/NOTICES.txt from the two projects' locked
     * dependency trees; the same lists live in the repository as
     * docs/THIRD-PARTY-LICENSES.md.
     */
    private ScrollView buildLicencesPage(FrameLayout container,
                                         int dir) {
        LinearLayout root = screenContent(container, SCREEN_SET_LICENSES,
                dir);
        subHeaderTo(root, I18n.get(this, StringsTable.SET_LICENSES_TITLE),
                SCREEN_SET_ABOUT);
        TextView body = text(readLicenceNotices(), 11, Tokens.CONTENT,
                false);
        body.setTypeface(android.graphics.Typeface.MONOSPACE);
        root.addView(body, matchLpTop(16));
        return (ScrollView) screens[SCREEN_SET_LICENSES];
    }

    private String readLicenceNotices() {
        try (java.io.InputStream in =
                     getAssets().open("licenses/NOTICES.txt")) {
            java.io.ByteArrayOutputStream buf =
                    new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while ((n = in.read(chunk)) >= 0) {
                buf.write(chunk, 0, n);
            }
            return buf.toString(
                    java.nio.charset.StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return "";
        }
    }

    // ------------------------------------------------------------------
    // Storage (v4.1.0)
    // ------------------------------------------------------------------

    /**
     * The storage section of the settings screen: five usage
     * figures and one action, the resync — delete the chain
     * database so the node syncs from the start again.
     *
     * What the resync may delete is {@link StorageLogic}'s rule and
     * whether it may run at all is {@link
     * NodeService#resetChainDatabase}'s decision; this code builds
     * views, asks for a confirmation that names what goes and what
     * stays, and shows the answer. It never starts the node.
     */
    private void buildStorageSection(LinearLayout root) {
        // No section heading: the page header already carries
        // this title (v4.2.2, audit L-9).
        storageChainDbValue = infoRow(root, I18n.get(
                this, StringsTable.STORAGE_LABEL_CHAIN_DB), false);
        storageNodeValue = infoRow(root, I18n.get(
                this, StringsTable.STORAGE_LABEL_NODE), true);
        storageLogsValue = infoRow(root, I18n.get(
                this, StringsTable.STORAGE_LABEL_LOGS), true);
        storageDepinValue = infoRow(root, I18n.get(
                this, StringsTable.STORAGE_LABEL_DEPIN), true);
        storageTotalValue = infoRow(root, I18n.get(
                this, StringsTable.STORAGE_LABEL_TOTAL), true);
        // The device's free space under the total (gap
        // inventory A-20): the reading the diagnostic summary
        // already takes, so "how much room is left" is answered
        // where the sizes are.
        storageFreeValue = infoRow(root, I18n.get(
                this, StringsTable.STORAGE_FREE), true);
        storageRunningNote = text(I18n.get(
                this, StringsTable.STORAGE_RUNNING_NOTE), 14, Tokens.FAINT, false);
        storageRunningNote.setVisibility(View.GONE);
        root.addView(storageRunningNote, matchLpTop(4));

        storageResyncButton = linkButton(I18n.get(
                this, StringsTable.STORAGE_RESYNC_BUTTON),
                v -> confirmChainResync());
        root.addView(storageResyncButton, matchLpTop(8));
        storageNeedsStopNote = text(I18n.get(
                this, StringsTable.STORAGE_RESYNC_NEEDS_STOP),
                14, Tokens.FAINT, false);
        storageNeedsStopNote.setVisibility(View.GONE);
        root.addView(storageNeedsStopNote, matchLpTop(4));
        storageResultView = text("", 14, Tokens.FAINT, false);
        storageResultView.setVisibility(View.GONE);
        root.addView(storageResultView, matchLpTop(4));
    }

    /** One figure: its size, "not available" when it could not be
     *  measured, "measuring" before the first answer. */
    private String storageText(StorageLogic.Usage u, long bytes) {
        if (u == null) {
            return I18n.get(this, StringsTable.STORAGE_MEASURING);
        }
        return bytes < 0
                ? I18n.get(this, StringsTable.VALUE_UNAVAILABLE)
                : DiagShare.formatBytesEn(bytes);
    }

    /**
     * The resync is offered only while the node is stopped — no
     * session live or being established, and no stop in flight.
     * This is the screen's own gate; the deletion re-checks, under
     * the launch lock, that no node process is alive.
     */
    private boolean chainResyncAllowed(NodeState st) {
        return st.status == NodeState.Status.STOPPED && !stopping;
    }

    private void refreshStorage(NodeState st, long now) {
        if (storageResyncButton == null) {
            return;
        }
        boolean busy = StorageResetState.PROCESS.busy();
        StorageResetState.Finished finished =
                StorageResetState.PROCESS.last();
        if (finished != null && finished.seq != storageResetSeen) {
            // The figures changed: measure again.
            storageResetSeen = finished.seq;
            storageMeasuredElapsedMs = -1;
        }
        // The storage page itself must trigger the measurement
        // (audit H-1): the gate used to name only the hub screen,
        // so on SCREEN_SET_STORAGE nothing ever measured and a
        // resync's fresh figures never replaced the old ones.
        // The hub is kept in the gate because its Storage row
        // shows the total.
        if ((currentScreen == SCREEN_SET_STORAGE
                || currentScreen == SCREEN_SETTINGS) && !storageMeasuring
                && (storageMeasuredElapsedMs < 0
                || now - storageMeasuredElapsedMs >= STORAGE_REMEASURE_MS)) {
            measureStorage();
        }
        StorageLogic.Usage u = storageUsage;
        setTextIfChanged(storageChainDbValue,
                storageText(u, u == null ? 0 : u.chainDbBytes));
        setTextIfChanged(storageNodeValue,
                storageText(u, u == null ? 0 : u.nodeBytes));
        setTextIfChanged(storageLogsValue,
                storageText(u, u == null ? 0 : u.logsBytes));
        setTextIfChanged(storageDepinValue,
                storageText(u, u == null ? 0 : u.depinBytes));
        setTextIfChanged(storageTotalValue,
                storageText(u, u == null ? 0 : u.totalBytes));
        // Free space is a live reading, not part of the measured
        // walk: it is shown as itself even while the sizes above
        // still say "measuring".
        long freeBytes = -1;
        try {
            freeBytes = getFilesDir().getUsableSpace();
        } catch (Throwable ignored) {
            // The row shows "unavailable".
        }
        setTextIfChanged(storageFreeValue, freeBytes < 0
                ? I18n.get(this, StringsTable.VALUE_UNAVAILABLE)
                : DiagShare.formatBytesEn(freeBytes));
        // A running node's database is open and moving: say that
        // the figures are estimates then.
        storageRunningNote.setVisibility(
                nodeLive(st) ? View.VISIBLE : View.GONE);

        boolean allowed = chainResyncAllowed(st);
        boolean locked = NodeSettings.settingsLocked(this);
        storageResyncButton.setEnabled(allowed && !locked && !busy);
        storageResyncButton.setAlpha(
                allowed && !locked && !busy ? 1f : 0.70f);
        setTextIfChanged(storageResyncButton, I18n.get(this,
                busy ? StringsTable.STORAGE_RESYNC_WORKING
                        : StringsTable.STORAGE_RESYNC_BUTTON));
        storageNeedsStopNote.setVisibility(
                allowed || busy ? View.GONE : View.VISIBLE);
        String result = busy ? null
                : storageNote != null ? storageNote
                : finished != null ? chainResyncMessage(finished)
                : StorageLogic.resetPending(getFilesDir())
                        ? I18n.get(this,
                                StringsTable.STORAGE_RESYNC_PENDING)
                        : null;
        if (result == null) {
            storageResultView.setVisibility(View.GONE);
        } else {
            setTextIfChanged(storageResultView, result);
            storageResultView.setVisibility(View.VISIBLE);
        }
    }

    /** Measure off the UI thread; the next refresh shows the result. */
    private void measureStorage() {
        storageMeasuring = true;
        final File filesDir = getFilesDir();
        final File dataDir = getDataDir();
        try {
            StorageResetState.PROCESS.executor().execute(() -> {
                StorageLogic.Usage measured = null;
                try {
                    measured = StorageLogic.measure(
                            filesDir, dataDir, NodeService.CHAIN_NAME);
                } catch (Throwable ignored) {
                    // The previous figures stand.
                }
                final StorageLogic.Usage result = measured;
                runOnUiThread(() -> {
                    storageMeasuring = false;
                    storageMeasuredElapsedMs = SystemClock.elapsedRealtime();
                    if (result != null) {
                        storageUsage = result;
                    }
                });
            });
        } catch (RuntimeException e) {
            storageMeasuring = false;
            storageMeasuredElapsedMs = SystemClock.elapsedRealtime();
        }
    }

    /**
     * The confirmation before the resync: what is deleted, what is
     * kept, and that it cannot be undone and starts nothing. Only
     * the dialog's own button goes on to delete.
     */
    private void confirmChainResync() {
        if (NodeSettings.settingsLocked(this)) {
            applySettingsLock();
            return;
        }
        if (StorageResetState.PROCESS.busy()
                || !chainResyncAllowed(NodeState.get())) {
            return;
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(8), dp(24), dp(8));
        box.addView(text(I18n.get(this,
                StringsTable.STORAGE_RESYNC_CONFIRM_DELETES),
                14, Tokens.CONTENT, false), matchLp());
        box.addView(text(I18n.get(this,
                StringsTable.STORAGE_RESYNC_CONFIRM_KEEPS),
                14, Tokens.CONTENT, false), matchLpTop(12));
        box.addView(text(I18n.get(this,
                StringsTable.STORAGE_RESYNC_CONFIRM_NOTE),
                14, Tokens.MUTED, false), matchLpTop(12));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(I18n.get(this,
                        StringsTable.STORAGE_RESYNC_CONFIRM_TITLE))
                .setView(scroll)
                .setNegativeButton(I18n.get(this, StringsTable.BUTTON_CANCEL), null)
                .setPositiveButton(I18n.get(this,
                        StringsTable.STORAGE_RESYNC_CONFIRM_BUTTON),
                        (d, which) -> {
                            ((AlertDialog) d).getButton(
                                    AlertDialog.BUTTON_POSITIVE)
                                    .performHapticFeedback(
                                            HapticFeedbackConstants
                                                    .LONG_PRESS);
                            runChainResync();
                        })
                .create();
        dialog.show();
        styleDialog(dialog);
    }

    /**
     * The confirmed resync. The state is looked at again — the
     * dialog may have been open for a while — and the deletion runs
     * off the UI thread; {@link NodeService#resetChainDatabase}
     * refuses on its own if a node turns out to be alive.
     */
    private void runChainResync() {
        if (NodeSettings.settingsLocked(this)
                || StorageResetState.PROCESS.busy()) {
            return;
        }
        if (!chainResyncAllowed(NodeState.get())) {
            storageNote = I18n.get(
                    this, StringsTable.STORAGE_RESYNC_REFUSED);
            refresh();
            return;
        }
        storageNote = null;
        final Context app = getApplicationContext();
        // The result is published process-wide; this instance, if
        // it is still the one on screen, shows it at once, and any
        // other reads it at its next refresh.
        StorageResetState.PROCESS.start(
                () -> NodeService.resetChainDatabase(app),
                () -> runOnUiThread(() -> {
                    if (!isDestroyed()) {
                        refresh();
                    }
                }));
        refresh();
    }

    /** The result line of a finished resync run. */
    private String chainResyncMessage(StorageResetState.Finished finished) {
        StorageLogic.ResetOutcome outcome = finished.outcome;
        if (finished.threw) {
            return I18n.format(this, StringsTable.STORAGE_RESYNC_FAILED,
                    I18n.get(this, StorageResetState.failureKey(null)));
        }
        if (outcome == null) {
            return I18n.get(this, StringsTable.STORAGE_RESYNC_REFUSED);
        }
        if (outcome.result == StorageLogic.ResetResult.DELETED) {
            return I18n.get(this, StringsTable.STORAGE_RESYNC_DONE);
        }
        if (outcome.result == StorageLogic.ResetResult.NOTHING_TO_DELETE) {
            return I18n.get(this, StringsTable.STORAGE_RESYNC_NOTHING);
        }
        return I18n.format(this, StringsTable.STORAGE_RESYNC_FAILED,
                I18n.get(this,
                        StorageResetState.failureKey(outcome.failureKind)));
    }

    // ------------------------------------------------------------------
    // Mining (v4.0.0)
    // ------------------------------------------------------------------

    /**
     * The mining section of the settings screen (specification
     * sections 3.1 and 3.3 to 3.5). Always present: the opt-in
     * switch with its note, and the two rows a user needs BEFORE
     * mining can be switched on — the rewards inner hash and the
     * worker count. Everything that reports on mining (state,
     * reasons, counters, the address the node reports, the miner's
     * log) sits in one block that is shown only while mining is in
     * use, so with mining off this section is the switch, the note
     * and the two rows, and nothing else on any screen changes.
     *
     * Every decision shown here is made in {@link MiningLogic};
     * this method only builds views, and {@link #refreshMining}
     * only fills them.
     */
    private void buildMiningSection(LinearLayout root) {
        // No section heading: the page header already carries
        // this title (v4.2.2, audit L-9).
        miningSwitch = settingsSwitch(root, 8, ID_ANCHOR_MINING,
                I18n.get(this, StringsTable.MINING_TOGGLE),
                NodeSettings.miningEnabled(this),
                (view, isChecked) -> {
                    if (syncingSettings) {
                        return;
                    }
                    onMiningToggled(isChecked);
                });
        miningRow = (LinearLayout) miningSwitch.getParent();
        miningPendingView = text("", 14, Tokens.FLARE, false);
        miningPendingView.setVisibility(View.GONE);
        root.addView(miningPendingView, matchLpTop(4));
        miningAppliedView = text("", 14, Tokens.FAINT, false);
        miningAppliedView.setVisibility(View.GONE);
        root.addView(miningAppliedView, matchLpTop(4));
        root.addView(text(I18n.get(this, StringsTable.MINING_NOTE),
                14, Tokens.FAINT, false), matchLpTop(4));
        miningLockedView = text(I18n.get(
                this, StringsTable.SETTINGS_LOCKED_NOTICE), 14, Tokens.FAINT, false);
        miningLockedView.setVisibility(View.GONE);
        root.addView(miningLockedView, matchLpTop(4));

        // Rewards: the row says only whether a value is saved.
        miningRewardsValue = valueRow(root,
                I18n.get(this, StringsTable.MINING_REWARDS_LABEL), 8, v -> {
                    if (!NodeSettings.settingsLocked(this)) {
                        showRewardsDialog();
                    }
                });
        miningRewardsRow = (LinearLayout) miningRewardsValue.getTag();
        miningRewardsPendingView = text("", 14, Tokens.FLARE, false);
        miningRewardsPendingView.setVisibility(View.GONE);
        root.addView(miningRewardsPendingView, matchLpTop(0));

        miningWorkersValue = valueRow(root,
                I18n.get(this, StringsTable.MINING_WORKERS_LABEL), 0, v -> {
                    if (!NodeSettings.settingsLocked(this)) {
                        showWorkersDialog();
                    }
                });
        miningWorkersRow = (LinearLayout) miningWorkersValue.getTag();
        miningWorkersPendingView = text("", 14, Tokens.FLARE, false);
        miningWorkersPendingView.setVisibility(View.GONE);
        root.addView(miningWorkersPendingView, matchLpTop(0));

        // The status block: on screen only while mining is in use.
        miningStatusBox = new LinearLayout(this);
        miningStatusBox.setOrientation(LinearLayout.VERTICAL);
        miningStatusBox.setVisibility(View.GONE);
        root.addView(miningStatusBox, matchLpTop(8));
        miningStateValue = infoRow(miningStatusBox,
                I18n.get(this, StringsTable.MINING_STATE_LABEL), true);
        miningReasonView = text("", 14, Tokens.MUTED, false);
        miningReasonView.setVisibility(View.GONE);
        miningStatusBox.addView(miningReasonView, matchLpTop(0));
        miningPendingNoteView = text("", 14, Tokens.FLARE, false);
        miningPendingNoteView.setVisibility(View.GONE);
        miningStatusBox.addView(miningPendingNoteView, matchLpTop(4));
        miningNodeFailedView = text(I18n.get(
                this, StringsTable.MINING_NODE_FAILED_HINT),
                14, Tokens.STATE_FAILED, false);
        miningNodeFailedView.setVisibility(View.GONE);
        miningStatusBox.addView(miningNodeFailedView, matchLpTop(4));
        // Waiting is the normal picture while the node catches up;
        // the sentence that says so is always there with the state.
        miningStatusBox.addView(text(I18n.get(
                this, StringsTable.MINING_WAITING_NOTE), 14, Tokens.FAINT, false),
                matchLpTop(8));

        // What a node launched with mining reports.
        miningStatsBox = new LinearLayout(this);
        miningStatsBox.setOrientation(LinearLayout.VERTICAL);
        miningStatsBox.setVisibility(View.GONE);
        miningStatusBox.addView(miningStatsBox, matchLpTop(8));
        miningHashrateValue = infoRow(miningStatsBox,
                I18n.get(this, StringsTable.MINING_LABEL_HASHRATE), true);
        miningJobsValue = infoRow(miningStatsBox,
                I18n.get(this, StringsTable.MINING_LABEL_JOBS), true);
        miningSubmittedValue = infoRow(miningStatsBox,
                I18n.get(this, StringsTable.MINING_LABEL_SUBMITTED), true);
        miningAcceptedValue = infoRow(miningStatsBox,
                I18n.get(this, StringsTable.MINING_LABEL_ACCEPTED), true);
        miningStatsBox.addView(text(I18n.get(
                this, StringsTable.MINING_STATS_NOTE), 14, Tokens.FAINT, false),
                matchLpTop(4));
        // The address is the node's own report for the inner hash it
        // was launched with — public information, shown in full so it
        // can be compared, character for character, with the address
        // the official key tool printed next to that inner hash.
        miningAddressValue = infoRow(miningStatsBox,
                I18n.get(this, StringsTable.MINING_LABEL_ADDRESS), true);
        miningAddressValue.setTextIsSelectable(true);
        miningStatsBox.addView(text(I18n.get(
                this, StringsTable.MINING_ADDRESS_NOTE), 14, Tokens.FAINT, false),
                matchLpTop(4));

        miningRestartButton = linkButton(
                I18n.get(this, StringsTable.MINING_RESTART_MINER), v -> {
                    if (NodeSettings.settingsLocked(this)) {
                        return;
                    }
                    // The miner alone: the supervisor has no way to
                    // touch the node.
                    MiningLogic.MinerView view = NodeState.get().minerView;
                    if (view != null) {
                        view.requestRestart();
                    }
                    refresh();
                });
        miningStatsBox.addView(miningRestartButton, matchLpTop(8));
        miningStatsBox.addView(text(I18n.get(
                this, StringsTable.MINING_RESTART_MINER_NOTE),
                14, Tokens.FAINT, false), matchLpTop(4));

        miningStatsBox.addView(text(I18n.get(
                this, StringsTable.MINING_LOG_LABEL), 14, Tokens.MUTED, true),
                matchLpTop(12));
        miningLogView = text("", 11, Tokens.MUTED, false);
        miningLogView.setTypeface(Typeface.MONOSPACE);
        miningLogView.setTextIsSelectable(true);
        miningStatsBox.addView(miningLogView, matchLpTop(4));
    }

    /**
     * A tappable settings row with a value: label at the start, the
     * current value and a chevron at the end. The label/value pair
     * is an {@link InfoRowView} — the same base as the read-only
     * {@link #infoRow} — so a long value in a verbose language
     * stacks under the label instead of crushing it (design 7.1:
     * value rows are InfoRowView + chevron; v4.2.2, audit L-3).
     * The label reads as content, like {@link #navRow}'s: a row
     * that can be tapped is navigation, not data. Returns the
     * value view; the row itself is the value's tag.
     */
    private TextView valueRow(LinearLayout parent, String label, int topDp,
                              View.OnClickListener click) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setBackground(ripple(null));
        r.setClickable(true);
        r.setFocusable(true);
        r.setOnClickListener(click);
        InfoRowView info = new InfoRowView(this);
        TextView l = text(label, 16, Tokens.CONTENT, false);
        info.addView(l);
        TextView v = text("", 16, Tokens.CONTENT, false);
        v.setGravity(Gravity.END);
        info.addView(v);
        r.addView(info, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView chev = text(I18n.isRtl(this) ? "‹" : "›", 22, Tokens.FAINT, false);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.setMarginStart(dp(12));
        r.addView(chev, clp);
        v.setTag(r);
        parent.addView(r, matchLpTop(topDp));
        return v;
    }

    /** A text-style action button (the DePIN export button's dress). */
    private Button linkButton(String label, View.OnClickListener click) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(15);
        b.setTextColor(Tokens.FLARE);
        b.setAllCaps(false);
        b.setBackground(ripple(null));
        b.setStateListAnimator(null);
        b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        b.setMinHeight(dp(48));
        b.setOnClickListener(click);
        return b;
    }

    /**
     * The mining switch was used. Switching OFF is immediate.
     * Switching ON is never done by the tap alone: the switch goes
     * straight back to off, and only the confirmation dialog's own
     * button stores the setting — after the heat, battery and role
     * warnings have been on screen (specification section 3.5).
     * Either way the running node is not touched: the change is
     * picked up at its next start, which the user performs.
     */
    private void onMiningToggled(boolean isChecked) {
        if (NodeSettings.settingsLocked(this)) {
            syncSwitch(miningSwitch, NodeSettings.miningEnabled(this));
            return;
        }
        if (!isChecked) {
            NodeSettings.setMiningEnabled(this, false);
            refresh();
            return;
        }
        syncSwitch(miningSwitch, false);
        if (!NodeSettings.hasRewardsInnerHash(this)) {
            Toast.makeText(this,
                    I18n.get(this, StringsTable.MINING_NEEDS_REWARDS),
                    Toast.LENGTH_LONG).show();
            return;
        }
        showMiningConfirmDialog();
    }

    private void showMiningConfirmDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(8), dp(24), dp(8));
        box.addView(text(I18n.get(this, StringsTable.MINING_CONFIRM_HEAT),
                14, Tokens.CONTENT, false), matchLp());
        box.addView(text(I18n.get(this, StringsTable.MINING_CONFIRM_ROLE),
                14, Tokens.CONTENT, false), matchLpTop(12));
        box.addView(text(I18n.get(this, StringsTable.MINING_CONFIRM_PORT),
                14, Tokens.MUTED, false), matchLpTop(12));
        box.addView(text(I18n.get(this, StringsTable.MINING_CONFIRM_RESTART),
                14, Tokens.MUTED, false), matchLpTop(12));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);
        miningDialog = new AlertDialog.Builder(this)
                .setTitle(I18n.get(this, StringsTable.MINING_CONFIRM_TITLE))
                .setView(scroll)
                .setNegativeButton(I18n.get(this, StringsTable.BUTTON_CANCEL), null)
                .setPositiveButton(
                        I18n.get(this, StringsTable.MINING_CONFIRM_BUTTON),
                        (d, which) -> {
                            if (NodeSettings.settingsLocked(this)) {
                                return;
                            }
                            // Refused by the setter itself while no
                            // valid inner hash is stored.
                            if (!NodeSettings.setMiningEnabled(this, true)) {
                                Toast.makeText(this, I18n.get(this,
                                        StringsTable.MINING_NEEDS_REWARDS),
                                        Toast.LENGTH_LONG).show();
                            }
                            refresh();
                        })
                .create();
        miningDialog.show();
        styleDialog(miningDialog);
    }

    /**
     * The rewards inner hash editor (specification section 3.3).
     *
     * The value is a secret as far as this app is concerned, and the
     * editor is built around that: the field always opens EMPTY (a
     * saved value is never shown again — the row only says "set"),
     * the keyboard is asked not to learn or suggest from it, autofill
     * is told to stay away, the dialog's window is flagged secure so
     * the typed value stays out of screenshots, screen recordings
     * and the recents thumbnail, and the field is cleared when the
     * dialog closes. Nothing here logs.
     *
     * Validation is the official node's own rule
     * ({@link MiningLogic#checkInnerHash}): a value the node would
     * refuse cannot be saved.
     */
    private void showRewardsDialog() {
        float density = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(8), dp(24), dp(8));
        box.addView(text(I18n.get(this, StringsTable.MINING_REWARDS_INFO_DEST),
                14, Tokens.CONTENT, false), matchLp());
        box.addView(text(I18n.get(this, StringsTable.MINING_REWARDS_INFO_HOW),
                14, Tokens.MUTED, false), matchLpTop(8));
        box.addView(text(I18n.get(this, StringsTable.MINING_REWARDS_INFO_WALLET),
                14, Tokens.MUTED, false), matchLpTop(8));
        box.addView(text(I18n.get(this, StringsTable.MINING_REWARDS_INFO_WRONG),
                14, Tokens.STATE_FAILED, false), matchLpTop(8));
        box.addView(text(I18n.get(this, StringsTable.MINING_REWARDS_INFO_ONLY),
                14, Tokens.STATE_FAILED, false), matchLpTop(8));
        box.addView(text(I18n.get(this, StringsTable.MINING_REWARDS_INFO_PRIVATE),
                14, Tokens.FAINT, false), matchLpTop(8));
        final boolean hadValue = NodeSettings.hasRewardsInnerHash(this);
        if (hadValue) {
            box.addView(text(I18n.get(
                    this, StringsTable.MINING_REWARDS_REPLACE_NOTE),
                    14, Tokens.MUTED, false), matchLpTop(8));
        }

        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHorizontallyScrolling(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE
                | EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        input.setHint(I18n.get(this, StringsTable.MINING_REWARDS_HINT));
        input.setTypeface(Typeface.MONOSPACE);
        input.setTextSize(14);
        input.setTextColor(Tokens.CONTENT);
        input.setHintTextColor(Tokens.FAINT);
        input.setBackground(rounded(Tokens.SURFACE, 12, density, Tokens.FIELD_BORDER, 1));
        input.setPadding(dp(16), dp(12), dp(16), dp(12));
        input.setMinHeight(dp(56));
        box.addView(input, matchLpTop(12));
        final TextView error = text("", 14, Tokens.STATE_FAILED, false);
        error.setVisibility(View.GONE);
        box.addView(error, matchLpTop(4));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(I18n.get(this, StringsTable.MINING_REWARDS_LABEL))
                .setView(scroll)
                .setNegativeButton(I18n.get(this, StringsTable.BUTTON_CANCEL), null)
                .setPositiveButton(
                        I18n.get(this, StringsTable.BUTTON_SAVE), null)
                .create();
        miningDialog = dialog;
        if (dialog.getWindow() != null) {
            dialog.getWindow().addFlags(
                    WindowManager.LayoutParams.FLAG_SECURE);
        }

        if (hadValue) {
            box.addView(linkButton(
                    I18n.get(this, StringsTable.MINING_REWARDS_DELETE),
                    v -> confirmRewardsDelete(dialog)), matchLpTop(8));
            box.addView(text(I18n.get(
                    this, StringsTable.MINING_REWARDS_DELETE_NOTE),
                    14, Tokens.FAINT, false), matchLpTop(4));
        }

        dialog.setOnShowListener(dlg -> {
            final Button save = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            final Runnable update = () -> {
                MiningLogic.EntryState state = MiningLogic.innerHashEntryState(
                        input.getText().toString());
                save.setEnabled(state == MiningLogic.EntryState.OK);
                if (state == MiningLogic.EntryState.BAD_FORMAT) {
                    error.setText(I18n.get(this,
                            StringsTable.MINING_REWARDS_ERR_FORMAT));
                    error.setVisibility(View.VISIBLE);
                } else if (state == MiningLogic.EntryState.NOT_CANONICAL) {
                    error.setText(I18n.get(this,
                            StringsTable.MINING_REWARDS_ERR_CANONICAL));
                    error.setVisibility(View.VISIBLE);
                } else {
                    error.setVisibility(View.GONE);
                }
            };
            input.addTextChangedListener(new AfterTextChanged(update));
            update.run();
            save.setOnClickListener(v -> {
                // Re-validate at the last moment, as the name editor
                // does: the lock may have become effective, and the
                // setter itself refuses anything but a valid value.
                if (NodeSettings.settingsLocked(this)) {
                    input.setEnabled(false);
                    save.setEnabled(false);
                    return;
                }
                if (!NodeSettings.setRewardsInnerHash(
                        this, input.getText().toString())) {
                    update.run();
                    return; // stay open with the error showing
                }
                dialog.dismiss();
                refresh();
            });
        });
        // Whichever way the dialog closes, the typed text does not
        // stay behind in a view.
        dialog.setOnDismissListener(d -> input.setText(""));
        dialog.show();
        styleDialog(dialog);
    }

    /**
     * A text watcher that runs one action after every change. A
     * named class on purpose: an anonymous one here would renumber
     * the compiler's synthetic classes for this file, and with them
     * the bytecode of methods this change does not touch.
     */
    private static final class AfterTextChanged implements TextWatcher {
        private final Runnable action;

        AfterTextChanged(Runnable action) {
            this.action = action;
        }

        @Override
        public void beforeTextChanged(
                CharSequence s, int start, int count, int after) {
        }

        @Override
        public void onTextChanged(
                CharSequence s, int start, int before, int count) {
        }

        @Override
        public void afterTextChanged(Editable s) {
            action.run();
        }
    }

    /** Deleting the saved inner hash also switches mining off; ask first. */
    private void confirmRewardsDelete(final AlertDialog editor) {
        if (NodeSettings.settingsLocked(this)) {
            return;
        }
        AlertDialog confirm = new AlertDialog.Builder(this)
                .setTitle(I18n.get(this, StringsTable.MINING_REWARDS_DELETE))
                .setMessage(I18n.get(
                        this, StringsTable.MINING_REWARDS_DELETE_NOTE))
                .setNegativeButton(I18n.get(this, StringsTable.BUTTON_CANCEL), null)
                .setPositiveButton(I18n.get(this, StringsTable.BUTTON_OK), (d, which) -> {
                    if (NodeSettings.settingsLocked(this)) {
                        return;
                    }
                    NodeSettings.clearRewardsInnerHash(this);
                    editor.dismiss();
                    refresh();
                })
                .create();
        confirm.show();
        styleDialog(confirm);
    }

    /** The picker label of a worker setting: "1", "2", "4" or "Auto (n)". */
    private String workerLabel(int setting, int cores) {
        if (setting == MiningLogic.WORKERS_AUTO) {
            return I18n.format(this, StringsTable.MINING_WORKERS_AUTO_VALUE,
                    String.valueOf(MiningLogic.effectiveCpuWorkers(
                            MiningLogic.WORKERS_AUTO, cores)));
        }
        return String.valueOf(setting);
    }

    /**
     * The worker-count picker: the fixed choices of
     * {@link MiningLogic#workerChoices} and nothing else — the count
     * is the one part of the miner's command the user controls. A
     * custom list view, for the reason given at the language picker.
     * A tap saves at once; a running miner picks the new count up
     * when it is restarted (the status block offers that).
     */
    private void showWorkersDialog() {
        final int[] choices = MiningLogic.workerChoices();
        final int cores = Runtime.getRuntime().availableProcessors();
        final int current = NodeSettings.miningCpuWorkers(this);
        CharSequence[] items = new CharSequence[choices.length];
        int checkedIndex = 0;
        for (int i = 0; i < choices.length; i++) {
            items[i] = workerLabel(choices[i], cores);
            if (choices[i] == current) {
                checkedIndex = i;
            }
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(8), dp(24), dp(8));
        box.addView(text(I18n.get(this, StringsTable.MINING_WORKERS_NOTE),
                14, Tokens.FAINT, false), matchLp());
        ListView list = new ListView(this);
        list.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        list.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_single_choice, items));
        list.setItemChecked(checkedIndex, true);
        list.setDivider(new ColorDrawable(Tokens.HAIRLINE));
        list.setDividerHeight(Math.max(1, dp(1)));
        LinearLayout.LayoutParams listLp = matchLpTop(8);
        listLp.height = Math.min(dp(56) * items.length, dp(400));
        box.addView(list, listLp);
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(I18n.get(this, StringsTable.MINING_WORKERS_LABEL))
                .setView(box)
                .setNegativeButton(I18n.get(this, StringsTable.BUTTON_CANCEL), null)
                .create();
        miningDialog = dialog;
        list.setOnItemClickListener((parent, view, which, id) -> {
            if (!NodeSettings.settingsLocked(this)
                    && choices[which] != current) {
                NodeSettings.setMiningCpuWorkers(this, choices[which]);
            }
            dialog.dismiss();
            refresh();
        });
        dialog.show();
        styleDialog(dialog);
    }

    /** Whether a node session is live, as the mining views mean it. */
    private static boolean nodeLive(NodeState st) {
        return st.serviceRunning && st.status != NodeState.Status.STOPPED;
    }

    /** What restarting the node would change about mining, if anything. */
    private MiningLogic.PendingKind miningPendingKind(NodeState st) {
        return MiningLogic.nodeRestartPendingKind(nodeLive(st),
                st.launchedMining, st.launchedRewardsTag,
                st.launchedMiningSkip, NodeSettings.miningEnabled(this),
                NodeSettings.rewardsInnerHash(this));
    }

    /** Home card and pending link: the mining settings, switch on top. */
    private void openMiningSettings() {
        showScreen(SCREEN_SET_MINING);
    }

    /**
     * Fill the mining views (1 s tick). With mining not in use — the
     * switch off and no node running that was launched with it —
     * this syncs the switch and the two rows, keeps the status block
     * and the home card GONE, and returns.
     */
    private void refreshMining(NodeState st, long now) {
        if (miningSwitch == null || homeMiningRow == null) {
            return;
        }
        String tag = I18n.effectiveTag(this);
        boolean enabled = NodeSettings.miningEnabled(this);
        boolean hasHash = NodeSettings.hasRewardsInnerHash(this);
        boolean locked = NodeSettings.settingsLocked(this);
        boolean nodeRunning = nodeLive(st);
        boolean launchedWith = nodeRunning
                && Boolean.TRUE.equals(st.launchedMining);
        boolean inUse = enabled || launchedWith;

        syncSwitch(miningSwitch, enabled);
        miningSwitch.setEnabled(!locked);
        miningRow.setEnabled(!locked);
        miningLockedView.setVisibility(locked ? View.VISIBLE : View.GONE);
        miningRewardsRow.setEnabled(!locked);
        miningRewardsRow.setAlpha(locked ? 0.55f : 1f);
        miningWorkersRow.setEnabled(!locked);
        miningWorkersRow.setAlpha(locked ? 0.55f : 1f);

        setTextIfChanged(miningRewardsValue, I18n.get(this, hasHash
                ? StringsTable.MINING_REWARDS_SET
                : StringsTable.MINING_REWARDS_UNSET));
        int setting = NodeSettings.miningCpuWorkers(this);
        int cores = Runtime.getRuntime().availableProcessors();
        setTextIfChanged(miningWorkersValue, workerLabel(setting, cores));

        // What a node restart would change (the telemetry rows'
        // pattern): the switch, or the inner hash.
        MiningLogic.PendingKind pending = miningPendingKind(st);
        boolean switchPending = pending == MiningLogic.PendingKind.ENABLE
                || pending == MiningLogic.PendingKind.DISABLE;
        miningPendingView.setVisibility(
                switchPending ? View.VISIBLE : View.GONE);
        miningAppliedView.setVisibility(
                switchPending ? View.VISIBLE : View.GONE);
        if (switchPending) {
            setTextIfChanged(miningPendingView,
                    I18n.get(this, StringsTable.SETTINGS_PENDING_STATUS));
            setTextIfChanged(miningAppliedView, I18n.format(this,
                    StringsTable.SETTINGS_APPLIED_VALUE,
                    I18n.get(this, pending == MiningLogic.PendingKind.DISABLE
                            ? StringsTable.VALUE_ON
                            : StringsTable.VALUE_OFF)));
        }
        boolean rewardsPending =
                pending == MiningLogic.PendingKind.REWARDS_CHANGED;
        miningRewardsPendingView.setVisibility(
                rewardsPending ? View.VISIBLE : View.GONE);
        if (rewardsPending) {
            setTextIfChanged(miningRewardsPendingView,
                    I18n.get(this, StringsTable.SETTINGS_PENDING_STATUS));
        }

        // What a MINER restart would change: its worker count.
        MiningLogic.MinerView view = nodeRunning ? st.minerView : null;
        MiningLogic.MinerSnapshot snap =
                view == null ? null : view.snapshot(now);
        boolean workersPending = MiningLogic.minerRestartPending(snap,
                MiningLogic.effectiveCpuWorkers(setting, cores));
        miningWorkersPendingView.setVisibility(
                workersPending ? View.VISIBLE : View.GONE);
        if (workersPending) {
            setTextIfChanged(miningWorkersPendingView, I18n.format(this,
                    StringsTable.MINING_WORKERS_PENDING,
                    String.valueOf(snap.cpuWorkers)));
        }

        miningStatusBox.setVisibility(inUse ? View.VISIBLE : View.GONE);
        homeMiningRow.setVisibility(inUse ? View.VISIBLE : View.GONE);
        if (!inUse) {
            return;
        }

        MiningLogic.Presentation p = MiningLogic.present(enabled, hasHash,
                nodeRunning, st.launchedMining, st.launchedMiningSkip,
                st.status == NodeState.Status.SYNCED, st.peers, snap, now);
        String stateText = MiningLogic.stateText(tag, p);
        setTextIfChanged(miningStateValue, stateText);
        miningStateValue.setTextColor(
                p.state == MiningLogic.UiState.MINING ? Tokens.STATE_SYNCED
                        : p.state == MiningLogic.UiState.STOPPED
                                ? Tokens.STATE_FAILED : Tokens.CONTENT);
        String reason = MiningLogic.reasonText(tag, p);
        miningReasonView.setVisibility(
                reason == null ? View.GONE : View.VISIBLE);
        if (reason != null) {
            setTextIfChanged(miningReasonView, reason);
        }
        // A node that still mines although the settings now say
        // otherwise: the status above stays the truth, and this line
        // says what a restart will change. (ENABLE needs no second
        // line — its reason line already is that sentence.)
        String pendingNote = pending == MiningLogic.PendingKind.DISABLE
                || pending == MiningLogic.PendingKind.REWARDS_CHANGED
                ? MiningLogic.pendingText(tag, pending) : null;
        miningPendingNoteView.setVisibility(
                pendingNote == null ? View.GONE : View.VISIBLE);
        if (pendingNote != null) {
            setTextIfChanged(miningPendingNoteView, pendingNote);
        }
        // The node's own failure stays the node's (its screen shows
        // it); this only connects it to mining, where the remedy is.
        boolean nodeFailed = !nodeRunning && enabled
                && Boolean.TRUE.equals(st.launchedMining)
                && (st.lastStopKind == NodeState.StopKind.START_FAILED
                || st.lastStopKind == NodeState.StopKind.UNEXPECTED);
        miningNodeFailedView.setVisibility(
                nodeFailed ? View.VISIBLE : View.GONE);

        miningStatsBox.setVisibility(launchedWith ? View.VISIBLE : View.GONE);
        String home = stateText;
        if (launchedWith) {
            MiningLogic.StatsSnapshot stats = snap == null
                    ? MiningLogic.StatsSnapshot.EMPTY : snap.stats;
            String rate = MiningLogic.formatHashrate(stats.hashrateHps);
            setTextIfChanged(miningHashrateValue, rate != null ? rate
                    : I18n.get(this, StringsTable.VALUE_UNAVAILABLE));
            setTextIfChanged(miningJobsValue,
                    UiLogic.grouped(stats.jobsReceived));
            setTextIfChanged(miningSubmittedValue,
                    UiLogic.grouped(stats.solutionsSubmitted));
            setTextIfChanged(miningAcceptedValue,
                    UiLogic.grouped(stats.blocksAccepted));
            setTextIfChanged(miningAddressValue, stats.rewardsAddress != null
                    ? stats.rewardsAddress
                    : I18n.get(this, StringsTable.MINING_ADDRESS_PENDING));
            // While the session's thermal latch is held, a miner
            // restart cannot start anything — the supervisor
            // refuses until the user's next node start — so the
            // button goes inert instead of sitting enabled and
            // doing nothing (v4.2.2, final-check FC-8). The
            // state text above the button already names the
            // thermal stop as the cause.
            miningRestartButton.setEnabled(view != null && !locked
                    && !(snap != null && snap.thermalStopped));
            StringBuilder tail = new StringBuilder();
            if (snap != null) {
                for (String line : snap.logTail) {
                    if (tail.length() > 0) {
                        tail.append('\n');
                    }
                    tail.append(line);
                }
            }
            setTextIfChanged(miningLogView, tail.toString());
            if (p.state == MiningLogic.UiState.MINING && rate != null) {
                home = stateText + " · " + rate;
            }
        }
        setTextIfChanged(homeMiningText, home);
    }

    /** The mining parts of one share, gathered off the UI thread. */
    private static final class MiningShare {
        /** "" when there is no miner log to add. */
        String minerLogSection = "";
        /** Empty when mining is not in use. */
        List<String> summaryLines = new ArrayList<>();
        /** Never null; holds nothing when there is nothing to remove. */
        MiningLogic.SecretScrubber scrubber =
                MiningLogic.SecretScrubber.of(null, null);
    }

    /**
     * What a share gains from mining (v4.0.0). Two independent parts:
     *
     *  - the scrubber is built whenever the app holds anything it
     *    could find — the saved inner hash, the node's miner token —
     *    whether or not mining is switched on, and is applied to
     *    every shared body. Neither value is ever written to a log;
     *    the share is the one path that leaves the device, so it is
     *    checked once more regardless;
     *  - the summary lines and the miner's log excerpt are added
     *    only while mining is in use. With mining off the share
     *    text is the v3.0.1 text.
     */
    private MiningShare gatherMiningShare(File logDir) {
        MiningShare m = new MiningShare();
        NodeState st = NodeState.get();
        boolean enabled = NodeSettings.miningEnabled(this);
        boolean hasHash = NodeSettings.hasRewardsInnerHash(this);
        boolean nodeRunning = nodeLive(st);
        boolean launchedWith = nodeRunning
                && Boolean.TRUE.equals(st.launchedMining);
        File tokenFile = MiningLogic.minerTokenFile(
                new File(getFilesDir(), "node"), NodeService.CHAIN_NAME);
        m.scrubber = MiningLogic.SecretScrubber.of(
                NodeSettings.rewardsInnerHash(this),
                MiningLogic.readSmallTextFile(tokenFile, 4096));
        if (!enabled && !launchedWith) {
            return m;
        }
        long now = SystemClock.elapsedRealtime();
        MiningLogic.MinerView view = nodeRunning ? st.minerView : null;
        MiningLogic.MinerSnapshot snap =
                view == null ? null : view.snapshot(now);
        MiningLogic.Presentation p = MiningLogic.present(enabled, hasHash,
                nodeRunning, st.launchedMining, st.launchedMiningSkip,
                st.status == NodeState.Status.SYNCED, st.peers, snap, now);
        MiningLogic.ShareFacts f = new MiningLogic.ShareFacts();
        f.enabledSetting = enabled;
        f.launchedMining = nodeRunning ? st.launchedMining : null;
        f.launchSkip = st.launchedMiningSkip;
        f.rewardsSaved = hasHash;
        f.workerSetting = NodeSettings.miningCpuWorkers(this);
        long miningChanged = NodeSettings.miningChangedMs(this);
        long rewardsChanged = NodeSettings.rewardsChangedMs(this);
        f.miningChangedText = miningChanged > 0
                ? DiagShare.formatTimestamp(miningChanged) : null;
        f.rewardsChangedText = rewardsChanged > 0
                ? DiagShare.formatTimestamp(rewardsChanged) : null;
        f.uiState = p.state;
        f.uiReason = p.reason;
        f.miner = snap;
        // v4.2.4 (audit L-7): kept by the service past the node exit
        // the failure causes, until the next launch.
        f.minerEndpointFailed = st.minerEndpointFailed;
        m.summaryLines = MiningLogic.summaryLines(f);
        boolean[] truncated = new boolean[1];
        String tail = BoundedLogFile.readTail(
                NodeService.minerLogFile(logDir), 40_000, truncated);
        m.minerLogSection = MiningLogic.minerLogSection(tail, truncated[0]);
        return m;
    }

    /**
     * The DePIN service's notification texts, resolved here (the UI
     * owns localisation) and handed to the facade for the service
     * process, mirroring how the node service receives its strings.
     */
    private DepinIpc.NotifStrings notifStrings() {
        DepinIpc.NotifStrings s = new DepinIpc.NotifStrings();
        s.title = I18n.get(this, StringsTable.DEPIN_NOTIF_TITLE);
        s.channelName = I18n.get(this, StringsTable.DEPIN_NOTIF_CHANNEL_NAME);
        s.channelDesc = I18n.get(this, StringsTable.DEPIN_NOTIF_CHANNEL_DESC);
        s.starting = I18n.get(this, StringsTable.DEPIN_NOTIF_STARTING);
        s.observing = I18n.get(this, StringsTable.DEPIN_NOTIF_OBSERVING);
        s.checkpointFormat = I18n.get(this, StringsTable.DEPIN_NOTIF_CHECKPOINT);
        s.cannotStart = I18n.get(this, StringsTable.DEPIN_NOTIF_CANNOT_START);
        return s;
    }

    private String versionText() {
        String v = "";
        try {
            v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
            // The version line is informational; never crash over it.
        }
        return I18n.format(this, StringsTable.VERSION_FORMAT, v == null ? "" : v);
    }

    /**
     * A settings switch row: label at the start, a {@link
     * ToggleView} at the end — the layout the platform Switch
     * produced when its text carried the label. The whole row is
     * the touch target (a tap anywhere toggles, as it did when the
     * row WAS the switch) and ripples on press; the toggle itself
     * stays the focusable, checkable accessibility target, with
     * the label as its content description. The row carries the
     * anchor id: it is the row's position a language-change
     * restore scrolls back to.
     *
     * The platform Switch dressed with code-generated drawables
     * (proposal §6, audit finding L-7) rendered broken on
     * GrapheneOS — a giant clipped half-disc for a track — so the
     * glyph is drawn on Canvas instead; see ToggleView.
     */
    private ToggleView settingsSwitch(LinearLayout root, int topDp,
                                      int anchorId, String label,
                                      boolean checked,
                                      ToggleView.OnCheckedChangeListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(56));
        row.setPadding(0, dp(12), 0, dp(12));
        row.setBackground(ripple(null));
        row.setClickable(true);
        row.setId(anchorId);
        settingsAnchors.add(row);
        TextView labelView = text(label, 16, Tokens.CONTENT, false);
        row.addView(labelView, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        ToggleView toggle = new ToggleView(this);
        toggle.setContentDescription(label);
        toggle.setChecked(checked);
        toggle.setOnCheckedChangeListener(listener);
        row.addView(toggle, new LinearLayout.LayoutParams(dp(52), dp(48)));
        row.setOnClickListener(v -> toggle.performClick());
        root.addView(row, matchLpTop(topDp));
        return toggle;
    }

    /** Enable or disable the settings controls to match the lock. */
    private void applySettingsLock() {
        boolean locked = NodeSettings.settingsLocked(this);
        if (telemetrySwitch != null) {
            telemetrySwitch.setEnabled(!locked);
        }
        if (telemetryRow != null) {
            // The row is the tap target around the toggle; locking
            // must close both, or the row would keep toggling a
            // disabled switch.
            telemetryRow.setEnabled(!locked);
        }
        if (depinSwitch != null) {
            depinSwitch.setEnabled(!locked);
        }
        if (priceSwitch != null) {
            priceSwitch.setEnabled(!locked);
        }
        if (priceSwitchRow != null) {
            priceSwitchRow.setEnabled(!locked);
        }
        if (priceFetchToggle != null) {
            // The same switch lives in the price sheet; the lock
            // closes it there too (v4.2.2, audit L-4).
            priceFetchToggle.setEnabled(!locked);
        }
        if (priceSheetSwitchRow != null) {
            // The row is the tap target around the toggle; locking
            // must close both, or the row would keep its click
            // sound and TalkBack would still offer it while the
            // toggle itself does nothing (v4.2.2 audit, L6).
            priceSheetSwitchRow.setEnabled(!locked);
        }
        if (priceSheetLockedView != null) {
            priceSheetLockedView.setVisibility(
                    locked ? View.VISIBLE : View.GONE);
        }
        if (priceLockedView != null) {
            priceLockedView.setVisibility(
                    locked ? View.VISIBLE : View.GONE);
        }
        if (depinRow != null) {
            // Same as the telemetry row: the row is the tap target
            // around the toggle, so locking must close both.
            depinRow.setEnabled(!locked);
        }
        // Say WHY the controls are inert while locked (step-5
        // fix for the step-3 soak feedback: a toggle that does
        // not react and gives no reason reads as a bug).
        if (telemetryLockedView != null) {
            telemetryLockedView.setVisibility(
                    locked ? View.VISIBLE : View.GONE);
        }
        if (depinLockedView != null) {
            depinLockedView.setVisibility(
                    locked ? View.VISIBLE : View.GONE);
        }
        if (depinExportButton != null) {
            depinExportButton.setEnabled(!locked);
        }
        if (depinAttemptResetButton != null) {
            depinAttemptResetButton.setEnabled(!locked);
        }
        if (languageRow != null) {
            languageRow.setEnabled(!locked);
            languageRow.setAlpha(locked ? 0.55f : 1f);
        }
        if (nameRow != null) {
            nameRow.setEnabled(!locked);
            nameRow.setClickable(!locked);
            nameChevron.setVisibility(locked ? View.GONE : View.VISIBLE);
            nameLockedView.setVisibility(locked ? View.VISIBLE : View.GONE);
            String shown = shownName();
            nameRow.setContentDescription(
                    I18n.get(this, StringsTable.SETTINGS_DISPLAY_NAME) + ": " + shown
                            + (locked
                                    ? ". " + I18n.get(this, StringsTable.SETTINGS_NAME_LOCKED)
                                    : ""));
        }
        syncSwitch(lockSwitch, locked);
    }

    private void syncSwitch(ToggleView sw, boolean value) {
        if (sw != null && sw.isChecked() != value) {
            syncingSettings = true;
            sw.setChecked(value);
            syncingSettings = false;
        }
    }

    /** The name as the settings row shows it: stored value, or "default". */
    private String shownName() {
        String raw = NodeSettings.nodeNameRaw(this);
        return (raw == null || raw.isEmpty())
                ? I18n.get(this, StringsTable.SETTINGS_NAME_DEFAULT)
                : raw;
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    private void onMainButtonClicked() {
        mainButton.performHapticFeedback(
                HapticFeedbackConstants.CONFIRM);
        if (stopping) {
            return;
        }
        NodeState st = NodeState.get();
        UiLogic.HomeState hs = UiLogic.homeState(st.status, st.lastStopKind);
        if (hs == UiLogic.HomeState.STARTING
                || hs == UiLogic.HomeState.SYNCING
                || hs == UiLogic.HomeState.SYNCED) {
            // Stop (or cancel the startup): the button flips to its
            // processing form at once; the service's own completion
            // is what brings the normal stopped screen back (spec H).
            stopping = true;
            Intent stop = new Intent(this, NodeService.class)
                    .setAction(NodeService.ACTION_STOP);
            startService(stop);
            Toast.makeText(this,
                    I18n.get(this, StringsTable.VISION_TOAST_STOP),
                    Toast.LENGTH_SHORT).show();
        } else {
            if (Build.VERSION.SDK_INT >= 33
                    && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        REQ_NOTIF);
            }
            Intent start = new Intent(this, NodeService.class)
                    .setAction(NodeService.ACTION_START);
            startForegroundService(start);
            Toast.makeText(this,
                    I18n.get(this, StringsTable.VISION_TOAST_START),
                    Toast.LENGTH_SHORT).show();
        }
        refresh();
    }

    /** Pending-settings link: settings screen, first pending item. */
    private void openPendingSettings() {
        NodeState st = NodeState.get();
        int pending = UiLogic.settingsPending(st.serviceRunning,
                st.launchedTelemetry, NodeSettings.telemetryEnabled(this),
                st.launchedNodeName, NodeSettings.launchNodeName(this));
        // Telemetry and the display name both live on the node
        // page now; a pending mining change goes to the mining
        // page (the hub replaced the one long scroll in the
        // UI/UX phase 1 work).
        if (pending == 0 && miningRow != null
                && miningPendingKind(st) != MiningLogic.PendingKind.NONE) {
            showScreen(SCREEN_SET_MINING);
        } else {
            showScreen(SCREEN_SET_NODE);
        }
    }

    /**
     * Share the node's log file as plain text through the system
     * share sheet: the read runs off the UI thread through
     * NodeService's coordinated bounded reader, and only ever fires
     * on the user's own tap. versionCode 28 adds, on the same
     * background thread, the diagnostic summary's facts and the
     * masking inputs (see {@link DiagShare}); the UI half shows the
     * mask dialog in front of the sheet.
     */
    private void onShareLogClicked() {
        final File logDir = new File(getFilesDir(), "logs");
        final int shareMaxChars = 100_000;
        new Thread(() -> {
            boolean[] truncated = new boolean[1];
            String read = NodeService.readLogTailForShare(
                    logDir, shareMaxChars, truncated);
            final String logText = read;
            final boolean wasTruncated = truncated[0];
            final DiagShare.Facts facts =
                    logText == null ? null : gatherDiagFacts();
            final boolean[] ipEnumFailed = new boolean[1];
            final List<String> ownIps =
                    logText == null ? null : ownIpAddresses(ipEnumFailed);
            final List<String> pathPrefixes =
                    logText == null ? null : appPathPrefixes();
            // v4.0.0: the mining parts of the share (see
            // gatherMiningShare); they add nothing while mining is
            // not in use.
            final MiningShare mining =
                    logText == null ? null : gatherMiningShare(logDir);
            runOnUiThread(() -> shareLog(logText, wasTruncated,
                    facts, ownIps, ipEnumFailed[0], pathPrefixes, mining));
        }, "share-log-read").start();
    }

    /**
     * UI-thread half of share-log: the "no log" toast, or the mask
     * dialog (versionCode 28). Masking is ON by default: the shared
     * text then hides the display name, this device's IP addresses
     * and the app-private paths, so a log pasted into a public
     * issue keeps its diagnostic structure without the identifying
     * values. The user can turn it off for the shares where a
     * complete log is what the diagnosis needs — except when the
     * own-address enumeration failed, in which case the app's
     * picture of what masking must hide is known to be incomplete
     * and the toggle is locked on for that share.
     */
    private void shareLog(String logText, boolean wasTruncated,
                          DiagShare.Facts facts, List<String> ownIps,
                          boolean ipEnumFailed,
                          List<String> pathPrefixes,
                          MiningShare mining) {
        if (isFinishing() || isDestroyed()) {
            // The background read finished into an activity that
            // is already going away: showing the dialog (or the
            // toast) on it can throw, and there is nobody left to
            // share for. The tap simply ends here.
            return;
        }
        if (logText == null || logText.trim().isEmpty()) {
            Toast.makeText(this, I18n.get(this, StringsTable.LOG_MISSING_NOTE),
                    Toast.LENGTH_LONG).show();
            return;
        }
        final String excerpt = logText;
        final boolean truncated = wasTruncated;

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(8), dp(24), dp(8));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(56));
        row.setPadding(0, dp(12), 0, dp(12));
        row.setBackground(ripple(null));
        row.setClickable(true);
        row.setFocusable(true);
        TextView maskLabel = text(I18n.get(this, StringsTable.SHARE_MASK_LABEL),
                16, Tokens.CONTENT, false);
        row.addView(maskLabel, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        final ToggleView maskToggle = new ToggleView(this);
        maskToggle.setContentDescription(
                I18n.get(this, StringsTable.SHARE_MASK_LABEL));
        maskToggle.setChecked(true);
        if (ipEnumFailed) {
            // Locked on (see the method doc): the row's tap checks
            // the toggle's enabled state, so disabling it is
            // enough to hold the choice.
            maskToggle.setEnabled(false);
        }
        row.addView(maskToggle, new LinearLayout.LayoutParams(dp(52), dp(48)));
        row.setOnClickListener(v -> {
            if (maskToggle.isEnabled()) {
                maskToggle.toggle();
            }
        });
        box.addView(row, matchLp());

        TextView maskNote = text(I18n.get(this, StringsTable.SHARE_MASK_NOTE),
                14, Tokens.FAINT, false);
        box.addView(maskNote, matchLpTop(4));

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(I18n.get(this, StringsTable.BUTTON_SHARE_LOG))
                .setView(box)
                .setNegativeButton(I18n.get(this, StringsTable.BUTTON_CANCEL), null)
                .setPositiveButton(
                        I18n.get(this, StringsTable.BUTTON_SHARE_LOG),
                        (d, which) -> doShareLog(excerpt, truncated, facts,
                                maskToggle.isChecked(), ownIps, pathPrefixes,
                                mining))
                .create();
        dialog.show();
        styleDialog(dialog);
    }

    /**
     * Assemble the share text and open the sheet: the summary
     * block first (outside the excerpt's character cap), then the
     * truncation note and the excerpt exactly as before. Masking,
     * when on, is applied to the body only: running the
     * replacement over the summary could damage its fixed labels
     * and values (a display name of "off" against "Telemetry:
     * off"). The summary's one free-text field — the price-fetch
     * error — is instead sanitised structurally inside
     * DiagShare (internal.26, F-09): only the error's kind is
     * ever rendered, so no address-bearing message can reach
     * the summary whether masking is on or off.
     */
    private void doShareLog(String excerpt, boolean truncated,
                            DiagShare.Facts facts, boolean maskOn,
                            List<String> ownIps, List<String> pathPrefixes,
                            MiningShare mining) {
        String body = excerpt;
        if (truncated) {
            body = "[... earlier log omitted for sharing; "
                    + "showing the last part ...]\n"
                    + body;
        }
        // v4.0.0: the miner's log excerpt follows the node's, inside
        // the body, so the masking below covers it as well. "" (and
        // so no change) while mining is not in use.
        if (mining != null) {
            body = body + mining.minerLogSection;
        }
        if (maskOn) {
            body = DiagShare.maskSensitive(body, shareMaskNames(),
                    ownIps, pathPrefixes);
        }
        // v4.0.0: with masking on, the rewards address the node
        // reported is hidden too; and whatever the masking choice,
        // the inner hash and the miner token are removed from the
        // body (MiningLogic.finishShareBody). A body that holds
        // none of them is returned as the very same string.
        body = MiningLogic.finishShareBody(body, maskOn,
                mining == null ? null : mining.scrubber);
        String summary = DiagShare.buildSummary(facts, excerpt, truncated);
        if (mining != null) {
            summary = MiningLogic.insertSummaryLines(
                    summary, mining.summaryLines);
        }
        String full = summary + "\n" + body;
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT,
                I18n.get(this, StringsTable.SHARE_LOG_SUBJECT));
        send.putExtra(Intent.EXTRA_TEXT, full);
        startActivity(Intent.createChooser(send,
                I18n.get(this, StringsTable.BUTTON_SHARE_LOG)));
    }

    /**
     * Every display name the shared log can carry: the running
     * session's launched name — the one this session's lines
     * actually bear, which can differ from the setting after an
     * edit — and the current setting's name, which is what a
     * stopped log's lines bear and what the next start will use.
     * The built-in default is omitted: it identifies the app, not
     * the person, and appears throughout the log as ordinary text.
     */
    private List<String> shareMaskNames() {
        List<String> names = new ArrayList<>();
        String launched = NodeState.get().launchedNodeName;
        if (launched != null && !launched.isEmpty()
                && !NodeSettings.DEFAULT_NODE_NAME.equals(launched)) {
            names.add(launched);
        }
        String current = NodeSettings.effectiveNodeName(this);
        if (current != null && !current.isEmpty()
                && !NodeSettings.DEFAULT_NODE_NAME.equals(current)
                && !names.contains(current)) {
            names.add(current);
        }
        return names;
    }

    /**
     * The diagnostic summary's inputs (versionCode 28): package
     * facts, the service's state snapshot, device resources and
     * the durable records. A source that fails leaves its sentinel
     * in the Facts and the summary prints "n/a" for it, so nothing
     * here throws.
     */
    private DiagShare.Facts gatherDiagFacts() {
        DiagShare.Facts f = new DiagShare.Facts();
        try {
            PackageInfo pi = getPackageManager()
                    .getPackageInfo(getPackageName(), 0);
            f.appVersionName = pi.versionName;
            f.appVersionCode = (int) pi.getLongVersionCode();
            f.installMs = pi.firstInstallTime;
            f.updateMs = pi.lastUpdateTime;
        } catch (Exception ignored) {
            // The sentinels stand.
        }
        f.androidRelease = Build.VERSION.RELEASE;
        f.androidApi = Build.VERSION.SDK_INT;
        f.deviceModel = Build.MODEL;
        f.deviceMaker = Build.MANUFACTURER;
        f.chainName = NodeService.CHAIN_NAME;
        // nodeBinaryVersion and genesisShort stay unset on purpose:
        // the app cannot know either (see DiagShare), and the
        // summary says "n/a" instead of guessing.
        NodeState st = NodeState.get();
        f.status = st.status;
        if (st.serviceRunning && st.sessionAcceptedElapsedMs >= 0) {
            f.sessionUptimeMs = SystemClock.elapsedRealtime()
                    - st.sessionAcceptedElapsedMs;
        }
        f.bestBlock = st.bestBlock;
        f.highestBlock = st.highestBlock;
        f.peers = st.peers;
        f.lastRpcOkWallMs = st.lastRpcOkWallMs;
        DiagShare.fillPreviousEnd(f, st.lastStopKind, st.lastExitCode,
                st.prevStopKind, st.prevExitCode);
        // The telemetry line reports what the node is actually
        // running with when there is a launched value; the saved
        // setting stands in only when there is none, and the
        // summary marks it as a setting so the two are never
        // confused (a settings edit mid-session must not rewrite
        // what the running node was launched with).
        if (st.serviceRunning && st.launchedTelemetry != null) {
            f.telemetryOn = st.launchedTelemetry;
            f.telemetryIsSetting = false;
        } else {
            f.telemetryOn = NodeSettings.telemetryEnabled(this);
            f.telemetryIsSetting = true;
        }
        // The QTC price's fetch record (internal.23): when the
        // last fetch succeeded, and — while failures persist —
        // what the last one failed with. Both live in
        // PriceStore's persisted state, not in this process's
        // memory, so they survive the restarts that used to
        // erase every trace of a stopped price.
        PriceStore.Snapshot priceSnap = PriceStore.snapshot(this);
        f.priceFetchedMs = priceSnap.fetchedWallMs;
        f.priceError = PriceStore.lastErrorText(this);
        f.priceErrorMs = PriceStore.lastErrorWallMs(this);
        f.nodeDataBytes = dirSizeBytes(new File(getFilesDir(), "node"));
        try {
            f.freeStorageBytes = getFilesDir().getUsableSpace();
        } catch (Throwable ignored) {
            // Sentinel stands.
        }
        Intent battery = registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery != null) {
            int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            if (level >= 0 && scale > 0) {
                f.batteryPercent = (int) (100L * level / scale);
            }
            if (battery.hasExtra(BatteryManager.EXTRA_TEMPERATURE)) {
                f.batteryTempTenths = battery.getIntExtra(
                        BatteryManager.EXTRA_TEMPERATURE, 0);
            }
        }
        try {
            ActivityManager am = (ActivityManager)
                    getSystemService(Context.ACTIVITY_SERVICE);
            if (am != null) {
                ActivityManager.MemoryInfo mi =
                        new ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                f.freeMemBytes = mi.availMem;
            }
        } catch (Throwable ignored) {
            // Sentinel stands.
        }
        try {
            PowerManager pm = (PowerManager)
                    getSystemService(Context.POWER_SERVICE);
            if (pm != null) {
                f.batteryOptExempt =
                        pm.isIgnoringBatteryOptimizations(getPackageName());
            }
        } catch (Throwable ignored) {
            // Sentinel stands.
        }
        NotificationManager nm = (NotificationManager)
                getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) {
            f.notificationsGranted = nm.areNotificationsEnabled();
        }
        long now = System.currentTimeMillis();
        f.startsLast24h = TrackStore.startsSince(
                this, now - 24L * 3600 * 1000);
        f.startsCapped = TrackStore.startRingAtCap(this);
        f.trackUptimeMs = TrackStore.uptimeMs(this);
        f.trackBlocks = TrackStore.blockUpdates(this);
        f.trackMaxPeers = TrackStore.maxPeers(this);
        // v4.1.0: how many days the daily record holds — the count
        // only; the summary carries none of its values.
        f.dailyRecordDays = DailyTrackStore.dayCount(
                DailyTrackStore.file(getFilesDir()));
        f.telemetryChangedMs = NodeSettings.telemetryChangedMs(this);
        f.nameChangedMs = NodeSettings.nodeNameChangedMs(this);
        f.languageChangedMs = NodeSettings.languageChangedMs(this);
        return f;
    }

    /**
     * Recursive size of a directory tree, -1 when it cannot be
     * read. The walk is capped: the node database is a few
     * thousand files, and past the cap a partial total would be a
     * wrong answer rather than a smaller one — the sentinel is
     * the honest result there. The same goes for a directory
     * whose listing fails mid-walk: its subtree's size is
     * unknowable, so the whole measurement is "not known" (the
     * summary prints n/a), never a partial total dressed as a
     * measurement.
     */
    private static long dirSizeBytes(File dir) {
        if (dir == null || !dir.isDirectory()) {
            return -1;
        }
        long total = 0;
        ArrayDeque<File> stack = new ArrayDeque<>();
        stack.push(dir);
        int seen = 0;
        while (!stack.isEmpty()) {
            File cur = stack.pop();
            File[] kids = cur.listFiles();
            if (kids == null) {
                return -1;
            }
            for (File k : kids) {
                if (++seen > 200_000) {
                    return -1;
                }
                if (k.isDirectory()) {
                    stack.push(k);
                } else {
                    total += k.length();
                }
            }
        }
        return total;
    }

    /**
     * This device's own non-loopback IP addresses (masking
     * input). When the enumeration itself fails — outright or
     * partway — {@code failed[0]} is set and the caller locks
     * masking on for that share: the list is then known to be
     * incomplete, and proceeding as if it were whole would
     * silently share addresses the app meant to hide. What was
     * collected before the failure is still returned and still
     * masked.
     */
    private static List<String> ownIpAddresses(boolean[] failed) {
        List<String> out = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> ifs =
                    NetworkInterface.getNetworkInterfaces();
            while (ifs != null && ifs.hasMoreElements()) {
                Enumeration<InetAddress> addrs =
                        ifs.nextElement().getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (!a.isLoopbackAddress()) {
                        String host = a.getHostAddress();
                        if (host != null && !host.isEmpty()) {
                            // Strip an IPv6 zone id ("%wlan0"): the
                            // log lines carry the bare address.
                            int zone = host.indexOf('%');
                            out.add(zone >= 0
                                    ? host.substring(0, zone) : host);
                        }
                    }
                }
            }
        } catch (Throwable t) {
            failed[0] = true;
        }
        return out;
    }

    /** The app-private path prefixes the log can carry (masking input). */
    private List<String> appPathPrefixes() {
        List<String> out = new ArrayList<>();
        try {
            out.add(getFilesDir().getAbsolutePath());
            out.add(getCacheDir().getAbsolutePath());
            out.add(getApplicationInfo().dataDir);
            out.add(getApplicationInfo().nativeLibraryDir);
            out.add("/data/data/" + getPackageName());
        } catch (Throwable ignored) {
            // Fewer prefixes: masking still covers what it has.
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Display-name dialog (spec C)
    // ------------------------------------------------------------------

    private void showNameDialog() {
        float density = getResources().getDisplayMetrics().density;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(8), dp(24), dp(8));

        TextView hint = text(I18n.get(
                this, StringsTable.SETTINGS_NAME_EMPTY_HINT), 14, Tokens.FAINT, false);
        box.addView(hint, matchLp());

        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHorizontallyScrolling(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setHint(NodeSettings.DEFAULT_NODE_NAME);
        input.setTextSize(16);
        input.setTextColor(Tokens.CONTENT);
        input.setHintTextColor(Tokens.FAINT);
        input.setBackground(rounded(Tokens.SURFACE, 12, density, Tokens.FIELD_BORDER, 1));
        input.setPadding(dp(16), dp(12), dp(16), dp(12));
        input.setMinHeight(dp(56));
        String raw = NodeSettings.nodeNameRaw(this);
        input.setText(raw == null ? "" : raw);
        input.setSelection(input.getText().length());
        box.addView(input, matchLpTop(12));

        final TextView counter = text("", 14, Tokens.FAINT, false);
        box.addView(counter, matchLpTop(8));
        // display_name_limit is a quantity string: resolve its
        // wording for the limit value itself.
        final TextView error = text(UiLogic.quantityText(I18n.effectiveTag(this),
                StringsTable.DISPLAY_NAME_LIMIT,
                NodeSettings.nameLimitCodePoints()),
                14, Tokens.STATE_FAILED, false);
        error.setVisibility(View.GONE);
        box.addView(error, matchLpTop(4));
        final TextView preview = text("", 14, Tokens.FAINT, false);
        preview.setVisibility(View.GONE);
        box.addView(preview, matchLpTop(4));

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(I18n.get(this, StringsTable.SETTINGS_DISPLAY_NAME))
                .setView(box)
                .setNegativeButton(I18n.get(this, StringsTable.BUTTON_CANCEL), null)
                .setPositiveButton(
                        I18n.get(this, StringsTable.BUTTON_SAVE), null);
        nameDialog = b.create();
        nameDialog.setOnShowListener(dlg -> {
            Button save = nameDialog.getButton(AlertDialog.BUTTON_POSITIVE);
            Runnable update = () -> updateNameDialogState(
                    input, counter, error, preview, save);
            input.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(
                        CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(
                        CharSequence s, int start, int before, int count) {
                }

                @Override
                public void afterTextChanged(Editable s) {
                    update.run();
                }
            });
            update.run();
            save.setOnClickListener(v -> {
                // Re-validate at the last moment (spec C): the lock
                // may have become effective, and the final text is
                // what gets validated — never a truncated form.
                if (NodeSettings.settingsLocked(this)) {
                    input.setEnabled(false);
                    save.setEnabled(false);
                    return;
                }
                String value = input.getText().toString();
                if (!UiLogic.nameSaveable(value)) {
                    update.run();
                    return; // stay open with the error showing
                }
                // versionCode 27 (external audit of v0.2.0): store
                // the NORMALIZED name, not the typed text — the one
                // normalization the preview showed and the launch
                // will use, so what was previewed is byte-for-byte
                // what gets saved and passed to the node. (Before,
                // the raw text was stored and the launch re-derived
                // the name with a differently ordered sanitizer,
                // which could hand the node a shorter name than the
                // one the user had confirmed.)
                String normalized = NodeSettings.normalizeNodeName(value);
                String current = NodeSettings.nodeNameRaw(this);
                if (!normalized.equals(current == null ? "" : current)) {
                    NodeSettings.setNodeNameRaw(this, normalized);
                }
                nameDialog.dismiss();
                refresh();
            });
        });
        nameDialog.show();
        styleDialog(nameDialog);
    }

    /** Counter / error / preview under the name field, from the text. */
    private void updateNameDialogState(EditText input, TextView counter,
                                       TextView error, TextView preview,
                                       Button save) {
        String value = input.getText().toString();
        String tag = I18n.effectiveTag(this);
        int count = UiLogic.nameCodePoints(value);
        int limit = NodeSettings.nameLimitCodePoints();
        counter.setText(I18n.format(this, StringsTable.CHARACTER_COUNTER_FORMAT,
                UiLogic.grouped(count), UiLogic.grouped(limit)));
        counter.setContentDescription(String.format(Locale.US,
                StringsTable.quantity(tag, StringsTable.DISPLAY_NAME_COUNT_A11Y, count),
                UiLogic.grouped(count), UiLogic.grouped(limit)));
        // v4.2.4 (audit L-9): the same rule the save button applies
        // — the node's own, on the normalized name (the length is the
        // part the normalization cannot fix, and the message names it).
        boolean saveable = UiLogic.nameSaveable(value);
        error.setVisibility(saveable ? View.GONE : View.VISIBLE);
        save.setEnabled(saveable);
        String saved = UiLogic.nameSavedPreview(value);
        if (!saved.equals(value) && saveable) {
            preview.setText(I18n.format(
                    this, StringsTable.SETTINGS_NAME_SAVED_PREVIEW, saved));
            preview.setVisibility(View.VISIBLE);
        } else {
            preview.setVisibility(View.GONE);
        }
    }

    // ------------------------------------------------------------------
    // Language dialog (spec B: immediate apply, no save button)
    // ------------------------------------------------------------------

    /** Picker label for the currently stored language choice. */
    private String currentLanguageLabel() {
        String raw = NodeSettings.languageRaw(this);
        if (raw == null || raw.isEmpty() || !I18n.isSupported(raw)) {
            return I18n.get(this, StringsTable.LANGUAGE_FOLLOW_SYSTEM);
        }
        String name = StringsTable.LANGUAGE_NAMES.get(raw);
        return name != null ? name : raw;
    }

    private void showLanguageDialog() {
        String[] tags = StringsTable.TAGS;
        CharSequence[] items = new CharSequence[tags.length + 1];
        items[0] = I18n.get(this, StringsTable.LANGUAGE_FOLLOW_SYSTEM);
        for (int i = 0; i < tags.length; i++) {
            items[i + 1] = StringsTable.LANGUAGE_NAMES.get(tags[i]);
        }
        String raw = NodeSettings.languageRaw(this);
        int checkedIndex = 0;
        for (int i = 0; i < tags.length; i++) {
            if (tags[i].equals(raw)) {
                checkedIndex = i + 1;
                break;
            }
        }
        final int checked = checkedIndex;
        // A custom view carries the picker (device finding,
        // 2026-11-06): setMessage + setSingleChoiceItems together
        // lose the list entirely on GrapheneOS — the dialog showed
        // the title, the message and Cancel, and no languages. So
        // the note and the single-choice list both live in the
        // dialog's own view here: the immediate-apply note on top,
        // the list below it, capped in height so the dialog fits
        // short screens (the list scrolls past the cap). The item
        // semantics are the ones the builder's listener had: a tap
        // saves at once, closes and rebuilds.
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(8), dp(24), dp(8));
        TextView note = text(I18n.get(
                this, StringsTable.SETTINGS_LANGUAGE_IMMEDIATE),
                14, Tokens.FAINT, false);
        box.addView(note, matchLp());
        ListView list = new ListView(this);
        list.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        list.setAdapter(new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_single_choice, items));
        list.setItemChecked(checked, true);
        list.setDivider(new ColorDrawable(Tokens.HAIRLINE));
        list.setDividerHeight(Math.max(1, dp(1)));
        LinearLayout.LayoutParams listLp = matchLpTop(8);
        listLp.height = Math.min(dp(56) * items.length, dp(400));
        box.addView(list, listLp);
        list.setOnItemClickListener((parent, view, which, id) -> {
            if (which == checked) {
                languageDialog.dismiss();
                return; // current language: nothing to do
            }
            // Spec G order: keep the UI state, save, close,
            // rebuild, restore on the settings screen with
            // focus back on the language row. For the
            // settings screen the restore anchor is the
            // first visible row's stable id plus the offset
            // inside it (translations change row heights,
            // so the raw scroll offset alone would land
            // elsewhere); other screens keep raw offsets.
            sScreenAfterRecreate = currentScreen;
            sScrollAfterRecreate = new int[screens.length];
            for (int i = 0; i < screens.length; i++) {
                sScrollAfterRecreate[i] =
                        screens[i] instanceof ScrollView
                                ? screens[i].getScrollY() : 0;
            }
            sAnchorIdAfterRecreate = -1;
            sAnchorOffsetAfterRecreate = 0;
            ScrollView ssv = (ScrollView) screens[SCREEN_SETTINGS];
            if (ssv != null && ssv.getChildAt(0) != null) {
                int sy = ssv.getScrollY();
                View content = ssv.getChildAt(0);
                for (View anchor : settingsAnchors) {
                    int top = offsetInContent(anchor, content);
                    if (top <= sy) {
                        sAnchorIdAfterRecreate = anchor.getId();
                        sAnchorOffsetAfterRecreate = sy - top;
                    } else {
                        break;
                    }
                }
            }
            sDiagAfterRecreate = diagExpanded;
            sFocusLanguageAfterRecreate = true;
            NodeSettings.setLanguageRaw(
                    this, which == 0 ? "" : tags[which - 1]);
            languageDialog.dismiss();
            recreate();
        });
        languageDialog = new AlertDialog.Builder(this)
                .setTitle(I18n.get(this, StringsTable.SETTINGS_LANGUAGE))
                .setView(box)
                .setNegativeButton(I18n.get(this, StringsTable.BUTTON_CANCEL), null)
                .show();
        styleDialog(languageDialog);
    }

    // ------------------------------------------------------------------
    // Refresh (1 s tick while visible; NodeState + TrackStore only)
    // ------------------------------------------------------------------

    private void refresh() {
        NodeState st = NodeState.get();
        String tag = I18n.effectiveTag(this);
        UiLogic.HomeState hs = UiLogic.homeState(st.status, st.lastStopKind);
        if (!st.serviceRunning) {
            stopping = false;
        }
        long now = SystemClock.elapsedRealtime();

        // One field event per newly recorded observation while
        // SYNCED, measured against the display-start baseline
        // (finding L-1): the baseline is adopted as a VALUE when
        // the UI (re)starts displaying, so the first increase
        // after a cold start fires, a rebuild never manufactures
        // one, and the event reaches the network field only
        // while its tab is the visible one.
        if (st.lastObservedBestBlock >= 0) {
            if (UiLogic.shouldRipple(sRippleBaselineReady, firstRefresh,
                    hs == UiLogic.HomeState.SYNCED,
                    currentScreen == SCREEN_NETWORK,
                    st.lastObservedBestBlock, sLastRippleObservedBest)) {
                networkField.onBlockArrived();
            }
            if (st.lastObservedBestBlock > sLastRippleObservedBest) {
                sLastRippleObservedBest = st.lastObservedBestBlock;
            }
        }
        PriceStore.maybeFetch(this);
        refreshVisionHome(st, tag, hs, now);
        refreshNetwork(st, hs);
        refreshMember();
        refreshDetails(st, tag, hs, now);
        trackUptimeValue.setText(UiLogic.durationText(
                tag, TrackStore.uptimeMs(this), true));
        trackBlocksValue.setText(
                UiLogic.grouped(TrackStore.blockUpdates(this)));
        trackPeersValue.setText(
                UiLogic.grouped(TrackStore.maxPeers(this)));
        refreshSettings(st);
        // v4.0.0: its own step — refreshSettings can return early
        // (no DePIN status yet), and the mining views must not
        // depend on that.
        refreshMining(st, now);
        // v4.1.0: its own step for the same reason.
        refreshStorage(st, now);
        refreshTransferState();
        firstRefresh = false;
    }

    private void refreshVisionHome(NodeState st, String tag,
                                   UiLogic.HomeState hs, long now) {
        // State dot + word. The word uses the vision strings for
        // the two calm states (synced / stopped) and the existing
        // status strings for the transitional and failed ones.
        int stateColor;
        String statusKey;
        switch (hs) {
            case STARTING:
                stateColor = Tokens.STATE_STARTING;
                statusKey = StringsTable.STATUS_STARTING;
                break;
            case SYNCING:
                stateColor = Tokens.STATE_SYNCING;
                statusKey = StringsTable.STATUS_SYNCING;
                break;
            case SYNCED:
                stateColor = Tokens.STATE_SYNCED;
                statusKey = StringsTable.VISION_SYNCED;
                break;
            case FAILED_START:
            case FAILED_BINARY:
                stateColor = Tokens.STATE_FAILED;
                statusKey = StringsTable.STATUS_START_FAILED;
                break;
            case FAILED_UNEXPECTED:
                stateColor = Tokens.STATE_FAILED;
                statusKey = StringsTable.STATUS_UNEXPECTED_STOP;
                break;
            case STOPPED:
            case STOPPED_TIME_LIMIT:
            default:
                stateColor = Tokens.STATE_STOPPED;
                statusKey = StringsTable.VISION_STOPPED;
                break;
        }
        if (stateColor != stateDotColor) {
            stateDotColor = stateColor;
            float density = getResources().getDisplayMetrics().density;
            stateDotView.setBackground(
                    rounded(stateColor, 999, density, 0, 0));
        }
        statusView.setTextColor(stateColor == Tokens.STATE_SYNCED
                ? Tokens.STATE_SYNCED : stateColor == Tokens.STATE_STOPPED
                        ? Tokens.MUTED : Tokens.CONTENT);
        // Syncing shows its numbers in the hero itself (stage
        // 1 spec S1-06 / S1-10): the percent and the blocks to
        // go, computed here from the two heights. While either
        // height is unknown the plain word stands, and the
        // supplement below keeps its gap / unknown-target line
        // (heroSync suppresses the duplicate).
        String statusText = I18n.get(this, statusKey);
        boolean heroSync = false;
        if (hs == UiLogic.HomeState.SYNCING) {
            long gap = UiLogic.syncGap(st.bestBlock, st.highestBlock);
            if (gap >= 0 && st.highestBlock > 0) {
                long pct = st.bestBlock * 100L / st.highestBlock;
                statusText = I18n.format(this,
                        StringsTable.STATE_SYNCING_FMT,
                        UiLogic.grouped(pct), UiLogic.grouped(gap));
                heroSync = true;
            }
        }
        setTextIfChanged(statusView, statusText);

        // The big best number: "#" in faint ahead of the grouped
        // height (mock v5). Unknown height reads as a dash.
        String bestText = st.bestBlock >= 0
                ? "#" + UiLogic.grouped(st.bestBlock) : "\u2014";
        if (!bestText.equals(vhBestValue.getText().toString())) {
            android.text.SpannableString span =
                    new android.text.SpannableString(bestText);
            if (bestText.startsWith("#")) {
                span.setSpan(new android.text.style.ForegroundColorSpan(
                                Tokens.FAINT), 0, 1,
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            vhBestValue.setText(span);
        }
        // Fit the number to its line (B-4): the old formula
        // sized it from the screen's width alone and let it
        // wrap once the font scale passed about 1.15. The fit
        // measures the text itself, px-based like the member
        // card's (B-3): 56 down to a 32 floor.
        fitSingleLinePx(vhBestValue, 56f, 40f, 32f);

        vhPeersValue.setText(st.peers >= 0
                ? UiLogic.grouped(st.peers)
                : I18n.get(this, StringsTable.VALUE_UNAVAILABLE));
        vhUptimeValue.setText(st.sessionAcceptedElapsedMs >= 0
                ? sessionClockText(sessionUptimeMs(st, now))
                : I18n.get(this, StringsTable.VALUE_UNAVAILABLE));
        setTextIfChanged(vhBestAgoView, st.lastObservedElapsedMs >= 0
                ? agoText(now - st.lastObservedElapsedMs)
                : "\u2014");

        // Supplement (unchanged rules from the previous home:
        // facts only, from the service's own records). The old
        // cause-specific detail link is absorbed into the node
        // details row of the list (design 7.1).
        String supplement = null;
        switch (hs) {
            case STOPPED_TIME_LIMIT:
                supplement = I18n.get(this, StringsTable.MESSAGE_TIME_LIMIT_STOP);
                break;
            case FAILED_START:
                supplement = I18n.get(this, StringsTable.MESSAGE_START_FAILED);
                break;
            case FAILED_UNEXPECTED:
                supplement = I18n.get(this, StringsTable.MESSAGE_UNEXPECTED_STOP);
                break;
            case FAILED_BINARY:
                supplement = I18n.get(this, StringsTable.MESSAGE_BINARY_MISSING);
                break;
            case SYNCING: {
                if (!heroSync) {
                    String gap = UiLogic.syncGapText(tag,
                            st.bestBlock, st.highestBlock);
                    supplement = gap != null ? gap
                            : I18n.get(this,
                                    StringsTable.MESSAGE_SYNC_TARGET_UNKNOWN);
                }
                break;
            }
            case SYNCED:
                if (st.peers == 0) {
                    supplement = I18n.get(this, StringsTable.MESSAGE_NO_PEERS);
                } else if (st.lastObservedElapsedMs >= 0) {
                    supplement = UiLogic.lastSeenText(
                            tag, now - st.lastObservedElapsedMs);
                } else {
                    supplement = I18n.get(this, StringsTable.MESSAGE_WAITING_BLOCK);
                }
                break;
            default:
                break;
        }
        supplementView.setVisibility(supplement != null ? View.VISIBLE : View.GONE);
        if (supplement != null) {
            setTextIfChanged(supplementView, supplement);
        }
        // First-run paragraph (design 3.2): visible only while
        // the node has never run and is not running now.
        boolean firstRun = !st.serviceRunning
                && TrackStore.uptimeMs(this) == 0;
        firstRunView.setVisibility(firstRun ? View.VISIBLE : View.GONE);
        // While the paragraph shows, the metric block hides
        // (design 3.2's wireframe; audit M-4): its values are
        // all dashes in this state, and stacking both pushed
        // the Start button toward the fold in long languages
        // and at larger font scales.
        int metricsVis = firstRun ? View.GONE : View.VISIBLE;
        vhBestLabel.setVisibility(metricsVis);
        vhBestValue.setVisibility(metricsVis);
        vhBestAgoView.setVisibility(metricsVis);
        vhStatsRow.setVisibility(metricsVis);

        // The primary button: the vision wording (start / stop
        // from the spec's dictionary) over the same state machine
        // as before, stopping flag included. The three dresses
        // are built once and cached (finding L-5).
        if (stopping && st.serviceRunning) {
            mainButton.setText(I18n.get(this, StringsTable.BUTTON_STOPPING));
            mainButton.setEnabled(false);
            mainButton.setBackground(stoppingButtonBg());
            mainButton.setTextColor(Tokens.FAINT);
            mainButton.setTypeface(VisionFonts.displayMedium(this));
        } else {
            mainButton.setEnabled(true);
            switch (hs) {
                case STOPPED:
                case STOPPED_TIME_LIMIT:
                    mainButton.setText(
                            I18n.get(this, StringsTable.VISION_START));
                    mainButton.setBackground(accentButtonBg());
                    mainButton.setTextColor(Tokens.VOID);
                    mainButton.setTypeface(VisionFonts.displayBold(this));
                    break;
                case FAILED_START:
                case FAILED_UNEXPECTED:
                case FAILED_BINARY:
                    mainButton.setText(I18n.get(this, StringsTable.BUTTON_RETRY));
                    mainButton.setBackground(accentButtonBg());
                    mainButton.setTextColor(Tokens.VOID);
                    mainButton.setTypeface(VisionFonts.displayBold(this));
                    break;
                case STARTING:
                    mainButton.setText(
                            I18n.get(this, StringsTable.BUTTON_CANCEL_STARTUP));
                    mainButton.setBackground(surfaceButtonBg());
                    mainButton.setTextColor(Tokens.CONTENT);
                    mainButton.setTypeface(VisionFonts.displayMedium(this));
                    break;
                case SYNCING:
                case SYNCED:
                default:
                    mainButton.setText(
                            I18n.get(this, StringsTable.VISION_STOP));
                    mainButton.setBackground(surfaceButtonBg());
                    mainButton.setTextColor(Tokens.CONTENT);
                    mainButton.setTypeface(VisionFonts.displayMedium(this));
                    break;
            }
        }

        // Pending-settings link (condition P), as before: shown
        // with the running presentations only.
        int pending = UiLogic.settingsPending(st.serviceRunning,
                st.launchedTelemetry, NodeSettings.telemetryEnabled(this),
                st.launchedNodeName, NodeSettings.launchNodeName(this));
        // v4.0.0: a mining change waiting for a node restart counts
        // too. It is NONE whenever mining is not in use, which leaves
        // this exactly the v3.0.1 condition.
        boolean miningPending =
                miningPendingKind(st) != MiningLogic.PendingKind.NONE;
        boolean showPending = (pending != 0 || miningPending)
                && UiLogic.showsStats(hs);
        pendingLinkView.setVisibility(showPending ? View.VISIBLE : View.GONE);
        if (showPending) {
            setTextIfChanged(pendingLinkView,
                    I18n.get(this, StringsTable.SETTINGS_PENDING_LINK));
        }

        refreshPriceViews();
        refreshSettingsHub();
        refreshTodayRow();
    }

    /**
     * A relative age in the ago_* wording (stage 1 spec
     * S1-10): whole seconds, then whole minutes, then whole
     * hours. The number is handed over pre-formatted, so the
     * strings need no plural machinery.
     */
    private String agoText(long ageMs) {
        long sec = Math.max(0, ageMs / 1000);
        if (sec < 60) {
            return I18n.format(this, StringsTable.AGO_SEC,
                    UiLogic.grouped(sec));
        }
        if (sec < 3600) {
            return I18n.format(this, StringsTable.AGO_MIN,
                    UiLogic.grouped(sec / 60));
        }
        return I18n.format(this, StringsTable.AGO_HOUR,
                UiLogic.grouped(sec / 3600));
    }

    /**
     * The today's-record row (stage 1 spec S1-10): visible
     * only while DePIN is enabled and its aggregates are
     * known — a device that is not recording is never shown
     * a 0% bar for a record it does not keep. The bar is the
     * share of today's elapsed checkpoint slots that hold a
     * record. While recording is HELD the bar freezes where
     * it was and the line says so.
     */
    /** Residual F-08: whether the snapshot's aggregates
     *  may be presented as current — a live presented state
     *  and a build within the freshness bound (UiLogic).
     *  A dead observer's snapshot fails one or both, and
     *  its aggregates stop presenting as live values. */
    private boolean depinAggregatesCurrent(DepinStatus ds) {
        boolean live = ds.state == DepinStatus.STATE_RECORDING
                || ds.state == DepinStatus.STATE_PREPARING
                || ds.state == DepinStatus.STATE_HELD;
        return UiLogic.aggregatesCurrent(
                live, ds.updatedWallMs, System.currentTimeMillis());
    }

    /** The settings hub's row values (design 6.2): each page's
     *  state, readable without opening it. */
    private void refreshSettingsHub() {
        if (hubMiningValue == null) {
            return;
        }
        setTextIfChanged(hubMiningValue, I18n.get(this,
                NodeSettings.miningEnabled(this)
                        ? StringsTable.VALUE_ON : StringsTable.VALUE_OFF));
        DepinStatus ds = DepinModule.get(this).latestStatus();
        setTextIfChanged(hubDepinValue, I18n.get(this,
                ds != null && ds.enabled
                        ? StringsTable.VALUE_ON : StringsTable.VALUE_OFF));
        if (storageTotalValue != null
                && storageTotalValue.getText() != null) {
            setTextIfChanged(hubStorageValue,
                    storageTotalValue.getText().toString());
        }
        setTextIfChanged(hubExternalValue, I18n.get(this,
                PriceStore.isEnabled(this)
                        ? StringsTable.VALUE_ON : StringsTable.VALUE_OFF));
        String langName = StringsTable.LANGUAGE_NAMES.get(
                I18n.effectiveTag(this));
        setTextIfChanged(hubLanguageValue, langName != null ? langName : "");
    }

    private void refreshTodayRow() {
        DepinStatus ds = DepinModule.get(this).latestStatus();
        if (ds == null || !ds.enabled || ds.todayCheckpoints < 0
                || ds.todaySlotsElapsed < 0
                || !depinAggregatesCurrent(ds)) {
            homeTodayRow.setVisibility(View.GONE);
            return;
        }
        homeTodayRow.setVisibility(View.VISIBLE);
        if (ds.state == DepinStatus.STATE_HELD || ds.holdActive) {
            setTextIfChanged(homeTodayText,
                    I18n.get(this, StringsTable.HOME_HELD_TODAY));
            return;
        }
        setTextIfChanged(homeTodayText, I18n.format(this,
                StringsTable.HOME_RECORDED_FMT,
                UiLogic.grouped(ds.todayCheckpoints),
                UiLogic.grouped(ds.todaySlotsElapsed)));
        final float frac = ds.todaySlotsElapsed > 0
                ? Math.min(1f, (float) ds.todayCheckpoints
                        / (float) ds.todaySlotsElapsed)
                : 0f;
        homeTodayTrack.post(() -> {
            int w = homeTodayTrack.getWidth();
            if (w > 0) {
                FrameLayout.LayoutParams lp =
                        (FrameLayout.LayoutParams)
                                homeTodayFill.getLayoutParams();
                int want = Math.round(w * frac);
                if (lp.width != want) {
                    lp.width = want;
                    homeTodayFill.setLayoutParams(lp);
                }
            }
        });
    }

    /** The current session's uptime: counted live while the session
     *  runs, frozen at its end once it has ended. */
    private static long sessionUptimeMs(NodeState st, long now) {
        long end = st.sessionEndedElapsedMs >= 0
                ? st.sessionEndedElapsedMs : now;
        return end - st.sessionAcceptedElapsedMs;
    }

    private static String sessionClockText(long elapsedMs) {
        long s = Math.max(0, elapsedMs / 1000);
        return String.format(Locale.US, "%02d:%02d:%02d",
                s / 3600, (s % 3600) / 60, s % 60);
    }

    /**
     * The price row and sheet from {@link PriceStore}'s snapshot
     * (design 7.1): the row carries the price and the 24 h change
     * in one line; the sheet carries the big number and the
     * source. No value yet is a dash and nothing else (design
     * 2.5); a stale value dims and its source line says so. Stage
     * 1 spec S1-07 / S1-08: a fetch that carried no 24 h change
     * shows "24h —" in the sheet at the same width (never a
     * computed-looking 0.0%), tapping it explains why, and the
     * source tag's time is CoinGecko's own update time, formatted
     * HH:mm for today and M/d HH:mm for older values. The
     * change's ▲▼ stays, in the neutral label colour — never
     * green or red. With the fetch switched off (14-2) the row
     * says so and the sheet is empty; nothing is fetched.
     */
    private void refreshPriceViews() {
        if (priceSwitch != null) {
            syncSwitch(priceSwitch, PriceStore.isEnabled(this));
        }
        PriceStore.Snapshot snap = PriceStore.snapshot(this);
        if (!PriceStore.isEnabled(this) || !snap.hasValue) {
            setTextIfChanged(priceRowValue,
                    !PriceStore.isEnabled(this)
                            ? I18n.get(this, StringsTable.VALUE_OFF)
                            : "\u2014");
            setTextIfChanged(priceValueView, "\u2014");
            priceValueView.setTextColor(Tokens.CONTENT);
            setTextIfChanged(priceDeltaView, "");
            setTextIfChanged(priceSrcView, "");
            return;
        }
        String price = priceText(snap.usd);
        setTextIfChanged(priceValueView, price);
        priceValueView.setTextColor(snap.stale ? Tokens.MUTED : Tokens.CONTENT);
        priceDeltaView.setTextColor(Tokens.MUTED);
        String rowText = price;
        if (snap.hasChange) {
            double ch = snap.change24h;
            // Residual F-22: an exactly-zero change wore an
            // up arrow ("▲ 0.0%"). The arrow follows the
            // DISPLAYED value: a change that rounds to 0.0
            // at this precision gets no arrow.
            double shown = Math.round(Math.abs(ch) * 10.0) / 10.0;
            String delta = shown == 0.0
                    ? String.format(Locale.US, "0.0%% 24H")
                    : String.format(Locale.US, "%s %.1f%% 24H",
                            ch > 0 ? "\u25B2" : "\u25BC", shown);
            setTextIfChanged(priceDeltaView, delta);
            if (shown != 0.0) {
                rowText = price + "  " + (ch > 0 ? "\u25B2" : "\u25BC")
                        + String.format(Locale.US, " %.1f%%", shown);
            }
        } else {
            setTextIfChanged(priceDeltaView, "24h \u2014");
        }
        setTextIfChanged(priceRowValue, rowText);
        // A stale value dims on the row too, not only inside
        // the sheet (audit M-3).
        priceRowValue.setTextColor(
                snap.stale ? Tokens.MUTED : Tokens.CONTENT);
        long srcMs = snap.updatedUnix > 0
                ? snap.updatedUnix * 1000L : snap.fetchedWallMs;
        String time = priceTimeText(srcMs);
        setTextIfChanged(priceSrcView, I18n.format(this,
                snap.stale ? StringsTable.VISION_PRICE_STALE
                        : StringsTable.VISION_SRC, time));
    }

    /** The source tag's time (spec S1-08): HH:mm when the
     *  value is from today (local), M/d HH:mm otherwise. */
    private static String priceTimeText(long wallMs) {
        java.util.Calendar when = java.util.Calendar.getInstance();
        when.setTimeInMillis(wallMs);
        java.util.Calendar now = java.util.Calendar.getInstance();
        boolean today = when.get(java.util.Calendar.YEAR)
                        == now.get(java.util.Calendar.YEAR)
                && when.get(java.util.Calendar.DAY_OF_YEAR)
                        == now.get(java.util.Calendar.DAY_OF_YEAR);
        return new SimpleDateFormat(
                today ? "HH:mm" : "M/d HH:mm", Locale.US)
                .format(new Date(wallMs));
    }

    /** USD price text: 2 decimals at $1 and above, 4 below (the
     *  mock's precision for QTC's current price band; spec 2.6). */
    private static String priceText(double usd) {
        return usd >= 1.0
                ? String.format(Locale.US, "$%.2f", usd)
                : String.format(Locale.US, "$%.4f", usd);
    }

    /** HH:mm of a wall-clock time, for ticker captions. */
    private static String timeHhMm(long wallMs) {
        return new SimpleDateFormat("HH:mm", Locale.US)
                .format(new Date(wallMs));
    }

    /** HH:mm:ss of a wall-clock time, for source tags. */
    private static String timeHhMmSs(long wallMs) {
        return new SimpleDateFormat("HH:mm:ss", Locale.US)
                .format(new Date(wallMs));
    }

    /** Whether the device reports an internet-capable network
     *  right now (the offline state of spec S1-06). */
    private boolean deviceOnline() {
        ConnectivityManager cm = (ConnectivityManager)
                getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            return true;
        }
        Network n = cm.getActiveNetwork();
        if (n == null) {
            return false;
        }
        NetworkCapabilities caps = cm.getNetworkCapabilities(n);
        return caps != null && caps.hasCapability(
                NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    private void refreshNetwork(NodeState st, UiLogic.HomeState hs) {
        boolean running = UiLogic.showsStats(hs);
        boolean failed = UiLogic.isFailure(hs);
        boolean synced = hs == UiLogic.HomeState.SYNCED;
        String tag = I18n.effectiveTag(this);
        long nowWall = System.currentTimeMillis();
        long nowEl = SystemClock.elapsedRealtime();

        // Advance tracking for the stalled state: the wall
        // time this UI last saw the best block grow. The first
        // sighting only sets the baseline (a fresh start is
        // not a stall), and a stop clears it, so a restarted
        // node is never judged against the old session's best.
        if (!running) {
            netAdvanceBest = -1;
            netAdvanceWallMs = -1;
        }
        if (st.bestBlock >= 0) {
            if (netAdvanceBest < 0 || st.bestBlock > netAdvanceBest) {
                netAdvanceBest = st.bestBlock;
                netAdvanceWallMs = nowWall;
            }
        }
        long rpcAgeSec = st.lastRpcOkWallMs > 0
                ? (nowWall - st.lastRpcOkWallMs) / 1000 : -1;
        long advanceAgeSec = netAdvanceWallMs > 0
                ? (nowWall - netAdvanceWallMs) / 1000 : -1;
        UiLogic.NetState ns = UiLogic.classifyNetState(
                deviceOnline(), running, failed,
                hs == UiLogic.HomeState.SYNCING, st.peers,
                rpcAgeSec, advanceAgeSec);

        float widthDp = getResources().getDisplayMetrics().widthPixels
                / getResources().getDisplayMetrics().density;
        netNumView.setTextSize(Math.min(96f, widthDp * 0.26f));
        networkField.setNodeState(running, synced);
        networkField.setPeers(running ? st.peers : -1);
        networkField.setBestBlock(running ? st.bestBlock : -1);

        String peersText = st.peers >= 0
                ? UiLogic.grouped(st.peers) : "\u2014";
        String sub;
        boolean tagVisible = running && st.lastRpcOkWallMs > 0;
        switch (ns) {
            case OFFLINE:
                setTextIfChanged(netNumView, "\u2014");
                netNumView.setTextColor(Tokens.FAINT);
                sub = I18n.get(this, StringsTable.STATE_OFFLINE);
                tagVisible = false;
                break;
            case STOPPED:
                setTextIfChanged(netNumView, "\u2014");
                netNumView.setTextColor(Tokens.FAINT);
                sub = I18n.get(this, StringsTable.VISION_NET_OFF);
                tagVisible = false;
                break;
            case RPC_FAIL:
                // The last measured value, dimmed (spec S1-06):
                // it is shown AS a stale value, never as a live
                // one. The state line carries the last success.
                setTextIfChanged(netNumView, peersText);
                netNumView.setTextColor(Tokens.FAINT);
                sub = I18n.format(this, StringsTable.STATE_RPC_FAIL,
                        timeHhMmSs(st.lastRpcOkWallMs));
                tagVisible = false;
                break;
            case SYNCING: {
                setTextIfChanged(netNumView, peersText);
                netNumView.setTextColor(Tokens.FLARE);
                long gap = UiLogic.syncGap(st.bestBlock,
                        st.highestBlock);
                if (gap >= 0 && st.highestBlock > 0) {
                    long pct = st.bestBlock * 100L / st.highestBlock;
                    sub = I18n.format(this,
                            StringsTable.STATE_SYNCING_FMT,
                            UiLogic.grouped(pct),
                            UiLogic.grouped(gap));
                } else {
                    sub = NodeState.label(this, st.status);
                }
                break;
            }
            case SEARCHING: {
                // A measured zero reads "0"; not-yet-measured
                // reads "—". Residual F-22: the two are no
                // longer the same line, either — a measured
                // zero says so (MESSAGE_NO_PEERS, an existing
                // string), and the searching line's elapsed
                // time is measured from when THIS condition
                // began, not from the session's acceptance
                // (the old line could open at "searching for
                // 3 hours" the moment peers dropped to zero
                // in a long-running session).
                setTextIfChanged(netNumView,
                        st.peers == 0 ? "0" : "\u2014");
                netNumView.setTextColor(Tokens.FLARE);
                boolean measuredZero = st.peers == 0;
                if (netSearchSinceElapsedMs < 0
                        || netSearchMeasuredZero != measuredZero) {
                    netSearchSinceElapsedMs = nowEl;
                    netSearchMeasuredZero = measuredZero;
                }
                if (measuredZero) {
                    sub = I18n.get(this, StringsTable.MESSAGE_NO_PEERS);
                } else {
                    long elapsedMs = Math.max(0,
                            nowEl - netSearchSinceElapsedMs);
                    sub = I18n.format(this, StringsTable.STATE_SEARCHING,
                            UiLogic.durationText(tag, elapsedMs, false));
                }
                break;
            }
            case STALLED:
                setTextIfChanged(netNumView, peersText);
                netNumView.setTextColor(Tokens.FLARE);
                sub = I18n.format(this, StringsTable.STATE_STALLED,
                        UiLogic.durationText(tag,
                                Math.max(0, advanceAgeSec) * 1000L,
                                false));
                break;
            case NORMAL:
            default:
                setTextIfChanged(netNumView, peersText);
                netNumView.setTextColor(Tokens.FLARE);
                sub = I18n.get(this, StringsTable.NET_LABEL_PEERS);
                break;
        }
        if (ns != UiLogic.NetState.SEARCHING) {
            // The stretch is over; the next one times itself
            // from its own start (residual F-22).
            netSearchSinceElapsedMs = -1;
        }
        setTextIfChanged(netSubView, sub);
        netTagView.setVisibility(tagVisible ? View.VISIBLE : View.GONE);
        if (tagVisible) {
            setTextIfChanged(netTagView, I18n.format(this,
                    StringsTable.TAG_NODE,
                    timeHhMmSs(st.lastRpcOkWallMs)));
        }

        // Journal-derived field data (stage 2): the record
        // ring / sparkline slot window and the ghost layer.
        // Display data of this device's own journal only.
        DepinStatus ds = DepinModule.get(this).latestStatus();
        if (ds != null && depinAggregatesCurrent(ds)) {
            networkField.setRecordRing(ds.recentSlotPeers);
            networkField.setGhostData(ds.recentDayTs,
                    ds.recentDayPeers, netShow24h);
            sparkView.setSlots(ds.recentSlotPeers);
        } else if (ds != null) {
            // Residual F-08: a stale snapshot's aggregates
            // are withdrawn, not shown as current values.
            networkField.setRecordRing(null);
            networkField.setGhostData(null, null, false);
            sparkView.setSlots(null);
        }

        // The aggregate TalkBack readout (S2-04): one text for
        // the whole field, in the same facts as the hero.
        if (running) {
            networkField.setA11yText(I18n.format(this,
                    StringsTable.FIELD_A11Y, peersText,
                    st.bestBlock >= 0
                            ? UiLogic.grouped(st.bestBlock) : "\u2014",
                    st.lastRpcOkWallMs > 0
                            ? timeHhMmSs(st.lastRpcOkWallMs)
                            : "\u2014"));
        } else {
            networkField.setA11yText(
                    I18n.get(this, StringsTable.VISION_NET_OFF));
        }

        deriveTicker(st, ds, running, nowWall, nowEl);
    }

    /**
     * Ticker derivation (stage 2 spec S2-03): events are the
     * DIFFERENCES between successive snapshots this UI has
     * seen, measured against display baselines adopted at
     * display start (the L-1 discipline), so a rebuild or a
     * return to the tab can never replay history. Captions
     * use observation language only — a block was observed, a
     * peer count was seen, a checkpoint was recorded. Nothing
     * here can say where a block came from or went: the node
     * does not know, so the log does not claim it. Times are
     * the observations' own times where the service recorded
     * them (the block's observation instant, the checkpoint's
     * own timestamp), else the snapshot time — the log's
     * granularity is the refresh cadence and it says nothing
     * finer.
     */
    private void deriveTicker(NodeState st, DepinStatus ds,
                              boolean running, long nowWall,
                              long nowEl) {
        if (!tickerInit) {
            tickerInit = true;
            tickerRunning = running;
            tickerBest = st.lastObservedBestBlock;
            tickerPeers = running && st.peers >= 0
                    ? st.peers : Integer.MIN_VALUE;
            tickerCpCount = ds != null ? ds.checkpointCount : -1;
            tickerCpDeviceId = ds != null ? ds.deviceId : null;
            tickerCpGeneration = ds != null
                    ? ds.identityGeneration : -1;
            tickerHeld = ds != null && ds.holdActive;
            return;
        }
        if (running != tickerRunning) {
            addTickerLine(I18n.get(this, running
                    ? StringsTable.TICK_STARTED
                    : StringsTable.TICK_STOPPED)
                    + "  " + timeHhMm(nowWall));
            tickerRunning = running;
            if (!running) {
                tickerPeers = Integer.MIN_VALUE;
            }
        }
        if (running && tickerBest >= 0
                && st.lastObservedBestBlock > tickerBest) {
            long obsWall = st.lastObservedElapsedMs >= 0
                    ? nowWall - (nowEl - st.lastObservedElapsedMs)
                    : nowWall;
            addTickerLine(I18n.format(this, StringsTable.TICK_BLOCK,
                    timeHhMm(obsWall),
                    UiLogic.grouped(st.lastObservedBestBlock)));
        }
        if (st.lastObservedBestBlock > tickerBest) {
            tickerBest = st.lastObservedBestBlock;
        }
        if (running && st.peers >= 0) {
            if (tickerPeers != Integer.MIN_VALUE
                    && st.peers != tickerPeers) {
                addTickerLine(I18n.format(this,
                        StringsTable.TICK_PEERS, timeHhMm(nowWall),
                        UiLogic.grouped(st.peers)));
            }
            tickerPeers = st.peers;
        }
        if (ds != null && ds.checkpointCount >= 0) {
            // Residual F-24: the checkpoint baseline is
            // keyed to the identity (device + generation).
            // After a transfer, the count alone either
            // stayed silent until the new lineage's count
            // passed the old one, or — receiving a larger
            // journal — announced a checkpoint this session
            // never recorded. An identity change re-baselines
            // silently, exactly like the first snapshot.
            boolean sameIdentity =
                    java.util.Objects.equals(
                            tickerCpDeviceId, ds.deviceId)
                    && tickerCpGeneration
                            == ds.identityGeneration;
            if (sameIdentity && tickerCpCount >= 0
                    && ds.checkpointCount > tickerCpCount) {
                long cpWall = ds.lastCheckpointUnix > 0
                        ? ds.lastCheckpointUnix * 1000L : nowWall;
                addTickerLine(I18n.format(this,
                        StringsTable.TICK_CHECKPOINT,
                        timeHhMm(cpWall),
                        UiLogic.grouped(ds.checkpointCount)));
                networkField.onCheckpointRecorded();
            }
            tickerCpCount = ds.checkpointCount;
            tickerCpDeviceId = ds.deviceId;
            tickerCpGeneration = ds.identityGeneration;
            if (sameIdentity && ds.holdActive && !tickerHeld) {
                addTickerLine(I18n.format(this,
                        StringsTable.TICK_HELD, timeHhMm(nowWall)));
            }
            tickerHeld = ds.holdActive;
        }
    }

    /** Prepend one ticker line (newest first, cap 50) and
     *  re-render the visible tail. Lines live in memory only. */
    private void addTickerLine(String line) {
        tickerLines.add(0, line);
        while (tickerLines.size() > 50) {
            tickerLines.remove(tickerLines.size() - 1);
        }
        if (tickerBox == null) {
            return;
        }
        tickerBox.removeAllViews();
        // One line only (A-12): the ticker names the newest
        // event; the stream it used to stack lives in the log.
        int show = Math.min(1, tickerLines.size());
        for (int i = 0; i < show; i++) {
            TextView row = text(tickerLines.get(i), 13,
                    Tokens.CONTENT, false);
            row.setTypeface(VisionFonts.mono(this));
            row.setSingleLine(true);
            row.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tickerBox.addView(row, matchLp());
        }
    }

    /**
     * The member card from the DePIN facade's status: the card
     * exists only once the journal holds real records (design
     * 7.2); before that the tab shows the guidance alone. Every
     * number is an aggregate of this device's own journal.
     */
    private void refreshMember() {
        DepinStatus ds = DepinModule.get(this).latestStatus();
        boolean hasCard = ds != null && ds.deviceId != null
                && ds.checkpointCount > 0;
        memberCardBox.setVisibility(hasCard ? View.VISIBLE : View.GONE);
        memberEmptyView.setVisibility(hasCard ? View.GONE : View.VISIBLE);
        if (!hasCard) {
            return;
        }
        String name = NodeSettings.nodeNameRaw(this);
        memNameView.setVisibility(name != null && !name.isEmpty()
                ? View.VISIBLE : View.GONE);
        if (name != null && !name.isEmpty()) {
            setTextIfChanged(memNameView, name);
        }
        memIdRaw = ds.deviceId;
        setTextIfChanged(memIdView, memIdGrouped
                ? groupMemberId(ds.deviceId) : ds.deviceId);
        fitSingleLinePx(memIdView, 14f, 10f, 7f);
        fitSingleLinePx(memNameView, 40f, 20f, 12f);
        memSinceValue.setText(ds.firstCheckpointUnix > 0
                ? new SimpleDateFormat("yyyy.MM.dd", Locale.US)
                        .format(new Date(ds.firstCheckpointUnix * 1000L))
                : "\u2014");
        memCpsValue.setText(UiLogic.grouped(ds.checkpointCount));
        memDaysValue.setText(ds.longestStreakDays >= 0
                ? UiLogic.quantityText(I18n.effectiveTag(this),
                        StringsTable.VISION_DAYS_VAL,
                        ds.longestStreakDays)
                : "\u2014");
        for (TextView lab : statLabelViews) {
            fitSingleLinePx(lab, 12f, 10f, 8f);
        }
        for (TextView val : statValueViews) {
            fitSingleLinePx(val, 20f, 14f, 9f);
        }
        // Keep the 1.42:1 ratio via the spacer (see
        // buildMember): the target height comes from the
        // card's own width; content taller than the target
        // keeps its full height (the spacer floors at 0).
        memberCardBox.post(() -> {
            int w = memberCardBox.getWidth();
            if (w <= 0 || memberContent == null
                    || memberSpacer == null) {
                return;
            }
            int target = Math.round(w / 1.42f);
            int contentH = memberContent.getHeight();
            int spacerH = memberSpacer.getHeight();
            int want = Math.max(0, target - (contentH - spacerH));
            if (want != spacerH) {
                LinearLayout.LayoutParams lp =
                        (LinearLayout.LayoutParams)
                                memberSpacer.getLayoutParams();
                lp.height = want;
                memberSpacer.setLayoutParams(lp);
            }
        });
    }

    /** The member number for display: hyphens removed, then a
     *  space every four characters (stage 1 spec S1-11). The
     *  raw id stays one tap away on the card itself. */
    private static String groupMemberId(String deviceId) {
        if (deviceId == null) {
            return "";
        }
        String flat = deviceId.replace("-", "");
        StringBuilder sb = new StringBuilder(flat.length() + 8);
        for (int i = 0; i < flat.length(); i++) {
            if (i > 0 && i % 4 == 0) {
                sb.append(' ');
            }
            sb.append(flat.charAt(i));
        }
        return sb.toString();
    }

    /** What a view was last fitted for. Fitting is idempotent
     *  per (text, width), so the per-second refresh can offer
     *  every text every time without the old reset-and-shrink
     *  churn (audit F3-L8). */
    private static final class FitState {
        String text;
        int width;
    }

    private final java.util.Map<TextView, FitState> fitStates =
            new java.util.IdentityHashMap<>();

    /**
     * Fit a single-line view's text into its width — in PIXELS,
     * never in sp, so the device font-size setting cannot scale
     * the fitted size back up and break the one-line promise
     * (audit F3-L8: at font scale 2.0 the old sp-based floor of
     * 10 sp still overflowed the card and the member number's
     * tail was silently clipped). The size shrinks from startDp
     * toward softFloorDp; if the text is still wide, letter
     * spacing tightens to -0.06 em; if still wide, the size
     * keeps shrinking toward hardFloorDp and, in the limit, as
     * far as needed. The text is never wrapped or ellipsised.
     * Sizes are dp-denominated pixel counts, so the result is
     * identical at any font scale.
     */
    private void fitSingleLinePx(final TextView v, float startDp,
                                 float softFloorDp, float hardFloorDp) {
        v.post(() -> {
            int avail = v.getWidth() - v.getPaddingLeft()
                    - v.getPaddingRight();
            if (avail <= 0) {
                return;
            }
            String s = v.getText().toString();
            FitState st = fitStates.get(v);
            if (st != null && st.width == avail
                    && s.equals(st.text)) {
                return;
            }
            float density =
                    getResources().getDisplayMetrics().density;
            float step = Math.max(1f, density * 0.5f);
            float size = startDp * density;
            float soft = softFloorDp * density;
            float hard = hardFloorDp * density;
            android.graphics.Paint p = v.getPaint();
            float ls = 0f;
            v.setLetterSpacing(0f);
            v.setTextSize(
                    android.util.TypedValue.COMPLEX_UNIT_PX, size);
            while (lineWidthPx(p, s, size, ls) > avail
                    && size > soft) {
                size = Math.max(soft, size - step);
                v.setTextSize(
                        android.util.TypedValue.COMPLEX_UNIT_PX, size);
            }
            while (lineWidthPx(p, s, size, ls) > avail
                    && ls > -0.06f) {
                ls = Math.max(-0.06f, ls - 0.02f);
                v.setLetterSpacing(ls);
            }
            while (lineWidthPx(p, s, size, ls) > avail
                    && size > hard) {
                size = Math.max(hard, size - step);
                v.setTextSize(
                        android.util.TypedValue.COMPLEX_UNIT_PX, size);
            }
            while (lineWidthPx(p, s, size, ls) > avail
                    && size > 4f) {
                size -= step;
                v.setTextSize(
                        android.util.TypedValue.COMPLEX_UNIT_PX, size);
            }
            if (st == null) {
                st = new FitState();
                fitStates.put(v, st);
            }
            st.text = s;
            st.width = avail;
        });
    }

    /** Width of s at the paint's current size, plus the letter
     *  spacing extras that Paint.measureText does not count. */
    private static float lineWidthPx(android.graphics.Paint p,
                                     String s, float sizePx,
                                     float lsEm) {
        float w = p.measureText(s);
        if (lsEm != 0f && s.length() > 1) {
            w += lsEm * sizePx * (s.length() - 1);
        }
        return w;
    }

    private void refreshDetails(NodeState st, String tag,
                                UiLogic.HomeState hs, long now) {
        boolean stopped = st.status == NodeState.Status.STOPPED;
        detailsRunningBox.setVisibility(stopped ? View.GONE : View.VISIBLE);
        detailsStoppedBox.setVisibility(stopped ? View.VISIBLE : View.GONE);
        if (!stopped) {
            detBestValue.setText(st.bestBlock >= 0
                    ? UiLogic.grouped(st.bestBlock)
                    : I18n.get(this, StringsTable.VALUE_UNAVAILABLE));
            detHighestValue.setText(st.highestBlock >= 0
                    ? UiLogic.grouped(st.highestBlock)
                    : I18n.get(this, StringsTable.VALUE_UNAVAILABLE));
            long gap = UiLogic.syncGap(st.bestBlock, st.highestBlock);
            detGapValue.setText(gap >= 0
                    ? UiLogic.grouped(gap)
                    : I18n.get(this, StringsTable.VALUE_UNAVAILABLE));
            detPeersValue.setText(st.peers >= 0
                    ? UiLogic.grouped(st.peers)
                    : I18n.get(this, StringsTable.VALUE_UNAVAILABLE));
            detSessionValue.setText(st.sessionAcceptedElapsedMs >= 0
                    ? UiLogic.durationText(tag,
                            sessionUptimeMs(st, now), false)
                    : I18n.get(this, StringsTable.VALUE_UNAVAILABLE));
        } else {
            cumUptimeValue.setText(UiLogic.durationText(
                    tag, TrackStore.uptimeMs(this), true));
            cumBlocksValue.setText(
                    UiLogic.grouped(TrackStore.blockUpdates(this)));
            cumPeersValue.setText(
                    UiLogic.grouped(TrackStore.maxPeers(this)));
        }
        // v4.2.4 (audit V4-7): the address the poller uses, as the
        // running node reported it; a dash before that and when
        // stopped.
        setTextIfChanged(rpcNoteView, UiLogic.rpcNoteLine(
                I18n.get(this, StringsTable.RPC_LOCAL_NOTE),
                stopped ? null : st.rpcEndpoint));

        // Diagnostics text: the service's own records, verbatim.
        StringBuilder sb = new StringBuilder();
        if (st.lastStopKind == NodeState.StopKind.USER) {
            sb.append(I18n.get(this, StringsTable.MESSAGE_USER_STOP));
        }
        if (st.lastExitCode != null) {
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append(I18n.format(
                    this, StringsTable.NOTE_LAST_EXIT_CODE, st.lastExitCode));
        }
        if (st.lastNote != null) {
            if (sb.length() > 0) {
                sb.append("\n");
            }
            sb.append(st.lastNote);
        }
        List<NodeState.Attempt> attempts = st.lastAttempts;
        if (attempts != null && !attempts.isEmpty()) {
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append(I18n.get(this, StringsTable.ATTEMPTS_TITLE));
            int n = 1;
            for (NodeState.Attempt a : attempts) {
                sb.append("\n");
                String method = I18n.get(this, a.viaLinker
                        ? StringsTable.ATTEMPT_METHOD_LINKER
                        : StringsTable.ATTEMPT_METHOD_DIRECT);
                if (a.exitCode != null) {
                    sb.append(I18n.format(this, StringsTable.ATTEMPT_SUMMARY,
                            n, method, a.exitCode, a.livedMs));
                } else {
                    sb.append(I18n.format(this,
                            StringsTable.ATTEMPT_SUMMARY_NO_EXIT,
                            n, method, a.livedMs));
                }
                if (a.lines.isEmpty()) {
                    sb.append("\n").append(
                            I18n.get(this, StringsTable.ATTEMPT_NO_OUTPUT));
                } else {
                    for (String line : a.lines) {
                        sb.append("\n").append(line);
                    }
                }
                n++;
            }
        }
        String diag = sb.length() == 0
                ? I18n.get(this, StringsTable.DIAGNOSTICS_EMPTY)
                : sb.toString();
        if (!diag.equals(lastDiagText)) {
            lastDiagText = diag;
            diagText.setText(diag);
        }
    }

    private void refreshSettings(NodeState st) {
        syncSwitch(telemetrySwitch, NodeSettings.telemetryEnabled(this));
        syncSwitch(lockSwitch, NodeSettings.settingsLocked(this));
        applySettingsLock();

        int pending = UiLogic.settingsPending(st.serviceRunning,
                st.launchedTelemetry, NodeSettings.telemetryEnabled(this),
                st.launchedNodeName, NodeSettings.launchNodeName(this));
        boolean telPending = (pending & UiLogic.PENDING_TELEMETRY) != 0;
        boolean namePending = (pending & UiLogic.PENDING_NAME) != 0;
        telemetryPendingView.setVisibility(telPending ? View.VISIBLE : View.GONE);
        telemetryAppliedView.setVisibility(telPending ? View.VISIBLE : View.GONE);
        if (telPending) {
            setTextIfChanged(telemetryPendingView,
                    I18n.get(this, StringsTable.SETTINGS_PENDING_STATUS));
            // Symmetric with the display name (finding L-6): the
            // value the running node is actually using, as a word.
            // telPending implies a launch snapshot exists.
            boolean applied = st.launchedTelemetry != null
                    && st.launchedTelemetry;
            setTextIfChanged(telemetryAppliedView, I18n.format(this,
                    StringsTable.SETTINGS_APPLIED_VALUE,
                    I18n.get(this, applied
                            ? StringsTable.VALUE_ON
                            : StringsTable.VALUE_OFF)));
        }
        namePendingView.setVisibility(namePending ? View.VISIBLE : View.GONE);
        nameAppliedView.setVisibility(namePending ? View.VISIBLE : View.GONE);
        if (namePending) {
            setTextIfChanged(namePendingView,
                    I18n.get(this, StringsTable.SETTINGS_PENDING_STATUS));
            String applied = st.launchedNodeName;
            setTextIfChanged(nameAppliedView, I18n.format(this,
                    StringsTable.SETTINGS_APPLIED_VALUE,
                    applied == null ? "" : applied));
        }
        nameValue.setText(shownName());
        languageValue.setText(currentLanguageLabel());

        // DePIN section: pull the facade's latest status and mirror
        // it. syncSwitch guards the programmatic toggle sync with
        // syncingSettings, exactly as for telemetry above.
        DepinModule facade = DepinModule.get(this);
        facade.refreshStatus();
        DepinStatus ds = facade.latestStatus();
        syncSwitch(depinSwitch, ds != null && ds.enabled);
        if (ds == null) {
            depinDeviceIdValue.setText(
                    I18n.get(this, StringsTable.DEPIN_VALUE_NONE));
            depinStateValue.setText(
                    I18n.get(this, StringsTable.DEPIN_STATE_UNAVAILABLE));
            depinCheckpointsValue.setText("\u2014");
            depinLastCheckpointValue.setText(
                    I18n.get(this, StringsTable.DEPIN_VALUE_NONE));
            depinNoteView.setVisibility(View.GONE);
            if (depinAttemptResetButton != null) {
                depinAttemptResetButton.setVisibility(View.GONE);
            }
            return;
        }
        depinDeviceIdValue.setText(ds.deviceId != null
                ? ds.deviceId
                : I18n.get(this, StringsTable.DEPIN_VALUE_NONE));
        String depinStateKey;
        switch (ds.state) {
            case DepinStatus.STATE_PREPARING:
                depinStateKey = StringsTable.DEPIN_STATE_PREPARING;
                break;
            case DepinStatus.STATE_RECORDING:
                depinStateKey = StringsTable.DEPIN_STATE_RECORDING;
                break;
            case DepinStatus.STATE_HELD:
                depinStateKey = StringsTable.DEPIN_STATE_HELD;
                break;
            case DepinStatus.STATE_SELF_STOPPED:
                depinStateKey = StringsTable.DEPIN_STATE_SELF_STOPPED;
                break;
            case DepinStatus.STATE_UNAVAILABLE:
                depinStateKey = StringsTable.DEPIN_STATE_UNAVAILABLE;
                break;
            case DepinStatus.STATE_OFF:
            default:
                depinStateKey = StringsTable.DEPIN_STATE_OFF;
                break;
        }
        depinStateValue.setText(I18n.get(this, depinStateKey));
        depinCheckpointsValue.setText(ds.checkpointCount >= 0
                ? UiLogic.grouped(ds.checkpointCount)
                : "\u2014");
        depinLastCheckpointValue.setText(ds.lastCheckpointUnix > 0
                ? new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                        .format(new Date(ds.lastCheckpointUnix * 1000L))
                : I18n.get(this, StringsTable.DEPIN_VALUE_NONE));
        // G-11 (design section 2.3): the confirmed repair is
        // offered exactly in the state that needs it — the
        // observer self-stopped on the attempt log and the
        // corruption is still reported. Everywhere else the
        // button stays hidden.
        if (depinAttemptResetButton != null) {
            boolean offerReset =
                    ds.state == DepinStatus.STATE_SELF_STOPPED
                            && ds.attemptLogCorrupt
                            && DepinIpc.REASON_ATTEMPT_LOG.equals(
                                    ds.selfStopReason);
            depinAttemptResetButton.setVisibility(
                    offerReset ? View.VISIBLE : View.GONE);
        }
        if (ds.state == DepinStatus.STATE_SELF_STOPPED) {
            String reason = ds.selfStopDetail != null
                    && !ds.selfStopDetail.isEmpty()
                    ? ds.selfStopDetail
                    : ds.selfStopReason != null
                            && !ds.selfStopReason.isEmpty()
                            ? ds.selfStopReason
                            : I18n.get(this, StringsTable.DEPIN_VALUE_NONE);
            depinNoteView.setText(I18n.format(
                    this, StringsTable.DEPIN_NOTE_SELF_STOPPED, reason));
            depinNoteView.setVisibility(View.VISIBLE);
        } else if (ds.commandDiscarded) {
            depinNoteView.setText(
                    I18n.get(this, StringsTable.DEPIN_NOTE_DISCARDED));
            depinNoteView.setVisibility(View.VISIBLE);
        } else if (ds.attemptLogResetUnix > 0
                && System.currentTimeMillis() / 1000L
                        - ds.attemptLogResetUnix
                        < ATTEMPT_RESET_NOTE_WINDOW_SEC) {
            // H-06 (final check round 3): the reset is never
            // silent — but the note is TIME-BOXED (seven days
            // from the execution, in any enable state) and it
            // yields to the command-discarded note above. The
            // pre-H-06 shape showed it for as long as the
            // observer sat OFF — which can be forever — ahead
            // of the discarded note, so a command discarded
            // after a reset was never surfaced at all.
            depinNoteView.setText(I18n.format(this,
                    StringsTable.DEPIN_NOTE_ATTEMPT_RESET_DONE,
                    new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
                            .format(new Date(
                                    ds.attemptLogResetUnix * 1000L))));
            depinNoteView.setVisibility(View.VISIBLE);
        } else if ((ds.state == DepinStatus.STATE_RECORDING
                        || ds.state == DepinStatus.STATE_HELD)
                && ds.lastError != null && !ds.lastError.isEmpty()) {
            // H-09 (final check round 3): the observer's
            // last-error text finally has a reader. While
            // recording (or held), the reasons the loop gives
            // for skipping or holding a checkpoint — an
            // implausible reading, a held append, the
            // free-space floor — were written into the status
            // and displayed NOWHERE, so a silent stall looked
            // exactly like healthy recording. The state label
            // still leads; this note carries the detail.
            depinNoteView.setText(I18n.format(this,
                    StringsTable.DEPIN_NOTE_LAST_ERROR,
                    ds.lastError));
            depinNoteView.setVisibility(View.VISIBLE);
        } else if (ds.enabled && ds.restartFailures > 0
                && ds.state == DepinStatus.STATE_UNAVAILABLE) {
            // Internal.24: the restart backoff, stated
            // truthfully — restarts have been failing, they
            // continue automatically with growing spacing, and
            // an off→on retries immediately. (The internal.23
            // copy claimed restarts had STOPPED behind a latch
            // whose only exit was this same toggle; that state
            // no longer exists.)
            String when = ds.restartNextWallMs
                    > System.currentTimeMillis()
                    ? priceTimeText(ds.restartNextWallMs)
                    : I18n.get(this,
                            StringsTable.DEPIN_NEXT_RETRY_SOON);
            depinNoteView.setText(I18n.format(
                    this, StringsTable.DEPIN_NOTE_RESTART_BACKOFF,
                    when));
            depinNoteView.setVisibility(View.VISIBLE);
        } else {
            depinNoteView.setVisibility(View.GONE);
        }
    }

    /**
     * The user's explicit confirmation of the attempt-log
     * repair (design section 2.3; G-11). On confirm, the facade
     * records the one-shot reset request and the normal enable
     * path runs: the :depin process performs the reset during
     * its initialisation — corrupt bytes set aside under the
     * fixed name rpc-attempts.jsonl.corrupt (with -1, -2…
     * suffixes on collision), the fact recorded in the
     * settings and the dedicated log — and recording resumes.
     * This process writes no DePIN
     * settings itself (G-01: the settings writers are
     * :depin-only; the request is a bare flag file, in the
     * user-intent shape).
     */
    private void confirmAttemptLogReset() {
        new AlertDialog.Builder(this)
                .setTitle(I18n.get(this,
                        StringsTable.DEPIN_ATTEMPT_RESET_CONFIRM_TITLE))
                .setMessage(I18n.get(this,
                        StringsTable.DEPIN_ATTEMPT_RESET_CONFIRM_BODY))
                .setNegativeButton(I18n.get(this, StringsTable.BUTTON_CANCEL), null)
                .setPositiveButton(I18n.get(this,
                        StringsTable.DEPIN_ATTEMPT_RESET_BUTTON),
                        (dialog, which) -> {
                            DepinModule.get(this)
                                    .requestAttemptLogReset();
                            DepinStatus cur = DepinModule.get(this)
                                    .latestStatus();
                            String deviceId = cur != null
                                    ? cur.deviceId : null;
                            long generation = cur != null
                                    ? cur.identityGeneration : 0;
                            if (deviceId != null
                                    && currentTransferActiveDeviceId
                                            != null
                                    && !deviceId.equals(
                                            currentTransferActiveDeviceId)) {
                                // Same displaced-binding guard as
                                // the toggle: no expectation for a
                                // displaced identity.
                                deviceId = null;
                                generation = 0;
                            }
                            DepinModule.get(this).enable(
                                    deviceId, generation, notifStrings());
                            refresh();
                        })
                .show();
    }

    /** Skip a setText when the text is unchanged (avoids re-measure churn). */
    private static void setTextIfChanged(TextView v, String s) {
        CharSequence cur = v.getText();
        if (cur == null || !cur.toString().equals(s)) {
            v.setText(s);
        }
    }

    // ------------------------------------------------------------------
    // Proof-bundle export (integration step 5)
    // ------------------------------------------------------------------

    private void showExportNote(String text) {
        if (depinExportNoteView != null) {
            depinExportNoteView.setText(text);
            depinExportNoteView.setVisibility(View.VISIBLE);
        }
    }

    /** M-4: the export button is disabled from the tap until
     *  the export (and any save of its staged file) completes,
     *  so exports cannot overlap. */
    private void setExportBusy(boolean busy) {
        exportBusy = busy;
        if (depinExportButton != null) {
            depinExportButton.setEnabled(
                    !busy && !NodeSettings.settingsLocked(this));
        }
    }

    private void onExportBundleClicked() {
        if (NodeSettings.settingsLocked(this)) {
            applySettingsLock();
            return;
        }
        if (exportBusy) {
            return;
        }
        if (depinExportNoteView != null) {
            depinExportNoteView.setVisibility(View.GONE);
        }
        setExportBusy(true);
        DepinModule.get(this).exportBundle((result, name, error) ->
                runOnUiThread(() -> {
                    switch (result) {
                        case DepinIpc.BUNDLE_RESULT_OK: {
                            Intent intent = new Intent(
                                    Intent.ACTION_CREATE_DOCUMENT);
                            intent.addCategory(Intent.CATEGORY_OPENABLE);
                            intent.setType("application/json");
                            intent.putExtra(Intent.EXTRA_TITLE, name);
                            try {
                                startActivityForResult(
                                        intent, REQ_EXPORT_SAVE);
                                // Busy stays set: the staged file
                                // is in use until the save ends
                                // (finishBundleSave / picker cancel
                                // clears it).
                            } catch (Throwable t) {
                                DepinBundleExport.discardStaged(this);
                                setExportBusy(false);
                                showExportNote(I18n.format(this,
                                        StringsTable.DEPIN_EXPORT_FAILED,
                                        t.getClass().getSimpleName()));
                            }
                            break;
                        }
                        case DepinIpc.BUNDLE_RESULT_NONE:
                            setExportBusy(false);
                            showExportNote(I18n.get(
                                    this, StringsTable.DEPIN_EXPORT_NONE));
                            break;
                        case DepinModule.BUNDLE_NOT_RUNNING:
                            setExportBusy(false);
                            showExportNote(I18n.get(this,
                                    StringsTable.DEPIN_EXPORT_NOT_RUNNING));
                            break;
                        default:
                            setExportBusy(false);
                            showExportNote(I18n.format(this,
                                    StringsTable.DEPIN_EXPORT_FAILED,
                                    error != null ? error : "?"));
                            break;
                    }
                }));
    }

    /** Stream the staged bundle to the picked destination,
     *  then drop the staged copy either way. Runs on the
     *  shared serial executor (M-4) and ends the export's
     *  busy period. */
    private void finishBundleSave(Uri uri) {
        depinWorkExecutor.execute(() -> {
            boolean ok = false;
            try (java.io.InputStream in = new java.io.FileInputStream(
                         DepinFiles.pendingBundleFile(this));
                 java.io.OutputStream out =
                         getContentResolver().openOutputStream(uri)) {
                if (out == null) {
                    throw new IllegalStateException("no output stream");
                }
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                out.flush();
                ok = true;
            } catch (Throwable t) {
                final String msg = t.getClass().getSimpleName()
                        + ": " + t.getMessage();
                runOnUiThread(() -> showExportNote(I18n.format(
                        this, StringsTable.DEPIN_EXPORT_FAILED, msg)));
            } finally {
                DepinBundleExport.discardStaged(this);
            }
            runOnUiThread(() -> setExportBusy(false));
            if (ok) {
                runOnUiThread(() -> showExportNote(I18n.get(
                        this, StringsTable.DEPIN_EXPORT_SAVED)));
            }
        });
    }

    // ------------------------------------------------------------------
    // Device transfer (integration step 4; design sections 4, 5.3)
    // ------------------------------------------------------------------

    /** Follow-up for a completed transfer operation, run on
     *  the UI thread with the reply payload (dialogs, save
     *  pickers). */
    private interface TransferOk {
        void run(Bundle data) throws Exception;
    }

    private Button transferButton(LinearLayout root, String labelKey,
                                  final int action) {
        Button b = new Button(this);
        b.setText(I18n.get(this, labelKey));
        b.setTextSize(15);
        b.setTextColor(Tokens.FLARE);
        b.setAllCaps(false);
        b.setBackground(ripple(null));
        b.setStateListAnimator(null);
        b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        b.setMinHeight(dp(48));
        b.setOnClickListener(v -> runTransferAction(action));
        depinTransferButtons.add(b);
        root.addView(b, matchLpTop(4));
        return b;
    }

    /** One role's main action button: its label and the action
     *  it runs are (re)assigned by every state derivation, so
     *  the button always offers the flow's next step. */
    private Button mainTransferButton(View.OnClickListener listener) {
        Button b = new Button(this);
        b.setTextSize(15);
        b.setTextColor(Tokens.FLARE);
        b.setAllCaps(false);
        b.setBackground(ripple(null));
        b.setStateListAnimator(null);
        b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        b.setMinHeight(dp(48));
        b.setOnClickListener(listener);
        depinTransferButtons.add(b);
        return b;
    }

    /** The "other actions" expander over a role's secondary
     *  operations container. */
    private TextView othersToggle(final LinearLayout container,
                                  final boolean receiver) {
        final TextView t = text(I18n.get(
                this, StringsTable.DEPIN_TRANSFER_OTHER_OPS) + " ▸",
                14, Tokens.FAINT, false);
        t.setClickable(true);
        t.setFocusable(true);
        t.setMinHeight(dp(48));
        t.setGravity(Gravity.CENTER_VERTICAL);
        t.setOnClickListener(v -> {
            boolean expanded = receiver
                    ? (receiverOthersExpanded = !receiverOthersExpanded)
                    : (senderOthersExpanded = !senderOthersExpanded);
            container.setVisibility(
                    expanded ? View.VISIBLE : View.GONE);
            t.setText(I18n.get(this,
                    StringsTable.DEPIN_TRANSFER_OTHER_OPS)
                    + (expanded ? " ▾" : " ▸"));
        });
        return t;
    }

    /**
     * Run one device-transfer operation (audit H-1: in the
     * :depin process, via DepinTransferClient — this process
     * never loads a seed or a transfer machine). The transfer
     * layer is introduced by the service on first use
     * (legacy-upgrade mode; the positive legacy-journal
     * confirmation runs inside its initialise()). Until then
     * the app behaves exactly as in build step 2.
     *
     * Audit H-3 discipline is unchanged: the busy flag disables
     * the transfer buttons from the tap until the reply lands,
     * so a second tap cannot start a parallel operation over
     * the same transfer state and staging files. The reply is
     * asynchronous — a confirm under the section 3.5 budget may
     * take a long time and may come back PAUSED (a resumable
     * stop; re-issuing the same operation continues it) — and
     * success / failure land exactly where the in-process flow
     * put them: success refreshes the state line, failure (and
     * pause) shows the "Class: message" line the in-process
     * catch block produced.
     */
    private void transferOp(final int op, Bundle params,
                            final TransferOk ok) {
        // A new operation supersedes the held failure: the
        // error export always describes the LAST failure.
        pendingErrorOp = null;
        transferBusy = true;
        applyTransferButtonStates();
        DepinTransferClient.get(this).request(op, params,
                (rop, result, data) -> {
                    if (result == DepinIpc.T_RESULT_OK) {
                        runOnUiThread(() -> {
                            transferBusy = false;
                            hideTransferResult();
                            try {
                                if (ok != null) {
                                    ok.run(data);
                                }
                            } catch (Throwable t) {
                                captureTransferError(
                                        transferOpPhase(op),
                                        transferOpMode(op),
                                        transferOpRole(op),
                                        app.quantusnode.node.depin
                                                .TransferErrorReport
                                                .classify(t));
                                showTransferFailure(
                                        t.getClass().getSimpleName()
                                                + ": " + t.getMessage());
                                return;
                            }
                            refreshTransferState();
                        });
                    } else if (result == DepinIpc.T_RESULT_PAUSED) {
                        // Section 3.5 budget pause: a resumable
                        // stop, NOT a failure (the pre-consolidation
                        // UI lumped it into the failure branch and
                        // showed it as "failed", with no way to
                        // resume). The durable cursor is saved;
                        // the derived state now offers the resume.
                        final String msg = data.getString(
                                DepinIpc.KEY_T_ERROR_CLASS,
                                "VerificationPausedException")
                                + ": " + data.getString(
                                        DepinIpc.KEY_T_ERROR);
                        runOnUiThread(() -> {
                            transferBusy = false;
                            showTransferPaused(msg);
                        });
                    } else if (result
                            == DepinTransferClient.RESULT_UNAVAILABLE) {
                        runOnUiThread(() -> {
                            transferBusy = false;
                            captureTransferError(
                                    transferOpPhase(op),
                                    transferOpMode(op),
                                    transferOpRole(op),
                                    app.quantusnode.node.depin
                                            .TransferErrorReport
                                            .R_SERVICE);
                            showTransferFailure(
                                    "IllegalStateException: the "
                                            + "transfer service is "
                                            + "unavailable");
                        });
                    } else {
                        final String errorClass = data.getString(
                                DepinIpc.KEY_T_ERROR_CLASS,
                                "TransferException");
                        final String errorMessage = data.getString(
                                DepinIpc.KEY_T_ERROR);
                        final String errorChain = data.getString(
                                DepinIpc.KEY_T_ERROR_CHAIN);
                        final String msg = errorClass + ": "
                                + errorMessage;
                        runOnUiThread(() -> {
                            transferBusy = false;
                            captureTransferError(
                                    transferOpPhase(op),
                                    transferOpMode(op),
                                    transferOpRole(op),
                                    data.getString(
                                            DepinIpc.KEY_T_REASON,
                                            app.quantusnode.node.depin
                                                    .TransferErrorReport
                                                    .R_UNKNOWN));
                            showTransferFailure(msg);
                        });
                    }
                });
    }

    private void showTransferFailure(String msg) {
        applyTransferButtonStates();
        if (depinTransferResultView != null) {
            depinTransferResultView.setText(I18n.format(
                    this, StringsTable.DEPIN_TRANSFER_FAILED, msg));
            depinTransferResultView.setVisibility(View.VISIBLE);
        }
    }

    /** A section 3.5 pause gets its own wording: the state is
     *  saved and the same action resumes it. The state lines are
     *  refreshed too — after a paused stage they offer the
     *  resume as the main action. */
    private void showTransferPaused(String msg) {
        applyTransferButtonStates();
        if (depinTransferResultView != null) {
            depinTransferResultView.setText(I18n.format(
                    this, StringsTable.DEPIN_TRANSFER_PAUSED, msg));
            depinTransferResultView.setVisibility(View.VISIBLE);
        }
        refreshTransferState();
    }

    private void hideTransferResult() {
        if (depinTransferResultView != null) {
            depinTransferResultView.setVisibility(View.GONE);
        }
    }

    private static String transferOpLabel(int op) {
        switch (op) {
            case DepinIpc.T_OP_EXPORT_PACKAGE:
                return "export_package";
            case DepinIpc.T_OP_EXPORT_PACKAGE_ONESHOT:
                return "export_package_oneshot";
            case DepinIpc.T_OP_EXPORT_PACKAGE_ONESHOT_AGAIN:
                return "export_package_oneshot_again";
            case DepinIpc.T_OP_ISSUE_PERMIT:
                return "issue_permit";
            case DepinIpc.T_OP_CANCEL_SENDER:
                return "cancel_sender";
            case DepinIpc.T_OP_INTAKE_RECEIPT:
                return "intake_receipt";
            case DepinIpc.T_OP_INTAKE_ABANDON:
                return "intake_abandon";
            case DepinIpc.T_OP_REPRESENT_ACK:
                return "represent_ack";
            case DepinIpc.T_OP_INTAKE_NOTICE:
                return "intake_notice";
            case DepinIpc.T_OP_STAGE_PACKAGE:
                return "stage_package";
            case DepinIpc.T_OP_COMPLETE_INTAKE:
                return "complete_intake";
            case DepinIpc.T_OP_INTAKE_PERMIT:
                return "intake_permit";
            case DepinIpc.T_OP_INTAKE_ACK:
                return "intake_ack";
            case DepinIpc.T_OP_INTAKE_CANCEL_TOKEN:
                return "intake_cancel_token";
            case DepinIpc.T_OP_EXPORT_NOTICE:
                return "export_notice";
            case DepinIpc.T_OP_CONFIRM:
                return "confirm";
            case DepinIpc.T_OP_CONFIRM_ONESHOT:
                return "confirm_oneshot";
            case DepinIpc.T_OP_CLOSE_ONESHOT_SENDER:
                return "close_oneshot_sender";
            case DepinIpc.T_OP_CANCEL_ONESHOT_SENDER:
                return "cancel_oneshot_sender";
            case DepinIpc.T_OP_EXPORT_RECEIPT:
                return "export_receipt";
            case DepinIpc.T_OP_EXPORT_ABANDON:
                return "export_abandon";
            default:
                return "op_" + op;
        }
    }

    private static String transferOpMode(int op) {
        switch (op) {
            case DepinIpc.T_OP_EXPORT_PACKAGE_ONESHOT:
            case DepinIpc.T_OP_EXPORT_PACKAGE_ONESHOT_AGAIN:
            case DepinIpc.T_OP_CONFIRM_ONESHOT:
            case DepinIpc.T_OP_CLOSE_ONESHOT_SENDER:
            case DepinIpc.T_OP_CANCEL_ONESHOT_SENDER:
                return "oneshot";
            default:
                return "strict";
        }
    }

    private static String transferOpRole(int op) {
        switch (op) {
            case DepinIpc.T_OP_EXPORT_PACKAGE:
            case DepinIpc.T_OP_EXPORT_PACKAGE_ONESHOT:
            case DepinIpc.T_OP_ISSUE_PERMIT:
            case DepinIpc.T_OP_CANCEL_SENDER:
            case DepinIpc.T_OP_INTAKE_RECEIPT:
            case DepinIpc.T_OP_INTAKE_ABANDON:
            case DepinIpc.T_OP_REPRESENT_ACK:
            case DepinIpc.T_OP_INTAKE_NOTICE:
            case DepinIpc.T_OP_EXPORT_PACKAGE_ONESHOT_AGAIN:
            case DepinIpc.T_OP_CLOSE_ONESHOT_SENDER:
            case DepinIpc.T_OP_CANCEL_ONESHOT_SENDER:
                return "sender";
            default:
                return "receiver";
        }
    }

    /** The diagnostic taxonomy phase for an operation (E10). */
    private static String transferOpPhase(int op) {
        switch (op) {
            case DepinIpc.T_OP_EXPORT_PACKAGE:
            case DepinIpc.T_OP_EXPORT_PACKAGE_ONESHOT:
            case DepinIpc.T_OP_EXPORT_PACKAGE_ONESHOT_AGAIN:
                return app.quantusnode.node.depin
                        .TransferErrorReport.PHASE_EXPORT;
            case DepinIpc.T_OP_STAGE_PACKAGE:
            case DepinIpc.T_OP_COMPLETE_INTAKE:
                return app.quantusnode.node.depin
                        .TransferErrorReport.PHASE_STAGE;
            case DepinIpc.T_OP_CONFIRM:
            case DepinIpc.T_OP_CONFIRM_ONESHOT:
                return app.quantusnode.node.depin
                        .TransferErrorReport.PHASE_CONFIRM;
            case DepinIpc.T_OP_CLOSE_ONESHOT_SENDER:
                return app.quantusnode.node.depin
                        .TransferErrorReport.PHASE_CLOSE;
            case DepinIpc.T_OP_CANCEL_SENDER:
            case DepinIpc.T_OP_CANCEL_ONESHOT_SENDER:
            case DepinIpc.T_OP_INTAKE_ABANDON:
            case DepinIpc.T_OP_EXPORT_ABANDON:
                return app.quantusnode.node.depin
                        .TransferErrorReport.PHASE_CANCEL;
            case DepinIpc.T_OP_EXPORT_NOTICE:
            case DepinIpc.T_OP_INTAKE_NOTICE:
                return app.quantusnode.node.depin
                        .TransferErrorReport.PHASE_NOTICE;
            case DepinIpc.T_OP_ISSUE_PERMIT:
            case DepinIpc.T_OP_INTAKE_PERMIT:
                return app.quantusnode.node.depin
                        .TransferErrorReport.PHASE_PERMIT;
            case DepinIpc.T_OP_EXPORT_RECEIPT:
            case DepinIpc.T_OP_INTAKE_RECEIPT:
                return app.quantusnode.node.depin
                        .TransferErrorReport.PHASE_RECEIPT;
            case DepinIpc.T_OP_INTAKE_ACK:
            case DepinIpc.T_OP_REPRESENT_ACK:
                return app.quantusnode.node.depin
                        .TransferErrorReport.PHASE_ACK;
            case DepinIpc.T_OP_INTAKE_CANCEL_TOKEN:
                return app.quantusnode.node.depin
                        .TransferErrorReport.PHASE_RECOVERY;
            default:
                return app.quantusnode.node.depin
                        .TransferErrorReport.PHASE_SERVICE;
        }
    }

    /** Hold a failure for the error-file export (E10: the
     *  file is built from a strict whitelist — own state plus
     *  manifest-derived facts — and the failure is described
     *  by OUR phase + reason taxonomy, never exception text).
     *  Kept here: the phase, mode and role, the reason code,
     *  and the durable context the last state derivation read
     *  — device ids, generation, package hash and the
     *  staging's verified/total counts. */
    private void captureTransferError(String phase, String mode,
                                      String role, String reason) {
        pendingErrorOp = phase;
        pendingErrorMode = mode;
        pendingErrorRole = role;
        pendingErrorReason = reason;
        pendingErrorWhenMs = System.currentTimeMillis();
        // Audit Low (error-file labels): the report's
        // peer_from_manifest block is only truthful for a
        // RECEIVER-side failure — the manifest's identity is
        // the peer there. For a sender-side failure this code
        // used to fill it with whichever track existed (with
        // both tracks present, the RECEIVER's id — the exact
        // opposite of the failing side). Sender-side failures
        // omit the peer block; the sender identity still
        // appears in local_state from the snapshot.
        if ("sender".equals(role)) {
            pendingErrorDeviceId = null;
            pendingErrorGeneration = -1;
            pendingErrorPackageSha256 = null;
        } else {
            pendingErrorDeviceId = currentReceiverDeviceId != null
                    ? currentReceiverDeviceId : currentSenderDeviceId;
            pendingErrorGeneration = currentReceiverDeviceId != null
                    ? currentReceiverGeneration
                    : currentSenderGeneration;
            pendingErrorPackageSha256 = currentReceiverPackageSha256;
        }
        pendingErrorCounterpartId = currentReceiverDeviceId != null
                && currentSenderDeviceId != null
                && !currentSenderDeviceId.equals(currentReceiverDeviceId)
                ? currentSenderDeviceId : null;
        TransferFlow.StagingRef ref = currentUnnotifiedStaging;
        pendingErrorTotal = ref != null ? ref.checkpointCount : -1;
        pendingErrorVerified = ref != null ? ref.verifiedCount : -1;
        applyTransferButtonStates();
    }

    /** Write the held failure's diagnostic JSON and offer it
     *  through the usual save picker. The state classification
     *  is re-derived at save time ("where readable"), and the
     *  build identity comes from the package manager. */
    private void saveTransferErrorFile() {
        if (pendingErrorOp == null) return;
        final String phase = pendingErrorOp;
        final String mode = pendingErrorMode;
        final String role = pendingErrorRole;
        final String reason = pendingErrorReason;
        final long whenMs = pendingErrorWhenMs;
        final String deviceId = pendingErrorDeviceId;
        final long generation = pendingErrorGeneration;
        final String sha = pendingErrorPackageSha256;
        final long total = pendingErrorTotal;
        final long verified = pendingErrorVerified;
        transferBusy = true;
        applyTransferButtonStates();
        depinWorkExecutor.execute(() -> {
            TransferFlow.Snapshot snap = null;
            try {
                snap = TransferFlow.derive(DepinFiles.depinDir(this));
            } catch (Throwable ignored) {
                // The classification is best-effort; the rest of
                // the report does not depend on it.
            }
            String appName = "Quantus Node";
            String versionName = "";
            long versionCode = -1;
            try {
                android.content.pm.PackageInfo pi =
                        getPackageManager().getPackageInfo(
                                getPackageName(), 0);
                versionName = pi.versionName;
                versionCode = pi.getLongVersionCode();
                CharSequence label = getApplicationInfo().loadLabel(
                        getPackageManager());
                if (label != null) appName = label.toString();
            } catch (Throwable ignored) {
            }
            final String name = app.quantusnode.node.depin
                    .TransferErrorReport.suggestedFileName();
            final java.io.File out = new java.io.File(
                    getCacheDir(), name);
            try {
                String json = app.quantusnode.node.depin
                        .TransferErrorReport.buildJson(
                                appName, versionName, versionCode,
                                whenMs, phase, mode, role, reason,
                                snap, deviceId, generation, sha,
                                total, verified);
                app.quantusnode.node.depin.core.TransferIo.writeFileSync(
                        out, json.getBytes(
                                java.nio.charset.StandardCharsets.UTF_8));
            } catch (Throwable t) {
                final String msg = t.getClass().getSimpleName()
                        + ": " + t.getMessage();
                runOnUiThread(() -> {
                    transferBusy = false;
                    showTransferFailure(msg);
                });
                return;
            }
            runOnUiThread(() -> {
                transferBusy = false;
                applyTransferButtonStates();
                saveTransferDocument(name, out);
            });
        });
    }

    /**
     * Show a dialog whose window is FLAG_SECURE (v4.2.4, audit L-8):
     * no screenshot, screen recording or recents thumbnail captures
     * what it displays. Used for the dialogs that show the transfer
     * code — the one secret guarding the identity seed inside an
     * exported package — and the confirmation number, and for the
     * code entry. Until v4.2.3 only the inner-hash editor had this.
     */
    private AlertDialog showSecure(AlertDialog.Builder builder) {
        AlertDialog dialog = builder.create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().addFlags(
                    WindowManager.LayoutParams.FLAG_SECURE);
        }
        dialog.show();
        return dialog;
    }

    /** A finished export's reply (strict and one-shot share
     *  the shape): the transfer code is shown once, on this
     *  screen only, as when the export ran in-process; then
     *  the save picker takes the package file the service
     *  named. */
    /** A one-shot confirm finished (E4): show the
     *  confirmation number the user must carry to the sender
     *  side — it is also kept in the DONE state text — and
     *  try to delete the picked source file (E1). */
    private void onConfirmOneshotReply(Bundle data) {
        String code = data.getString(DepinIpc.KEY_T_COMMIT_CODE);
        if (code != null) {
            // v4.2.4 (audit L-8): FLAG_SECURE, like every dialog that
            // shows transfer material.
            showSecure(new AlertDialog.Builder(this)
                    .setTitle(I18n.get(this,
                            StringsTable
                                    .DEPIN_TRANSFER_COMMIT_CODE_TITLE))
                    .setMessage(I18n.format(this,
                            StringsTable
                                    .DEPIN_TRANSFER_COMMIT_CODE_BODY,
                            code))
                    .setPositiveButton(I18n.get(this, StringsTable.BUTTON_OK), null));
        }
        attemptDeletePickedPackage();
    }

    /** E7 re-export reply: the new code, with the note that
     *  the previous file remains valid. */
    private void onExportReplyAgain(Bundle data) {
        final String code = data.getString(
                DepinIpc.KEY_T_CODE_DISPLAY);
        // v4.2.4 (audit L-8): the transfer code on a FLAG_SECURE window.
        showSecure(new AlertDialog.Builder(this)
                .setMessage(code + "\n\n" + I18n.get(this,
                        StringsTable
                                .DEPIN_TRANSFER_EXPORTED_AGAIN_NOTE))
                .setPositiveButton(I18n.get(this, StringsTable.BUTTON_OK), null));
        saveStagedOutput(data);
    }

    /** E1: after a successful intake, try to delete the
     *  picked source file through the provider; when that is
     *  not possible, ask the user to delete it. Runs once per
     *  pick. */
    private void attemptDeletePickedPackage() {
        final android.net.Uri uri = pickedPackageUri;
        pickedPackageUri = null;
        if (uri == null) return;
        new Thread(() -> {
            boolean deleted = false;
            try {
                deleted = android.provider.DocumentsContract
                        .deleteDocument(getContentResolver(), uri);
            } catch (Throwable ignored) {
                deleted = false;
            }
            if (!deleted) {
                runOnUiThread(() -> new AlertDialog.Builder(this)
                        .setMessage(I18n.get(this,
                                StringsTable
                                        .DEPIN_TRANSFER_DELETE_SOURCE_PROMPT))
                        .setPositiveButton(I18n.get(this, StringsTable.BUTTON_OK), null)
                        .show());
            }
        }).start();
    }

    private void onExportReply(Bundle data) {
        final String code = data.getString(
                DepinIpc.KEY_T_CODE_DISPLAY);
        // v4.2.4 (audit L-8): the transfer code on a FLAG_SECURE window.
        showSecure(new AlertDialog.Builder(this)
                .setMessage(code)
                .setPositiveButton(I18n.get(this, StringsTable.BUTTON_OK), null));
        saveStagedOutput(data);
    }

    /** Save-picker follow-up for the message outputs an
     *  operation staged: the reply's suggested name, sourced
     *  from the :depin staging slot (or, for the sender
     *  package, the core outbox file named in the reply). */
    private void saveStagedOutput(Bundle data) {
        String name = data.getString(DepinIpc.KEY_T_SAVE_NAME);
        String path = data.getString(DepinIpc.KEY_T_PACKAGE_PATH);
        java.io.File source = path != null
                ? new java.io.File(path)
                : DepinFiles.transferOutputFile(this);
        saveTransferDocument(name, source);
    }

    private void applyTransferButtonStates() {
        final boolean locked = NodeSettings.settingsLocked(this);
        for (Button b : depinTransferButtons) {
            b.setEnabled(!locked && !transferBusy);
        }
        // The ack re-presentation additionally needs a completed
        // send in the history to re-present from.
        if (depinTransferRepresentAckButton != null) {
            depinTransferRepresentAckButton.setEnabled(
                    !locked && !transferBusy && representAckAvailable);
        }
        // The one-shot other-actions exist only in their states.
        if (depinTransferConfirmOneshotButton != null) {
            depinTransferConfirmOneshotButton.setEnabled(
                    !locked && !transferBusy && oneshotConfirmAvailable);
        }
        if (depinTransferCancelOneshotButton != null) {
            depinTransferCancelOneshotButton.setEnabled(
                    !locked && !transferBusy && oneshotSenderOpen);
        }
        if (depinTransferReexportButton != null) {
            depinTransferReexportButton.setEnabled(
                    !locked && !transferBusy && oneshotSenderOpen);
        }
        // The error export mirrors the failure line's visibility
        // (it is not gated on the settings lock: saving a
        // diagnostic file changes no setting or state).
        if (depinTransferSaveErrorButton != null) {
            depinTransferSaveErrorButton.setVisibility(
                    pendingErrorOp != null
                            ? View.VISIBLE : View.GONE);
            depinTransferSaveErrorButton.setEnabled(!transferBusy);
        }
    }

    private void refreshTransferState() {
        if (depinTransferReceiverStateView == null) return;
        applyTransferButtonStates();
        final boolean locked = NodeSettings.settingsLocked(this);
        new Thread(() -> {
            TransferFlow.Snapshot snap;
            try {
                snap = TransferFlow.derive(DepinFiles.depinDir(this));
            } catch (Throwable t) {
                snap = null;
            }
            final TransferFlow.Snapshot fs = snap;
            runOnUiThread(() -> applyTransferSnapshot(fs, locked));
        }, "depin-transfer-state").start();
    }

    /** Apply one derived snapshot to the two role blocks: the
     *  state lines ("what is done, what is next"), the main
     *  buttons' labels and actions, and the lock note's
     *  visibility. All state comes from TransferFlow's reading
     *  of the durable store — including the initialisation
     *  record, whose absence the pre-consolidation status line
     *  could not distinguish from "nothing imported yet" (it
     *  showed "not introduced" even after the introduction had
     *  completed). */
    private void applyTransferSnapshot(TransferFlow.Snapshot snap,
                                       boolean locked) {
        if (depinTransferReceiverStateView == null) return;
        if (depinTransferLockedView != null) {
            depinTransferLockedView.setVisibility(
                    locked ? View.VISIBLE : View.GONE);
        }
        if (snap == null || snap.loadFailed) {
            depinTransferReceiverStateView.setText(I18n.format(
                    this, StringsTable.DEPIN_TRANSFER_FAILED,
                    "transfer state unreadable"));
            depinTransferSenderStateView.setText("");
            currentUnnotifiedStaging = null;
            representAckAvailable = false;
            oneshotSenderOpen = false;
            oneshotConfirmAvailable = false;
            currentOneshotBlockedByLive = false;
            applyTransferButtonStates();
            return;
        }
        currentUnnotifiedStaging = snap.unnotifiedStaging;
        currentTransferActiveDeviceId = snap.activeDeviceId;
        currentReceiverDeviceId = snap.receiverDeviceId;
        currentReceiverGeneration = snap.receiverGeneration;
        currentReceiverPackageSha256 = snap.receiverPackageSha256;
        currentSenderDeviceId = snap.senderDeviceId;
        currentSenderGeneration = snap.senderGeneration;
        currentCompletedSenderGeneration =
                snap.completedSenderGeneration;
        representAckAvailable = snap.sender == TransferFlow.SenderState.DONE
                && snap.completedSenderGeneration >= 0;
        oneshotSenderOpen = snap.senderOneshot;
        // Audit M1: E8's refusal is predictable at stage time —
        // when a DIFFERENT identity is live, the one-shot
        // confirm can only fail, so it is not offered (the
        // button below, and the confirmation dialog's simple
        // choice, which shows the reason instead).
        currentOneshotBlockedByLive = snap.oneshotBlockedByLiveIdentity;
        oneshotConfirmAvailable = !snap.oneshotBlockedByLiveIdentity
                && (snap.receiver
                        == TransferFlow.ReceiverState.STAGED_NOTIFIED
                        || snap.receiver
                        == TransferFlow.ReceiverState.PERMIT_IN);

        // Receiver: state line + the main action it implies.
        String rKey;
        boolean rGenArg = true;
        switch (snap.receiver) {
            case INTRODUCING:
                rKey = StringsTable.DEPIN_TRANSFER_INTRODUCING;
                rGenArg = false;
                receiverMainAction = ACT_IMPORT_PACKAGE;
                break;
            case READY:
                rKey = StringsTable.DEPIN_TRANSFER_RS_READY;
                rGenArg = false;
                receiverMainAction = ACT_IMPORT_PACKAGE;
                break;
            case STAGED_UNNOTIFIED:
                rKey = StringsTable.DEPIN_TRANSFER_RS_STAGED_UNNOTIFIED;
                rGenArg = false;
                receiverMainAction = ACT_RESUME_INTAKE;
                break;
            case STAGED_NOTIFIED:
                rKey = StringsTable.DEPIN_TRANSFER_RS_NOTIFIED;
                receiverMainAction = ACT_EXPORT_NOTICE;
                break;
            case PERMIT_IN:
                rKey = StringsTable.DEPIN_TRANSFER_RS_PERMIT_IN;
                receiverMainAction = ACT_CONFIRM;
                break;
            case PERMIT_EXPIRED:
                rKey = StringsTable.DEPIN_TRANSFER_RS_PERMIT_EXPIRED;
                receiverMainAction = ACT_IMPORT_PERMIT;
                break;
            case TRIAL_CLOSED:
                rKey = StringsTable.DEPIN_TRANSFER_RS_TRIAL_CLOSED;
                receiverMainAction = ACT_EXPORT_ABANDON;
                break;
            case CONFIRMED:
                rKey = StringsTable.DEPIN_TRANSFER_RS_CONFIRMED;
                receiverMainAction = ACT_EXPORT_RECEIPT;
                break;
            case DONE:
                rKey = StringsTable.DEPIN_TRANSFER_RS_DONE;
                receiverMainAction = ACT_IMPORT_PACKAGE;
                break;
            case CLOSED_REFUSAL:
                rKey = StringsTable.DEPIN_TRANSFER_RS_CLOSED_REFUSAL;
                receiverMainAction = ACT_IMPORT_PACKAGE;
                break;
            case CLOSED_CANCEL:
                rKey = StringsTable.DEPIN_TRANSFER_RS_CLOSED_CANCEL;
                receiverMainAction = ACT_IMPORT_PACKAGE;
                break;
            case CLOSED_OTHER:
                rKey = StringsTable.DEPIN_TRANSFER_RS_CLOSED_OTHER;
                receiverMainAction = ACT_IMPORT_PACKAGE;
                break;
            case NOT_INTRODUCED:
            default:
                rKey = StringsTable.DEPIN_TRANSFER_RS_NONE;
                rGenArg = false;
                receiverMainAction = ACT_IMPORT_PACKAGE;
                break;
        }
        // File-production hints: the notice / receipt exports
        // change no durable state, so the derived state alone
        // would offer the export forever and never the intake
        // that follows it. A matching session hint advances the
        // main action to that intake (and the state line says
        // so); the exports stay reachable under "other actions".
        if (snap.receiver == TransferFlow.ReceiverState.STAGED_NOTIFIED
                && hintMatches(hintNoticeDeviceId, hintNoticeGeneration,
                        hintNoticePackageSha256, snap)) {
            rKey = StringsTable.DEPIN_TRANSFER_RS_NOTICE_OUT;
            receiverMainAction = ACT_IMPORT_PERMIT;
        } else if (snap.receiver
                == TransferFlow.ReceiverState.CONFIRMED
                && hintMatches(hintReceiptDeviceId, hintReceiptGeneration,
                        hintReceiptPackageSha256, snap)) {
            rKey = StringsTable.DEPIN_TRANSFER_RS_RECEIPT_OUT;
            receiverMainAction = ACT_IMPORT_ACK;
        }
        if (snap.receiver == TransferFlow.ReceiverState.STAGED_UNNOTIFIED
                && snap.unnotifiedStaging != null
                && snap.unnotifiedStaging.verifiedCount >= 0
                && snap.unnotifiedStaging.checkpointCount > 0) {
            // Section 3.5 progress, derived from the durable
            // resume cursor: how much of the staged journal is
            // already re-verified. This line is what the user
            // watches move while a stage runs (the periodic
            // refresh re-derives it), and what a budget-stopped
            // intake reports alongside the wait guidance.
            String progress = I18n.format(this,
                    StringsTable.DEPIN_TRANSFER_RS_STAGED_PROGRESS,
                    snap.unnotifiedStaging.verifiedCount,
                    snap.unnotifiedStaging.checkpointCount);
            if (snap.unnotifiedStaging.archiveCheckpointCount > 0) {
                // E11: the archive counts are shown separately
                // from the current-format progress.
                progress += "\n" + I18n.format(this,
                        StringsTable.DEPIN_TRANSFER_RS_ARCHIVE_LINE,
                        snap.unnotifiedStaging.archiveCheckpointCount);
            }
            depinTransferReceiverStateView.setText(progress);
        } else if (snap.receiver == TransferFlow.ReceiverState.DONE
                && snap.receiverCommitCode != null) {
            // E4: the confirmation number stays visible in the
            // completed state until the sender side has used it.
            depinTransferReceiverStateView.setText(
                    I18n.format(this, rKey, snap.receiverGeneration)
                            + "\n" + I18n.format(this,
                                    StringsTable
                                            .DEPIN_TRANSFER_RS_COMMIT_CODE,
                                    snap.receiverCommitCode));
        } else {
            depinTransferReceiverStateView.setText(rGenArg
                    ? I18n.format(this, rKey, snap.receiverGeneration)
                    : I18n.get(this, rKey));
        }
        depinTransferReceiverMainButton.setText(I18n.get(this,
                receiverMainLabel(receiverMainAction)));

        // Sender: same treatment.
        String sKey;
        boolean sGenArg = true;
        switch (snap.sender) {
            case INTRODUCING:
                sKey = StringsTable.DEPIN_TRANSFER_INTRODUCING;
                sGenArg = false;
                senderMainAction = ACT_EXPORT_PACKAGE_ONESHOT;
                break;
            case READY:
                sKey = StringsTable.DEPIN_TRANSFER_SS_READY;
                sGenArg = false;
                senderMainAction = ACT_EXPORT_PACKAGE_ONESHOT;
                break;
            case FROZEN_WAIT_NOTICE:
                sKey = StringsTable.DEPIN_TRANSFER_SS_WAIT_NOTICE;
                senderMainAction = ACT_IMPORT_NOTICE;
                break;
            case FROZEN_NOTICE_IN:
                sKey = StringsTable.DEPIN_TRANSFER_SS_NOTICE_IN;
                senderMainAction = ACT_ISSUE_PERMIT;
                break;
            case PERMIT_ISSUED:
                sKey = StringsTable.DEPIN_TRANSFER_SS_PERMIT_ISSUED;
                senderMainAction = ACT_IMPORT_RECEIPT;
                break;
            case FROZEN_ONESHOT:
                sKey = snap.senderCheckpointCount >= 0
                        ? StringsTable.DEPIN_TRANSFER_SS_FROZEN_ONESHOT
                        : StringsTable.DEPIN_TRANSFER_SS_FROZEN_ONESHOT_NC;
                sGenArg = false;
                senderMainAction = ACT_CLOSE_ONESHOT;
                break;
            case DONE:
                sKey = StringsTable.DEPIN_TRANSFER_SS_DONE;
                sGenArg = false;
                senderMainAction = ACT_EXPORT_PACKAGE_ONESHOT;
                break;
            case CLOSED:
                sKey = StringsTable.DEPIN_TRANSFER_SS_CLOSED;
                sGenArg = false;
                senderMainAction = ACT_EXPORT_PACKAGE_ONESHOT;
                break;
            case NOT_INTRODUCED:
            default:
                sKey = StringsTable.DEPIN_TRANSFER_SS_NONE;
                sGenArg = false;
                senderMainAction = ACT_EXPORT_PACKAGE_ONESHOT;
                break;
        }
        if (snap.sender == TransferFlow.SenderState.FROZEN_ONESHOT) {
            // The frozen one-shot line carries the generation
            // and, when the manifest recorded it, the checkpoint
            // count — numbers, not a bare "in progress".
            depinTransferSenderStateView.setText(
                    snap.senderCheckpointCount >= 0
                            ? I18n.format(this, sKey,
                                    snap.senderGeneration,
                                    snap.senderCheckpointCount)
                            : I18n.format(this, sKey,
                                    snap.senderGeneration));
        } else {
            depinTransferSenderStateView.setText(sGenArg
                    ? I18n.format(this, sKey, snap.senderGeneration)
                    : I18n.get(this, sKey));
        }
        depinTransferSenderMainButton.setText(I18n.get(this,
                senderMainLabel(senderMainAction)));
        applyTransferButtonStates();
    }

    private static boolean hintMatches(String deviceId, long generation,
                                       String packageSha256,
                                       TransferFlow.Snapshot snap) {
        return deviceId != null
                && deviceId.equals(snap.receiverDeviceId)
                && generation == snap.receiverGeneration
                && packageSha256 != null
                && packageSha256.equals(snap.receiverPackageSha256);
    }

    /** The label a main action carries: the operation's own
     *  string, so the button reads exactly like the step it
     *  runs. */
    private String receiverMainLabel(int action) {
        switch (action) {
            case ACT_RESUME_INTAKE:
                return StringsTable.DEPIN_TRANSFER_RESUME_INTAKE;
            case ACT_EXPORT_NOTICE:
                return StringsTable.DEPIN_TRANSFER_EXPORT_NOTICE;
            case ACT_IMPORT_PERMIT:
                return StringsTable.DEPIN_TRANSFER_IMPORT_PERMIT;
            case ACT_CONFIRM:
                return StringsTable.DEPIN_TRANSFER_CONFIRM_BUTTON;
            case ACT_EXPORT_ABANDON:
                return StringsTable.DEPIN_TRANSFER_EXPORT_ABANDON;
            case ACT_EXPORT_RECEIPT:
                return StringsTable.DEPIN_TRANSFER_EXPORT_RECEIPT;
            case ACT_IMPORT_ACK:
                return StringsTable.DEPIN_TRANSFER_IMPORT_ACK;
            case ACT_IMPORT_PACKAGE:
            default:
                return StringsTable.DEPIN_TRANSFER_IMPORT_PACKAGE;
        }
    }

    private String senderMainLabel(int action) {
        switch (action) {
            case ACT_IMPORT_NOTICE:
                return StringsTable.DEPIN_TRANSFER_IMPORT_NOTICE;
            case ACT_ISSUE_PERMIT:
                return StringsTable.DEPIN_TRANSFER_ISSUE_PERMIT;
            case ACT_IMPORT_RECEIPT:
                return StringsTable.DEPIN_TRANSFER_IMPORT_RECEIPT;
            case ACT_CLOSE_ONESHOT:
                return StringsTable.DEPIN_TRANSFER_CLOSE_ONESHOT;
            case ACT_EXPORT_PACKAGE:
                return StringsTable.DEPIN_TRANSFER_EXPORT_PACKAGE;
            case ACT_EXPORT_PACKAGE_ONESHOT:
            default:
                return StringsTable.DEPIN_TRANSFER_EXPORT_PACKAGE_ONESHOT;
        }
    }

    private void runTransferAction(int action) {
        if (action == ACT_SAVE_ERROR) {
            // Saving the diagnostic file changes nothing and is
            // offered precisely when something else just failed;
            // it is not gated on the settings lock.
            saveTransferErrorFile();
            return;
        }
        if (NodeSettings.settingsLocked(this)) {
            refreshTransferState();
            return;
        }
        switch (action) {
            case ACT_IMPORT_PACKAGE:
                openTransferDocument(T_OPEN_PACKAGE);
                break;
            case ACT_IMPORT_PERMIT:
                openTransferDocument(T_OPEN_PERMIT);
                break;
            case ACT_IMPORT_ACK:
                openTransferDocument(T_OPEN_ACK);
                break;
            case ACT_IMPORT_NOTICE:
                openTransferDocument(T_OPEN_NOTICE);
                break;
            case ACT_IMPORT_RECEIPT:
                openTransferDocument(T_OPEN_RECEIPT);
                break;
            case ACT_IMPORT_ABANDON:
                openTransferDocument(T_OPEN_ABANDON);
                break;
            case ACT_IMPORT_CANCEL_TOKEN:
                openTransferDocument(T_OPEN_CANCEL_TOKEN);
                break;
            case ACT_EXPORT_ABANDON:
                transferOp(DepinIpc.T_OP_EXPORT_ABANDON, null,
                        this::saveStagedOutput);
                break;
            case ACT_EXPORT_NOTICE:
                transferOp(DepinIpc.T_OP_EXPORT_NOTICE, null, data -> {
                    hintNoticeDeviceId = currentReceiverDeviceId;
                    hintNoticeGeneration = currentReceiverGeneration;
                    hintNoticePackageSha256 =
                            currentReceiverPackageSha256;
                    saveStagedOutput(data);
                });
                break;
            case ACT_CONFIRM:
                transferOp(DepinIpc.T_OP_CONFIRM, null,
                        data -> attemptDeletePickedPackage());
                break;
            case ACT_EXPORT_RECEIPT:
                transferOp(DepinIpc.T_OP_EXPORT_RECEIPT, null, data -> {
                    hintReceiptDeviceId = currentReceiverDeviceId;
                    hintReceiptGeneration = currentReceiverGeneration;
                    hintReceiptPackageSha256 =
                            currentReceiverPackageSha256;
                    saveStagedOutput(data);
                });
                break;
            case ACT_RESUME_INTAKE:
                resumeStagedIntake();
                break;
            case ACT_REPRESENT_ACK: {
                Bundle params = new Bundle();
                params.putLong(DepinIpc.KEY_T_GENERATION,
                        currentCompletedSenderGeneration);
                transferOp(DepinIpc.T_OP_REPRESENT_ACK, params,
                        this::saveStagedOutput);
                break;
            }
            case ACT_EXPORT_PACKAGE:
                transferOp(DepinIpc.T_OP_EXPORT_PACKAGE, null,
                        this::onExportReply);
                break;
            case ACT_EXPORT_PACKAGE_ONESHOT:
                transferOp(DepinIpc.T_OP_EXPORT_PACKAGE_ONESHOT, null,
                        this::onExportReply);
                break;
            case ACT_CONFIRM_ONESHOT:
                // From "other actions" on a notified record: the
                // all-values confirmation was shown at stage
                // time; ask once more, plainly, then confirm in
                // one-shot mode (no notice / permit / receipt).
                new AlertDialog.Builder(this)
                        .setTitle(I18n.get(this,
                                StringsTable
                                        .DEPIN_TRANSFER_CONFIRM_ONESHOT_TITLE))
                        .setMessage(I18n.format(this,
                                StringsTable
                                        .DEPIN_TRANSFER_CONFIRM_ONESHOT_BODY,
                                currentReceiverDeviceId,
                                currentReceiverGeneration))
                        .setPositiveButton(I18n.get(this,
                                StringsTable.DEPIN_TRANSFER_CONFIRM_ONESHOT),
                                (d, w) -> transferOp(
                                        DepinIpc.T_OP_CONFIRM_ONESHOT,
                                        null, this::onConfirmOneshotReply))
                        .setNegativeButton(I18n.get(this, StringsTable.BUTTON_CANCEL), null)
                        .show();
                break;
            case ACT_CLOSE_ONESHOT: {
                // E4: the erasure requires the confirmation
                // number the RECEIVER displayed after its
                // confirm completed. A bare tap erases
                // nothing; a wrong number erases nothing (the
                // core checks it against the stored key).
                final android.widget.EditText codeInput =
                        new android.widget.EditText(this);
                codeInput.setInputType(
                        android.text.InputType.TYPE_CLASS_NUMBER);
                codeInput.setHint("12345678");
                new AlertDialog.Builder(this)
                        .setTitle(I18n.get(this,
                                StringsTable
                                        .DEPIN_TRANSFER_CLOSE_ONESHOT_TITLE))
                        .setMessage(I18n.format(this,
                                StringsTable
                                        .DEPIN_TRANSFER_CLOSE_ONESHOT_BODY,
                                currentSenderDeviceId))
                        .setView(codeInput)
                        .setPositiveButton(I18n.get(this,
                                StringsTable.DEPIN_TRANSFER_CLOSE_ONESHOT),
                                (d, w) -> {
                                    Bundle params = new Bundle();
                                    params.putString(
                                            DepinIpc.KEY_T_COMMIT_CODE,
                                            codeInput.getText().toString()
                                                    .replace(" ", ""));
                                    transferOp(
                                            DepinIpc.T_OP_CLOSE_ONESHOT_SENDER,
                                            params, dd -> {
                                                new AlertDialog.Builder(this)
                                                        .setMessage(I18n.get(this,
                                                                StringsTable
                                                                        .DEPIN_TRANSFER_ONESHOT_CLOSED_MSG))
                                                        .setPositiveButton(
                                                                I18n.get(this, StringsTable.BUTTON_OK),
                                                                null)
                                                        .show();
                                            });
                                })
                        .setNegativeButton(I18n.get(this, StringsTable.BUTTON_CANCEL), null)
                        .show();
                break;
            }
            case ACT_REEXPORT_ONESHOT:
                // E7: a fresh file + code from the same frozen
                // tip; the previous file remains valid.
                transferOp(DepinIpc.T_OP_EXPORT_PACKAGE_ONESHOT_AGAIN,
                        null, this::onExportReplyAgain);
                break;
            case ACT_CANCEL_ONESHOT:
                new AlertDialog.Builder(this)
                        .setTitle(I18n.get(this,
                                StringsTable
                                        .DEPIN_TRANSFER_CANCEL_ONESHOT_TITLE))
                        .setMessage(I18n.get(this,
                                StringsTable
                                        .DEPIN_TRANSFER_CANCEL_ONESHOT_BODY))
                        .setPositiveButton(I18n.get(this,
                                StringsTable.DEPIN_TRANSFER_CANCEL_ONESHOT),
                                (d, w) -> transferOp(
                                        DepinIpc.T_OP_CANCEL_ONESHOT_SENDER,
                                        null, null))
                        .setNegativeButton(I18n.get(this, StringsTable.BUTTON_CANCEL), null)
                        .show();
                break;
            case ACT_ISSUE_PERMIT:
                transferOp(DepinIpc.T_OP_ISSUE_PERMIT, null,
                        this::saveStagedOutput);
                break;
            case ACT_CANCEL_TRANSFER:
                transferOp(DepinIpc.T_OP_CANCEL_SENDER, null,
                        this::saveStagedOutput);
                break;
            default:
                break;
        }
    }

    private void openTransferDocument(int kind) {
        pendingTransferOpen = kind;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        startActivityForResult(intent, REQ_TRANSFER_OPEN);
    }

    private void saveTransferDocument(String name,
                                      java.io.File source) {
        pendingTransferSaveFile = source;
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, name);
        startActivityForResult(intent, REQ_TRANSFER_SAVE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode,
                                    Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_EXPORT_SAVE) {
            // Bundle export (step 5): a cancelled picker drops
            // the staged bundle, like the companion clears its
            // pending export; a picked destination receives it.
            if (resultCode != Activity.RESULT_OK || data == null
                    || data.getData() == null) {
                DepinBundleExport.discardStaged(this);
                setExportBusy(false);
                return;
            }
            finishBundleSave(data.getData());
            return;
        }
        if (resultCode != Activity.RESULT_OK || data == null
                || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        if (requestCode == REQ_TRANSFER_SAVE) {
            final java.io.File source = pendingTransferSaveFile;
            pendingTransferSaveFile = null;
            if (source == null) return;
            // Stream the staged file to the picked destination
            // (the pending-bundle save pattern): plain file I/O
            // on the UI work executor, no transfer operation.
            transferBusy = true;
            applyTransferButtonStates();
            depinWorkExecutor.execute(() -> {
                try (java.io.InputStream in =
                             new java.io.FileInputStream(source);
                     java.io.OutputStream out =
                             getContentResolver().openOutputStream(uri)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                    }
                    out.flush();
                } catch (Throwable t) {
                    final String msg = t.getClass().getSimpleName()
                            + ": " + t.getMessage();
                    runOnUiThread(() -> {
                        transferBusy = false;
                        captureTransferError(
                                app.quantusnode.node.depin
                                        .TransferErrorReport.PHASE_FILE,
                                null, null,
                                app.quantusnode.node.depin
                                        .TransferErrorReport.classify(t));
                        showTransferFailure(msg);
                    });
                    return;
                }
                runOnUiThread(() -> {
                    transferBusy = false;
                    refreshTransferState();
                });
            });
            return;
        }
        if (requestCode != REQ_TRANSFER_OPEN) return;
        final int kind = pendingTransferOpen;
        if (kind == T_OPEN_PACKAGE && data != null
                && data.getData() != null) {
            pickedPackageUri = data.getData();
        }
        // Read the picked file (with the same size cap as the
        // in-process flow) on the UI work executor, stage it as
        // the :depin process's input, then dispatch the picked
        // kind's operation. Failures of the read itself land on
        // the state line exactly as the in-process catch did.
        transferBusy = true;
        applyTransferButtonStates();
        depinWorkExecutor.execute(() -> {
            try {
                // v4.2.4 (audit L-10): stream the pick into the input
                // slot under the transfer cap (16 MiB), one buffer at
                // a time. Until v4.2.3 the whole pick was buffered
                // here first, in the process that also hosts the
                // node's service, so a large file exhausted its heap
                // long before the (then 512 MB) cap. Past the cap the
                // copy stops at once and the slot keeps its previous
                // content.
                try (java.io.InputStream in =
                             getContentResolver().openInputStream(uri)) {
                    app.quantusnode.node.depin.core.TransferIo
                            .copyToFileCapped(in,
                                    DepinFiles.transferInputFile(this),
                                    app.quantusnode.node.depin.core
                                            .TransferPackage.MAX_PACKAGE_BYTES);
                } catch (app.quantusnode.node.depin.core.TransferIo
                        .CapExceededException e) {
                    throw new IllegalStateException(
                            "file exceeds the transfer size limit");
                }
            } catch (Throwable t) {
                final String msg = t.getClass().getSimpleName()
                        + ": " + t.getMessage();
                runOnUiThread(() -> {
                    transferBusy = false;
                    captureTransferError(
                            app.quantusnode.node.depin
                                    .TransferErrorReport.PHASE_FILE,
                            null, null,
                            app.quantusnode.node.depin
                                    .TransferErrorReport.classify(t));
                    showTransferFailure(msg);
                });
                return;
            }
            runOnUiThread(() -> {
                transferBusy = false;
                dispatchPicked(kind);
            });
        });
    }

    /** Dispatch the operation for a picked file whose bytes
     *  are already staged as the transfer input. */
    private void dispatchPicked(int kind) {
        switch (kind) {
            case T_OPEN_PACKAGE:
                handlePickedPackage();
                break;
            case T_OPEN_PERMIT:
                transferOp(DepinIpc.T_OP_INTAKE_PERMIT, null, null);
                break;
            case T_OPEN_ACK:
                transferOp(DepinIpc.T_OP_INTAKE_ACK, null, null);
                break;
            case T_OPEN_NOTICE:
                transferOp(DepinIpc.T_OP_INTAKE_NOTICE, null, null);
                break;
            case T_OPEN_RECEIPT:
                transferOp(DepinIpc.T_OP_INTAKE_RECEIPT, null,
                        this::saveStagedOutput);
                break;
            case T_OPEN_ABANDON:
                transferOp(DepinIpc.T_OP_INTAKE_ABANDON, null, null);
                break;
            case T_OPEN_CANCEL_TOKEN:
                transferOp(DepinIpc.T_OP_INTAKE_CANCEL_TOKEN,
                        null, null);
                break;
            default:
                break;
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt("transfer_pending_open", pendingTransferOpen);
        if (pendingTransferSaveFile != null) {
            outState.putString("transfer_pending_save",
                    pendingTransferSaveFile.getAbsolutePath());
        }
    }

    /** Package picked (its bytes are already staged as the
     *  transfer input): ask for the code, stage it in the
     *  :depin process, show the full-value confirmation; only
     *  the proceed button creates the notified R1 (design
     *  sections 4.3, 5.3). */
    private void handlePickedPackage() {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        // v4.2.4 (audit L-8): the inner-hash editor's protections for
        // the transfer code, the one secret guarding the identity seed
        // inside a package: no suggestions and no keyboard learning
        // (a learned, possibly cloud-synced dictionary would keep it),
        // no autofill capture, no full-screen extract editor, and a
        // FLAG_SECURE window; the typed text does not stay in the view.
        input.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE
                | EditorInfo.IME_FLAG_NO_EXTRACT_UI
                | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        AlertDialog dialog = showSecure(new AlertDialog.Builder(this)
                .setTitle(I18n.get(this,
                        StringsTable.DEPIN_TRANSFER_CODE_PROMPT))
                .setView(input)
                .setPositiveButton(I18n.get(this, StringsTable.BUTTON_OK),
                        (d, w) -> {
                            Bundle params = new Bundle();
                            params.putString(DepinIpc.KEY_T_CODE,
                                    input.getText().toString());
                            transferOp(DepinIpc.T_OP_STAGE_PACKAGE,
                                    params, this::onStageReply);
                        })
                .setNegativeButton(I18n.get(this, StringsTable.BUTTON_CANCEL), null));
        // The positive button's listener has read the code by the
        // time the dialog is dismissed.
        dialog.setOnDismissListener(d -> input.setText(""));
    }

    /** A finished stage's reply, shared by the fresh intake and
     *  the resume: hold the result for the confirmation dialog
     *  and show the full-value confirmation. */
    private void onStageReply(Bundle data) {
        app.quantusnode.node.depin.core.TransferReceiver.IntakeResult info =
                new app.quantusnode.node.depin.core.TransferReceiver
                        .IntakeResult();
        info.deviceId = data.getString(DepinIpc.KEY_T_DEVICE_ID);
        info.generation = data.getLong(DepinIpc.KEY_T_GENERATION);
        info.packageSha256 = data.getString(
                DepinIpc.KEY_T_PACKAGE_SHA256);
        info.checkpointCount = data.getLong(DepinIpc.KEY_T_CHECKPOINTS);
        info.firstTimestampUnix = data.getLong(DepinIpc.KEY_T_FIRST_TS);
        info.tailTimestampUnix = data.getLong(DepinIpc.KEY_T_TAIL_TS);
        pendingStaged = info;
        showStagedConfirmation(info);
    }

    /** Resume an interrupted intake (the STAGED_UNNOTIFIED main
     *  action): copy the staging's retained package copy into
     *  the IPC input slot and re-issue the stage. The core
     *  takes its resume branch — no re-expansion, the transfer
     *  code is not consulted, verification continues from the
     *  durable section 3.5 cursor — and the full-value
     *  confirmation dialog follows as usual. This is the route
     *  that did not exist when a paused or interrupted stage
     *  stranded the first production attempt: the staging was
     *  durable on disk, but only volatile Activity state led
     *  back to it. */
    private void resumeStagedIntake() {
        final TransferFlow.StagingRef ref = currentUnnotifiedStaging;
        if (ref == null || ref.packageFile == null) {
            refreshTransferState();
            return;
        }
        transferBusy = true;
        applyTransferButtonStates();
        depinWorkExecutor.execute(() -> {
            try {
                // v4.2.4 (audit L-10): streamed, like the pick above,
                // so the main process never holds the package.
                try (java.io.InputStream in =
                             new java.io.FileInputStream(ref.packageFile)) {
                    app.quantusnode.node.depin.core.TransferIo
                            .copyToFileCapped(in,
                                    DepinFiles.transferInputFile(this),
                                    app.quantusnode.node.depin.core
                                            .TransferPackage.MAX_PACKAGE_BYTES);
                }
            } catch (Throwable t) {
                final String msg = t.getClass().getSimpleName()
                        + ": " + t.getMessage();
                runOnUiThread(() -> {
                    transferBusy = false;
                    captureTransferError(
                            app.quantusnode.node.depin
                                    .TransferErrorReport.PHASE_FILE,
                            null, "receiver",
                            app.quantusnode.node.depin
                                    .TransferErrorReport.classify(t));
                    showTransferFailure(msg);
                });
                return;
            }
            runOnUiThread(() -> {
                transferBusy = false;
                Bundle params = new Bundle();
                params.putString(DepinIpc.KEY_T_CODE, "");
                transferOp(DepinIpc.T_OP_STAGE_PACKAGE, params,
                        this::onStageReply);
            });
        });
    }

    private void showStagedConfirmation(
            app.quantusnode.node.depin.core.TransferReceiver.IntakeResult info) {
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat(
                "yyyy-MM-dd HH:mm", java.util.Locale.US);
        String body = I18n.format(this,
                StringsTable.DEPIN_TRANSFER_CONFIRM_BODY,
                info.deviceId,
                info.checkpointCount,
                fmt.format(new java.util.Date(info.firstTimestampUnix * 1000L)),
                fmt.format(new java.util.Date(info.tailTimestampUnix * 1000L)),
                info.packageSha256);
        // Audit M1: with a DIFFERENT identity live on this
        // device, the simple (one-shot) choice can only be
        // refused at confirm time (E8) — so the dialog does
        // not offer it and says why, pointing at the strict
        // proceed button, which quarantines the live identity
        // safely instead of displacing it.
        final boolean oneshotBlocked = currentOneshotBlockedByLive
                || (currentTransferActiveDeviceId != null
                        && !currentTransferActiveDeviceId
                                .equals(info.deviceId));
        if (oneshotBlocked) {
            body = body + "\n\n" + I18n.get(this,
                    StringsTable.DEPIN_TRANSFER_ONESHOT_BLOCKED_LIVE);
        }
        final String dialogBody = body;
        runOnUiThread(() -> {
            AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle(I18n.get(this,
                        StringsTable.DEPIN_TRANSFER_CONFIRM_TITLE))
                .setMessage(dialogBody)
                .setPositiveButton(I18n.get(this,
                        StringsTable.DEPIN_TRANSFER_PROCEED),
                        (d, w) -> {
                            app.quantusnode.node.depin.core.TransferReceiver
                                    .IntakeResult cur = pendingStaged;
                            if (cur == null) {
                                // The Activity was recreated while
                                // the dialog was up and the held
                                // result went with it. The staged
                                // intake itself is durable: resume
                                // it from the retained copy (which
                                // re-shows this confirmation) instead
                                // of silently dropping the tap — the
                                // exact dead end the first production
                                // attempt hit.
                                resumeStagedIntake();
                                return;
                            }
                            Bundle params = new Bundle();
                            params.putString(DepinIpc.KEY_T_DEVICE_ID,
                                    cur.deviceId);
                            params.putLong(DepinIpc.KEY_T_GENERATION,
                                    cur.generation);
                            params.putString(
                                    DepinIpc.KEY_T_PACKAGE_SHA256,
                                    cur.packageSha256);
                            pendingStaged = null;
                            transferOp(DepinIpc.T_OP_COMPLETE_INTAKE,
                                    params, null);
                        });
            if (!oneshotBlocked) {
                builder.setNeutralButton(I18n.get(this,
                        StringsTable.DEPIN_TRANSFER_CONFIRM_ONESHOT),
                        (d, w) -> {
                            // The simple mode's confirm, taken on
                            // this same all-values dialog: the
                            // service completes the intake and
                            // confirms in one operation — no
                            // notice, permit, receipt or ack.
                            app.quantusnode.node.depin.core.TransferReceiver
                                    .IntakeResult cur = pendingStaged;
                            if (cur == null) {
                                resumeStagedIntake();
                                return;
                            }
                            Bundle params = new Bundle();
                            params.putString(DepinIpc.KEY_T_DEVICE_ID,
                                    cur.deviceId);
                            params.putLong(DepinIpc.KEY_T_GENERATION,
                                    cur.generation);
                            params.putString(
                                    DepinIpc.KEY_T_PACKAGE_SHA256,
                                    cur.packageSha256);
                            pendingStaged = null;
                            transferOp(DepinIpc.T_OP_CONFIRM_ONESHOT,
                                    params, this::onConfirmOneshotReply);
                        });
            }
            builder.setNegativeButton(I18n.get(this,
                    StringsTable.DEPIN_TRANSFER_ABORT), null)
                    .show();
        });
    }
}
