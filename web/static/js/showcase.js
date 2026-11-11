"use strict";

// ALL SETTINGS LIVE IN .env (or the settings page) - don't edit them here.
// These are only fallbacks, used if the server's settings can't be loaded.
const config = {
    imageInterval: 10000,
    transitionDuration: 1500,
    artQuality: "original",
    animations: ["zoomIn", "zoomOut", "moveRight", "moveLeft", "moveUp", "moveDown", "goGray", "goColor"],
    showTitle: true,
    titleColor: "#ffffff",
    titleOpacity: 1,
    titlePosition: "right",
    showTime: true,
    timeColor: "#ffffff",
    timeOpacity: 1,
    timePosition: "right",
    clock24h: true,
    showDate: true,
    titleLogos: true,
    showDetails: true,
    dimStart: "",
    dimEnd: "",
    dimLevel: 0.6,
    weatherEnabled: false,
    fahrenheit: false,
    showLogo: true,
    pixelShift: true,
    refreshMinutes: 30,
    usePlex: true,
    extraImages: [],
    extraLinks: [],
};

(function () {
    const $ = id => document.getElementById(id);
    const slides = [...document.querySelectorAll(".slide")];
    const overlay = $("overlay"), info = $("info"), clock = $("clock"), date = $("date");
    const weatherRow = $("weather"), title = $("title"), poster = $("poster"), message = $("message");
    const caption = $("caption"), logo = $("logo"), details = $("details"), dim = $("dim");

    let active = 0;                 // index of the visible slide
    let items = [];                 // everything we can show
    let bag = [];                   // shuffle bag: every item is shown once before any repeats
    let started = false;
    let weather = null;

    // ------------------------------------------------------------------ settings
    function applyStyles() {
        const body = document.body.classList;
        body.toggle("hiddenTitle", !config.showTitle);
        body.toggle("hiddenTime", !config.showTime);
        body.toggle("hiddenDate", !config.showDate);
        body.toggle("hiddenLogo", !config.showLogo);
        body.toggle("noPoster", !config.showLogo || poster.hidden);
        body.toggle("noTop", !config.showTime && !config.showDate && !(config.weatherEnabled && weather));
        body.toggle("noBottom", !config.showTitle && !config.showLogo);
        document.documentElement.style.setProperty("--fade", config.transitionDuration + "ms");

        info.className = "info " + config.timePosition;
        info.style.color = config.timeColor;
        info.style.opacity = config.timeOpacity;
        caption.className = "caption " + config.titlePosition;
        caption.style.color = config.titleColor;
        caption.style.opacity = config.titleOpacity;
        if (!config.pixelShift) overlay.style.transform = "";
    }

    // Pull the display settings from the server. Keep the current ones if that fails.
    async function loadConfig() {
        try {
            const r = await fetch("api/config", { cache: "no-store" });
            if (r.ok) Object.assign(config, await r.json());
        } catch (e) {
            console.warn("Could not load settings, using current ones", e);
        }
        applyStyles();
        renderClock();
        renderDim();
        renderWeather();
    }

    function showMessage(text) {
        message.textContent = text || "";
        message.hidden = !text;
    }

    // ------------------------------------------------------------------ library
    function screenSize() {
        // Some WebViews report 0 for screen size, so fall back to the window, then to 1080p.
        const dpr = window.devicePixelRatio || 1;
        const w = Math.max(window.screen.width || 0, window.innerWidth || 0) * dpr || 1920;
        const h = Math.max(window.screen.height || 0, window.innerHeight || 0) * dpr || 1080;
        return {
            w: Math.min(3840, Math.max(1280, Math.round(w))),
            h: Math.min(3840, Math.max(720, Math.round(h))),
        };
    }

    function extraItems() {
        const list = [];
        for (const [t, file] of config.extraImages || []) list.push({ title: t, art: "extraImages/" + encodeURIComponent(file) });
        for (const [t, url] of config.extraLinks || []) list.push({ title: t, art: url });
        return list;
    }

    async function loadItems() {
        let plexItems = [];
        if (config.usePlex) {
            const r = await fetch("api/items", { cache: "no-store" });
            if (!r.ok) {
                let msg = "Error " + r.status;
                try { msg = (await r.json()).error || msg; } catch (_) { /* not JSON */ }
                if (r.status === 401) msg = "Access key missing or wrong - open this page with ?key=...";
                throw new Error(msg);
            }
            const { w, h } = screenSize();
            const size = config.artQuality === "screen" ? `?w=${w}&h=${h}` : "";
            plexItems = (await r.json()).items.map(it => ({
                title: it.title,
                art: `api/item/${it.key}/art${size}`,
                thumb: it.hasThumb ? `api/item/${it.key}/thumb?w=640&h=960` : null,
                info: `api/item/${it.key}/info`,
                logo: `api/item/${it.key}/logo`,
            }));
        }
        const all = plexItems.concat(extraItems());
        if (all.length === 0) throw new Error("No artwork found - check which libraries are switched on");
        items = all;
        bag = [];
    }

    async function loadWithRetry() {
        let wait = 5000;
        for (;;) {
            try {
                await loadItems();
                showMessage("");
                return;
            } catch (e) {
                console.error(e);
                showMessage(e.message + "\nRetrying in " + Math.round(wait / 1000) + "s…");
                await sleep(wait);
                wait = Math.min(wait * 2, 5 * 60 * 1000);
            }
        }
    }

    function nextItem() {
        if (bag.length === 0) {
            bag = items.slice();
            for (let i = bag.length - 1; i > 0; i--) {          // Fisher-Yates shuffle
                const j = Math.floor(Math.random() * (i + 1));
                [bag[i], bag[j]] = [bag[j], bag[i]];
            }
        }
        return bag.pop();
    }

    // ------------------------------------------------------------------ images
    function preload(url) {
        return new Promise((resolve, reject) => {
            const img = new Image();
            // decode() avoids a stutter on swap, but can hang when the page isn't rendering - cap it.
            img.onload = () => Promise.race([img.decode ? img.decode().catch(() => {}) : null, sleep(3000)])
                .then(() => resolve(url));
            img.onerror = () => reject(new Error("Failed to load " + url));
            img.src = url;
        });
    }

    // Load the next working image into the hidden slide (skips broken ones).
    async function prepare() {
        const img = slides[1 - active].querySelector(".art");
        for (let attempt = 0; attempt < 5; attempt++) {
            const item = nextItem();
            try {
                await preload(item.art);
                let thumbOk = false;
                if (item.thumb && config.showLogo) thumbOk = await preload(item.thumb).then(() => true, () => false);
                const meta = await loadInfo(item);
                img.style.animationName = "none";
                img.src = item.art;
                return { item, thumbOk, ...meta };
            } catch (e) {
                console.warn(e.message);
            }
        }
        return null;
    }

    // Year, genres and rating, plus the film's title logo if Plex has one. Never holds up the slideshow for long.
    async function loadInfo(item) {
        const out = { detail: "", logoOk: false };
        if (!item.info || !(config.titleLogos || config.showDetails)) return out;
        try {
            const ctrl = new AbortController();
            const timer = setTimeout(() => ctrl.abort(), 4000);
            const r = await fetch(item.info, { signal: ctrl.signal });
            clearTimeout(timer);
            if (!r.ok) return out;
            const d = await r.json();
            out.detail = [d.year, (d.genres || []).join(" / "), d.rating].filter(Boolean).join("  ·  ");
            if (config.titleLogos && d.logo) {
                out.logoOk = await Promise.race([preload(item.logo).then(() => true, () => false), sleep(4000).then(() => false)]);
            }
        } catch (e) { /* no details this time */ }
        return out;
    }

    // Fade the prepared slide in on top of the current one (no dip to black), then swap title and poster.
    function show(p) {
        const next = slides[1 - active], prev = slides[active];
        const img = next.querySelector(".art");
        const names = config.animations;
        img.style.animationDuration = (config.imageInterval + config.transitionDuration) + "ms";
        void img.offsetWidth;                               // restart the animation
        img.style.animationName = names.length ? names[Math.floor(Math.random() * names.length)] : "none";
        next.style.zIndex = 2;
        prev.style.zIndex = 1;
        next.classList.add("visible");
        setTimeout(() => prev.classList.remove("visible"), config.transitionDuration + 50);
        active = 1 - active;

        caption.classList.add("fading");
        poster.classList.add("fading");
        setTimeout(() => {
            title.textContent = p.item.title || "";
            title.hidden = !!p.logoOk;                      // the logo replaces the title text
            logo.hidden = !p.logoOk;
            if (p.logoOk) { logo.src = p.item.logo; logo.alt = p.item.title || ""; }
            details.textContent = config.showDetails ? p.detail : "";
            poster.hidden = !p.thumbOk;
            if (p.thumbOk) poster.src = p.item.thumb;
            document.body.classList.toggle("noPoster", !config.showLogo || poster.hidden);
            if (config.pixelShift) {                        // burn-in protection
                const dx = Math.round(Math.random() * 16 - 8), dy = Math.round(Math.random() * 16 - 8);
                overlay.style.transform = `translate(${dx}px, ${dy}px)`;
            }
            caption.classList.remove("fading");
            poster.classList.remove("fading");
        }, Math.max(1, config.transitionDuration / 2));
    }

    async function run() {
        if (started) return;            // never start two loops
        started = true;
        await loadConfig();
        await loadWithRetry();
        const first = await prepare();
        if (first) show(first);
        let lastRefresh = Date.now(), lastConfig = Date.now();
        for (;;) {
            const t0 = Date.now();
            await sleep(config.transitionDuration + 100);     // let the old slide finish fading out
            let reloadItems = Date.now() - lastRefresh > config.refreshMinutes * 60000;
            if (Date.now() - lastConfig > 60000) {            // pick up setting changes within a minute
                const before = JSON.stringify([config.artQuality, config.usePlex, config.extraImages, config.extraLinks]);
                await loadConfig();
                lastConfig = Date.now();
                if (before !== JSON.stringify([config.artQuality, config.usePlex, config.extraImages, config.extraLinks])) {
                    reloadItems = true;
                }
            }
            if (reloadItems) {
                try { await loadItems(); } catch (e) { console.warn("Refresh failed, keeping the old list", e); }
                lastRefresh = Date.now();
            }
            const next = await prepare();                     // preload during the wait
            await sleep(Math.max(0, config.imageInterval - (Date.now() - t0)));
            if (next) show(next);
        }
    }

    // ------------------------------------------------------------------ clock and date
    function renderClock() {
        const now = new Date();
        const h = now.getHours();
        const hours = config.clock24h ? String(h).padStart(2, "0") : String(h % 12 || 12);
        clock.textContent = hours + ":" + String(now.getMinutes()).padStart(2, "0");
        // "Wednesday 11 November" / "Wednesday, November 11" - whichever order the browser's language uses.
        date.textContent = new Intl.DateTimeFormat(undefined, { weekday: "long", day: "numeric", month: "long" }).format(now);
        return now;
    }

    // ------------------------------------------------------------------ night dimming
    function minutes(hhmm) {
        const m = /^(\d{1,2}):(\d{2})$/.exec(hhmm || "");
        return m ? Number(m[1]) * 60 + Number(m[2]) : null;
    }

    function isNight(now) {
        const start = minutes(config.dimStart), end = minutes(config.dimEnd);
        if (start === null || end === null || start === end) return false;
        const t = now.getHours() * 60 + now.getMinutes();
        return start < end ? (t >= start && t < end) : (t >= start || t < end);   // e.g. 22:00-07:00 crosses midnight
    }

    function renderDim(now) {
        dim.style.opacity = isNight(now || new Date()) ? config.dimLevel : 0;
    }

    function tickClock() {
        const now = renderClock();
        renderDim(now);
        setTimeout(tickClock, 60000 - (now.getSeconds() * 1000 + now.getMilliseconds()) + 50);   // align to the minute
    }

    // ------------------------------------------------------------------ weather (fetched by the server, shared by all screens)
    const { ICONS, describe } = window.ShowcaseWeather;     // js/weather-icons.js, shared with the settings page

    function renderWeather() {
        if (!config.weatherEnabled || !weather) { weatherRow.hidden = true; return; }
        const [text, icon] = describe(weather.code, weather.isDay);
        const t = config.fahrenheit ? weather.tempC * 9 / 5 + 32 : weather.tempC;
        weatherRow.querySelector("svg").innerHTML = ICONS[icon];
        weatherRow.querySelector("span").textContent = [Math.round(t) + "°", text].filter(Boolean).join(" ");
        weatherRow.hidden = false;
        document.body.classList.toggle("noTop", false);
    }

    async function weatherLoop() {
        for (;;) {
            if (config.weatherEnabled) {
                try {
                    const r = await fetch("api/weather", { cache: "no-store" });
                    if (r.ok) weather = (await r.json()).weather;
                } catch (e) { /* keep the last reading */ }
            } else {
                weather = null;
            }
            renderWeather();
            await sleep(10 * 60 * 1000);
        }
    }

    function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }

    applyStyles();
    tickClock();
    run().then(() => {}, e => console.error(e));
    loadConfig().then(weatherLoop);
})();
