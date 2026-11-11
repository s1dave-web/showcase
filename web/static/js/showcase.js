"use strict";

// ALL SETTINGS LIVE IN .env - don't edit them here.
// These are only fallbacks, used if the server's settings can't be loaded.
// The server's values replace them on startup and at every refresh.
const config = {
    imageInterval: 10000,
    transitionDuration: 1500,
    artQuality: "original",
    animations: ["zoomIn", "zoomOut", "moveRight", "moveLeft", "moveUp", "moveDown", "goGray", "goColor"],
    showTitle: true,
    titleColor: "#ffffff",
    titleOpacity: 0.8,
    titlePosition: "right",
    showTime: true,
    timeColor: "#ffffff",
    timeOpacity: 0.8,
    timePosition: "right",
    clock24h: true,
    showLogo: true,
    pixelShift: true,
    refreshMinutes: 30,
    usePlex: true,
    extraImages: [],
    extraLinks: [],
};

(function () {
    const slides = [document.getElementById("one"), document.getElementById("two")];
    const infoBox = document.getElementById("info");
    let active = 0;                 // index into slides of the visible one
    let items = [];                 // everything we can show
    let bag = [];                   // shuffle bag: every item is shown once before any repeats
    let started = false;

    // ------------------------------------------------------------------ setup
    function applyStyles() {
        document.body.classList.toggle("hiddenTitle", !config.showTitle);
        document.body.classList.toggle("hiddenTime", !config.showTime);
        document.body.classList.toggle("hiddenLogo", !config.showLogo);
        for (const s of slides) {
            s.style.transitionDuration = config.transitionDuration + "ms";
            Object.assign(s.querySelector(".Title").style,
                { color: config.titleColor, textAlign: config.titlePosition, opacity: config.titleOpacity });
            Object.assign(s.querySelector(".Time").style,
                { color: config.timeColor, textAlign: config.timePosition, opacity: config.timeOpacity });
        }
    }

    // Pull the display settings from the server (.env). Keep the current ones if that fails.
    async function loadConfig() {
        try {
            const r = await fetch("api/config", { cache: "no-store" });
            if (r.ok) Object.assign(config, await r.json());
        } catch (e) {
            console.warn("Could not load settings, using current ones", e);
        }
        applyStyles();
        renderClock();
    }

    function showInfo(text) {
        infoBox.textContent = text;
        infoBox.hidden = !text;
    }

    // ------------------------------------------------------------------ data
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
        for (const [title, file] of config.extraImages || []) {
            list.push({ title, art: "extraImages/" + encodeURIComponent(file) });
        }
        for (const [title, url] of config.extraLinks || []) list.push({ title, art: url });
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
                thumb: it.hasThumb ? `api/item/${it.key}/thumb?w=600&h=900` : null,
            }));
        }
        const all = plexItems.concat(extraItems());
        if (all.length === 0) throw new Error("No artwork found - check your libraries and config");
        console.log("Loaded " + all.length + " images");
        items = all;
        bag = [];
    }

    async function loadWithRetry() {
        let delay = 5000;
        for (;;) {
            try {
                await loadItems();
                showInfo("");
                return;
            } catch (e) {
                console.error(e);
                showInfo(e.message + "\nRetrying in " + Math.round(delay / 1000) + "s...");
                await sleep(delay);
                delay = Math.min(delay * 2, 5 * 60 * 1000);
            }
        }
    }

    function nextItem() {
        if (bag.length === 0) {
            bag = items.slice();
            for (let i = bag.length - 1; i > 0; i--) {          // Fisher-Yates
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
            img.onload = () => Promise.race([
                img.decode ? img.decode().catch(() => {}) : null,
                sleep(3000),
            ]).then(() => resolve(url));
            img.onerror = () => reject(new Error("Failed to load " + url));
            img.src = url;
        });
    }

    // Prepare the hidden slide with the next working image (skips broken ones).
    async function prepare(slide) {
        for (let attempt = 0; attempt < 5; attempt++) {
            const item = nextItem();
            try {
                await preload(item.art);
                let thumbOk = false;
                if (item.thumb && config.showLogo) {
                    thumbOk = await preload(item.thumb).then(() => true, () => false);
                }
                const poster = slide.querySelector(".Poster");
                const logo = slide.querySelector(".Logo");
                poster.style.animationName = "none";
                poster.src = item.art;
                logo.hidden = !thumbOk;
                if (thumbOk) logo.src = item.thumb;
                slide.querySelector(".Title").textContent = item.title || "";
                return true;
            } catch (e) {
                console.warn(e.message);
            }
        }
        return false;
    }

    function animate(slide) {
        const poster = slide.querySelector(".Poster");
        const names = config.animations;
        poster.style.animationName = "none";
        void poster.offsetWidth;                        // restart the animation
        poster.style.animationDuration = (config.imageInterval + config.transitionDuration) + "ms";
        poster.style.animationName = names.length ? names[Math.floor(Math.random() * names.length)] : "none";

        const overlay = slide.querySelector(".overlay");
        if (config.pixelShift) {
            const dx = Math.round(Math.random() * 16 - 8), dy = Math.round(Math.random() * 16 - 8);
            overlay.style.transform = `translate(${dx}px, ${dy}px)`;
        }
    }

    // Fade the prepared hidden slide in on top of the current one (no dip to black).
    function show() {
        const next = 1 - active, prev = active;
        animate(slides[next]);
        slides[next].style.zIndex = 2;
        slides[prev].style.zIndex = 1;
        slides[next].classList.add("visible");
        setTimeout(() => slides[prev].classList.remove("visible"), config.transitionDuration + 50);
        active = next;
    }

    async function run() {
        if (started) return;            // never start two loops
        started = true;
        await loadConfig();
        await loadWithRetry();
        if (await prepare(slides[1 - active])) show();
        let lastRefresh = Date.now(), lastConfig = Date.now();
        for (;;) {
            const t0 = Date.now();
            await sleep(config.transitionDuration + 100);     // let the old slide finish fading out
            let reloadItems = Date.now() - lastRefresh > config.refreshMinutes * 60000;
            if (Date.now() - lastConfig > 60000) {            // pick up .env edits within a minute
                const before = JSON.stringify([config.artQuality, config.usePlex, config.extraImages, config.extraLinks]);
                await loadConfig();
                lastConfig = Date.now();
                const after = JSON.stringify([config.artQuality, config.usePlex, config.extraImages, config.extraLinks]);
                if (before !== after) reloadItems = true;
            }
            if (reloadItems) {
                try { await loadItems(); } catch (e) { console.warn("Refresh failed, keeping old list", e); }
                lastRefresh = Date.now();
            }
            const ok = await prepare(slides[1 - active]);    // preload during the wait
            await sleep(Math.max(0, config.imageInterval - (Date.now() - t0)));
            if (ok) show();
        }
    }

    // ------------------------------------------------------------------ clock
    function renderClock() {
        const now = new Date();
        const h = now.getHours();
        const hours = config.clock24h ? String(h).padStart(2, "0") : String(h % 12 || 12);
        const text = hours + ":" + String(now.getMinutes()).padStart(2, "0");
        for (const el of document.querySelectorAll(".Time")) el.textContent = text;
        return now;
    }

    function tickClock() {
        const now = renderClock();
        setTimeout(tickClock, 60000 - (now.getSeconds() * 1000 + now.getMilliseconds()) + 50);   // align to minute
    }

    function sleep(ms) { return new Promise(r => setTimeout(r, ms)); }

    slides[0].classList.add("visible");
    applyStyles();
    tickClock();
    run();
})();
