"use strict";

(() => {
    const $ = id => document.getElementById(id);
    const el = (tag, props = {}, ...children) => {
        const e = Object.assign(document.createElement(tag), props);
        e.append(...children);
        return e;
    };

    let csrf = "";
    let current = {};          // last saved settings from the server
    let libs = null;           // [{title, type, supported}] or null while loading
    let libsError = null;
    let dirty = false;
    let setupMode = false;
    let pollTimer = null;
    let popup = null;

    // The Plex sign-in window lands back here when done - just tell the user to close it.
    if (new URLSearchParams(location.search).get("plex") === "done") {
        $("popup-done").hidden = false;
        if (window.opener) window.close();
        return;
    }

    // ------------------------------------------------------------------ server calls
    class ApiError extends Error {
        constructor(message, status, data) { super(message); this.status = status; this.data = data || {}; }
    }

    async function api(path, { method = "GET", body } = {}) {
        const init = { method, credentials: "same-origin", headers: { Accept: "application/json" } };
        if (method !== "GET") {
            init.headers["Content-Type"] = "application/json";
            init.headers["X-CSRF-Token"] = csrf;
            init.body = JSON.stringify(body || {});
        }
        let r;
        try {
            r = await fetch("api/" + path, init);
        } catch (_) {
            throw new ApiError("Can't reach the Showcase Web server.", 0);
        }
        const text = await r.text();
        let data = {};
        try { data = JSON.parse(text); } catch (_) { data = { error: text.trim() || null }; }
        if (data.csrf) csrf = data.csrf;
        if (r.status === 401 && path !== "login" && path !== "setup") {   // session ended
            showAuth(false);
            throw new ApiError(data.error || "Please sign in again.", 401, data);
        }
        if (!r.ok) throw new ApiError(data.error || `Something went wrong (error ${r.status}).`, r.status, data);
        return data;
    }

    // ------------------------------------------------------------------ feedback
    function say(message) {
        const s = $("status");
        s.textContent = message;
        clearTimeout(say.timer);
        say.timer = setTimeout(() => { s.textContent = ""; }, 7000);
    }

    function showAlert(message, details) {
        const a = $("alert");
        a.replaceChildren();
        if (!message) { a.hidden = true; return; }
        a.append(el("p", { textContent: message }));
        if (details && details.length) a.append(el("ul", {}, ...details.map(d => el("li", { textContent: d }))));
        a.hidden = false;
        a.scrollIntoView({ block: "nearest" });
    }

    async function withBusy(button, fn) {
        button.disabled = true;
        button.setAttribute("aria-busy", "true");
        try { return await fn(); } finally {
            button.disabled = false;
            button.removeAttribute("aria-busy");
        }
    }

    const confirmAction = message => window.confirm(message);

    // ------------------------------------------------------------------ sign in / setup
    function showAuth(setup) {
        setupMode = setup;
        stopWaiting();
        $("app").hidden = true;
        $("logout").hidden = true;
        $("auth").hidden = false;
        $("preview").removeAttribute("src");
        $("auth-title").textContent = setup ? "Create a password" : "Sign in";
        $("auth-help").textContent = setup
            ? "First time here. Enter the setup code from the server window, then choose a password for this page (at least 8 characters)."
            : "Enter the settings password.";
        $("auth-code-wrap").hidden = !setup;
        $("auth-code").required = setup;
        $("auth-pw2-wrap").hidden = !setup;
        $("auth-pw2").required = setup;
        $("auth-pw").autocomplete = setup ? "new-password" : "current-password";
        $("auth-submit").textContent = setup ? "Create password" : "Sign in";
        $("auth-pw").value = $("auth-pw2").value = "";
        (setup ? $("auth-code") : $("auth-pw")).focus();
    }

    $("auth-form").addEventListener("submit", e => {
        e.preventDefault();
        showAlert();
        const pw = $("auth-pw").value;
        if (setupMode && pw.length < 8) return showAlert("Use at least 8 characters.");
        if (setupMode && pw !== $("auth-pw2").value) return showAlert("The two passwords don't match.");
        withBusy($("auth-submit"), async () => {
            try {
                await api(setupMode ? "setup" : "login", { method: "POST",
                    body: { password: pw, code: $("auth-code").value } });
                await showApp();
                say(setupMode ? "Password created. You're signed in." : "Signed in.");
            } catch (err) {
                showAlert(err.message);
                $("auth-pw").select();
            }
        });
    });

    $("logout").addEventListener("click", async () => {
        if (dirty && !confirmAction("You have unsaved changes. Sign out anyway?")) return;
        try { await api("logout", { method: "POST" }); } catch (_) { /* signing out anyway */ }
        setDirty(false);
        showAuth(false);
        say("Signed out.");
    });

    async function showApp() {
        $("auth").hidden = true;
        $("app").hidden = false;
        $("logout").hidden = false;
        reloadPreview();
        await Promise.all([loadSettings(), loadPlexStatus()]);
        loadLibraries();
        $("main").focus();
    }

    // ------------------------------------------------------------------ settings form
    const settingEls = () => document.querySelectorAll("#settings-form [data-setting]");

    function fill(values) {
        current = values;
        for (const node of settingEls()) {
            const v = values[node.dataset.setting];
            switch (node.dataset.kind) {
                case "bool": node.checked = !!v; break;
                case "number":
                case "coord":
                case "text": node.value = v; break;
                case "radio": node.querySelectorAll("input").forEach(i => { i.checked = i.value === v; }); break;
                case "checkset": node.querySelectorAll("input").forEach(i => { i.checked = v.includes(i.value); }); break;
                case "pairs": node.value = v.map(([t, x]) => (t ? `${t} | ${x}` : x)).join("\n"); break;
            }
        }
        syncPickers();
        updateOutputs();
        renderLibraries();
        weatherState.justSet = false;
        showWeatherPlace();
        setDirty(false);
    }

    function collect() {
        const out = {};
        for (const node of settingEls()) {
            const name = node.dataset.setting;
            switch (node.dataset.kind) {
                case "bool": out[name] = node.checked; break;
                case "number": out[name] = Number(node.value); break;
                case "text": out[name] = node.value.trim(); break;
                case "coord": out[name] = node.value === "" ? "" : Number(node.value); break;
                case "radio": out[name] = (node.querySelector("input:checked") || {}).value; break;
                case "checkset": out[name] = [...node.querySelectorAll("input:checked")].map(i => i.value); break;
                case "pairs":
                    out[name] = node.value.split("\n").map(l => l.trim()).filter(Boolean).map(line => {
                        const i = line.indexOf("|");
                        return i < 0 ? ["", line] : [line.slice(0, i).trim(), line.slice(i + 1).trim()];
                    });
                    break;
            }
        }
        out.LIBRARIES_TO_EXCLUDE = excludedFromForm();
        return out;
    }

    // ------------------------------------------------------------------ weather town
    // The chosen town is shown in a panel with its current weather, outlined right after choosing it.
    const weatherState = { justSet: false, key: "", now: null, failed: false };

    function showWeatherPlace() {
        const place = $("WEATHER_PLACE").value;
        const panel = $("weather-chosen");
        $("weather-current").textContent = place ? "" : "Weather is off. Search for your town to turn it on.";
        $("weather-off").hidden = !place;
        panel.hidden = !place;
        if (!place) return;
        panel.classList.toggle("just-set", weatherState.justSet);
        $("weather-chosen-place").textContent = weatherState.justSet
            ? `Weather set to ${place}. Save changes to apply it.`
            : `Showing the weather for ${place}.`;
        const svg = panel.querySelector("svg");
        const now = weatherState.now;
        if (now) {
            const [text, icon] = window.ShowcaseWeather.describe(now.code, now.isDay);
            const f = $("TEMP_UNIT").value === "f";
            const t = Math.round(f ? now.tempC * 9 / 5 + 32 : now.tempC);
            $("weather-chosen-now").textContent = `Right now: ${t}°${f ? "F" : "C"}${text ? "  " + text : ""}`;
            svg.innerHTML = window.ShowcaseWeather.ICONS[icon];     // fixed icon markup, no user data
            svg.removeAttribute("hidden");
        } else {
            $("weather-chosen-now").textContent = weatherState.failed
                ? "Couldn't get the current weather just now. The screens will keep trying."
                : "Getting the current weather…";
            svg.setAttribute("hidden", "");
        }
        loadWeatherNow();
    }

    async function loadWeatherNow() {
        const lat = $("WEATHER_LAT").value, lon = $("WEATHER_LON").value;
        const key = `${lat},${lon}`;
        if (!$("WEATHER_PLACE").value || lat === "" || lon === "" || key === weatherState.key) return;
        Object.assign(weatherState, { key, now: null, failed: false });
        try {
            const d = await api(`weather/now?lat=${encodeURIComponent(lat)}&lon=${encodeURIComponent(lon)}`);
            if (weatherState.key !== key) return;            // another town was chosen meanwhile
            weatherState.now = d.weather;
        } catch (_) {
            if (weatherState.key !== key) return;
            weatherState.failed = true;
        }
        showWeatherPlace();
    }

    function setWeatherPlace(label, lat, lon) {
        $("WEATHER_PLACE").value = label;
        $("WEATHER_LAT").value = lat;
        $("WEATHER_LON").value = lon;
        $("weather-results").replaceChildren();
        weatherState.justSet = !!label;
        showWeatherPlace();
        setDirty(true);
    }

    async function searchWeather() {
        const q = $("weather-query").value.trim();
        if (!q) return $("weather-query").focus();
        const box = $("weather-results");
        await withBusy($("weather-search"), async () => {
            try {
                const places = (await api("weather/search?q=" + encodeURIComponent(q))).places;
                box.replaceChildren();
                if (!places.length) {
                    box.append(el("p", { className: "hint", textContent: `No places called "${q}". Try another spelling or a nearby city.` }));
                    return;
                }
                places.forEach(p => {
                    const b = el("button", { type: "button", className: "secondary place" },
                        el("strong", { textContent: p.name }), " ", el("span", { className: "hint", textContent: p.detail }));
                    b.addEventListener("click", () => {
                        $("weather-query").value = "";
                        setWeatherPlace(p.label, p.lat, p.lon);
                        say(`Weather set to ${p.label}. Save changes to apply it.`);
                        $("save").focus();
                    });
                    box.append(b);
                });
                box.querySelector("button").focus();
            } catch (err) {
                showAlert(err.message);
            }
        });
    }

    $("weather-search").addEventListener("click", searchWeather);
    $("weather-query").addEventListener("keydown", e => {
        if (e.key === "Enter") { e.preventDefault(); searchWeather(); }
    });
    $("TEMP_UNIT").addEventListener("change", showWeatherPlace);
    $("weather-off").addEventListener("click", () => {
        setWeatherPlace("", "", "");
        say("Weather turned off. Save changes to apply it.");
    });

    async function loadSettings() {
        const d = await api("settings");
        fill(d.values);
        showAccess(d);
    }

    function setDirty(on) {
        dirty = on;
        $("dirty").hidden = !on;
    }

    $("settings-form").addEventListener("input", () => { setDirty(true); updateOutputs(); });
    $("settings-form").addEventListener("change", () => setDirty(true));
    window.addEventListener("beforeunload", e => { if (dirty) { e.preventDefault(); e.returnValue = ""; } });

    $("settings-form").addEventListener("submit", e => {
        e.preventDefault();
        showAlert();
        document.querySelectorAll("[aria-invalid]").forEach(n => n.removeAttribute("aria-invalid"));
        const form = e.target;
        if (!form.checkValidity()) { form.reportValidity(); return; }
        withBusy($("save"), async () => {
            try {
                const d = await api("settings", { method: "POST", body: { values: collect() } });
                fill(d.values);
                reloadPreview();
                say("Saved. Screens will update within a minute.");
            } catch (err) {
                const errors = err.data.errors || [];
                showAlert(errors.length ? "Nothing was saved. Please fix:" : err.message, errors.map(x => x.message));
                let first = null;
                for (const x of errors) {
                    const node = document.querySelector(`[data-setting="${CSS.escape(x.field)}"]`);
                    if (node) { node.setAttribute("aria-invalid", "true"); first = first || node; }
                }
                if (first) (first.matches("fieldset") ? first.querySelector("input") : first).focus();
            }
        });
    });

    // Opacity sliders show their value.
    function updateOutputs() {
        for (const id of ["TITLE_OPACITY", "TIME_OPACITY", "DIM_LEVEL"]) {
            $(id + "-out").textContent = Math.round(Number($(id).value) * 100) + "%";
            $(id).setAttribute("aria-valuetext", $(id + "-out").textContent);
        }
    }

    // Colour picker <-> text box.
    function toHex(value) {
        const ctx = document.createElement("canvas").getContext("2d");
        ctx.fillStyle = "#010203";
        ctx.fillStyle = value.trim();
        const out = ctx.fillStyle;
        return /^#[0-9a-f]{6}$/i.test(out) && (out !== "#010203" || value.trim() === "#010203") ? out : null;
    }

    function syncPickers() {
        document.querySelectorAll("[data-pair]").forEach(pick => {
            const hex = toHex($(pick.dataset.pair).value);
            if (hex) pick.value = hex;
        });
    }

    document.querySelectorAll("[data-pair]").forEach(pick => {
        const text = $(pick.dataset.pair);
        pick.addEventListener("input", () => { text.value = pick.value; });
        text.addEventListener("input", () => { const hex = toHex(text.value); if (hex) pick.value = hex; });
    });

    // ------------------------------------------------------------------ libraries
    async function loadLibraries() {
        libs = null;
        renderLibraries();
        try {
            libs = (await api("libraries")).libraries;
            libsError = null;
        } catch (err) {
            libs = [];
            libsError = err.status === 409 ? "Connect to Plex to choose libraries." : err.message;
        }
        renderLibraries();
    }

    function renderLibraries() {
        const box = $("libraries");
        box.replaceChildren();
        const excluded = current.LIBRARIES_TO_EXCLUDE || [];
        if (libs === null) { box.append(el("p", { className: "hint", textContent: "Loading libraries…" })); return; }
        if (libsError) {
            box.append(el("p", { className: "hint", textContent: libsError }));
            if (excluded.length) box.append(el("p", { className: "hint", textContent: "Currently hidden: " + excluded.join(", ") }));
            return;
        }
        if (!libs.length) { box.append(el("p", { className: "hint", textContent: "This server has no libraries." })); return; }
        for (const lib of libs) {
            const input = el("input", { type: "checkbox" });
            input.dataset.lib = lib.title;
            const text = el("span", { textContent: lib.title });
            if (lib.supported) {
                input.checked = !excluded.includes(lib.title);
            } else {
                input.disabled = true;
                text.append(" ", el("span", { className: "hint", textContent: `(${lib.type || "other"} library: no artwork used)` }));
            }
            box.append(el("label", { className: "choice" + (lib.supported ? "" : " disabled") }, input, text));
        }
    }

    function excludedFromForm() {
        const ex = new Set(current.LIBRARIES_TO_EXCLUDE || []);
        for (const input of document.querySelectorAll("#libraries input[data-lib]:not(:disabled)")) {
            if (input.checked) ex.delete(input.dataset.lib); else ex.add(input.dataset.lib);
        }
        return [...ex];
    }

    // ------------------------------------------------------------------ Plex connection
    const KIND = { local: "on your network", remote: "over the internet", relay: "through Plex Relay (slow)" };

    async function loadPlexStatus() {
        const p = $("plex-status");
        p.textContent = "Checking connection…";
        let s;
        try { s = await api("plex/status"); } catch (err) { p.textContent = err.message; return; }
        p.replaceChildren();
        if (!s.configured) {
            p.append(el("span", { className: "bad", textContent: "Not connected." }),
                " Sign in with Plex to choose a server.");
            $("plex-signin").textContent = "Sign in with Plex";
            $("plex-disconnect").hidden = true;
            return;
        }
        const details = [s.serverName || "Plex server", s.username && `signed in as ${s.username}`, s.url].filter(Boolean).join(" · ");
        p.append(el("span", { className: s.reachable ? "ok" : "bad",
                              textContent: s.reachable ? "Connected" : (s.error || "Not reachable") }),
                 ": " + details);
        $("plex-signin").textContent = "Sign in again / switch server";
        $("plex-disconnect").hidden = false;
        if (!$("manual-url").value) $("manual-url").value = s.url;
    }

    $("plex-signin").addEventListener("click", () => {
        showAlert();
        stopWaiting();
        // Open the window now (inside the click) so pop-up blockers allow it; point it at Plex once we have the link.
        popup = window.open("", "showcase-signin", "width=800,height=760");
        withBusy($("plex-signin"), async () => {
            try {
                const d = await api("plex/pin", { method: "POST" });
                $("plex-link").href = d.authUrl;
                if (popup && !popup.closed) popup.location.href = d.authUrl;
                $("server-pick").hidden = true;
                $("plex-wait").hidden = false;
                say("Waiting for you to approve Showcase Web in the Plex window.");
                poll();
            } catch (err) {
                if (popup) popup.close();
                showAlert(err.message);
            }
        });
    });

    function poll() {
        pollTimer = setTimeout(async () => {
            let d;
            try { d = await api("plex/pin"); } catch (err) { stopWaiting(); showAlert(err.message); return; }
            if (d.status === "waiting") { poll(); return; }
            stopWaiting();
            if (d.status === "expired") { showAlert("The Plex sign-in timed out. Please try again."); return; }
            if (popup && !popup.closed) popup.close();
            showServers(d.user, d.servers);
        }, 2000);
    }

    function stopWaiting() {
        clearTimeout(pollTimer);
        pollTimer = null;
        $("plex-wait").hidden = true;
    }

    $("plex-cancel").addEventListener("click", () => {
        stopWaiting();
        if (popup && !popup.closed) popup.close();
        say("Plex sign-in cancelled.");
        $("plex-signin").focus();
    });

    function showServers(user, servers) {
        $("plex-user").textContent = user;
        const list = $("server-list");
        list.replaceChildren();
        servers.sort((a, b) => (b.owned - a.owned) || (b.online - a.online));
        if (!servers.length) {
            list.append(el("p", { className: "hint", textContent: "This Plex account doesn't have access to any servers." }));
        }
        servers.forEach((s, i) => {
            const radio = el("input", { type: "radio", name: "server", value: s.id, checked: i === 0 });
            const facts = [s.owned ? "Your server" : `Shared by ${s.owner || "someone else"}`];
            if (!s.online) facts.push("currently offline");
            if (s.kinds.length) facts.push("reachable " + s.kinds.map(k => KIND[k] || k).join(", "));
            list.append(el("label", { className: "choice" }, radio,
                el("span", {}, el("strong", { textContent: s.name }), el("span", { className: "hint", textContent: facts.join(" · ") }))));
        });
        $("server-use").hidden = !servers.length;
        $("server-pick").hidden = false;
        say(`Signed in to Plex as ${user}. Choose a server.`);
        const first = list.querySelector("input");
        if (first) first.focus();
    }

    $("server-pick").addEventListener("submit", e => {
        e.preventDefault();
        showAlert();
        const chosen = document.querySelector("#server-list input:checked");
        if (!chosen) return showAlert("Pick a server first.");
        withBusy($("server-use"), async () => {
            say("Testing the server's addresses…");
            try {
                const d = await api("plex/connect", { method: "POST", body: { serverId: chosen.value } });
                $("server-pick").hidden = true;
                say(`Connected to ${d.serverName}, ${KIND[d.kind] || ""}.`);
                if (d.kind === "relay") {
                    showAlert("Connected through Plex Relay, which is limited to about 1–2 Mbps, so images will load slowly. " +
                        "For a direct connection, enable Remote Access in Plex, or run Showcase Web on the same network as Plex.");
                }
                await loadPlexStatus();
                loadLibraries();
                reloadPreview();
                $("plex-signin").focus();
            } catch (err) {
                const tested = (err.data.tested || []).map(t => `${t.uri} (${t.kind}): ${t.ok ? "OK" : (t.error || "failed")}`);
                showAlert(err.message + (tested.length ? " Addresses tried:" : ""), tested);
            }
        });
    });

    $("manual-form").addEventListener("submit", e => {
        e.preventDefault();
        showAlert();
        if (!e.target.checkValidity()) { e.target.reportValidity(); return; }
        withBusy(e.target.querySelector("button"), async () => {
            try {
                const d = await api("plex/manual", { method: "POST",
                    body: { url: $("manual-url").value.trim(), token: $("manual-token").value.trim() } });
                $("manual-token").value = "";
                say(`Connected to ${d.serverName || d.uri}.`);
                await loadPlexStatus();
                loadLibraries();
                reloadPreview();
            } catch (err) {
                showAlert(err.message);
            }
        });
    });

    $("plex-disconnect").addEventListener("click", async () => {
        if (!confirmAction("Disconnect from Plex? Screens will stop showing Plex artwork until you sign in again.")) return;
        try {
            await api("plex/disconnect", { method: "POST" });
            say("Disconnected from Plex.");
            await loadPlexStatus();
            loadLibraries();
            reloadPreview();
        } catch (err) { showAlert(err.message); }
    });

    // ------------------------------------------------------------------ preview
    function reloadPreview() {
        $("preview").src = "../?preview=" + Date.now();
    }
    $("preview-reload").addEventListener("click", reloadPreview);

    // ------------------------------------------------------------------ screen access key
    function showAccess(d) {
        $("key-status").textContent = d.screenKeySet
            ? "A key is required. Screens need the full address below, including the key."
            : "No key: anyone who can reach this server can view the screensaver.";
        $("screen-url").value = d.screenUrl;
        $("key-clear").hidden = !d.screenKeySet;
        $("key-generate").textContent = d.screenKeySet ? "Make a new key" : "Require a key";
    }

    async function changeKey(action, question) {
        if (!confirmAction(question)) return;
        try {
            showAccess(await api("screen-key", { method: "POST", body: { action } }));
            say(action === "generate" ? "New key saved. Update the address on your screens." : "Key removed.");
        } catch (err) { showAlert(err.message); }
    }

    $("key-generate").addEventListener("click", () => changeKey("generate",
        "Create a new key? Every screen will need the new address before it can show artwork again."));
    $("key-clear").addEventListener("click", () => changeKey("clear",
        "Remove the key? Anyone who can reach this server will be able to view the screensaver."));

    $("copy-url").addEventListener("click", async () => {
        const input = $("screen-url");
        try {
            await navigator.clipboard.writeText(input.value);
        } catch (_) {                 // clipboard API needs https; fall back for plain-http LAN use
            input.select();
            document.execCommand("copy");
        }
        say("Address copied.");
    });

    // ------------------------------------------------------------------ password
    $("password-form").addEventListener("submit", e => {
        e.preventDefault();
        showAlert();
        const form = e.target;
        if (!form.checkValidity()) { form.reportValidity(); return; }
        if ($("pw-new").value !== $("pw-new2").value) return showAlert("The new passwords don't match.");
        withBusy(form.querySelector("button[type=submit]"), async () => {
            try {
                await api("password", { method: "POST", body: { current: $("pw-current").value, new: $("pw-new").value } });
                form.reset();
                say("Password changed. Other devices have been signed out.");
            } catch (err) { showAlert(err.message); }
        });
    });

    // ------------------------------------------------------------------ start
    (async () => {
        try {
            const s = await api("state");
            if (s.version) $("version").textContent = "v" + s.version;
            if (s.loggedIn) await showApp(); else showAuth(s.setupNeeded);
        } catch (err) {
            showAlert(err.message);
        }
    })();
})();
