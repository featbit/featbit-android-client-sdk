package co.featbit.sample.java;

import static co.featbit.sample.java.CafeModel.*;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import co.featbit.android.api.*;
import co.featbit.android.testing.*;
import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

/** Application-owned Java integration. SDK completions marshal to main; no blocking waits. */
final class SampleSession {
    final ScreenState state = new ScreenState();
    final List<FlagSpec> specs = new ArrayList<>();
    final List<Person> people = new ArrayList<>();
    final ConnectionDraft draft = new ConnectionDraft();
    String destination = "Demo", detailKey, editorKey, editorText = "", filter = "";
    boolean formOpen, userSheet;
    int userChoice, scrollY;
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, String> localValues = new LinkedHashMap<>();
    private final List<Runnable> listeners = new ArrayList<>();
    private FeatBitClient client;
    private TestData data;
    private Registration changes, statusRegistration;
    private long generation, readyVersion;
    private boolean started;

    SampleSession(Context context) {
        this.context = context.getApplicationContext();
        for (JsonElement e : asset("demo-flags.json")) {
            FlagSpec f = new FlagSpec(e.getAsJsonObject());
            specs.add(f);
            localValues.put(f.key, f.initial);
        }
        for (JsonElement e : asset("users.json")) people.add(new Person(e.getAsJsonObject()));
    }

    private JsonArray asset(String name) {
        try (Reader reader =
                new InputStreamReader(context.getAssets().open(name), StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Missing shared sample asset: " + name, ex);
        }
    }

    Registration observe(Runnable listener) {
        listeners.add(listener);
        listener.run();
        return () -> listeners.remove(listener);
    }

    private void changed() {
        state.revision++;
        for (Runnable r : new ArrayList<>(listeners)) r.run();
    }

    void start() {
        if (!started) {
            started = true;
            connect();
        }
    }

    private String now() {
        return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
    }

    private void log(String message) {
        state.history.add(new ActivityEntry(now(), message));
        while (state.history.size() > 100) state.history.remove(0);
        changed();
    }

    void dismissMessage() {
        state.message = null;
        changed();
    }

    void invalidateReads() {
        for (ReadRecord r : state.reads.values()) r.stale = true;
    }

    String sdkVersion() {
        return client == null ? SdkInfo.getVersion() : client.getVersion();
    }

    FlagSpec spec(String key) {
        for (FlagSpec s : specs) if (s.key.equals(key)) return s;
        return null;
    }

    String localValue(String key) {
        return localValues.containsKey(key) ? localValues.get(key) : spec(key).initial;
    }

    private List<BootstrapFlag> records(Map<String, String> values) {
        List<BootstrapFlag> result = new ArrayList<>();
        for (FlagSpec f : specs)
            if (values.containsKey(f.key))
                result.add(BootstrapFlag.create(f.key, values.get(f.key), f.type).getValue());
        return result;
    }

    boolean validateValue(FlagSpec spec, String value) {
        try {
            switch (spec.type) {
                case NUMBER:
                    return Double.isFinite(Double.parseDouble(value));
                case BOOLEAN:
                    return value.equals("true") || value.equals("false");
                case JSON:
                    json(value);
                    break;
                case STRING:
                    break;
            }
            return BootstrapFlag.create(spec.key, value, spec.type).isSuccess();
        } catch (RuntimeException ex) {
            return false;
        }
    }

    Map<String, String> validateDraft() {
        Map<String, String> errors = new LinkedHashMap<>();
        if (draft.local) return errors;
        if (draft.key.trim().isEmpty()) errors.put("sdkKey", "Client SDK Key is required");
        boolean streaming = draft.mode == SyncMode.STREAMING;
        if ((streaming ? draft.streaming : draft.polling).trim().isEmpty())
            errors.put(
                    streaming ? "streamingUrl" : "pollingUrl", "Synchronization URL is required");
        if (draft.events && draft.eventsUrl.trim().isEmpty())
            errors.put("eventsUrl", "Events URL is required");
        if (streaming && draft.pollingFallback && draft.polling.trim().isEmpty())
            errors.put("pollingUrl", "Polling URL is required when fallback is enabled");
        if (errors.isEmpty()) {
            Outcome<ClientOptions> o = liveOptions().build();
            if (!o.isSuccess())
                errors.put(
                        o.getDiagnostic() != null && o.getDiagnostic().getField() != null
                                ? o.getDiagnostic().getField()
                                : "form",
                        "Invalid configuration: " + diagnostic(o));
        }
        return errors;
    }

    private ClientOptions.Builder liveOptions() {
        ClientOptions.Builder b =
                ClientOptions.builder()
                        .user(people.get(state.user).user())
                        .sdkKey(draft.key)
                        .mode(draft.mode)
                        .disableEvents(!draft.events);
        if (draft.mode == SyncMode.STREAMING) {
            b.streamingUrl(draft.streaming).pollingFallback(draft.pollingFallback);
            if (draft.pollingFallback) b.pollingUrl(draft.polling);
        } else b.pollingUrl(draft.polling).pollingFallback(false);
        if (draft.events) b.eventsUrl(draft.eventsUrl);
        return b;
    }

    static String result(Outcome<?> o) {
        return String.valueOf(o.isSuccess() && o.getValue() != null ? o.getValue() : o.getCode());
    }

    private static String diagnostic(Outcome<?> o) {
        return o.getDiagnostic() == null ? o.getCode().toString() : o.getDiagnostic().getCode();
    }

    /**
     * Detaching an observer never cancels SDK work. On registration rejection retry observation of
     * the same operation, checking its settled result; never submit a duplicate operation.
     */
    private <T> void settled(Operation<T> op, Consumer<Outcome<T>> callback) {
        class Wait implements Runnable {
            Registration registration;
            boolean done;

            void complete(Outcome<T> outcome) {
                if (done) return;
                done = true;
                main.removeCallbacks(this);
                if (registration != null) registration.close();
                callback.accept(outcome);
            }

            @Override
            public void run() {
                Outcome<T> existing = op.getResult();
                if (existing != null) {
                    complete(existing);
                    return;
                }
                Outcome<Registration> observing = op.observe(o -> main.post(() -> complete(o)));
                if (observing.isSuccess()) registration = observing.getValue();
                else {
                    state.message = "Operation observation unavailable: " + observing.getCode();
                    changed();
                    main.postDelayed(this, 100);
                }
            }
        }
        new Wait().run();
    }

    void connect() {
        if (state.busy != null || state.flushPending) return;
        Map<String, String> errors = validateDraft();
        if (!errors.isEmpty()) {
            state.message = errors.values().iterator().next();
            changed();
            return;
        }
        boolean nextLocal = draft.local, nextEvents = !nextLocal && draft.events;
        TestData nextData =
                nextLocal
                        ? TestDataFactory.getDefault().create(records(localValues)).getValue()
                        : null;
        Outcome<ClientOptions> options =
                nextLocal
                        ? (nextData == null
                                ? null
                                : nextData.clientOptions(people.get(state.user).user()))
                        : liveOptions().build();
        if (options == null || !options.isSuccess()) {
            state.message = "Invalid configuration";
            changed();
            return;
        }
        long revision = ++generation;
        readyVersion++;
        if (changes != null) changes.close();
        if (statusRegistration != null) statusRegistration.close();
        changes = null;
        statusRegistration = null;
        invalidateReads();
        state.busy = "Reconnecting…";
        state.message = null;
        state.waitTimedOut = false;
        state.active = false;
        changed();
        FeatBitClient old = client;
        client = null;
        Runnable create =
                () ->
                        settled(
                                ClientFactory.getDefault().create(context, options.getValue()),
                                made -> {
                                    if (revision != generation) {
                                        if (made.getValue() != null) made.getValue().close();
                                        return;
                                    }
                                    state.busy = null;
                                    if (!made.isSuccess()) {
                                        state.active = false;
                                        state.status = null;
                                        state.snapshot = Collections.emptyMap();
                                        state.business = new Business();
                                        state.message = "Connection failed: " + diagnostic(made);
                                        log("Create failed: " + made.getCode());
                                        return;
                                    }
                                    FeatBitClient c = made.getValue();
                                    client = c;
                                    data = nextData;
                                    state.local = nextLocal;
                                    state.events = nextEvents;
                                    state.active = true;
                                    state.status = c.getConnectionInformation();
                                    state.lastTrack = null;
                                    state.lastFlush = null;
                                    Outcome<ChangeSubscription> registered =
                                            c.subscribeChanges(
                                                    change ->
                                                            main.post(
                                                                    () -> {
                                                                        if (generation
                                                                                == revision) {
                                                                            refresh(c);
                                                                            invalidateReads();
                                                                            log("Flags updated");
                                                                        }
                                                                    }));
                                    if (registered.isSuccess()) {
                                        changes = registered.getValue().getRegistration();
                                        state.snapshot = registered.getValue().getInitialValues();
                                    } else
                                        state.message =
                                                "Flag subscription failed: " + registered.getCode();
                                    Outcome<Registration> statuses =
                                            c.subscribeStatus(
                                                    info ->
                                                            main.post(
                                                                    () -> {
                                                                        if (generation
                                                                                == revision) {
                                                                            state.status = info;
                                                                            if (info
                                                                                    .getRemoteConfirmed())
                                                                                state.waitTimedOut =
                                                                                        false;
                                                                            changed();
                                                                        }
                                                                    }));
                                    if (statuses.isSuccess())
                                        statusRegistration = statuses.getValue();
                                    else
                                        state.message =
                                                "Status observation unavailable: "
                                                        + statuses.getCode();
                                    refresh(c);
                                    log(
                                            nextLocal
                                                    ? "Local demo ready"
                                                    : "Client created; waiting for remote data");
                                    long wait = ++readyVersion;
                                    settled(
                                            c.awaitReady(5000),
                                            ready -> {
                                                if (revision == generation
                                                        && wait == readyVersion) {
                                                    state.waitTimedOut =
                                                            ready.getCode()
                                                                    == OutcomeCode.TIMED_OUT;
                                                    log("Readiness: " + result(ready));
                                                }
                                            });
                                });
        if (old == null) create.run();
        else
            settled(
                    old.close(),
                    closed -> {
                        if (!closed.isSuccess()
                                || closed.getValue() == null
                                || !closed.getValue().getCleanupComplete()
                                || closed.getValue().getUndeliveredEvents() > 0) {
                            String warning =
                                    "Close: "
                                            + closed.getCode()
                                            + "; undelivered="
                                            + (closed.getValue() == null
                                                    ? "unknown"
                                                    : closed.getValue().getUndeliveredEvents())
                                            + "; cleanup="
                                            + (closed.getValue() == null
                                                    ? "unknown"
                                                    : closed.getValue().getCleanupComplete());
                            state.message = warning;
                            log(warning);
                        }
                        create.run();
                    });
    }

    private void refresh(FeatBitClient c) {
        state.snapshot = c.allVariations();
        EvaluationDetail<Boolean> compact = c.boolVariationDetail(specs.get(0).key, false);
        EvaluationDetail<String> promo =
                c.stringVariationDetail(specs.get(1).key, specs.get(1).fallback);
        EvaluationDetail<Double> discount = c.numberVariationDetail(specs.get(2).key, 0.0);
        EvaluationDetail<String> raw =
                c.jsonTextVariationDetail(specs.get(3).key, specs.get(3).fallback);
        Business b = new Business();
        b.compact = compact.getValue();
        b.promo = promo.getValue();
        b.discountInvalid =
                !Double.isFinite(discount.getValue())
                        || discount.getValue() < 0
                        || discount.getValue() > 100;
        b.discount = b.discountInvalid ? 0 : discount.getValue();
        Menu parsed = Business.menu(raw.getValue());
        b.menuInvalid = parsed == null;
        b.menu = parsed == null ? Business.DEFAULT_MENU : parsed;
        b.usingFallback =
                compact.getReason() != EvaluationReason.MATCH
                        || promo.getReason() != EvaluationReason.MATCH
                        || discount.getReason() != EvaluationReason.MATCH
                        || raw.getReason() != EvaluationReason.MATCH;
        state.business = b;
        if (!b.menu.contains(state.selectedSize)) state.selectedSize = b.menu.defaultSize;
        state.status = c.getConnectionInformation();
        changed();
    }

    void selectSize(String id) {
        if (state.business.menu.contains(id)) {
            state.selectedSize = id;
            changed();
        }
    }

    void identify(int index) {
        FeatBitClient c = client;
        if (c == null || !state.available() || state.flushPending || index == state.user) return;
        readyVersion++;
        Operation<ReadyResult> op = c.identify(people.get(index).user(), 5000);
        Outcome<ReadyResult> immediate = op.getResult();
        if (immediate != null
                && (immediate.getCode() == OutcomeCode.INVALID
                        || immediate.getCode() == OutcomeCode.CLOSED
                        || immediate.getCode() == OutcomeCode.CAPACITY_EXCEEDED)) {
            state.message = "User change rejected: " + immediate.getCode();
            changed();
            return;
        }
        long revision = generation;
        invalidateReads();
        state.user = index;
        state.busy = "Switching to " + people.get(index).name + "…";
        state.waitTimedOut = false;
        refresh(c);
        settled(
                op,
                o -> {
                    if (revision == generation) {
                        userSheet = false;
                        state.busy = null;
                        state.waitTimedOut = o.getCode() == OutcomeCode.TIMED_OUT;
                        state.message = "Identify: " + result(o);
                        refresh(c);
                        log("Identify " + people.get(index).name + ": " + result(o));
                    }
                });
    }

    void offline(boolean offline) {
        FeatBitClient c = client;
        if (c == null || !state.available() || state.flushPending) return;
        long revision = generation;
        state.busy = offline ? "Going offline…" : "Going online…";
        changed();
        settled(
                offline ? c.setOffline(5000) : c.setOnline(5000),
                o -> {
                    if (generation == revision) {
                        state.busy = null;
                        state.message = "Mode: " + result(o);
                        refresh(c);
                        log(state.message);
                    }
                });
    }

    void edit(String key, String value) {
        edit(key, value, false, ok -> {});
    }

    void edit(String key, String value, boolean restoreAll, Consumer<Boolean> completed) {
        if (data == null || !state.local || !state.available()) return;
        FlagSpec spec = spec(key);
        if (spec != null && value != null && !validateValue(spec, value)) {
            completed.accept(false);
            return;
        }
        Map<String, String> proposed = new LinkedHashMap<>(localValues);
        Operation<TestDataResult> operation;
        if (restoreAll) {
            proposed.clear();
            for (FlagSpec f : specs) proposed.put(f.key, f.initial);
            operation = data.replace(records(proposed));
        } else if (spec != null && value != null) {
            proposed.put(key, value);
            operation = data.update(BootstrapFlag.create(key, value, spec.type).getValue());
        } else if (spec != null) {
            proposed.remove(key);
            operation = data.remove(key);
        } else return;
        state.busy = "Saving local flags…";
        changed();
        long revision = generation;
        settled(
                operation,
                o -> {
                    if (revision == generation) {
                        if (o.isSuccess()) {
                            localValues.clear();
                            localValues.putAll(proposed);
                            if (Objects.equals(editorKey, key)) editorKey = null;
                        }
                        state.busy = null;
                        state.message =
                                o.getValue() == TestDataResult.COMMITTED
                                        ? "Applied locally"
                                        : o.getValue() == TestDataResult.SAVED_FOR_NEXT_START
                                                ? "Saved; applies when resumed"
                                                : "Save failed: " + diagnostic(o);
                        if (o.getValue() == TestDataResult.COMMITTED && client != null)
                            refresh(client);
                        log("TestData: " + result(o));
                        completed.accept(o.isSuccess());
                    }
                });
    }

    void evaluate(String key) {
        if (client == null || !state.available()) return;
        FlagSpec f = spec(key);
        EvaluationDetail<?> r;
        switch (f.type) {
            case BOOLEAN:
                r = client.boolVariationDetail(key, false);
                break;
            case NUMBER:
                r = client.numberVariationDetail(key, 0.0);
                break;
            case JSON:
                r = client.jsonTextVariationDetail(key, f.fallback);
                break;
            default:
                r = client.stringVariationDetail(key, f.fallback);
        }
        state.reads.put(
                key,
                new ReadRecord(
                        String.valueOf(r.getValue()),
                        r.getReason().name(),
                        f.fallback,
                        people.get(state.user).name,
                        now()));
        log("Evaluated " + key + ": " + r.getReason());
    }

    void order() {
        if (client == null || !state.available()) return;
        String size = "";
        for (CupSize s : state.business.menu.sizes)
            if (s.id.equals(state.selectedSize)) size = s.label;
        String r =
                state.local
                        ? "Events disabled in Local"
                        : result(
                                client.track(
                                        "sample-order-completed",
                                        state.business.total().doubleValue()));
        state.order = new OrderRecord(size, state.business.total(), people.get(state.user).name, r);
        state.lastTrack = r;
        state.message = "Demo order created · " + r;
        log("Order " + state.order.amount + ": " + r);
    }

    void flush() {
        if (client == null || !state.available() || !state.events || state.flushPending) return;
        long revision = generation;
        state.flushPending = true;
        changed();
        settled(
                client.flush(),
                o -> {
                    if (revision == generation) {
                        state.flushPending = false;
                        state.lastFlush = result(o);
                        state.message = "Flush: " + state.lastFlush;
                        log(state.message);
                    }
                });
    }
}
