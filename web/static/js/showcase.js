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
        title.className = "title " + config.titlePosition;
        title.style.color = config.titleColor;
        title.style.opacity = config.titleOpacity;
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
                img.style.animationName = "none";
                img.src = item.art;
                return { item, thumbOk };
            } catch (e) {
                console.warn(e.message);
            }
        }
        return null;
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

        title.classList.add("fading");
        poster.classList.add("fading");
        setTimeout(() => {
            title.textContent = p.item.title || "";
            poster.hidden = !p.thumbOk;
            if (p.thumbOk) poster.src = p.item.thumb;
            document.body.classList.toggle("noPoster", !config.showLogo || poster.hidden);
            if (config.pixelShift) {                        // burn-in protection
                const dx = Math.round(Math.random() * 16 - 8), dy = Math.round(Math.random() * 16 - 8);
                overlay.style.transform = `translate(${dx}px, ${dy}px)`;
            }
            title.classList.remove("fading");
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

    function tickClock() {
        const now = renderClock();
        setTimeout(tickClock, 60000 - (now.getSeconds() * 1000 + now.getMilliseconds()) + 50);   // align to the minute
    }

    // ------------------------------------------------------------------ weather (fetched by the server, shared by all screens)
    const CLOUD = "M7,18.5h10.5a3.5,3.5 0,0 0,0.3,-6.99a5.5,5.5 0,0 0,-10.6,-1.2a4,4 0,0 0,-0.2,8.19z";
    const SUN = "M12,8a4,4 0,1 1,0 8a4,4 0,1 1,0 -8zM12,2.5v1.5M12,20v1.5M4.6,4.6l1.1,1.1M18.3,18.3l1.1,1.1M2.5,12h1.5M20,12h1.5M4.6,19.4l1.1,-1.1M18.3,5.7l1.1,-1.1";
    const MOON = "M20,14.5A8,8 0,1 1,9.5 4a6.2,6.2 0,0 0,10.5 10.5z";
    const small = d => `<g transform="translate(-4.5,-4.5) translate(12,12) scale(.62) translate(-12,-12)" stroke-width="2"><path d="${d}"/></g>`;
    const up = (extra) => `<g transform="translate(0,-3.5)"><path d="${CLOUD}"/></g><path d="${extra}"/>`;
    const ICONS = {
        clearDay: `<path d="${SUN}"/>`,
        clearNight: `<path d="${MOON}"/>`,
        partlyDay: small(SUN) + `<g transform="translate(1.5,1.5)"><path d="${CLOUD}"/></g>`,
        partlyNight: small(MOON) + `<g transform="translate(1.5,1.5)"><path d="${CLOUD}"/></g>`,
        cloudy: `<path d="${CLOUD}"/>`,
        fog: `<g transform="translate(0,-3)"><path d="${CLOUD}"/></g><path d="M5,19h14M7,22h10"/>`,
        rain: up("M9,18.5l-1,2.5M13,18.5l-1,2.5M17,18.5l-1,2.5"),
        snow: up("M8.5,19.5v0.01M12,21v0.01M15.5,19.5v0.01M10,22.5v0.01M14,22.5v0.01"),
        thunder: up("M13,15.5l-2.5,3.5h3l-2.5,3.5"),
    };

    function describe(code, isDay) {
        const c = Number(code);
        if (c === 0) return [isDay ? "Sunny" : "Clear", isDay ? "clearDay" : "clearNight"];
        if (c === 1) return [isDay ? "Mostly sunny" : "Mostly clear", isDay ? "clearDay" : "clearNight"];
        if (c === 2) return ["Partly cloudy", isDay ? "partlyDay" : "partlyNight"];
        if (c === 3) return ["Cloudy", "cloudy"];
        if (c === 45 || c === 48) return ["Fog", "fog"];
        if ([71, 73, 75, 77, 85, 86].includes(c)) return ["Snow", "snow"];
        if ([95, 96, 99].includes(c)) return ["Thunderstorms", "thunder"];
        if ([51, 53, 55, 56, 57].includes(c)) return ["Drizzle", "rain"];
        if ([65, 67, 82].includes(c)) return ["Heavy rain", "rain"];
        if ((c >= 61 && c <= 67) || (c >= 80 && c <= 82)) return ["Rain", "rain"];
        return ["", "cloudy"];
    }

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
